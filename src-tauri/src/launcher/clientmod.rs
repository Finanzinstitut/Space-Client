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

    let mods_dir = instance.mods_dir();
    fs::create_dir_all(&mods_dir).await?;

    let bytes = client
        .get(&asset_url)
        .send()
        .await?
        .error_for_status()?
        .bytes()
        .await?;

    let dest = mods_dir.join(FILE_NAME);
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
