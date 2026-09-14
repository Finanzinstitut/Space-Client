//! Copies of your worlds, kept beside the instance.
//!
//! This is the only thing in the launcher that guards against a loss nothing
//! else can undo. A wrong mod is a rename away from fixed and a broken install
//! is a redownload; a world eaten by a corrupt region file, a misfiring
//! worldedit or a creeper in the storage room is simply gone, and no amount of
//! care afterwards brings it back.
//!
//! Deliberately plain. A zip of the world folder, named for the world and the
//! moment, in a folder next to the instance - openable by hand, restorable by
//! hand if this launcher ever stops existing. Clever incremental formats are a
//! better use of disk and a worse thing to be holding when you are upset.

use crate::launcher::instance::{self, Instance};

use serde::Serialize;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize)]
pub struct BackupEntry {
    /// File name of the archive.
    pub file: String,
    /// Which world it holds.
    pub world: String,
    /// Unix seconds, taken from the file so a copied backup still reads right.
    pub made: u64,
    pub size: u64,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct BackupReport {
    pub made: Vec<String>,
    pub removed: Vec<String>,
    pub skipped: Vec<String>,
    pub note: String,
}

/// Name of the note inside each archive that records the real world name.
const MARKER: &str = "spaceclient-backup.json";

fn backups_dir(inst: &Instance) -> PathBuf {
    inst.dir().join("backups")
}

fn saves_dir(inst: &Instance) -> PathBuf {
    inst.game_dir().join("saves")
}

/// Keeps a name usable as a file name on Windows as well as everywhere else.
fn safe(name: &str) -> String {
    name.chars()
        .map(|c| if c.is_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect()
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// The worlds in an instance, newest first by last change.
pub fn list_worlds(instance_id: &str) -> Vec<String> {
    let Some(inst) = instance::get(instance_id) else { return Vec::new() };
    let mut out: Vec<(String, u64)> = Vec::new();

    if let Ok(entries) = std::fs::read_dir(saves_dir(&inst)) {
        for entry in entries.flatten() {
            if !entry.path().is_dir() {
                continue;
            }
            // level.dat is what makes a folder a world rather than a stray
            // directory somebody dropped in saves/.
            if !entry.path().join("level.dat").exists() {
                continue;
            }
            let changed = entry
                .metadata()
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs())
                .unwrap_or(0);
            out.push((entry.file_name().to_string_lossy().to_string(), changed));
        }
    }
    out.sort_by(|a, b| b.1.cmp(&a.1));
    out.into_iter().map(|(name, _)| name).collect()
}

pub fn list(instance_id: &str) -> Vec<BackupEntry> {
    let Some(inst) = instance::get(instance_id) else { return Vec::new() };
    let mut out = Vec::new();

    if let Ok(entries) = std::fs::read_dir(backups_dir(&inst)) {
        for entry in entries.flatten() {
            let file = entry.file_name().to_string_lossy().to_string();
            if !file.ends_with(".zip") {
                continue;
            }
            let meta = entry.metadata().ok();
            let made = meta
                .as_ref()
                .and_then(|m| m.modified().ok())
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs())
                .unwrap_or(0);
            // "<world>--<stamp>.zip"; anything else was not written by us and
            // is left alone rather than guessed at.
            let world = file
                .rsplit_once("--")
                .map(|(w, _)| w.to_string())
                .unwrap_or_else(|| file.trim_end_matches(".zip").to_string());
            out.push(BackupEntry {
                file,
                world,
                made,
                size: meta.map(|m| m.len()).unwrap_or(0),
            });
        }
    }
    out.sort_by(|a, b| b.made.cmp(&a.made));
    out
}

fn add_dir(zip: &mut zip::ZipWriter<std::fs::File>, root: &Path, dir: &Path) -> anyhow::Result<()> {
    let options = zip::write::FileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);

    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        let rel = path.strip_prefix(root)?.to_string_lossy().replace('\\', "/");

        if path.is_dir() {
            add_dir(zip, root, &path)?;
        } else {
            // A world that is open has session.lock held by the game, and on
            // Windows that file cannot be read at all. It carries nothing worth
            // keeping, so it is skipped rather than allowed to fail the backup.
            if path.file_name().map(|n| n == "session.lock").unwrap_or(false) {
                continue;
            }
            let mut file = match std::fs::File::open(&path) {
                Ok(f) => f,
                Err(_) => continue,
            };
            zip.start_file(rel, options)?;
            let mut buffer = Vec::new();
            file.read_to_end(&mut buffer)?;
            zip.write_all(&buffer)?;
        }
    }
    Ok(())
}

