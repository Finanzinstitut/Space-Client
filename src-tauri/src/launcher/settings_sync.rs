//! Shared game settings: one set of options, server list and Space Client
//! setup for every instance.
//!
//! Setting the keybinds, the server list and the HUD up again in each new
//! instance is the chore this exists to remove. One instance is the source -
//! the one played most, or the one picked in the settings - and its files are
//! copied into a shared folder beside the launcher's data. Before any instance
//! starts, the shared copy is put into it.
//!
//! The direction is deliberate. "Whoever closed last wins" sounds friendlier
//! but means a test instance opened for five minutes can overwrite the setup
//! somebody spent an evening on. A named source never surprises anybody.
//!
//! The account's tokens (spaceclient-tokens.json) are left out on purpose:
//! they are secrets, and a copy in a shared folder is one more place for one
//! to leak from.

use crate::launcher::config::LauncherConfig;
use crate::launcher::instance::{self, Instance};
use serde::Serialize;
use sha2::Digest;
use std::path::{Path, PathBuf};

/// Single files, relative to .minecraft.
pub const FILES: &[&str] = &[
    "options.txt",
    "servers.dat",
    "config/spaceclient.json",
    "config/spaceclient-server-layouts.json",
];

/// Folders copied whole, relative to .minecraft.
pub const DIRS: &[&str] = &["config/spaceclient-profiles"];

#[derive(Debug, Serialize, Clone)]
pub struct SyncFile {
    pub path: String,
    /// Present in the shared folder.
    pub present: bool,
    /// Seconds since the epoch the shared copy was written, 0 when absent.
    pub modified: u64,
}

#[derive(Debug, Serialize, Clone)]
pub struct SyncStatus {
    pub enabled: bool,
    /// "auto" or an instance id.
    pub source: String,
    /// The instance actually used right now, after "auto" is resolved.
    pub source_id: String,
    pub source_name: String,
    pub shared_dir: String,
    pub files: Vec<SyncFile>,
}

pub fn shared_dir(cfg: &LauncherConfig) -> PathBuf {
    PathBuf::from(&cfg.install_path).join("shared-settings")
}

/// The instance the settings come from.
///
/// "auto" means the one played most often, and among equals the one played
/// last - and only instances that have been started at all, since an
/// instance that never ran has no options of its own to give.
pub fn source(cfg: &LauncherConfig, all: &[Instance]) -> Option<Instance> {
    if cfg.sync_source != "auto" {
        if let Some(found) = all.iter().find(|i| i.id == cfg.sync_source) {
            return Some(found.clone());
        }
    }
    // Horizon instances only take: their options are Horizon's, and the
    // other way round they would hand Java games settings it never wrote
    all.iter()
        .filter(|i| i.loader != crate::launcher::horizon::LOADER)
        .filter(|i| i.game_dir().join("options.txt").exists())
        .max_by_key(|i| (i.play_count, i.last_played))
        .cloned()
}

fn modified_secs(path: &Path) -> u64 {
    std::fs::metadata(path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub fn status(cfg: &LauncherConfig) -> SyncStatus {
    let all = instance::load_all();
    let picked = source(cfg, &all);
    let dir = shared_dir(cfg);
    let files = FILES
        .iter()
        .chain(DIRS.iter())
        .map(|rel| {
            let path = dir.join(rel);
            SyncFile {
                path: rel.to_string(),
                present: path.exists(),
                modified: modified_secs(&path),
            }
        })
        .collect();
    SyncStatus {
        enabled: cfg.sync_settings,
        source: cfg.sync_source.clone(),
        source_id: picked.as_ref().map(|i| i.id.clone()).unwrap_or_default(),
        source_name: picked.map(|i| i.name).unwrap_or_default(),
        shared_dir: dir.to_string_lossy().to_string(),
        files,
    }
}

fn copy_dir(from: &Path, to: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)? {
        let entry = entry?;
        let target = to.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir(&entry.path(), &target)?;
        } else {
            std::fs::copy(entry.path(), target)?;
        }
    }
    Ok(())
}

