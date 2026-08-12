use crate::launcher::config::LauncherConfig;
use crate::launcher::instance::{self, Instance};
use crate::launcher::progress::{emit_progress, InstallProgress};
use futures_util::StreamExt;
use serde::Serialize;
use std::io::Read;
use std::path::{Component, Path, PathBuf};
use tauri::AppHandle;
use tokio::fs;
use tokio::io::AsyncWriteExt;

#[derive(Debug, Serialize, Clone)]
pub struct ImportResult {
    pub instance: Instance,
    /// Files the pack referenced that could not be fetched automatically.
    pub skipped: Vec<String>,
    /// Human readable note shown after the import, e.g. about CurseForge keys.
    pub note: String,
}

fn http() -> anyhow::Result<reqwest::Client> {
    Ok(reqwest::Client::builder()
        .user_agent("Finanzinstitut/SpaceClient/0.1")
        .build()?)
}

/// Rejects absolute paths and anything containing "..", so a malicious archive
/// cannot write outside the instance folder.
fn safe_relative(path: &str) -> Option<PathBuf> {
    let p = PathBuf::from(path.replace('\\', "/"));
    if p.is_absolute() {
        return None;
    }
    for c in p.components() {
        match c {
            Component::Normal(_) => {}
            _ => return None,
        }
    }
    Some(p)
}

fn read_zip_entry(archive: &mut zip::ZipArchive<std::fs::File>, name: &str) -> Option<Vec<u8>> {
    let mut entry = archive.by_name(name).ok()?;
    let mut buf = Vec::new();
    entry.read_to_end(&mut buf).ok()?;
    Some(buf)
}

/// Copies the pack's overrides into the instance's .minecraft folder.
/// `client-overrides` wins over plain `overrides`, matching the mrpack spec.
fn extract_overrides(
    archive: &mut zip::ZipArchive<std::fs::File>,
    game_dir: &Path,
    prefixes: &[&str],
) -> anyhow::Result<u32> {
    let mut count = 0;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i)?;
        let name = entry.name().to_string();
        if name.ends_with('/') {
            continue;
        }
        let Some(prefix) = prefixes.iter().find(|p| name.starts_with(**p)) else {
            continue;
        };
        let rel = &name[prefix.len()..];
        let Some(rel_path) = safe_relative(rel) else {
            continue;
        };
        let dest = game_dir.join(rel_path);
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut buf = Vec::new();
        entry.read_to_end(&mut buf)?;
        std::fs::write(&dest, buf)?;
        count += 1;
    }
    Ok(count)
}

/// Maps a mrpack dependency block onto our loader names.
fn loader_from_mrpack(deps: &serde_json::Value) -> (String, String) {
    for (key, loader) in [
        ("fabric-loader", "fabric"),
        ("quilt-loader", "quilt"),
        ("neoforge", "neoforge"),
        ("forge", "forge"),
    ] {
        if let Some(v) = deps.get(key).and_then(|v| v.as_str()) {
            return (loader.to_string(), v.to_string());
        }
    }
    ("vanilla".to_string(), String::new())
}

/// CurseForge writes loader ids like "fabric-0.16.9" or "neoforge-21.1.66".
fn loader_from_curseforge(id: &str) -> (String, String) {
    let (name, version) = match id.split_once('-') {
        Some((n, v)) => (n, v.to_string()),
        None => (id, String::new()),
    };
    let loader = match name {
        "fabric" => "fabric",
        "quilt" => "quilt",
        "neoforge" => "neoforge",
        "forge" => "forge",
        _ => "vanilla",
    };
    (loader.to_string(), version)
}