/// Packs one world. Returns the archive's file name.
pub fn create(instance_id: &str, world: &str) -> anyhow::Result<String> {
    let inst = instance::get(instance_id)
        .ok_or_else(|| anyhow::anyhow!("Instance not found"))?;

    let source = saves_dir(&inst).join(world);
    if !source.join("level.dat").exists() {
        return Err(anyhow::anyhow!("{} is not a world folder", world));
    }

    let dir = backups_dir(&inst);
    std::fs::create_dir_all(&dir)?;

    let file_name = format!("{}--{}.zip", safe(world), now_secs());
    let target = dir.join(&file_name);

    // Written to a part file and renamed at the end. An archive interrupted
    // half way through is worse than none: it looks like a backup, and you
    // only find out otherwise at the moment you need it.
    let part = dir.join(format!("{file_name}.part"));
    {
        let file = std::fs::File::create(&part)?;
        let mut zip = zip::ZipWriter::new(file);

        // The world's real name, written inside the archive.
        //
        // The file name cannot carry it: a world called "Meine Welt" has to
        // become "Meine_Welt" to be a safe file name on every platform, and
        // restoring from that name created a brand new folder beside the world
        // it was supposed to replace - leaving the damaged one in place and the
        // recovered one somewhere nobody was looking. Found by restoring a
        // world with a space in its name and watching it not come back.
        let note = serde_json::json!({ "world": world, "made": now_secs() });
        zip.start_file(MARKER, zip::write::FileOptions::default())?;
        zip.write_all(note.to_string().as_bytes())?;

        add_dir(&mut zip, &source, &source)?;
        zip.finish()?;
    }
    std::fs::rename(&part, &target)?;

    Ok(file_name)
}

/// Throws away the oldest copies of one world beyond `keep`.
pub fn prune(instance_id: &str, world: &str, keep: usize) -> Vec<String> {
    let mut removed = Vec::new();
    let Some(inst) = instance::get(instance_id) else { return removed };

    let mut mine: Vec<BackupEntry> = list(instance_id)
        .into_iter()
        .filter(|b| b.world == safe(world))
        .collect();
    mine.sort_by(|a, b| b.made.cmp(&a.made));

    for old in mine.into_iter().skip(keep.max(1)) {
        if std::fs::remove_file(backups_dir(&inst).join(&old.file)).is_ok() {
            removed.push(old.file);
        }
    }
    removed
}

/// Backs up every world in the instance and prunes each to `keep`.
///
/// Never returns an error: this runs on the way to starting the game, and a
/// world that could not be packed is a reason to say so, not a reason to stop
/// somebody from playing.
pub fn run_all(instance_id: &str, keep: usize) -> BackupReport {
    let mut report = BackupReport::default();

    let worlds = list_worlds(instance_id);
    if worlds.is_empty() {
        report.note = "no worlds in this instance yet".into();
        return report;
    }

    for world in worlds {
        match create(instance_id, &world) {
            Ok(file) => {
                report.made.push(file);
                report.removed.extend(prune(instance_id, &world, keep));
            }
            Err(e) => report.skipped.push(format!("{world}: {e}")),
        }
    }
    report
}

/// Puts a world back from an archive.
///
/// The world that is there now is moved aside rather than deleted, under
/// "<name>-replaced-<stamp>". Restoring the wrong backup is an easy mistake to
/// make and an unbearable one to make irreversible.
pub fn restore(instance_id: &str, file: &str) -> anyhow::Result<String> {
    let inst = instance::get(instance_id)
        .ok_or_else(|| anyhow::anyhow!("Instance not found"))?;

    if file.contains('/') || file.contains('\\') || file.contains("..") {
        return Err(anyhow::anyhow!("That is not a backup in this instance."));
    }

    let archive_path = backups_dir(&inst).join(file);

    let reader = std::fs::File::open(&archive_path)?;
    let mut zip = zip::ZipArchive::new(reader)?;

    // The name from inside the archive, falling back to the file name for
    // copies made before the note existed.
    let world = read_marker(&mut zip).unwrap_or_else(|| {
        file.rsplit_once("--")
            .map(|(w, _)| w.to_string())
            .unwrap_or_else(|| file.trim_end_matches(".zip").to_string())
    });

    let target = saves_dir(&inst).join(&world);
    if target.exists() {
        let aside = saves_dir(&inst).join(format!("{}-replaced-{}", world, now_secs()));
        std::fs::rename(&target, &aside)?;
    }
    std::fs::create_dir_all(&target)?;

    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        // enclosed_name refuses anything that would climb out of the folder,
        // which is the whole defence against a hand-edited archive.
        let Some(rel) = entry.enclosed_name() else { continue };
        // The note is ours, not part of the world.
        if rel.to_string_lossy() == MARKER {
            continue;
        }
        let out = target.join(rel);

        if entry.is_dir() {
            std::fs::create_dir_all(&out)?;
            continue;
        }
        if let Some(parent) = out.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut sink = std::fs::File::create(&out)?;
        std::io::copy(&mut entry, &mut sink)?;
    }

    Ok(world)
}

/// The world name recorded inside an archive, if it carries one.
fn read_marker(zip: &mut zip::ZipArchive<std::fs::File>) -> Option<String> {
    let mut entry = zip.by_name(MARKER).ok()?;
    let mut text = String::new();
    entry.read_to_string(&mut text).ok()?;
    let json: serde_json::Value = serde_json::from_str(&text).ok()?;
    json.get("world")?.as_str().map(String::from)
}

pub fn remove(instance_id: &str, file: &str) -> anyhow::Result<()> {
    let inst = instance::get(instance_id)
        .ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    if file.contains('/') || file.contains('\\') || file.contains("..") {
        return Err(anyhow::anyhow!("That is not a backup in this instance."));
    }
    std::fs::remove_file(backups_dir(&inst).join(file))?;
    Ok(())
}
