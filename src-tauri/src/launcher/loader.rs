use crate::launcher::config::LauncherConfig;
use crate::launcher::progress::{emit_progress, InstallProgress};
use serde::Serialize;
use tauri::AppHandle;
use tokio::fs;
use tokio::io::AsyncWriteExt;

const FABRIC_META: &str = "https://meta.fabricmc.net/v2";
const QUILT_META: &str = "https://meta.quiltmc.org/v3";
const MAVEN_CENTRAL: &str = "https://repo1.maven.org/maven2/";

#[derive(Debug, Serialize, Clone)]
pub struct LoaderVersion {
    pub version: String,
    pub stable: bool,
}

fn http() -> anyhow::Result<reqwest::Client> {
    Ok(reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .build()?)
}

/// Lists loader versions available for a given Minecraft version.
pub async fn list_loader_versions(loader: &str, mc_version: &str) -> anyhow::Result<Vec<LoaderVersion>> {
    let base = match loader {
        "fabric" => FABRIC_META,
        "quilt" => QUILT_META,
        _ => return Ok(vec![]),
    };
    let url = format!("{}/versions/loader/{}", base, mc_version);
    let list: serde_json::Value = http()?.get(&url).send().await?.error_for_status()?.json().await?;

    let mut out = Vec::new();
    if let Some(arr) = list.as_array() {
        for item in arr {
            if let Some(v) = item.pointer("/loader/version").and_then(|v| v.as_str()) {
                let stable = item
                    .pointer("/loader/stable")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);
                out.push(LoaderVersion { version: v.to_string(), stable });
            }
        }
    }
    Ok(out)
}

/// Converts a maven coordinate ("net.fabricmc:fabric-loader:0.16.9") into the
/// relative path used inside the libraries folder.
pub fn maven_to_path(coord: &str) -> Option<String> {
    let parts: Vec<&str> = coord.split(':').collect();
    if parts.len() < 3 {
        return None;
    }
    let group = parts[0].replace('.', "/");
    let artifact = parts[1];
    let version = parts[2];
    let classifier = parts.get(3);

    let file = match classifier {
        Some(c) => format!("{}-{}-{}.jar", artifact, version, c),
        None => format!("{}-{}.jar", artifact, version),
    };
    Some(format!("{}/{}/{}/{}", group, artifact, version, file))
}

/// Installs a loader for the given Minecraft version and returns the version id
/// that should be launched (e.g. "fabric-loader-0.16.9-1.21.1").
pub async fn install_loader(
    app: &AppHandle,
    cfg: &LauncherConfig,
    loader: &str,
    mc_version: &str,
    loader_version: &str,
) -> anyhow::Result<String> {
    let base = match loader {
        "fabric" => FABRIC_META,
        "quilt" => QUILT_META,
        other => anyhow::bail!("Unsupported loader: {}", other),
    };

    emit_progress(app, InstallProgress {
        stage: "loader".into(),
        current: 0,
        total: 1,
        file: format!("{} {}", loader, loader_version),
    });

    // The meta server hands us a ready-made version profile that inherits
    // from the vanilla version - exactly the format the launcher already reads.
    let url = format!(
        "{}/versions/loader/{}/{}/profile/json",
        base, mc_version, loader_version
    );
    let profile: serde_json::Value = http()?.get(&url).send().await?.error_for_status()?.json().await?;

    let version_id = profile
        .get("id")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow::anyhow!("Loader profile has no id"))?
        .to_string();

    // Save the profile next to the vanilla versions
    let dir = cfg.versions_dir().join(&version_id);
    fs::create_dir_all(&dir).await?;
    fs::write(
        dir.join(format!("{}.json", version_id)),
        serde_json::to_vec_pretty(&profile)?,
    )
    .await?;

    // Loader libraries are plain maven coordinates without a downloads block,
    // so we build the URL from the coordinate plus the repo given per library.
    let empty = vec![];
    let libs = profile.get("libraries").and_then(|v| v.as_array()).unwrap_or(&empty);
    let total = libs.len() as u64;

    for (i, lib) in libs.iter().enumerate() {
        let name = match lib.get("name").and_then(|v| v.as_str()) {
            Some(n) => n,
            None => continue,
        };
        let rel = match maven_to_path(name) {
            Some(p) => p,
            None => continue,
        };
        let repo = lib.get("url").and_then(|v| v.as_str()).unwrap_or(MAVEN_CENTRAL);
        let repo = if repo.ends_with('/') { repo.to_string() } else { format!("{}/", repo) };
        let full_url = format!("{}{}", repo, rel);
        let dest = cfg.libraries_dir().join(&rel);

        emit_progress(app, InstallProgress {
            stage: "loader".into(),
            current: i as u64,
            total,
            file: name.to_string(),
        });

        if dest.exists() {
            continue;
        }
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent).await?;
        }
        match http()?.get(&full_url).send().await {
            Ok(resp) => match resp.error_for_status() {
                Ok(ok) => {
                    let bytes = ok.bytes().await?;
                    let mut f = fs::File::create(&dest).await?;
                    f.write_all(&bytes).await?;
                }
                Err(e) => eprintln!("Loader library {} failed: {}", name, e),
            },
            Err(e) => eprintln!("Loader library {} failed: {}", name, e),
        }
    }

    Ok(version_id)
}

