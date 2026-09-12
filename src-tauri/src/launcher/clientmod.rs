use crate::launcher::instance::Instance;
use crate::launcher::progress::{emit_progress, InstallProgress};
use tauri::AppHandle;
use tokio::fs;
use tokio::io::AsyncWriteExt;

/// Where the in-game companion mod is published.
const MOD_REPO: &str = "Finanzinstitut/Space-Client-Mod";

/// Fixed file name, so installing a new build replaces the old one instead of
/// leaving two versions in the folder fighting each other.
const FILE_NAME: &str = "spaceclient.jar";

fn http() -> anyhow::Result<reqwest::Client> {
    Ok(reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .build()?)
}

/// The mod is a Fabric mod; Quilt loads it too. Forge and NeoForge cannot.
pub fn supports_loader(loader: &str) -> bool {
    matches!(loader, "fabric" | "quilt")
}

/// Installs (or refreshes) the companion mod in this instance.
/// Returns the version tag that was installed, or None if there was nothing to do.
pub async fn install_client_mod(app: &AppHandle, instance: &Instance) -> anyhow::Result<Option<String>> {
    if !instance.install_client_mod {
        return Ok(None);
    }
    if !supports_loader(&instance.loader) {
        return Ok(None);
    }

    emit_progress(app, InstallProgress {
        stage: "clientmod".into(),
        current: 0,
        total: 1,
        file: "Space Client mod".into(),
    });

    let client = http()?;
    let url = format!("https://api.github.com/repos/{}/releases/latest", MOD_REPO);
    let resp = client.get(&url).send().await?;

    if !resp.status().is_success() {
        // No release published yet is a normal state early on, not an error
        // worth failing the whole instance install over.
        return Ok(None);
    }

    let release: serde_json::Value = resp.json().await?;
    let tag = release
        .get("tag_name")
        .and_then(|v| v.as_str())
        .unwrap_or("unknown")
        .to_string();

    // Pick the mod jar, skipping the sources and dev jars Loom also produces.
    let mods_dir = instance.mods_dir();
    let dest = mods_dir.join(FILE_NAME);

    // Already on this build. Checked before anything is downloaded, so pressing
    // install on an instance that is current costs one small API call instead of
    // a few megabytes and a rewritten file.
    if let Some(existing) = installed_version(&dest) {
        if existing == tag {
            emit_progress(app, InstallProgress {
                stage: "clientmod".into(),
                current: 1,
                total: 1,
                file: format!("Space Client mod {} already installed", tag),
            });
            return Ok(Some(tag));
        }
    }

    let asset_url = release
        .get("assets")
        .and_then(|a| a.as_array())
        .and_then(|assets| {
            assets.iter().find(|a| {
                let name = a.get("name").and_then(|n| n.as_str()).unwrap_or("");
                name.ends_with(".jar")
                    && !name.contains("sources")
                    && !name.contains("dev")
                    && !name.contains("shadow")
            })
        })
        .and_then(|a| a.get("browser_download_url"))
        .and_then(|u| u.as_str())
        .map(String::from);

    let Some(asset_url) = asset_url else {
        return Ok(None);
    };

    fs::create_dir_all(&mods_dir).await?;

    let bytes = client
        .get(&asset_url)
        .send()
        .await?
        .error_for_status()?
        .bytes()
        .await?;

    // Checked against what the jar says about itself, not against its file
    // name. A release can carry a build for a different Minecraft version, and
    // a name is whatever somebody typed - fabric.mod.json is what the loader
    // will actually read, so it is what decides here too.
    match jar_fits(&bytes, instance) {
        Fit::Yes => {}
        Fit::No(reason) => {
            emit_progress(app, InstallProgress {
                stage: "clientmod".into(),
                current: 1,
                total: 1,
                file: format!("Space Client mod skipped: {}", reason),
            });
            return Ok(None);
        }
    }

    let mut f = fs::File::create(&dest).await?;
    f.write_all(&bytes).await?;
    f.flush().await?;

    emit_progress(app, InstallProgress {
        stage: "clientmod".into(),
        current: 1,
        total: 1,
        file: format!("Space Client mod {}", tag),
    });

    Ok(Some(tag))
}

/// Whether a downloaded jar belongs in this instance.
enum Fit {
    Yes,
    No(String),
}

/// Reads the version out of an installed jar, so a reinstall can be skipped.
///
/// From the jar rather than from a note kept beside it: a note goes stale the
/// moment somebody replaces the file by hand, and then the launcher insists
/// everything is current while the folder says otherwise.
fn installed_version(path: &std::path::Path) -> Option<String> {
    let file = std::fs::File::open(path).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let mut entry = archive.by_name("fabric.mod.json").ok()?;

    let mut text = String::new();
    use std::io::Read;
    entry.read_to_string(&mut text).ok()?;

    let json: serde_json::Value = serde_json::from_str(&text).ok()?;
    json.get("version")?.as_str().map(String::from)
}

