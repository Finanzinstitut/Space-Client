//! Exporting an instance, either as a Space Client pack (.spc) or as a
//! Modrinth pack (.mrpack).
//!
//! A .spc is a zip that carries everything itself:
//!
//! ```text
//! spaceclient.json   what the instance is: name, version, loader, memory
//! modrinth.json      the launcher's own record of which file is which project
//! files/...          the chosen files, laid out as in .minecraft
//! ```
//!
//! It needs no network to import and restores the instance exactly, which is
//! what it is for: moving an instance to another computer or handing it to a
//! friend who also plays Space Client.
//!
//! A .mrpack is for everybody else. Files Modrinth knows are listed by hash and
//! download link, the way the format intends, so the pack stays small; files it
//! does not know - configs, private mods, the options - go into overrides.

use crate::launcher::instance::{self, Instance};
use crate::launcher::progress::{emit_progress, InstallProgress};
use serde::Serialize;
use sha1::Digest;
use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};
use tauri::AppHandle;

/// The index file at the root of a .spc.
pub const SPC_INDEX: &str = "spaceclient.json";
/// The folder inside a .spc that mirrors .minecraft.
pub const SPC_FILES: &str = "files/";
/// The format this launcher writes. An importer refuses anything newer.
pub const SPC_FORMAT: u32 = 1;

/// One row of the file picker.
#[derive(Debug, Serialize, Clone)]
pub struct ExportNode {
    /// Relative to .minecraft, always with forward slashes.
    pub path: String,
    pub name: String,
    pub is_dir: bool,
    /// Bytes, for a folder everything below it.
    pub size: u64,
    /// Whether it starts ticked: what makes up an instance rather than what
    /// the game wrote while being played.
    pub default_on: bool,
}

#[derive(Debug, Serialize, Clone)]
pub struct ExportReport {
    pub path: String,
    pub files: u32,
    /// .mrpack only: files listed by download link rather than packed.
    pub linked: u32,
    pub bytes: u64,
}

/// Top-level entries that make up an instance. Everything else - worlds, logs,
/// screenshots, caches - starts unticked; "all files" is one button away.
const DEFAULT_ON: &[&str] = &[
    "mods",
    "config",
    "resourcepacks",
    "shaderpacks",
    "defaultconfigs",
    "kubejs",
    "options.txt",
    "optionsof.txt",
    "optionsshaders.txt",
    "servers.dat",
];

/// Never offered and never packed. Locks and the launcher's own copy of the
/// Space Client mod, which every launcher installs fresh for the version at
/// hand - an exported one would be for the wrong Minecraft half the time.
fn always_skipped(rel: &str) -> bool {
    let name = rel.rsplit('/').next().unwrap_or(rel);
    name == "session.lock"
        || rel == format!("mods/{}", crate::launcher::clientmod::FILE_NAME)
}