// ===================================================================
// Forge / NeoForge
// ===================================================================
// Unlike Fabric, these cannot be installed from a JSON profile alone:
// their installers run bytecode-patching "processors" over the vanilla
// jar. Reimplementing that would be a project of its own, so instead we
// download the official installer and run it headless with --installClient,
// pointing it at our shared data folder. The result is a normal version
// profile that the launcher already knows how to start.

const FORGE_PROMOTIONS: &str =
    "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
const FORGE_MAVEN: &str = "https://maven.minecraftforge.net/net/minecraftforge/forge";
const NEOFORGE_META: &str =
    "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
const NEOFORGE_MAVEN: &str = "https://maven.neoforged.net/releases/net/neoforged/neoforge";

/// Forge publishes a "recommended" and a "latest" build per Minecraft version.
pub async fn list_forge_versions(mc_version: &str) -> anyhow::Result<Vec<LoaderVersion>> {
    let json: serde_json::Value = http()?
        .get(FORGE_PROMOTIONS)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let promos = json
        .get("promos")
        .and_then(|v| v.as_object())
        .ok_or_else(|| anyhow::anyhow!("Unexpected response from Forge"))?;

    let mut out = Vec::new();
    // Recommended first - it is the safer default.
    if let Some(v) = promos
        .get(&format!("{}-recommended", mc_version))
        .and_then(|v| v.as_str())
    {
        out.push(LoaderVersion { version: v.to_string(), stable: true });
    }
    if let Some(v) = promos
        .get(&format!("{}-latest", mc_version))
        .and_then(|v| v.as_str())
    {
        if !out.iter().any(|l| l.version == v) {
            out.push(LoaderVersion { version: v.to_string(), stable: false });
        }
    }

    if out.is_empty() {
        anyhow::bail!("Forge has no build for Minecraft {}", mc_version);
    }
    Ok(out)
}

/// NeoForge versions encode the Minecraft version: 1.21.1 -> 21.1.x
fn neoforge_prefix(mc_version: &str) -> Option<String> {
    let parts: Vec<&str> = mc_version.split('.').collect();
    if parts.len() < 2 || parts[0] != "1" {
        return None;
    }
    let minor = parts[1];
    let patch = parts.get(2).copied().unwrap_or("0");
    Some(format!("{}.{}", minor, patch))
}

/// Reads NeoForge's maven-metadata.xml. Pulling in a full XML parser for one
/// file would be overkill, so we scan for <version> elements directly.
pub async fn list_neoforge_versions(mc_version: &str) -> anyhow::Result<Vec<LoaderVersion>> {
    let prefix = neoforge_prefix(mc_version)
        .ok_or_else(|| anyhow::anyhow!("NeoForge does not support Minecraft {}", mc_version))?;

    let xml = http()?
        .get(NEOFORGE_META)
        .send()
        .await?
        .error_for_status()?
        .text()
        .await?;

    let mut all: Vec<String> = Vec::new();
    for chunk in xml.split("<version>").skip(1) {
        if let Some(end) = chunk.find("</version>") {
            all.push(chunk[..end].trim().to_string());
        }
    }

    // Newest first
    let mut matching: Vec<String> = all
        .into_iter()
        .filter(|v| v.starts_with(&format!("{}.", prefix)))
        .collect();
    matching.reverse();

    if matching.is_empty() {
        anyhow::bail!("NeoForge has no build for Minecraft {}", mc_version);
    }

    Ok(matching
        .into_iter()
        .map(|v| {
            let stable = !v.contains("beta") && !v.contains("alpha");
            LoaderVersion { version: v, stable }
        })
        .collect())
}

/// The installer refuses to run without a launcher_profiles.json in the target
/// directory - it wants to add a profile entry there, vanilla-launcher style.
fn ensure_launcher_profiles(dir: &std::path::Path) -> anyhow::Result<()> {
    let file = dir.join("launcher_profiles.json");
    if !file.exists() {
        std::fs::write(
            &file,
            r#"{"profiles":{},"selectedProfile":"","clientToken":"","authenticationDatabase":{},"launcherVersion":{},"settings":{}}"#,
        )?;
    }
    // NeoForge's installer looks for the microsoft-store variant on some builds
    let alt = dir.join("launcher_profiles_microsoft_store.json");
    if !alt.exists() {
        std::fs::copy(&file, &alt).ok();
    }
    Ok(())
}

