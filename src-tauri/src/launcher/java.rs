use crate::launcher::config::LauncherConfig;
use crate::launcher::progress::{emit_progress, InstallProgress};
use futures_util::StreamExt;
use sha1::{Digest, Sha1};
use std::path::{Path, PathBuf};
use tauri::AppHandle;
use tokio::fs;
use tokio::io::AsyncWriteExt;

/// Mojang's index of all shipped Java runtimes, per platform and component.
const JAVA_RUNTIME_MANIFEST: &str =
    "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

/// The platform keys Mojang uses inside that index.
pub fn platform_key() -> Option<&'static str> {
    if cfg!(target_os = "windows") {
        if cfg!(target_arch = "aarch64") {
            Some("windows-arm64")
        } else if cfg!(target_arch = "x86") {
            Some("windows-x86")
        } else {
            Some("windows-x64")
        }
    } else if cfg!(target_os = "macos") {
        if cfg!(target_arch = "aarch64") {
            Some("mac-os-arm64")
        } else {
            Some("mac-os")
        }
    } else if cfg!(target_os = "linux") {
        if cfg!(target_arch = "x86") {
            Some("linux-i386")
        } else if cfg!(target_arch = "x86_64") {
            Some("linux")
        } else {
            // Mojang ships no runtime for linux-arm64 - we fall back to system java
            None
        }
    } else {
        None
    }
}

/// Reads the required runtime component from a version JSON.
/// Versions before 1.17 have no `javaVersion` block and need Java 8 ("jre-legacy").
pub fn required_component(details: &serde_json::Value) -> String {
    details
        .pointer("/javaVersion/component")
        .and_then(|v| v.as_str())
        .unwrap_or("jre-legacy")
        .to_string()
}

fn runtime_dir(cfg: &LauncherConfig, component: &str) -> PathBuf {
    let plat = platform_key().unwrap_or("unknown");
    cfg.runtimes_dir().join(component).join(plat)
}

/// Returns the java executable inside an installed runtime, if present.
fn java_binary_in(dir: &Path) -> Option<PathBuf> {
    let candidates = if cfg!(target_os = "windows") {
        vec![dir.join("bin").join("javaw.exe"), dir.join("bin").join("java.exe")]
    } else if cfg!(target_os = "macos") {
        vec![
            dir.join("jre.bundle/Contents/Home/bin/java"),
            dir.join("bin").join("java"),
        ]
    } else {
        vec![dir.join("bin").join("java")]
    };
    candidates.into_iter().find(|p| p.exists())
}

/// Resolves which java binary to launch with:
/// 1. user override from settings, 2. downloaded Mojang runtime, 3. system `java` on PATH.
pub fn resolve_java_binary(cfg: &LauncherConfig, details: &serde_json::Value) -> String {
    if !cfg.custom_java_path.trim().is_empty() {
        return cfg.custom_java_path.clone();
    }
    let component = required_component(details);
    if let Some(bin) = java_binary_in(&runtime_dir(cfg, &component)) {
        return bin.to_string_lossy().to_string();
    }
    "java".to_string()
}

async fn download_file(url: &str, dest: &Path, sha1: Option<&str>, executable: bool) -> anyhow::Result<()> {
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).await?;
    }

    // Skip if already present and intact
    if dest.exists() {
        match sha1 {
            Some(expected) => {
                if let Ok(existing) = fs::read(dest).await {
                    let mut h = Sha1::new();
                    h.update(&existing);
                    if hex::encode(h.finalize()) == expected {
                        return Ok(());
                    }
                }
            }
            None => return Ok(()),
        }
    }

    let client = reqwest::Client::builder().user_agent("SpaceClient/0.1").build()?;
    let bytes = client.get(url).send().await?.error_for_status()?.bytes().await?;

    let mut f = fs::File::create(dest).await?;
    f.write_all(&bytes).await?;
    f.flush().await?;
    drop(f);

    // On Linux/macOS the java binary and its helpers must be marked executable,
    // otherwise the launch fails with "Permission denied".
    #[cfg(unix)]
    {
        if executable {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(dest)?.permissions();
            perms.set_mode(0o755);
            std::fs::set_permissions(dest, perms)?;
        }
    }
    #[cfg(not(unix))]
    let _ = executable;

    Ok(())
}