/// Copies one file through a temporary name, so a game starting at the same
/// moment never reads half of it.
fn copy_file(from: &Path, to: &Path) -> std::io::Result<()> {
    if let Some(parent) = to.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let mut temp = to.as_os_str().to_owned();
    temp.push(".sync-tmp");
    let temp = PathBuf::from(temp);
    std::fs::copy(from, &temp)?;
    std::fs::rename(&temp, to)
}

/// Takes the source instance's files into the shared folder.
pub fn capture(cfg: &LauncherConfig, from: &Instance) -> anyhow::Result<u32> {
    let dir = shared_dir(cfg);
    let game = from.game_dir();
    let mut count = 0;
    for rel in FILES {
        let src = game.join(rel);
        if src.is_file() {
            copy_file(&src, &dir.join(rel))?;
            count += 1;
        }
    }
    for rel in DIRS {
        let src = game.join(rel);
        if src.is_dir() {
            let dst = dir.join(rel);
            if dst.exists() {
                std::fs::remove_dir_all(&dst)?;
            }
            copy_dir(&src, &dst)?;
            count += 1;
        }
    }
    Ok(count)
}

/// options.txt as the shared copy has it, but with the target's own
/// "version:" line.
///
/// That line is the data version the file was written by, and Minecraft uses
/// it to decide how to upgrade the file. Carrying a newer instance's number
/// into an older game - 26.3's options into 1.21.11 - would claim the file is
/// from the future; keeping the target's own number lets each game read the
/// keys it knows and ignore the rest, which is what it does anyway.
pub fn merge_options(shared: &str, target: Option<&str>) -> String {
    let own_version = target.and_then(|t| t.lines().find(|l| l.starts_with("version:")));
    let mut out = String::with_capacity(shared.len());
    let mut wrote_version = false;
    for line in shared.lines() {
        if line.starts_with("version:") {
            if let Some(own) = own_version {
                out.push_str(own);
                out.push('\n');
                wrote_version = true;
            }
            continue;
        }
        out.push_str(line);
        out.push('\n');
    }
    if !wrote_version {
        if let Some(own) = own_version {
            out.insert_str(0, &format!("{}\n", own));
        }
    }
    out
}

/// Puts the shared files into one instance.
pub fn apply(cfg: &LauncherConfig, to: &Instance) -> anyhow::Result<u32> {
    let dir = shared_dir(cfg);
    let game = to.game_dir();
    let mut count = 0;
    for rel in FILES {
        let src = dir.join(rel);
        if !src.is_file() {
            continue;
        }
        let dst = game.join(rel);
        if *rel == "options.txt" {
            let shared = std::fs::read_to_string(&src)?;
            let own = std::fs::read_to_string(&dst).ok();
            let merged = merge_options(&shared, own.as_deref());
            if let Some(parent) = dst.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let temp = dst.with_extension("txt.sync-tmp");
            std::fs::write(&temp, merged)?;
            std::fs::rename(&temp, &dst)?;
        } else {
            copy_file(&src, &dst)?;
        }
        count += 1;
    }
    for rel in DIRS {
        let src = dir.join(rel);
        if src.is_dir() {
            let dst = game.join(rel);
            if dst.exists() {
                std::fs::remove_dir_all(&dst)?;
            }
            copy_dir(&src, &dst)?;
            count += 1;
        }
    }
    Ok(count)
}

/// Run before an instance starts: refresh the shared copy from the source,
/// then hand it to the instance being launched. Never fatal - a game that
/// starts with its own settings is better than one that does not start.
pub fn before_launch(cfg: &LauncherConfig, target: &Instance, running: &[String]) {
    if !cfg.sync_settings {
        return;
    }
    let all = instance::load_all();
    let Some(from) = source(cfg, &all) else { return };

    // A source that is running is mid-session: its files on disk are what it
    // had at start, and those are already in the shared folder.
    if !running.contains(&from.id) {
        if let Err(e) = capture(cfg, &from) {
            eprintln!("settings sync: could not read {} - {}", from.name, e);
        }
    }
    if from.id != target.id {
        if let Err(e) = apply(cfg, target) {
            eprintln!("settings sync: could not write into {} - {}", target.name, e);
        }
    }
}