/// Whether the jar's own declared dependencies match this instance.
///
/// The two things the user asked for, taken from the only place that cannot
/// disagree with reality: what the loader itself will read. A mod built for a
/// different Minecraft version does not fail politely - it crashes on a missing
/// class halfway through loading a world, which is a far worse way to find out.
fn jar_fits(bytes: &[u8], instance: &Instance) -> Fit {
    let reader = std::io::Cursor::new(bytes);

    let mut archive = match zip::ZipArchive::new(reader) {
        Ok(a) => a,
        Err(_) => return Fit::No("the download is not a readable jar".into()),
    };

    let mut text = String::new();
    {
        let mut entry = match archive.by_name("fabric.mod.json") {
            Ok(e) => e,
            Err(_) => return Fit::No("the jar carries no fabric.mod.json".into()),
        };
        use std::io::Read;
        if entry.read_to_string(&mut text).is_err() {
            return Fit::No("the jar's fabric.mod.json could not be read".into());
        }
    }

    let json: serde_json::Value = match serde_json::from_str(&text) {
        Ok(v) => v,
        Err(_) => return Fit::No("the jar's fabric.mod.json is malformed".into()),
    };

    // Loader first: a Forge instance has no business with this file at all,
    // and the caller has already checked, so this is the belt to that braces.
    if !supports_loader(&instance.loader) {
        return Fit::No(format!("{} cannot load a Fabric mod", instance.loader));
    }

    let depends = json.get("depends");

    let wanted_mc = depends
        .and_then(|d| d.get("minecraft"))
        .and_then(|v| v.as_str())
        .unwrap_or("*");

    if !version_matches(wanted_mc, &instance.mc_version) {
        return Fit::No(format!(
            "built for Minecraft {}, this instance is {}",
            wanted_mc, instance.mc_version
        ));
    }

    Fit::Yes
}

/// A deliberately small reading of Fabric's version ranges.
///
/// Only the shapes this project actually publishes: a wildcard, an exact
/// version, and the tilde range Fabric documents for a release series. Anything
/// more elaborate is treated as a match rather than guessed at, because
/// refusing to install over a range this cannot parse would be a worse failure
/// than installing something that then declines to load and says why.
fn version_matches(range: &str, version: &str) -> bool {
    let range = range.trim();

    if range == "*" || range.is_empty() {
        return true;
    }
    if range == version {
        return true;
    }

    if let Some(base) = range.strip_prefix('~') {
        // ~26.2 covers the 26.2 series: 26.2, 26.2.1 and so on
        return version == base || version.starts_with(&format!("{}.", base));
    }

    if let Some(base) = range.strip_prefix(">=") {
        return version >= base.trim();
    }

    // Something this does not understand. Say yes and let the loader have the
    // final word - it will refuse with a message naming the real requirement.
    true
}

/// Modrinth slug of the cosmetics mod offered alongside the client.
pub const COSMETICA_PROJECT: &str = "cosmetica";

/// What a round of extras installation produced, so the UI can say something
/// useful instead of failing the whole operation over a cosmetics mod.
#[derive(Debug, Default, serde::Serialize, Clone)]
pub struct ExtrasReport {
    pub client_mod: Option<String>,
    pub cosmetica: bool,
    /// Human readable reasons for anything that did not happen.
    pub notes: Vec<String>,
}

/// Installs the optional extras into an instance: the Space Client companion
/// mod and, if asked for, Cosmetica.
///
/// Both instance creation and modpack import go through here. Previously the
/// client mod was only installed as a side effect of `install_instance`, and
/// Cosmetica only from the create-instance button in the frontend - which is
/// why an imported pack ended up with neither.
///
/// Nothing in here is fatal: the instance is already usable, and losing a
/// cosmetics mod is not a reason to leave the user with a failed import.
pub async fn install_extras(
    app: &AppHandle,
    instance: &Instance,
    want_cosmetica: bool,
) -> ExtrasReport {
    let mut report = ExtrasReport::default();

    if instance.install_client_mod {
        if supports_loader(&instance.loader) {
            match install_client_mod(app, instance).await {
                Ok(tag) => report.client_mod = tag,
                Err(e) => report
                    .notes
                    .push(format!("The Space Client mod could not be installed: {}", e)),
            }
        } else {
            report.notes.push(format!(
                "The Space Client mod needs Fabric or Quilt, so it was skipped on this {} instance.",
                instance.loader
            ));
        }
    } else {
        // The toggle may have been switched off after an earlier install.
        remove_client_mod(instance).ok();
    }

    if want_cosmetica {
        if instance.loader == "vanilla" {
            report
                .notes
                .push("Cosmetica needs a mod loader, so it was skipped.".to_string());
        } else {
            match crate::launcher::mods::install_mod(
                app,
                instance.id.clone(),
                COSMETICA_PROJECT.to_string(),
                "mod".to_string(),
            )
            .await
            {
                Ok(_) => report.cosmetica = true,
                Err(e) => report
                    .notes
                    .push(format!("Cosmetica could not be installed: {}", e)),
            }
        }
    }

    report
}

/// Removes the companion mod, used when the per-instance toggle is switched off.
pub fn remove_client_mod(instance: &Instance) -> anyhow::Result<()> {
    let path = instance.mods_dir().join(FILE_NAME);
    if path.exists() {
        std::fs::remove_file(path)?;
    }
    Ok(())
}