/// Downloads the file list of a Modrinth pack into the instance.
async fn download_mrpack_files(
    app: &AppHandle,
    game_dir: &Path,
    files: &[serde_json::Value],
) -> Vec<String> {
    let total = files.len() as u64;
    let done = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(0));
    let skipped = std::sync::Arc::new(std::sync::Mutex::new(Vec::<String>::new()));

    futures_util::stream::iter(files.iter().cloned())
        .for_each_concurrent(6, |file| {
            let game_dir = game_dir.to_path_buf();
            let done = done.clone();
            let skipped = skipped.clone();
            async move {
                let path = file.get("path").and_then(|v| v.as_str()).unwrap_or_default();

                // Server-only files have no place in a client instance.
                if file.pointer("/env/client").and_then(|v| v.as_str()) == Some("unsupported") {
                    done.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    return;
                }

                let Some(rel) = safe_relative(path) else {
                    skipped.lock().unwrap().push(path.to_string());
                    return;
                };

                let url = file
                    .get("downloads")
                    .and_then(|d| d.as_array())
                    .and_then(|a| a.first())
                    .and_then(|u| u.as_str())
                    .unwrap_or_default()
                    .to_string();

                if url.is_empty() {
                    skipped.lock().unwrap().push(path.to_string());
                    return;
                }

                let dest = game_dir.join(rel);
                let result = async {
                    if let Some(parent) = dest.parent() {
                        fs::create_dir_all(parent).await?;
                    }
                    let bytes = http()?.get(&url).send().await?.error_for_status()?.bytes().await?;
                    let mut f = fs::File::create(&dest).await?;
                    f.write_all(&bytes).await?;
                    Ok::<(), anyhow::Error>(())
                }
                .await;

                if result.is_err() {
                    skipped.lock().unwrap().push(path.to_string());
                }

                let n = done.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
                emit_progress(app, InstallProgress {
                    stage: "modpack".into(),
                    current: n,
                    total,
                    file: path.to_string(),
                });
            }
        })
        .await;

    let out = skipped.lock().unwrap().clone();
    out
}

/// Imports a modpack archive and returns the instance it created.
pub async fn import_modpack(
    app: &AppHandle,
    cfg: &LauncherConfig,
    archive_path: String,
    parent_path: String,
) -> anyhow::Result<ImportResult> {
    let path = PathBuf::from(&archive_path);
    if !path.exists() {
        anyhow::bail!("File not found: {}", archive_path);
    }

    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();

    emit_progress(app, InstallProgress {
        stage: "modpack".into(),
        current: 0,
        total: 1,
        file: path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default(),
    });

    let file = std::fs::File::open(&path)?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|_| anyhow::anyhow!("This file is not a readable archive."))?;

    // Which index file is present tells us what kind of pack this is,
    // which is more reliable than trusting the extension alone.
    let has_modrinth = archive.by_name("modrinth.index.json").is_ok();
    let has_curseforge = archive.by_name("manifest.json").is_ok();
    let has_norisk = archive.by_name("profile.json").is_ok();

    match ext.as_str() {
        _ if has_modrinth => import_modrinth(app, cfg, &mut archive, parent_path).await,
        _ if has_norisk => import_norisk(app, cfg, &mut archive, parent_path).await,
        _ if has_curseforge => import_curseforge(app, cfg, &mut archive, parent_path).await,
        _ => anyhow::bail!(
            "Unrecognised modpack. Expected a Modrinth .mrpack, a NoRisk .noriskpack/.nrc, or a CurseForge .zip with a manifest.json."
        ),
    }
}