fn list_version_dirs(cfg: &LauncherConfig) -> Vec<String> {
    std::fs::read_dir(cfg.versions_dir())
        .map(|entries| {
            entries
                .flatten()
                .filter(|e| e.path().is_dir())
                .map(|e| e.file_name().to_string_lossy().to_string())
                .collect()
        })
        .unwrap_or_default()
}

/// Downloads and runs the Forge or NeoForge installer, returning the version id
/// it produced.
pub async fn install_forge_like(
    app: &AppHandle,
    cfg: &LauncherConfig,
    kind: &str,
    mc_version: &str,
    loader_version: &str,
) -> anyhow::Result<String> {
    let (url, label) = match kind {
        "forge" => (
            format!(
                "{}/{}-{}/forge-{}-{}-installer.jar",
                FORGE_MAVEN, mc_version, loader_version, mc_version, loader_version
            ),
            format!("Forge {}", loader_version),
        ),
        "neoforge" => (
            format!(
                "{}/{}/neoforge-{}-installer.jar",
                NEOFORGE_MAVEN, loader_version, loader_version
            ),
            format!("NeoForge {}", loader_version),
        ),
        other => anyhow::bail!("Unsupported loader: {}", other),
    };

    emit_progress(app, InstallProgress {
        stage: "loader".into(),
        current: 0,
        total: 3,
        file: format!("{} - downloading installer", label),
    });

    let install_dir = cfg.install_dir();
    std::fs::create_dir_all(&install_dir)?;
    ensure_launcher_profiles(&install_dir)?;

    // Keep the installer out of the way in a temp subfolder
    let tmp_dir = install_dir.join("tmp");
    fs::create_dir_all(&tmp_dir).await?;
    let installer_path = tmp_dir.join(format!("{}-{}-installer.jar", kind, loader_version));

    let bytes = http()?
        .get(&url)
        .send()
        .await?
        .error_for_status()?
        .bytes()
        .await?;
    {
        let mut f = fs::File::create(&installer_path).await?;
        f.write_all(&bytes).await?;
        f.flush().await?;
    }

    // The installer is a Java program, so we need a JVM. The vanilla version
    // for this Minecraft release has already been installed at this point,
    // so its runtime is the right one to use.
    let vanilla_json_path = cfg
        .versions_dir()
        .join(mc_version)
        .join(format!("{}.json", mc_version));
    let java_bin = if vanilla_json_path.exists() {
        let details: serde_json::Value = serde_json::from_slice(&std::fs::read(&vanilla_json_path)?)?;
        crate::launcher::java::resolve_java_binary(cfg, &details)
    } else {
        "java".to_string()
    };

    emit_progress(app, InstallProgress {
        stage: "loader".into(),
        current: 1,
        total: 3,
        file: format!("{} - running installer", label),
    });

    let before = list_version_dirs(cfg);

    // Blocking child process, so keep it off the async runtime's threads.
    let installer_path_c = installer_path.clone();
    let install_dir_c = install_dir.clone();
    let java_c = java_bin.clone();
    let output = tokio::task::spawn_blocking(move || {
        std::process::Command::new(&java_c)
            .arg("-jar")
            .arg(&installer_path_c)
            .arg("--installClient")
            .arg(&install_dir_c)
            .output()
    })
    .await??;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        anyhow::bail!(
            "The {} installer failed. Output:\n{}\n{}",
            label,
            stdout.chars().take(800).collect::<String>(),
            stderr.chars().take(800).collect::<String>()
        );
    }

    emit_progress(app, InstallProgress {
        stage: "loader".into(),
        current: 2,
        total: 3,
        file: format!("{} - finishing", label),
    });

    // Whatever new folder appeared under versions/ is the profile it created.
    let after = list_version_dirs(cfg);
    let new_id = after
        .iter()
        .find(|id| !before.contains(id))
        .cloned()
        .or_else(|| {
            // Fall back to the conventional naming if nothing new was detected
            let guess = match kind {
                "forge" => format!("{}-forge-{}", mc_version, loader_version),
                _ => format!("neoforge-{}", loader_version),
            };
            after.into_iter().find(|id| *id == guess)
        })
        .ok_or_else(|| {
            anyhow::anyhow!(
                "The {} installer ran but produced no version profile.",
                label
            )
        })?;

    std::fs::remove_file(&installer_path).ok();

    emit_progress(app, InstallProgress {
        stage: "loader".into(),
        current: 3,
        total: 3,
        file: new_id.clone(),
    });

    Ok(new_id)
}

/// Single entry point used by the install flow.
pub async fn list_versions_for(loader: &str, mc_version: &str) -> anyhow::Result<Vec<LoaderVersion>> {
    match loader {
        "fabric" | "quilt" => list_loader_versions(loader, mc_version).await,
        "forge" => list_forge_versions(mc_version).await,
        "neoforge" => list_neoforge_versions(mc_version).await,
        _ => Ok(vec![]),
    }
}