/// The lines of options.txt a Horizon instance takes from the shared copy:
/// the keybinds and the chosen resource packs. Only those - the rest of a
/// Java game's options (graphics, render distance) are for Java's renderer,
/// and Horizon keeps its own.
const HORIZON_KEYS: &[&str] = &["key_", "resourcePacks:", "incompatibleResourcePacks:"];

fn takes_for_horizon(line: &str) -> bool {
    HORIZON_KEYS.iter().any(|k| line.starts_with(k))
}

/// The shared keybind and pack lines laid over a Horizon instance's own
/// options.txt: a line with the same key is replaced, a missing one added,
/// everything else stays as Horizon wrote it.
pub fn merge_for_horizon(shared: &str, target: Option<&str>) -> String {
    let wanted: Vec<&str> = shared.lines().filter(|l| takes_for_horizon(l)).collect();
    let key = |line: &str| line.split_once(':').map(|(k, _)| k.to_string()).unwrap_or_default();
    let mut out = String::new();
    let mut written = std::collections::HashSet::new();
    for line in target.unwrap_or("").lines() {
        let k = key(line);
        if let Some(new) = wanted.iter().find(|w| key(w) == k) {
            out.push_str(new);
            written.insert(k);
        } else {
            out.push_str(line);
        }
        out.push('\n');
    }
    for line in wanted {
        if written.insert(key(line)) {
            out.push_str(line);
            out.push('\n');
        }
    }
    out
}

/// The pack files a list of packs names (`file/<name>` entries).
fn pack_files(options: &str) -> Vec<String> {
    let Some(value) = options.lines().find_map(|l| l.strip_prefix("resourcePacks:")) else {
        return Vec::new();
    };
    serde_json::from_str::<Vec<String>>(value.trim())
        .unwrap_or_default()
        .into_iter()
        .filter_map(|id| id.strip_prefix("file/").map(str::to_string))
        .filter(|name| !name.contains("..") && !name.contains('/') && !name.contains('\\'))
        .collect()
}

/// A Horizon instance's share: the server list, the keybinds, the chosen
/// resource packs and their files, copied from the source instance's
/// resourcepacks folder when the instance lacks them or has another size.
pub fn apply_to_horizon(cfg: &LauncherConfig, from: &Instance, to: &Instance) -> anyhow::Result<u32> {
    let dir = shared_dir(cfg);
    let game = to.game_dir();
    std::fs::create_dir_all(&game)?;
    let mut count = 0;
    let servers = dir.join("servers.dat");
    if servers.is_file() {
        copy_file(&servers, &game.join("servers.dat"))?;
        count += 1;
    }
    let shared = dir.join("options.txt");
    if let Ok(shared) = std::fs::read_to_string(&shared) {
        let dst = game.join("options.txt");
        let own = std::fs::read_to_string(&dst).ok();
        let temp = dst.with_extension("txt.sync-tmp");
        // The whole file, as a Java instance gets it: Horizon reads vanilla's
        // options.txt, and what only Java knows it leaves alone
        std::fs::write(&temp, merge_options(&shared, own.as_deref()))?;
        std::fs::rename(&temp, &dst)?;
        count += 1;
        for name in pack_files(&shared) {
            let src = from.game_dir().join("resourcepacks").join(&name);
            let dst = game.join("resourcepacks").join(&name);
            if src.is_dir() {
                if !dst.exists() {
                    copy_dir(&src, &dst)?;
                }
            } else if src.is_file() {
                let same = std::fs::metadata(&dst).ok().map(|m| m.len()) == std::fs::metadata(&src).ok().map(|m| m.len());
                if !same {
                    copy_file(&src, &dst)?;
                }
            }
        }
    }
    match space_client_to_horizon(from, to) {
        Ok(true) => count += 1,
        Ok(false) => {}
        Err(e) => eprintln!("settings sync: Space Client settings not carried into {} - {e}", to.name),
    }
    Ok(count)
}

