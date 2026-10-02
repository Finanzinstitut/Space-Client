//! The Space Client resource pack for Bedrock: shipped inside the launcher,
//! unpacked into the game's own pack folder.
//!
//! A resource pack is the sanctioned way to change how Bedrock looks - Mojang
//! reads it, the launcher only places the files. So this is all the Space
//! Client look Bedrock can have without reaching into the running game: the
//! menus and buttons recoloured to the dark theme. The textures are plain
//! recolours drawn by us; if a given Bedrock version no longer uses one of the
//! paths, that file is simply ignored, which is why the pack is safe to leave
//! installed across game updates.
//!
//! The files are embedded in the binary rather than bundled beside it, so there
//! is nothing to find at runtime and no path that differs between a dev build
//! and an installed one.

use crate::launcher::bedrock;
use std::fs;
use std::path::{Path, PathBuf};

/// The pack's own version. Bumped when the embedded files change, so an older
/// copy already in the folder is noticed and rewritten rather than left stale.
pub const PACK_VERSION: &str = "1.0.0";

/// The folder name under development_resource_packs. Also how the pack is
/// recognised again for removal and for the version check.
const FOLDER: &str = "SpaceClient";

/// A stamp written next to the pack so a future launcher build can tell which
/// version is on disk without parsing the manifest.
const STAMP: &str = ".spaceclient-version";

/// Every file in the pack, as (path within the pack, bytes). A fixed, small
/// set, so one entry each is clearer than walking a directory that would have
/// to exist at runtime.
const FILES: &[(&str, &[u8])] = &[
    ("manifest.json", include_bytes!("../../bedrock_pack/manifest.json")),
    ("pack_icon.png", include_bytes!("../../bedrock_pack/pack_icon.png")),
    (
        "textures/ui/dialog_background_opaque.png",
        include_bytes!("../../bedrock_pack/textures/ui/dialog_background_opaque.png"),
    ),
    (
        "textures/ui/dialog_background_hover.png",
        include_bytes!("../../bedrock_pack/textures/ui/dialog_background_hover.png"),
    ),
    (
        "textures/ui/button_borderless_light.png",
        include_bytes!("../../bedrock_pack/textures/ui/button_borderless_light.png"),
    ),
    (
        "textures/ui/button_borderless_lighthover.png",
        include_bytes!("../../bedrock_pack/textures/ui/button_borderless_lighthover.png"),
    ),
    (
        "textures/ui/button_borderless_lightpressed.png",
        include_bytes!("../../bedrock_pack/textures/ui/button_borderless_lightpressed.png"),
    ),
    (
        "textures/ui/accent.png",
        include_bytes!("../../bedrock_pack/textures/ui/accent.png"),
    ),
];

fn pack_dir() -> Option<PathBuf> {
    Some(bedrock::development_resource_packs()?.join(FOLDER))
}

/// Whether our pack is present and at this launcher's version.
///
/// An older stamp counts as not installed, so `install` will rewrite it: the
/// alternative is a game showing last version's textures with no way to say so.
pub fn is_installed() -> bool {
    let Some(dir) = pack_dir() else { return false };
    if !dir.join("manifest.json").exists() {
        return false;
    }
    match fs::read_to_string(dir.join(STAMP)) {
        Ok(v) => v.trim() == PACK_VERSION,
        Err(_) => false,
    }
}

/// Writes the pack into the game's development_resource_packs folder.
///
/// The folder is created along the way (a user who has launched Bedrock once
/// has LocalState; the games/com.mojang path under it may still be missing if
/// they have never opened a pack). Writing a fresh copy each time rather than
/// diffing keeps this a single obvious operation - the file set is tiny.
pub fn install() -> Result<(), String> {
    if !bedrock::is_installed() {
        return Err("Minecraft Bedrock is not installed on this computer.".into());
    }
    let dir = pack_dir().ok_or("Could not find the Bedrock pack folder.")?;

    // A half-written older copy must not survive under the new one
    if dir.exists() {
        let _ = fs::remove_dir_all(&dir);
    }

    for (rel, bytes) in FILES {
        let target = dir.join(rel);
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("Could not create {}: {e}", parent.display()))?;
        }
        write_atomic(&target, bytes).map_err(|e| format!("Could not write {}: {e}", target.display()))?;
    }

    fs::write(dir.join(STAMP), PACK_VERSION)
        .map_err(|e| format!("Could not finish installing the pack: {e}"))?;
    Ok(())
}

/// Takes the pack back out. Missing is success - the end state is what was asked
/// for either way.
pub fn uninstall() -> Result<(), String> {
    let Some(dir) = pack_dir() else { return Ok(()) };
    if !dir.exists() {
        return Ok(());
    }
    fs::remove_dir_all(&dir).map_err(|e| format!("Could not remove the pack: {e}"))
}

fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, bytes)?;
    fs::rename(&tmp, path)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The embedded set is a valid pack: a manifest that parses with both
    /// UUIDs, a PNG icon, and every texture a real PNG. This runs the same
    /// bytes the installer writes, so a pack file that went missing or got
    /// corrupted fails the build rather than a player's game.
    #[test]
    fn embedded_pack_is_valid() {
        let manifest = FILES
            .iter()
            .find(|(p, _)| *p == "manifest.json")
            .map(|(_, b)| *b)
            .expect("manifest present");
        let text = std::str::from_utf8(manifest).unwrap();
        assert!(text.contains("\"uuid\""), "manifest has a header uuid");
        assert_eq!(text.matches("\"uuid\"").count(), 2, "header and module uuid");
        assert!(text.contains("\"min_engine_version\""));

        let png = [0x89u8, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
        for (path, bytes) in FILES {
            if path.ends_with(".png") {
                assert!(bytes.len() > 8 && bytes[..8] == png, "{path} is a PNG");
            }
            assert!(!bytes.is_empty(), "{path} is not empty");
        }
    }

    #[test]
    fn version_is_sane() {
        assert_eq!(PACK_VERSION.matches('.').count(), 2);
    }
}
