//! Which mods should be loaded for which server.
//!
//! The thing this is working around is not a missing feature, it is a property
//! of the loader. Fabric puts every jar in `mods/` on the classpath before a
//! single Minecraft class exists, and mixins are woven in as classes load.
//! There is no unload, so "switch a mod off when you join a server" cannot
//! happen in a running game - by any client, not just this one. What can happen
//! is deciding the set before Java starts, which is the launcher's job, and is
//! all this does.
//!
//! A profile therefore says one thing: for this server, these mods stay parked.
//! Everything else is switched on. That rule is worth stating plainly because
//! the alternative - only ever touching what the profile names - leaves a mod
//! you parked under one server silently parked under the next, and "why is
//! Sodium off on singleplayer" is a worse bug than an explicit rule.

use crate::launcher::clientmod;
use crate::launcher::instance::{self, Instance};
use crate::launcher::mods;

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerProfile {
    /// The address as `servers.dat` spells it. This is the key.
    pub address: String,
    /// What to call it on screen. Copied from the server list when the profile
    /// is made, so a profile still reads sensibly if the server is later
    /// removed from the game's list.
    #[serde(default)]
    pub name: String,
    /// Mod filenames to keep parked for this server.
    #[serde(default)]
    pub disabled: Vec<String>,
    /// Hand the address to the game so it connects without another click.
    #[serde(default)]
    pub auto_join: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ProfileFile {
    #[serde(default)]
    pub profiles: Vec<ServerProfile>,
    /// The address whose profile the next launch uses. Empty means launch
    /// untouched, which is how an instance behaves until somebody picks one.
    #[serde(default)]
    pub active: String,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct Applied {
    pub enabled: Vec<String>,
    pub disabled: Vec<String>,
    /// Files that would not rename. Reported rather than swallowed: a profile
    /// that half applied and said nothing is how you end up on a server with
    /// the wrong mods and no idea why.
    pub failed: Vec<String>,
    pub note: String,
}

fn file_path(inst: &Instance) -> PathBuf {
    inst.dir().join("server-profiles.json")
}

pub fn load(instance_id: &str) -> ProfileFile {
    let Some(inst) = instance::get(instance_id) else {
        return ProfileFile::default();
    };
    match std::fs::read_to_string(file_path(&inst)) {
        Ok(text) => serde_json::from_str(&text).unwrap_or_default(),
        Err(_) => ProfileFile::default(),
    }
}

pub fn save(instance_id: &str, file: &ProfileFile) -> anyhow::Result<()> {
    let inst = instance::get(instance_id)
        .ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    std::fs::create_dir_all(inst.dir())?;
    std::fs::write(file_path(&inst), serde_json::to_string_pretty(file)?)?;
    Ok(())
}

/// Whether this file is allowed to be parked at all.
///
/// The client mod is not. Parking it removes the menu that manages these
/// profiles, and the only way back would be to know that the fix is renaming a
/// file in a folder - which is exactly the kind of trap a launcher should not
/// be able to set for its own user.
fn parkable(filename: &str) -> bool {
    if filename.eq_ignore_ascii_case(clientmod::FILE_NAME) {
        return false;
    }
    // The launcher installs it under one name, but a jar dropped in by hand
    // keeps whatever the download was called - usually the same word with a
    // version after it. Matching the stem as well costs nothing and covers the
    // case where the exact name check would have let it through.
    let stem = clientmod::FILE_NAME.trim_end_matches(".jar");
    !filename.to_ascii_lowercase().starts_with(stem)
}

/// Puts the mods folder into the state this server's profile asks for.
///
/// Only files whose state actually differs are touched, so a launch with
/// nothing to change does no file system work at all and cannot fail.
pub fn apply(instance_id: &str, address: &str) -> anyhow::Result<Applied> {
    let inst = instance::get(instance_id)
        .ok_or_else(|| anyhow::anyhow!("Instance not found"))?;

    let file = load(instance_id);
    let Some(profile) = file.profiles.iter().find(|p| p.address == address) else {
        return Ok(Applied {
            note: format!("no profile saved for {address}"),
            ..Default::default()
        });
    };

    let mut applied = Applied::default();

    for (filename, currently_enabled) in mods::scan_content(&inst, "mod") {
        if !parkable(&filename) {
            continue;
        }

        let should_be_enabled = !profile.disabled.iter().any(|d| d == &filename);
        if should_be_enabled == currently_enabled {
            continue;
        }

        match mods::set_mod_enabled(instance_id, &filename, "mod", should_be_enabled) {
            Ok(()) => {
                if should_be_enabled {
                    applied.enabled.push(filename);
                } else {
                    applied.disabled.push(filename);
                }
            }
            Err(e) => applied.failed.push(format!("{filename}: {e}")),
        }
    }

    Ok(applied)
}

/// The address the next launch should connect to on its own, if any.
pub fn auto_join_address(instance_id: &str) -> Option<String> {
    let file = load(instance_id);
    if file.active.is_empty() {
        return None;
    }
    file.profiles
        .iter()
        .find(|p| p.address == file.active && p.auto_join)
        .map(|p| p.address.clone())
}