/// Rejects anything that is not a plain relative path inside the folder.
fn safe_relative(path: &str) -> Option<PathBuf> {
    let p = PathBuf::from(path.replace('\\', "/"));
    if p.as_os_str().is_empty() || p.is_absolute() {
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

fn dir_size(dir: &Path) -> u64 {
    let Ok(read) = std::fs::read_dir(dir) else { return 0 };
    let mut total = 0;
    for entry in read.flatten() {
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_dir() {
            total += dir_size(&entry.path());
        } else {
            total += meta.len();
        }
    }
    total
}

/// The children of one folder in .minecraft, folders first.
pub fn list(instance_id: &str, rel: &str) -> anyhow::Result<Vec<ExportNode>> {
    let inst = instance::get(instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    let root = inst.game_dir();
    let dir = if rel.is_empty() {
        root.clone()
    } else {
        root.join(safe_relative(rel).ok_or_else(|| anyhow::anyhow!("Invalid path"))?)
    };

    let mut out = Vec::new();
    let Ok(read) = std::fs::read_dir(&dir) else { return Ok(out) };
    for entry in read.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let path = if rel.is_empty() { name.clone() } else { format!("{}/{}", rel, name) };
        if always_skipped(&path) {
            continue;
        }
        let Ok(meta) = entry.metadata() else { continue };
        let is_dir = meta.is_dir();
        let top = path.split('/').next().unwrap_or("");
        out.push(ExportNode {
            size: if is_dir { dir_size(&entry.path()) } else { meta.len() },
            default_on: DEFAULT_ON.contains(&top),
            path,
            name,
            is_dir,
        });
    }
    out.sort_by(|a, b| b.is_dir.cmp(&a.is_dir).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
    Ok(out)
}

/// Every file the selection covers, as (relative path, absolute path).
///
/// A ticked folder means everything below it. Overlapping picks - a folder and
/// a file inside it - are counted once.
fn expand(root: &Path, picks: &[String]) -> Vec<(String, PathBuf)> {
    fn walk(root: &Path, dir: &Path, out: &mut Vec<(String, PathBuf)>) {
        let Ok(read) = std::fs::read_dir(dir) else { return };
        for entry in read.flatten() {
            let path = entry.path();
            if path.is_dir() {
                walk(root, &path, out);
            } else if let Ok(rel) = path.strip_prefix(root) {
                out.push((rel.to_string_lossy().replace('\\', "/"), path));
            }
        }
    }

    let mut all = Vec::new();
    for pick in picks {
        let Some(rel) = safe_relative(pick) else { continue };
        let abs = root.join(rel);
        if abs.is_dir() {
            walk(root, &abs, &mut all);
        } else if abs.is_file() {
            all.push((pick.replace('\\', "/"), abs));
        }
    }

    let mut seen = HashSet::new();
    all.retain(|(rel, _)| !always_skipped(rel) && seen.insert(rel.clone()));
    all.sort_by(|a, b| a.0.cmp(&b.0));
    all
}

fn options_for(rel: &str) -> zip::write::FileOptions {
    // Jars and zips are already compressed; deflating them again costs time
    // and saves nothing.
    let lower = rel.to_lowercase();
    let method = if lower.ends_with(".jar") || lower.ends_with(".zip") || lower.ends_with(".png") {
        zip::CompressionMethod::Stored
    } else {
        zip::CompressionMethod::Deflated
    };
    zip::write::FileOptions::default()
        .compression_method(method)
        .large_file(true)
}

/// Streams one file into the archive. A file that cannot be read - held open
/// by a running game, say - is skipped rather than failing the export.
fn add_file(zip: &mut zip::ZipWriter<std::fs::File>, name: &str, abs: &Path) -> anyhow::Result<u64> {
    let Ok(mut file) = std::fs::File::open(abs) else { return Ok(0) };
    zip.start_file(name, options_for(name))?;
    Ok(std::io::copy(&mut file, zip)?)
}

fn progress(app: &AppHandle, current: u64, total: u64, file: &str) {
    emit_progress(app, InstallProgress {
        stage: "export".into(),
        current,
        total,
        file: file.to_string(),
    });
}

/// Writes to a part file and moves it into place at the end, so a cancelled
/// or failed export never leaves something that looks finished.
fn part_path(dest: &Path) -> PathBuf {
    let mut name = dest.file_name().map(|n| n.to_os_string()).unwrap_or_default();
    name.push(".part");
    dest.with_file_name(name)
}

pub async fn export(
    app: &AppHandle,
    instance_id: &str,
    picks: Vec<String>,
    format: &str,
    dest: String,
) -> anyhow::Result<ExportReport> {
    let inst = instance::get(instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    let dest = PathBuf::from(dest);
    let root = inst.game_dir();
    let files = expand(&root, &picks);
    if files.is_empty() {
        anyhow::bail!("Nothing selected to export.");
    }

    match format {
        "spc" => {
            let app = app.clone();
            tokio::task::spawn_blocking(move || write_spc(&app, &inst, &files, &dest)).await?
        }
        "mrpack" => {
            if inst.loader != "vanilla" && inst.loader_version.is_empty() {
                anyhow::bail!("Install the instance once before exporting it as a Modrinth pack - the loader version is not known yet.");
            }
            let links = modrinth_links(app, &files).await;
            let app = app.clone();
            tokio::task::spawn_blocking(move || write_mrpack(&app, &inst, &files, &links, &dest)).await?
        }
        other => anyhow::bail!("Unknown export format: {}", other),
    }
}

// ------------------------------------------------------------------ .spc

fn write_spc(
    app: &AppHandle,
    inst: &Instance,
    files: &[(String, PathBuf)],
    dest: &Path,
) -> anyhow::Result<ExportReport> {
    let part = part_path(dest);
    let total = files.len() as u64;
    let mut bytes = 0;
    let mut written = 0;

    let result = (|| -> anyhow::Result<()> {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&part)?);

        let index = serde_json::json!({
            "format": SPC_FORMAT,
            "name": inst.name,
            "mc_version": inst.mc_version,
            "loader": inst.loader,
            "loader_version": inst.loader_version,
            "ram_mb": inst.ram_mb,
            "install_client_mod": inst.install_client_mod,
            "exported_by": format!("Space Client {}", env!("CARGO_PKG_VERSION")),
        });
        zip.start_file(SPC_INDEX, zip::write::FileOptions::default())?;
        zip.write_all(serde_json::to_string_pretty(&index)?.as_bytes())?;

        // Only the entries whose files travel along, so the importer is not
        // told about mods it will not find.
        let present: HashSet<String> = files
            .iter()
            .filter_map(|(rel, _)| rel.rsplit('/').next().map(|n| n.trim_end_matches(".disabled").to_string()))
            .collect();
        let manifest: Vec<_> = crate::launcher::mods::load_manifest(inst)
            .into_iter()
            .filter(|m| present.contains(&m.filename))
            .collect();
        if !manifest.is_empty() {
            zip.start_file("modrinth.json", zip::write::FileOptions::default())?;
            zip.write_all(serde_json::to_string_pretty(&manifest)?.as_bytes())?;
        }

        for (i, (rel, abs)) in files.iter().enumerate() {
            progress(app, i as u64, total, rel);
            let n = add_file(&mut zip, &format!("{}{}", SPC_FILES, rel), abs)?;
            bytes += n;
            written += 1;
        }
        zip.finish()?;
        Ok(())
    })();

    finish(app, result, &part, dest)?;
    Ok(ExportReport {
        path: dest.to_string_lossy().to_string(),
        files: written,
        linked: 0,
        bytes,
    })
}

// ------------------------------------------------------------------ .mrpack

/// What Modrinth needs to list a file instead of carrying it.
#[derive(Clone)]
struct Link {
    sha1: String,
    sha512: String,
    size: u64,
    url: String,
}

/// Only content in the folders Modrinth serves is looked up; a jar in config/
/// is somebody's own business and goes into overrides.
fn linkable(rel: &str) -> bool {
    let lower = rel.to_lowercase();
    (lower.starts_with("mods/") && lower.ends_with(".jar"))
        || ((lower.starts_with("resourcepacks/") || lower.starts_with("shaderpacks/"))
            && lower.ends_with(".zip"))
}

fn hashes(path: &Path) -> Option<(String, String, u64)> {
    let mut file = std::fs::File::open(path).ok()?;
    let mut s1 = sha1::Sha1::new();
    let mut s512 = sha2::Sha512::new();
    let mut buf = vec![0u8; 1 << 16];
    let mut size = 0u64;
    loop {
        let n = file.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        s1.update(&buf[..n]);
        s512.update(&buf[..n]);
        size += n as u64;
    }
    Some((hex::encode(s1.finalize()), hex::encode(s512.finalize()), size))
}

/// Asks Modrinth, in one request, which of the files it hosts. Anything it does
/// not answer for - or everything, when offline - simply goes into overrides,
/// so a pack can always be written.
async fn modrinth_links(app: &AppHandle, files: &[(String, PathBuf)]) -> HashMap<String, Link> {
    let candidates: Vec<(String, PathBuf)> =
        files.iter().filter(|(rel, _)| linkable(rel)).cloned().collect();
    if candidates.is_empty() {
        return HashMap::new();
    }
    progress(app, 0, 1, "Modrinth");

    let hashed: Vec<(String, (String, String, u64))> = tokio::task::spawn_blocking(move || {
        candidates
            .into_iter()
            .filter_map(|(rel, abs)| hashes(&abs).map(|h| (rel, h)))
            .collect()
    })
    .await
    .unwrap_or_default();

    let sha1s: Vec<&str> = hashed.iter().map(|(_, h)| h.0.as_str()).collect();
    let answer: serde_json::Value = match async {
        reqwest::Client::builder()
            .user_agent("Finanzinstitut/SpaceClient")
            .build()?
            .post("https://api.modrinth.com/v2/version_files")
            .json(&serde_json::json!({ "hashes": sha1s, "algorithm": "sha1" }))
            .send()
            .await?
            .error_for_status()?
            .json()
            .await
    }
    .await
    {
        Ok(v) => v,
        Err(e) => {
            eprintln!("Modrinth lookup for export failed: {}", e);
            return HashMap::new();
        }
    };

    let mut out = HashMap::new();
    for (rel, (sha1, sha512, size)) in hashed {
        let Some(version) = answer.get(&sha1) else { continue };
        let url = version
            .get("files")
            .and_then(|f| f.as_array())
            .and_then(|files| {
                files.iter().find(|f| {
                    f.pointer("/hashes/sha1").and_then(|h| h.as_str()) == Some(sha1.as_str())
                })
            })
            .and_then(|f| f.get("url"))
            .and_then(|u| u.as_str());
        if let Some(url) = url {
            out.insert(rel, Link { sha1, sha512, size, url: url.to_string() });
        }
    }
    out
}

fn mrpack_loader_key(loader: &str) -> Option<&'static str> {
    match loader {
        "fabric" => Some("fabric-loader"),
        "quilt" => Some("quilt-loader"),
        "forge" => Some("forge"),
        "neoforge" => Some("neoforge"),
        _ => None,
    }
}

fn mrpack_index(inst: &Instance, files: &[(String, PathBuf)], links: &HashMap<String, Link>) -> serde_json::Value {
    let mut deps = serde_json::Map::new();
    deps.insert("minecraft".into(), inst.mc_version.clone().into());
    if let Some(key) = mrpack_loader_key(&inst.loader) {
        deps.insert(key.into(), inst.loader_version.clone().into());
    }

    let listed: Vec<serde_json::Value> = files
        .iter()
        .filter_map(|(rel, _)| {
            let link = links.get(rel)?;
            Some(serde_json::json!({
                "path": rel,
                "hashes": { "sha1": link.sha1, "sha512": link.sha512 },
                "env": { "client": "required", "server": "required" },
                "downloads": [link.url],
                "fileSize": link.size,
            }))
        })
        .collect();

    serde_json::json!({
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": "1.0.0",
        "name": inst.name,
        "summary": format!("Exported from Space Client {}", env!("CARGO_PKG_VERSION")),
        "files": listed,
        "dependencies": deps,
    })
}

fn write_mrpack(
    app: &AppHandle,
    inst: &Instance,
    files: &[(String, PathBuf)],
    links: &HashMap<String, Link>,
    dest: &Path,
) -> anyhow::Result<ExportReport> {
    let part = part_path(dest);
    let total = files.len() as u64;
    let mut bytes = 0;
    let mut written = 0;

    let result = (|| -> anyhow::Result<()> {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&part)?);
        let index = mrpack_index(inst, files, links);
        zip.start_file("modrinth.index.json", zip::write::FileOptions::default())?;
        zip.write_all(serde_json::to_string_pretty(&index)?.as_bytes())?;

        for (i, (rel, abs)) in files.iter().enumerate() {
            if links.contains_key(rel) {
                continue;
            }
            progress(app, i as u64, total, rel);
            bytes += add_file(&mut zip, &format!("overrides/{}", rel), abs)?;
            written += 1;
        }
        zip.finish()?;
        Ok(())
    })();

    finish(app, result, &part, dest)?;
    Ok(ExportReport {
        path: dest.to_string_lossy().to_string(),
        files: written + links.len() as u32,
        linked: links.len() as u32,
        bytes,
    })
}