async fn import_modrinth(
    app: &AppHandle,
    cfg: &LauncherConfig,
    archive: &mut zip::ZipArchive<std::fs::File>,
    parent_path: String,
) -> anyhow::Result<ImportResult> {
    let raw = read_zip_entry(archive, "modrinth.index.json")
        .ok_or_else(|| anyhow::anyhow!("modrinth.index.json is missing"))?;
    let index: serde_json::Value = serde_json::from_slice(&raw)?;

    let name = index
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or("Imported pack")
        .to_string();
    let deps = index
        .get("dependencies")
        .cloned()
        .unwrap_or(serde_json::Value::Null);
    let mc_version = deps
        .get("minecraft")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow::anyhow!("The pack does not say which Minecraft version it needs."))?
        .to_string();
    let (loader, loader_version) = loader_from_mrpack(&deps);

    let inst = instance::create(
        cfg,
        name,
        mc_version,
        loader,
        loader_version,
        cfg.max_ram_mb,
        parent_path,
    )?;

    let game_dir = inst.game_dir();
    std::fs::create_dir_all(&game_dir)?;

    // Overrides first, so pack files can replace them if they overlap.
    extract_overrides(archive, &game_dir, &["overrides/", "client-overrides/"])?;

    let empty = vec![];
    let files = index.get("files").and_then(|v| v.as_array()).unwrap_or(&empty);
    let skipped = download_mrpack_files(app, &game_dir, files).await;

    // mrpack files are addressed by path rather than project id, so they are
    // not in the manifest and repair has nothing to check. The pack declares
    // the Minecraft version it targets, so a mismatch is unlikely here.
    let note = if skipped.is_empty() {
        String::new()
    } else {
        format!("{} file(s) could not be downloaded.", skipped.len())
    };

    emit_progress(app, InstallProgress {
        stage: "done".into(),
        current: 1,
        total: 1,
        file: String::new(),
    });

    Ok(ImportResult { instance: inst, skipped, note })
}

async fn import_curseforge(
    app: &AppHandle,
    cfg: &LauncherConfig,
    archive: &mut zip::ZipArchive<std::fs::File>,
    parent_path: String,
) -> anyhow::Result<ImportResult> {
    let raw = read_zip_entry(archive, "manifest.json")
        .ok_or_else(|| anyhow::anyhow!("manifest.json is missing"))?;
    let manifest: serde_json::Value = serde_json::from_slice(&raw)?;

    let name = manifest
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or("Imported pack")
        .to_string();
    let mc_version = manifest
        .pointer("/minecraft/version")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow::anyhow!("The pack does not say which Minecraft version it needs."))?
        .to_string();

    let loader_id = manifest
        .pointer("/minecraft/modLoaders")
        .and_then(|v| v.as_array())
        .and_then(|a| {
            a.iter()
                .find(|l| l.get("primary").and_then(|p| p.as_bool()).unwrap_or(false))
                .or_else(|| a.first())
        })
        .and_then(|l| l.get("id"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let (loader, loader_version) = loader_from_curseforge(&loader_id);

    let inst = instance::create(
        cfg,
        name,
        mc_version,
        loader,
        loader_version,
        cfg.max_ram_mb,
        parent_path,
    )?;

    let game_dir = inst.game_dir();
    std::fs::create_dir_all(&game_dir)?;

    // CurseForge packs name their override folder in the manifest.
    let overrides_name = manifest
        .get("overrides")
        .and_then(|v| v.as_str())
        .unwrap_or("overrides")
        .to_string();
    let prefix = format!("{}/", overrides_name);
    extract_overrides(archive, &game_dir, &[prefix.as_str()])?;

    // The mod list only contains numeric project/file ids. Turning those into
    // download URLs requires the CurseForge API, which needs a personal key we
    // do not have - so those mods are listed as skipped rather than silently
    // producing a broken instance.
    let skipped: Vec<String> = manifest
        .get("files")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .map(|f| {
                    format!(
                        "projectID {} / fileID {}",
                        f.get("projectID").and_then(|v| v.as_u64()).unwrap_or(0),
                        f.get("fileID").and_then(|v| v.as_u64()).unwrap_or(0)
                    )
                })
                .collect()
        })
        .unwrap_or_default();

    let note = if skipped.is_empty() {
        String::new()
    } else {
        format!(
            "Config files and overrides were imported, but the {} mod(s) listed in the manifest need the CurseForge API, which requires a personal API key. Add them manually or via the Modrinth browser for now.",
            skipped.len()
        )
    };

    emit_progress(app, InstallProgress {
        stage: "done".into(),
        current: 1,
        total: 1,
        file: String::new(),
    });

    Ok(ImportResult { instance: inst, skipped, note })
}

