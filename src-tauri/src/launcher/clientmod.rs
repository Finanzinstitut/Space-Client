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

/// Removes the companion mod, used when the per-instance toggle is switched off.
pub fn remove_client_mod(instance: &Instance) -> anyhow::Result<()> {
    let path = instance.mods_dir().join(FILE_NAME);
    if path.exists() {
        std::fs::remove_file(path)?;
    }
    Ok(())
}
