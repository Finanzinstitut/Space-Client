//! Minecraft Bedrock, as far as a launcher can reach it from the outside.
//!
//! Bedrock is a Microsoft Store app, not a jar we assemble and start. So there
//! is no classpath to build, no Java to pick and no mods folder to arrange -
//! the one thing a launcher can do is find whether it is installed, read which
//! version, and ask Windows to open it, which is exactly what pressing its tile
//! does. The Space Client look is carried in separately as a resource pack (see
//! `bedrock_pack`); nothing here reaches into the running game.
//!
//! Everything is written to compile on every platform the launcher builds on.
//! Bedrock only exists on Windows, so off Windows every call here reports "not
//! installed" rather than failing to build.

use serde::Serialize;
use std::path::PathBuf;

/// The Store package Bedrock has shipped under since it became a UWP/GDK app.
/// Stable across versions - the version is inside, the identity is not.
const PACKAGE: &str = "Microsoft.MinecraftUWP_8wekyb3d8bbwe";

#[derive(Debug, Clone, Serialize)]
pub struct BedrockInfo {
    pub installed: bool,
    /// The version Windows reports, e.g. "1.21.44.0"; empty when unknown.
    pub version: String,
    /// Whether the Space Client resource pack is in the game's pack folder.
    pub pack_installed: bool,
}

/// `%LOCALAPPDATA%\Packages\<package>\LocalState`, where the game keeps the
/// files a user owns. Present only once the game has been run at least once.
pub fn local_state() -> Option<PathBuf> {
    let base = dirs::data_local_dir()?;
    let dir = base.join("Packages").join(PACKAGE).join("LocalState");
    Some(dir)
}

/// Where Bedrock loads unpacked resource packs from. The game watches this
/// folder, so a pack dropped in shows up in its list without a store trip.
pub fn development_resource_packs() -> Option<PathBuf> {
    Some(
        local_state()?
            .join("games")
            .join("com.mojang")
            .join("development_resource_packs"),
    )
}

/// Whether Bedrock is installed, going by its LocalState folder.
///
/// Checking the folder rather than asking the package manager keeps this cheap
/// and synchronous, and it answers the question the launcher actually cares
/// about: is there an installation this user has set up and can we reach its
/// pack folder. A package registered but never launched has no LocalState and
/// is, for our purposes, not yet ready - which is the honest answer.
pub fn is_installed() -> bool {
    local_state().map(|p| p.exists()).unwrap_or(false)
}

/// The installed version string, read from the Store package.
///
/// Only on Windows, and only through the package manager - there is no version
/// file in LocalState to read. Any failure (no PowerShell, a locked-down
/// machine, a parse that does not match) is an empty string, not an error: the
/// launcher shows "Bedrock" without a number and that is fine.
#[cfg(windows)]
pub fn version() -> String {
    use std::os::windows::process::CommandExt;
    use std::process::Command;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;

    let out = Command::new("powershell")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "(Get-AppxPackage -Name Microsoft.MinecraftUWP).Version",
        ])
        .creation_flags(CREATE_NO_WINDOW)
        .output();

    match out {
        Ok(o) if o.status.success() => {
            let v = String::from_utf8_lossy(&o.stdout).trim().to_string();
            // Guard against anything that is not a version: a machine with the
            // store app blocked prints a warning to stdout instead
            if !v.is_empty() && v.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false) {
                v
            } else {
                String::new()
            }
        }
        _ => String::new(),
    }
}

#[cfg(not(windows))]
pub fn version() -> String {
    String::new()
}

/// Everything the Bedrock view needs in one answer.
pub fn info(pack_installed: bool) -> BedrockInfo {
    let installed = is_installed();
    BedrockInfo {
        installed,
        version: if installed { version() } else { String::new() },
        pack_installed,
    }
}

/// Opens Minecraft Bedrock the same way its Start-menu tile does: the
/// `minecraft:` protocol, handed to the shell. We do not start the executable
/// ourselves - a GDK app launched by path refuses to run outside the gaming
/// services that the protocol brings up, so the protocol is the only launch
/// that works, and it is the plain one with nothing attached.
#[cfg(windows)]
pub fn launch() -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    use std::process::Command;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;

    if !is_installed() {
        return Err("Minecraft Bedrock is not installed on this computer.".into());
    }
    // Through explorer so the protocol is resolved by the shell exactly as a
    // click on the tile would resolve it.
    Command::new("explorer")
        .arg("minecraft://")
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("Could not open Minecraft Bedrock: {e}"))
}

#[cfg(not(windows))]
pub fn launch() -> Result<(), String> {
    Err("Minecraft Bedrock can only be launched on Windows.".into())
}
