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