/// Java's Space Client modules and the parts of Space Client for Horizon
/// that are the same thing: (Java module id, Horizon part).
const HORIZON_PARTS: &[(&str, &str)] = &[
    ("fps", "fps"),
    ("cps", "cps"),
    ("coordinates", "coordinates"),
    ("direction", "direction"),
    ("daytime", "daytime"),
    ("speedometer", "speed"),
    ("session", "session"),
    ("health", "health"),
    ("aim", "looking_at"),
    ("compass", "compass"),
    ("keystrokes", "keystrokes"),
    ("zoom", "zoom"),
    ("music", "music"),
];

/// Space Client's own setup, from the source's config/spaceclient.json into
/// Space Client for Horizon: which modules are on, and the zoom key. Only
/// when that file changed since the last time, so what is switched in
/// Horizon's /sc stays until the Java setup itself changes.
pub fn space_client_to_horizon(from: &Instance, to: &Instance) -> anyhow::Result<bool> {
    let source = from.game_dir().join("config").join("spaceclient.json");
    let Ok(text) = std::fs::read_to_string(&source) else { return Ok(false) };
    let stamp = format!("{:x}", sha2::Sha256::digest(text.as_bytes()));
    let marker = to.dir().join(".spaceclient-horizon-sync");
    if std::fs::read_to_string(&marker).map(|m| m.trim() == stamp).unwrap_or(false) {
        return Ok(false);
    }
    let json: serde_json::Value = serde_json::from_str(&text)?;
    let modules = &json["modules"];

    let data = to.game_dir().join("horizon-mods").join(".horizon-mod-data").join("spaceclient");
    std::fs::create_dir_all(&data)?;
    let settings = data.join("settings");
    let own = std::fs::read_to_string(&settings).unwrap_or_default();
    let mut lines: Vec<String> = own.lines().map(str::to_string).collect();
    for (java, part) in HORIZON_PARTS {
        let Some(on) = modules[*java]["enabled"].as_bool() else { continue };
        let line = format!("{part}={on}");
        match lines.iter().position(|l| l.split('=').next() == Some(part)) {
            Some(i) => lines[i] = line,
            None => lines.push(line),
        }
    }
    std::fs::write(&settings, lines.join("\n") + "\n")?;

    // The zoom key, as the Java module names it ("C", "CTRL", ...)
    if let Some(key) = modules["zoom"]["settings"]["key"].as_str().and_then(horizon_key) {
        let file = to.game_dir().join("horizon-mods.txt");
        let text = std::fs::read_to_string(&file).unwrap_or_default();
        let mut out: Vec<String> = text
            .lines()
            .filter(|l| !l.starts_with("key.spaceclient.zoom:"))
            .map(str::to_string)
            .collect();
        out.push(format!("key.spaceclient.zoom:{key}"));
        std::fs::write(&file, out.join("\n") + "\n")?;
    }
    std::fs::write(&marker, stamp)?;
    Ok(true)
}

/// A key as the Java zoom module stores it, as Horizon's options name it.
fn horizon_key(java: &str) -> Option<String> {
    match java {
        "CTRL" => Some("key.keyboard.left.control".into()),
        "ALT" => Some("key.keyboard.left.alt".into()),
        "BINDING" | "" => None,
        letter if letter.len() == 1 => Some(format!("key.keyboard.{}", letter.to_lowercase())),
        _ => None,
    }
}

/// before_launch for a Horizon instance: the source refreshed as usual,
/// then only Horizon's share handed over.
pub fn before_launch_horizon(cfg: &LauncherConfig, target: &Instance, running: &[String]) {
    if !cfg.sync_settings {
        return;
    }
    let all = instance::load_all();
    let Some(from) = source(cfg, &all) else { return };
    if from.id == target.id {
        return;
    }
    if !running.contains(&from.id) {
        if let Err(e) = capture(cfg, &from) {
            eprintln!("settings sync: could not read {} - {}", from.name, e);
        }
    }
    if let Err(e) = apply_to_horizon(cfg, &from, target) {
        eprintln!("settings sync: could not write into {} - {}", target.name, e);
    }
}

