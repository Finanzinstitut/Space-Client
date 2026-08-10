use crate::launcher::config::LauncherConfig;
use crate::launcher::java;
use crate::launcher::manifest::{fetch_version_details, fetch_version_manifest};
use crate::launcher::progress::{emit_progress, InstallProgress};
use futures_util::StreamExt;
use sha1::{Digest, Sha1};
use std::path::Path;
use tauri::AppHandle;
use tokio::fs;
use tokio::io::AsyncWriteExt;

fn current_os_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "osx"
    } else {
        "linux"
    }
}

fn rule_allows(rules: &serde_json::Value) -> bool {
    let os_name = current_os_name();
    let mut allowed = false;
    if let Some(arr) = rules.as_array() {
        for rule in arr {
            let action = rule.get("action").and_then(|v| v.as_str()).unwrap_or("allow");
            let os_ok = match rule.get("os") {
                Some(os) => {
                    let name_ok = os
                        .get("name")
                        .and_then(|v| v.as_str())
                        .map(|n| n == os_name)
                        .unwrap_or(true);
                    name_ok
                }
                None => true,
            };
            if os_ok {
                allowed = action == "allow";
            }
        }
    }
    allowed
}

async fn download_to_file(url: &str, dest: &Path, expected_sha1: Option<&str>) -> anyhow::Result<()> {
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).await?;
    }

    // Skip re-download if file exists and hash matches (or no hash to check and size > 0)
    if dest.exists() {
        if let Some(sha1) = expected_sha1 {
            if let Ok(existing) = fs::read(dest).await {
                let mut hasher = Sha1::new();
                hasher.update(&existing);
                let digest = hex::encode(hasher.finalize());
                if digest == sha1 {
                    return Ok(());
                }
            }
        } else {
            return Ok(());
        }
    }

    let client = reqwest::Client::builder().user_agent("SpaceClient/0.1").build()?;
    let resp = client.get(url).send().await?.error_for_status()?;
    let bytes = resp.bytes().await?;

    if let Some(sha1) = expected_sha1 {
        let mut hasher = Sha1::new();
        hasher.update(&bytes);
        let digest = hex::encode(hasher.finalize());
        if digest != sha1 {
            anyhow::bail!("SHA1 mismatch for {}: expected {} got {}", url, sha1, digest);
        }
    }

    let mut f = fs::File::create(dest).await?;
    f.write_all(&bytes).await?;
    Ok(())
}