/// Downloads the Java runtime required by this version, if it isn't installed yet.
/// Returns the path to the java binary, or None if we should fall back to system java.
pub async fn ensure_java(
    app: &AppHandle,
    cfg: &LauncherConfig,
    details: &serde_json::Value,
) -> anyhow::Result<Option<String>> {
    if !cfg.custom_java_path.trim().is_empty() {
        return Ok(Some(cfg.custom_java_path.clone()));
    }

    let component = required_component(details);
    let target_dir = runtime_dir(cfg, &component);

    // Already installed?
    if let Some(bin) = java_binary_in(&target_dir) {
        return Ok(Some(bin.to_string_lossy().to_string()));
    }

    let plat = match platform_key() {
        Some(p) => p,
        None => {
            // No Mojang runtime for this platform - the user's system java has to do.
            return Ok(None);
        }
    };

    emit_progress(app, InstallProgress {
        stage: "java".into(),
        current: 0,
        total: 1,
        file: format!("{} wird gesucht", component),
    });

    let client = reqwest::Client::builder().user_agent("SpaceClient/0.1").build()?;
    let index: serde_json::Value = client
        .get(JAVA_RUNTIME_MANIFEST)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let entries = index
        .pointer(&format!("/{}/{}", plat, component))
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();

    let first = match entries.first() {
        Some(e) => e,
        None => return Ok(None), // component not offered for this platform
    };

    let manifest_url = first
        .pointer("/manifest/url")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow::anyhow!("Kein Manifest fuer Java-Runtime {}", component))?;

    let manifest: serde_json::Value = client
        .get(manifest_url)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let files = manifest
        .get("files")
        .and_then(|v| v.as_object())
        .cloned()
        .unwrap_or_default();

    // Split into the three entry kinds Mojang uses: directory, file, link
    let mut to_download: Vec<(String, String, String, bool)> = Vec::new(); // path, url, sha1, executable
    let mut links: Vec<(String, String)> = Vec::new(); // path, target

    for (rel_path, entry) in files.iter() {
        let kind = entry.get("type").and_then(|v| v.as_str()).unwrap_or("file");
        match kind {
            "directory" => {
                fs::create_dir_all(target_dir.join(rel_path)).await.ok();
            }
            "link" => {
                if let Some(t) = entry.get("target").and_then(|v| v.as_str()) {
                    links.push((rel_path.clone(), t.to_string()));
                }
            }
            _ => {
                if let Some(raw) = entry.pointer("/downloads/raw") {
                    let url = raw.get("url").and_then(|v| v.as_str()).unwrap_or_default().to_string();
                    let sha1 = raw.get("sha1").and_then(|v| v.as_str()).unwrap_or_default().to_string();
                    let exec = entry.get("executable").and_then(|v| v.as_bool()).unwrap_or(false);
                    if !url.is_empty() {
                        to_download.push((rel_path.clone(), url, sha1, exec));
                    }
                }
            }
        }
    }

    let total = to_download.len() as u64;
    let done = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(0));

    futures_util::stream::iter(to_download.into_iter())
        .for_each_concurrent(8, |(rel_path, url, sha1, exec)| {
            let target_dir = target_dir.clone();
            let done = done.clone();
            let component = component.clone();
            async move {
                let dest = target_dir.join(&rel_path);
                let sha_opt = if sha1.is_empty() { None } else { Some(sha1.as_str()) };
                if let Err(e) = download_file(&url, &dest, sha_opt, exec).await {
                    eprintln!("Java-Datei fehlgeschlagen {}: {}", rel_path, e);
                }
                let n = done.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
                emit_progress(app, InstallProgress {
                    stage: "java".into(),
                    current: n,
                    total,
                    file: format!("{} - {}", component, rel_path),
                });
            }
        })
        .await;

    // Symlinks (mac/linux runtimes use a few)
    for (rel_path, target) in links {
        let link_path = target_dir.join(&rel_path);
        if link_path.exists() {
            continue;
        }
        if let Some(parent) = link_path.parent() {
            fs::create_dir_all(parent).await.ok();
        }
        #[cfg(unix)]
        {
            let _ = std::os::unix::fs::symlink(&target, &link_path);
        }
        #[cfg(not(unix))]
        {
            let _ = &target;
        }
    }

    Ok(java_binary_in(&target_dir).map(|p| p.to_string_lossy().to_string()))
}