/// Run when a game closes: if it was the source, what it changed is taken
/// into the shared folder straight away.
pub fn after_exit(cfg: &LauncherConfig, closed: &str) {
    if !cfg.sync_settings {
        return;
    }
    let all = instance::load_all();
    if let Some(from) = source(cfg, &all) {
        if from.id == closed {
            if let Err(e) = capture(cfg, &from) {
                eprintln!("settings sync: could not read {} - {}", from.name, e);
            }
        }
    }
}

/// "Sync now": take from the source and give to every instance that is not
/// running. Returns how many instances received the files.
pub fn sync_all(cfg: &LauncherConfig, running: &[String]) -> anyhow::Result<u32> {
    let all = instance::load_all();
    let from = source(cfg, &all)
        .ok_or_else(|| anyhow::anyhow!("No instance has been played yet, so there is nothing to copy."))?;
    if !running.contains(&from.id) {
        capture(cfg, &from)?;
    }
    let mut done = 0;
    for inst in &all {
        if inst.id == from.id || running.contains(&inst.id) {
            continue;
        }
        if inst.loader == crate::launcher::horizon::LOADER {
            apply_to_horizon(cfg, &from, inst)?;
        } else {
            apply(cfg, inst)?;
        }
        done += 1;
    }
    Ok(done)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn space_client_modules_carry_into_horizon() {
        let root = std::env::temp_dir().join(format!("sc-sync-h-{}", uuid::Uuid::new_v4()));
        let java = inst("java", &root.join("java"), 3, 1);
        let mut horizon = inst("hz", &root.join("hz"), 1, 2);
        horizon.loader = "horizon".into();
        std::fs::create_dir_all(java.game_dir().join("config")).unwrap();
        std::fs::write(
            java.game_dir().join("config/spaceclient.json"),
            r#"{"modules":{"fps":{"enabled":false},"speedometer":{"enabled":true},"zoom":{"enabled":true,"settings":{"key":"V"}}}}"#,
        )
        .unwrap();
        let data = horizon.game_dir().join("horizon-mods/.horizon-mod-data/spaceclient");
        std::fs::create_dir_all(&data).unwrap();
        std::fs::write(data.join("settings"), "fps=true\ncps=true\n").unwrap();
        std::fs::write(horizon.game_dir().join("horizon-mods.txt"), "mod.spaceclient:true\n").unwrap();

        assert!(space_client_to_horizon(&java, &horizon).unwrap());
        let settings = std::fs::read_to_string(data.join("settings")).unwrap();
        assert!(settings.contains("fps=false") && settings.contains("cps=true") && settings.contains("speed=true"));
        let mods = std::fs::read_to_string(horizon.game_dir().join("horizon-mods.txt")).unwrap();
        assert!(mods.contains("mod.spaceclient:true") && mods.contains("key.spaceclient.zoom:key.keyboard.v"));
        // Unchanged Java setup: nothing is written again
        assert!(!space_client_to_horizon(&java, &horizon).unwrap());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn horizon_takes_keybinds_and_packs_only() {
        let shared = "version:4700\nkey_key.jump:key.keyboard.space\nfov:0.9\nresourcePacks:[\"vanilla\",\"file/Faithful.zip\"]\n";
        let target = "version:4800\nfov:0.2\nkey_key.jump:key.keyboard.j\n";
        let merged = merge_for_horizon(shared, Some(target));
        assert_eq!(
            merged,
            "version:4800\nfov:0.2\nkey_key.jump:key.keyboard.space\nresourcePacks:[\"vanilla\",\"file/Faithful.zip\"]\n"
        );
        assert_eq!(pack_files(shared), vec!["Faithful.zip".to_string()]);
        assert!(pack_files("resourcePacks:[\"file/../x\"]").is_empty());
    }

    #[test]
    fn options_keep_the_targets_own_version_line() {
        let shared = "version:4700\nkey_key.jump:key.keyboard.space\nfov:0.5\n";
        let target = "version:4189\nfov:0.0\n";
        let merged = merge_options(shared, Some(target));
        assert_eq!(merged, "version:4189\nkey_key.jump:key.keyboard.space\nfov:0.5\n");
    }

    #[test]
    fn a_new_instance_takes_the_options_as_they_are() {
        let shared = "version:4700\nfov:0.5\n";
        assert_eq!(merge_options(shared, None), "fov:0.5\n");
        // A target without a version line gets none added either
        assert_eq!(merge_options(shared, Some("fov:0\n")), "fov:0.5\n");
    }

    #[test]
    fn a_shared_file_without_version_still_gets_the_targets() {
        let merged = merge_options("fov:0.5\n", Some("version:4189\n"));
        assert_eq!(merged, "version:4189\nfov:0.5\n");
    }

    fn inst(id: &str, dir: &Path, plays: u32, last: u64) -> Instance {
        Instance {
            id: id.into(),
            name: id.into(),
            path: dir.to_string_lossy().to_string(),
            mc_version: "26.3".into(),
            loader: "fabric".into(),
            loader_version: String::new(),
            version_id: String::new(),
            ram_mb: 4096,
            install_client_mod: true,
            created: String::new(),
            last_played: last,
            play_count: plays,
        }
    }

    #[test]
    fn auto_picks_the_most_played_instance_that_has_options() {
        let root = std::env::temp_dir().join(format!("sc-sync-{}", uuid::Uuid::new_v4()));
        let a = root.join("a");
        let b = root.join("b");
        let c = root.join("c");
        for d in [&a, &b] {
            std::fs::create_dir_all(d.join(".minecraft")).unwrap();
            std::fs::write(d.join(".minecraft/options.txt"), "fov:0\n").unwrap();
        }
        std::fs::create_dir_all(c.join(".minecraft")).unwrap();

        let all = vec![inst("a", &a, 3, 10), inst("b", &b, 9, 5), inst("c", &c, 50, 50)];
        let mut cfg = LauncherConfig::default();
        cfg.sync_source = "auto".into();
        assert_eq!(source(&cfg, &all).unwrap().id, "b", "c never wrote options");

        cfg.sync_source = "a".into();
        assert_eq!(source(&cfg, &all).unwrap().id, "a", "a chosen source wins");

        cfg.sync_source = "gone".into();
        assert_eq!(source(&cfg, &all).unwrap().id, "b", "a deleted choice falls back to auto");
        std::fs::remove_dir_all(root).ok();
    }

    #[test]
    fn capture_and_apply_move_the_files_across() {
        let root = std::env::temp_dir().join(format!("sc-sync-{}", uuid::Uuid::new_v4()));
        let a = root.join("a");
        let b = root.join("b");
        let ga = a.join(".minecraft");
        std::fs::create_dir_all(ga.join("config/spaceclient-profiles")).unwrap();
        std::fs::write(ga.join("options.txt"), "version:4700\nkey_key.jump:key.keyboard.f\n").unwrap();
        std::fs::write(ga.join("servers.dat"), [1u8, 2, 3]).unwrap();
        std::fs::write(ga.join("config/spaceclient.json"), "{\"fps\":true}").unwrap();
        std::fs::write(ga.join("config/spaceclient-tokens.json"), "secret").unwrap();
        std::fs::write(ga.join("config/spaceclient-profiles/pvp.json"), "{}").unwrap();
        std::fs::create_dir_all(b.join(".minecraft")).unwrap();
        std::fs::write(b.join(".minecraft/options.txt"), "version:4189\n").unwrap();

        let mut cfg = LauncherConfig::default();
        cfg.install_path = root.join("data").to_string_lossy().to_string();
        let from = inst("a", &a, 1, 1);
        let to = inst("b", &b, 0, 0);

        assert_eq!(capture(&cfg, &from).unwrap(), 4);
        apply(&cfg, &to).unwrap();

        let gb = b.join(".minecraft");
        assert_eq!(std::fs::read_to_string(gb.join("options.txt")).unwrap(),
                   "version:4189\nkey_key.jump:key.keyboard.f\n");
        assert_eq!(std::fs::read(gb.join("servers.dat")).unwrap(), vec![1, 2, 3]);
        assert!(gb.join("config/spaceclient.json").exists());
        assert!(gb.join("config/spaceclient-profiles/pvp.json").exists());
        assert!(!gb.join("config/spaceclient-tokens.json").exists(), "tokens never travel");
        std::fs::remove_dir_all(root).ok();
    }
}