fn finish(app: &AppHandle, result: anyhow::Result<()>, part: &Path, dest: &Path) -> anyhow::Result<()> {
    if let Err(e) = result {
        std::fs::remove_file(part).ok();
        return Err(e);
    }
    if dest.exists() {
        std::fs::remove_file(dest)?;
    }
    std::fs::rename(part, dest)?;
    progress(app, 1, 1, "");
    emit_progress(app, InstallProgress {
        stage: "done".into(),
        current: 1,
        total: 1,
        file: String::new(),
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tree() -> PathBuf {
        let root = std::env::temp_dir().join(format!("sc-export-{}", uuid::Uuid::new_v4()));
        for (p, body) in [
            ("mods/a.jar", "a"),
            ("mods/spaceclient.jar", "ours"),
            ("config/x/y.json", "{}"),
            ("saves/World/level.dat", "w"),
            ("saves/World/session.lock", "l"),
            ("options.txt", "o"),
        ] {
            let path = root.join(p);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(path, body).unwrap();
        }
        root
    }

    #[test]
    fn a_folder_means_everything_below_it_once() {
        let root = tree();
        let picked = expand(&root, &["config".into(), "config/x/y.json".into(), "options.txt".into()]);
        let names: Vec<_> = picked.iter().map(|(r, _)| r.as_str()).collect();
        assert_eq!(names, vec!["config/x/y.json", "options.txt"]);
        std::fs::remove_dir_all(root).ok();
    }

    #[test]
    fn locks_and_our_own_mod_never_travel() {
        let root = tree();
        let picked = expand(&root, &["mods".into(), "saves".into()]);
        let names: Vec<_> = picked.iter().map(|(r, _)| r.as_str()).collect();
        assert_eq!(names, vec!["mods/a.jar", "saves/World/level.dat"]);
        std::fs::remove_dir_all(root).ok();
    }

    #[test]
    fn paths_outside_the_folder_are_refused() {
        let root = tree();
        assert!(expand(&root, &["../etc".into(), "/etc/passwd".into()]).is_empty());
        std::fs::remove_dir_all(root).ok();
    }

    #[test]
    fn only_hosted_content_is_looked_up() {
        assert!(linkable("mods/sodium.jar"));
        assert!(linkable("resourcepacks/pack.zip"));
        assert!(linkable("shaderpacks/bsl.zip"));
        assert!(!linkable("mods/sodium.jar.disabled"));
        assert!(!linkable("config/thing.jar"));
    }

    #[test]
    fn the_index_lists_links_and_the_loader() {
        let inst = Instance {
            id: "i".into(),
            name: "Pack".into(),
            path: String::new(),
            mc_version: "26.3".into(),
            loader: "fabric".into(),
            loader_version: "0.19.5".into(),
            version_id: String::new(),
            ram_mb: 4096,
            install_client_mod: true,
            created: String::new(),
            last_played: 0,
        };
        let files = vec![
            ("mods/a.jar".to_string(), PathBuf::new()),
            ("options.txt".to_string(), PathBuf::new()),
        ];
        let mut links = HashMap::new();
        links.insert("mods/a.jar".to_string(), Link {
            sha1: "s1".into(),
            sha512: "s512".into(),
            size: 3,
            url: "https://cdn.modrinth.com/data/P/versions/V/a.jar".into(),
        });
        let index = mrpack_index(&inst, &files, &links);
        assert_eq!(index["dependencies"]["minecraft"], "26.3");
        assert_eq!(index["dependencies"]["fabric-loader"], "0.19.5");
        let listed = index["files"].as_array().unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0]["path"], "mods/a.jar");
        assert_eq!(listed[0]["hashes"]["sha512"], "s512");
    }
}