/// Installs (or repairs) a vanilla version into the user-configured install path.
pub async fn install_version(app: AppHandle, cfg: LauncherConfig, version_id: String) -> anyhow::Result<()> {
    cfg.ensure_dirs()?;

    emit_progress(&app, InstallProgress { stage: "manifest".into(), current: 0, total: 1, file: version_id.clone() });
    let manifest = fetch_version_manifest().await?;
    let entry = manifest
        .versions
        .iter()
        .find(|v| v.id == version_id)
        .ok_or_else(|| anyhow::anyhow!("Version {} not found", version_id))?;

    let details = fetch_version_details(&entry.url).await?;

    // Save version json
    let version_dir = cfg.versions_dir().join(&version_id);
    fs::create_dir_all(&version_dir).await?;
    let version_json_path = version_dir.join(format!("{}.json", version_id));
    fs::write(&version_json_path, serde_json::to_vec_pretty(&details)?).await?;

    // --- java runtime (matching this version's requirement) ---
    match java::ensure_java(&app, &cfg, &details).await {
        Ok(Some(path)) => println!("Using java: {}", path),
        Ok(None) => println!("No bundled runtime for this platform - falling back to system java"),
        Err(e) => eprintln!("Java-Runtime konnte nicht geladen werden: {} - versuche System-Java", e),
    }

    // --- client jar ---
    emit_progress(&app, InstallProgress { stage: "client".into(), current: 0, total: 1, file: format!("{}.jar", version_id) });
    if let Some(client) = details.pointer("/downloads/client") {
        let url = client.get("url").and_then(|v| v.as_str()).unwrap_or_default();
        let sha1 = client.get("sha1").and_then(|v| v.as_str());
        let jar_path = version_dir.join(format!("{}.jar", version_id));
        download_to_file(url, &jar_path, sha1).await?;
    }
    emit_progress(&app, InstallProgress { stage: "client".into(), current: 1, total: 1, file: "done".into() });

    // --- libraries (+ natives) ---
    let empty = vec![];
    let libraries = details.get("libraries").and_then(|v| v.as_array()).unwrap_or(&empty);
    let total_libs = libraries.len() as u64;

    for (i, lib) in libraries.iter().enumerate() {
        if let Some(rules) = lib.get("rules") {
            if !rule_allows(rules) {
                continue;
            }
        }
        let name = lib.get("name").and_then(|v| v.as_str()).unwrap_or("?");
        emit_progress(&app, InstallProgress { stage: "libraries".into(), current: i as u64, total: total_libs, file: name.to_string() });

        if let Some(artifact) = lib.pointer("/downloads/artifact") {
            let url = artifact.get("url").and_then(|v| v.as_str()).unwrap_or_default();
            let path = artifact.get("path").and_then(|v| v.as_str()).unwrap_or_default();
            let sha1 = artifact.get("sha1").and_then(|v| v.as_str());
            if !url.is_empty() && !path.is_empty() {
                let dest = cfg.libraries_dir().join(path);
                download_to_file(url, &dest, sha1).await?;
            }
        }

        // Natives (older-style "classifiers" mechanism, still used by <=1.18 libraries)
        if let Some(natives_map) = lib.get("natives") {
            if let Some(classifier_key) = natives_map.get(current_os_name()).and_then(|v| v.as_str()) {
                let classifier_key = classifier_key.replace("${arch}", "64");
                if let Some(classifier) = lib.pointer(&format!("/downloads/classifiers/{}", classifier_key)) {
                    let url = classifier.get("url").and_then(|v| v.as_str()).unwrap_or_default();
                    let path = classifier.get("path").and_then(|v| v.as_str()).unwrap_or_default();
                    let sha1 = classifier.get("sha1").and_then(|v| v.as_str());
                    if !url.is_empty() && !path.is_empty() {
                        let dest = cfg.libraries_dir().join(path);
                        download_to_file(url, &dest, sha1).await?;
                    }
                }
            }
        }
    }

    // --- assets ---
    if let Some(asset_index) = details.get("assetIndex") {
        let url = asset_index.get("url").and_then(|v| v.as_str()).unwrap_or_default();
        let id = asset_index.get("id").and_then(|v| v.as_str()).unwrap_or("legacy");
        let index_dest = cfg.assets_dir().join("indexes").join(format!("{}.json", id));
        download_to_file(url, &index_dest, None).await?;

        let index_data = fs::read(&index_dest).await?;
        let index_json: serde_json::Value = serde_json::from_slice(&index_data)?;
        let objects = index_json.get("objects").and_then(|v| v.as_object()).cloned().unwrap_or_default();
        let total_assets = objects.len() as u64;

        // Limit concurrent downloads so we don't open thousands of sockets at once
        let entries: Vec<(String, String, u64)> = objects
            .into_iter()
            .filter_map(|(name, obj)| {
                let hash = obj.get("hash")?.as_str()?.to_string();
                let size = obj.get("size").and_then(|v| v.as_u64()).unwrap_or(0);
                Some((name, hash, size))
            })
            .collect();

        let concurrency = 16;
        let cfg_ref = &cfg;
        let app_ref = &app;
        futures_util::stream::iter(entries.into_iter().enumerate())
            .for_each_concurrent(concurrency, |(i, (name, hash, _size))| async move {
                let sub = &hash[0..2];
                let dest = cfg_ref.assets_dir().join("objects").join(sub).join(&hash);
                let url = format!("https://resources.download.minecraft.net/{}/{}", sub, hash);
                if let Err(e) = download_to_file(&url, &dest, Some(&hash)).await {
                    eprintln!("asset download failed for {}: {}", name, e);
                }
                emit_progress(app_ref, InstallProgress { stage: "assets".into(), current: i as u64, total: total_assets, file: name });
            })
            .await;
    }

    emit_progress(&app, InstallProgress { stage: "done".into(), current: 1, total: 1, file: version_id });
    Ok(())
}

pub fn is_version_installed(cfg: &LauncherConfig, version_id: &str) -> bool {
    let jar = cfg.versions_dir().join(version_id).join(format!("{}.jar", version_id));
    jar.exists()
}