/// NoRisk packs (.noriskpack / .nrc) are ZIPs with a profile.json describing the
/// profile and an overrides/ folder. Every mod entry carries a ready-made
/// Modrinth download URL and SHA1, so no API lookup is needed to fetch them -
/// only to find out whether an entry is a mod, a resource pack or a shader,
/// since the profile does not record that.
async fn import_norisk(
    app: &AppHandle,
    cfg: &LauncherConfig,
    archive: &mut zip::ZipArchive<std::fs::File>,
    parent_path: String,
) -> anyhow::Result<ImportResult> {
    let raw = read_zip_entry(archive, "profile.json")
        .ok_or_else(|| anyhow::anyhow!("profile.json is missing"))?;
    let profile: serde_json::Value = serde_json::from_slice(&raw)?;

    let name = profile
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or("Imported pack")
        .to_string();
    let mc_version = profile
        .get("game_version")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow::anyhow!("The profile does not say which Minecraft version it needs."))?
        .to_string();
    let loader = profile
        .get("loader")
        .and_then(|v| v.as_str())
        .unwrap_or("vanilla")
        .to_string();
    let loader_version = profile
        .get("loader_version")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    // The profile carries its own memory setting; fall back to our default.
    let ram_mb = profile
        .pointer("/settings/memory/max")
        .and_then(|v| v.as_u64())
        .map(|m| m as u32)
        .unwrap_or(cfg.max_ram_mb);

    let inst = instance::create(
        cfg,
        name,
        mc_version,
        loader,
        loader_version,
        ram_mb,
        parent_path,
    )?;

    let game_dir = inst.game_dir();
    std::fs::create_dir_all(&game_dir)?;
    extract_overrides(archive, &game_dir, &["overrides/"])?;

    let empty = vec![];
    let mods = profile.get("mods").and_then(|v| v.as_array()).unwrap_or(&empty);

    // One bulk lookup tells us which folder each project belongs in.
    let project_ids: Vec<String> = mods
        .iter()
        .filter_map(|m| m.pointer("/source/project_id").and_then(|v| v.as_str()))
        .map(String::from)
        .collect();
    let types = fetch_project_types(&project_ids).await;

    let total = mods.len() as u64;
    let done = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(0));
    let skipped = std::sync::Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
    let installed = std::sync::Arc::new(std::sync::Mutex::new(Vec::<crate::launcher::mods::InstalledMod>::new()));

    futures_util::stream::iter(mods.iter().cloned())
        .for_each_concurrent(6, |m| {
            let inst = inst.clone();
            let types = types.clone();
            let done = done.clone();
            let skipped = skipped.clone();
            let installed = installed.clone();
            async move {
                let src = m.get("source").cloned().unwrap_or(serde_json::Value::Null);
                let file_name = src
                    .get("file_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or_default()
                    .to_string();
                let url = src
                    .get("download_url")
                    .and_then(|v| v.as_str())
                    .unwrap_or_default()
                    .to_string();
                let project_id = src
                    .get("project_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or_default()
                    .to_string();
                let display = m
                    .get("display_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or(&file_name)
                    .to_string();

                if url.is_empty() || file_name.is_empty() {
                    skipped.lock().unwrap().push(display.clone());
                    done.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    return;
                }

                // Unknown projects fall back to the extension: jars are mods,
                // zips are far more often resource packs than shaders.
                let project_type = types
                    .get(&project_id)
                    .cloned()
                    .unwrap_or_else(|| {
                        if file_name.ends_with(".jar") { "mod".to_string() } else { "resourcepack".to_string() }
                    });

                // Disabled entries keep the launcher-wide .disabled convention,
                // so they stay in the folder without being loaded.
                let enabled = m.get("enabled").and_then(|v| v.as_bool()).unwrap_or(true);
                let target_name = if enabled {
                    file_name.clone()
                } else {
                    format!("{}.disabled", file_name)
                };

                let Some(rel) = safe_relative(&target_name) else {
                    skipped.lock().unwrap().push(display.clone());
                    done.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    return;
                };
                let dest = inst.content_dir(&project_type).join(rel);

                let result = async {
                    if let Some(parent) = dest.parent() {
                        fs::create_dir_all(parent).await?;
                    }
                    let bytes = http()?.get(&url).send().await?.error_for_status()?.bytes().await?;
                    let mut f = fs::File::create(&dest).await?;
                    f.write_all(&bytes).await?;
                    Ok::<(), anyhow::Error>(())
                }
                .await;

                match result {
                    Ok(_) => {
                        if enabled {
                            installed.lock().unwrap().push(crate::launcher::mods::InstalledMod {
                                project_id,
                                version_id: src
                                    .get("version_id")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or_default()
                                    .to_string(),
                                title: display.clone(),
                                filename: target_name,
                                version_number: m
                                    .get("version")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or_default()
                                    .to_string(),
                                project_type,
                            });
                        }
                    }
                    Err(_) => skipped.lock().unwrap().push(display.clone()),
                }

                let n = done.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
                emit_progress(app, InstallProgress {
                    stage: "modpack".into(),
                    current: n,
                    total,
                    file: display,
                });
            }
        })
        .await;

    // Register everything so imported packs join the normal update checks.
    let entries = installed.lock().unwrap().clone();
    crate::launcher::mods::write_manifest(&inst, &entries).ok();

    // Packs pin exact versions, which are often not the ones this Minecraft
    // version and loader need - that is what makes Fabric refuse to start.
    // Repairing straight after the import puts every mod on a version that
    // actually fits, and parks the ones with no fit at all.
    let repair = crate::launcher::mods::repair_instance(app, inst.id.clone()).await;

    let mut skipped = skipped.lock().unwrap().clone();
    let mut note = String::new();
    if !skipped.is_empty() {
        note.push_str(&format!("{} item(s) could not be downloaded. ", skipped.len()));
    }

    if let Ok(report) = repair {
        if !report.replaced.is_empty() {
            note.push_str(&format!(
                "{} mod(s) were moved onto a version that fits this instance. ",
                report.replaced.len()
            ));
        }
        if !report.incompatible.is_empty() {
            note.push_str(&format!(
                "{} mod(s) have no version for Minecraft {} and were moved into the 'incompatible' folder so the game can still start. ",
                report.incompatible.len(),
                inst.mc_version
            ));
            skipped.extend(report.incompatible.clone());
        }
    }
    if profile
        .pointer("/settings/custom_jvm_args")
        .and_then(|v| v.as_str())
        .map(|s| !s.is_empty())
        .unwrap_or(false)
    {
        note.push_str("The pack's custom JVM arguments were not imported - Space Client has no per-instance JVM argument field yet.");
    }

    emit_progress(app, InstallProgress {
        stage: "done".into(),
        current: 1,
        total: 1,
        file: String::new(),
    });

    Ok(ImportResult { instance: inst, skipped, note })
}

/// Asks Modrinth in bulk which project type each id is. Failures are not fatal:
/// the caller falls back to guessing from the file extension.
async fn fetch_project_types(ids: &[String]) -> std::collections::HashMap<String, String> {
    let mut out = std::collections::HashMap::new();
    let client = match http() {
        Ok(c) => c,
        Err(_) => return out,
    };

    // The bulk endpoint takes a JSON array; keep chunks small enough for the URL.
    for chunk in ids.chunks(50) {
        let unique: Vec<&String> = {
            let mut seen = std::collections::HashSet::new();
            chunk.iter().filter(|id| seen.insert((*id).clone())).collect()
        };
        let ids_json = match serde_json::to_string(&unique) {
            Ok(j) => j,
            Err(_) => continue,
        };

        let resp = client
            .get("https://api.modrinth.com/v2/projects")
            .query(&[("ids", ids_json.as_str())])
            .send()
            .await;

        let Ok(resp) = resp else { continue };
        let Ok(json) = resp.json::<serde_json::Value>().await else { continue };

        if let Some(arr) = json.as_array() {
            for p in arr {
                let id = p.get("id").and_then(|v| v.as_str()).unwrap_or_default();
                let ptype = p
                    .get("project_type")
                    .and_then(|v| v.as_str())
                    .unwrap_or("mod");
                if !id.is_empty() {
                    out.insert(id.to_string(), ptype.to_string());
                }
            }
        }
    }
    out
}
