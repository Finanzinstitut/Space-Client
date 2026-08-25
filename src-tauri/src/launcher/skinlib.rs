//! A local library of skin files.
//!
//! Mojang only ever remembers the skin that is currently applied, so anything
//! the user dropped in before is gone the moment they switch. Keeping a copy
//! next to the launcher settings means every skin that ever passed through
//! stays one click away, and it works offline because the file is right there.

use crate::launcher::config::config_dir;
use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};
use std::path::PathBuf;

fn skins_dir() -> PathBuf {
    let dir = config_dir().join("skins");
    let _ = std::fs::create_dir_all(&dir);
    dir
}

fn index_file() -> PathBuf {
    skins_dir().join("index.json")
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct SavedSkin {
    pub id: String,
    pub name: String,
    /// "classic" or "slim" - remembered per skin, since the model is part of
    /// how a skin is meant to look.
    pub variant: String,
    /// File name inside the skins folder.
    pub file: String,
    pub added: i64,
    /// Filled in on the way out only. The webview cannot read the config
    /// folder, so the PNG travels as a data URL - a few kilobytes each.
    #[serde(default, skip_deserializing)]
    pub data_url: String,
}

fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn load_index() -> Vec<SavedSkin> {
    std::fs::read_to_string(index_file())
        .ok()
        .and_then(|d| serde_json::from_str(&d).ok())
        .unwrap_or_default()
}

fn save_index(list: &[SavedSkin]) -> anyhow::Result<()> {
    std::fs::write(index_file(), serde_json::to_string_pretty(list)?)?;
    Ok(())
}

/// Minimal base64 encoder, so a single data URL does not pull in a dependency.
fn base64(data: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;

        out.push(TABLE[(n >> 18) as usize & 63] as char);
        out.push(TABLE[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 {
            TABLE[(n >> 6) as usize & 63] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            TABLE[n as usize & 63] as char
        } else {
            '='
        });
    }
    out
}

fn to_data_url(bytes: &[u8]) -> String {
    format!("data:image/png;base64,{}", base64(bytes))
}

/// Rejects anything that is not a PNG before it is stored, so a broken file
/// cannot end up in the library and fail later at upload time.
fn check_png(bytes: &[u8]) -> anyhow::Result<()> {
    if bytes.len() < 8 || &bytes[0..8] != b"\x89PNG\r\n\x1a\n" {
        anyhow::bail!("That file is not a PNG image.");
    }
    Ok(())
}

fn normalise_variant(variant: &str) -> String {
    if variant.eq_ignore_ascii_case("slim") {
        "slim".to_string()
    } else {
        "classic".to_string()
    }
}

/// Every stored skin, newest first, with its image attached.
pub fn list() -> Vec<SavedSkin> {
    let dir = skins_dir();
    let mut list = load_index();

    // Drop entries whose file disappeared, so the grid never shows a blank.
    list.retain(|s| dir.join(&s.file).exists());
    list.sort_by(|a, b| b.added.cmp(&a.added));

    for skin in list.iter_mut() {
        if let Ok(bytes) = std::fs::read(dir.join(&skin.file)) {
            skin.data_url = to_data_url(&bytes);
        }
    }
    list
}

/// Absolute path of a stored skin, for handing back to the uploader.
pub fn path_of(id: &str) -> anyhow::Result<PathBuf> {
    let entry = load_index()
        .into_iter()
        .find(|s| s.id == id)
        .ok_or_else(|| anyhow::anyhow!("That skin is not in the library."))?;
    let path = skins_dir().join(&entry.file);
    if !path.exists() {
        anyhow::bail!("The file for this skin is gone.");
    }
    Ok(path)
}

pub fn variant_of(id: &str) -> String {
    load_index()
        .into_iter()
        .find(|s| s.id == id)
        .map(|s| s.variant)
        .unwrap_or_else(|| "classic".to_string())
}

/// Stores raw PNG bytes under a content hash.
///
/// Hashing means dropping the same file twice updates the existing entry
/// instead of filling the grid with duplicates.
pub fn store_bytes(bytes: Vec<u8>, name: &str, variant: &str) -> anyhow::Result<SavedSkin> {
    check_png(&bytes)?;

    let mut hasher = Sha1::new();
    hasher.update(&bytes);
    let id = hex::encode(hasher.finalize());
    let file = format!("{}.png", id);

    std::fs::write(skins_dir().join(&file), &bytes)?;

    let display = if name.trim().is_empty() {
        format!("Skin {}", &id[..6])
    } else {
        name.trim().to_string()
    };

    let mut list = load_index();
    let entry = match list.iter_mut().find(|s| s.id == id) {
        Some(existing) => {
            existing.variant = normalise_variant(variant);
            existing.clone()
        }
        None => {
            let entry = SavedSkin {
                id: id.clone(),
                name: display,
                variant: normalise_variant(variant),
                file,
                added: now(),
                data_url: String::new(),
            };
            list.push(entry.clone());
            entry
        }
    };
    save_index(&list)?;

    let mut out = entry;
    out.data_url = to_data_url(&bytes);
    Ok(out)
}

/// Copies a PNG from anywhere on disk into the library.
pub async fn store_file(path: &str, variant: &str) -> anyhow::Result<SavedSkin> {
    let bytes = tokio::fs::read(path).await?;
    let name = std::path::Path::new(path)
        .file_stem()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_default();
    store_bytes(bytes, &name, variant)
}

/// Fetches a skin texture by URL and stores it. Used to keep the skin that is
/// already on the account before it gets replaced.
pub async fn store_url(url: &str, name: &str, variant: &str) -> anyhow::Result<SavedSkin> {
    if url.trim().is_empty() {
        anyhow::bail!("There is no skin to save yet.");
    }
    let bytes = reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .build()?
        .get(url)
        .send()
        .await?
        .error_for_status()?
        .bytes()
        .await?
        .to_vec();
    store_bytes(bytes, name, variant)
}

pub fn rename(id: &str, name: &str) -> anyhow::Result<()> {
    if name.trim().is_empty() {
        anyhow::bail!("Please enter a name.");
    }
    let mut list = load_index();
    let entry = list
        .iter_mut()
        .find(|s| s.id == id)
        .ok_or_else(|| anyhow::anyhow!("That skin is not in the library."))?;
    entry.name = name.trim().to_string();
    save_index(&list)
}

/// Removes a skin from the library. The account keeps whatever is applied -
/// this only forgets the local copy.
pub fn delete(id: &str) -> anyhow::Result<()> {
    let list = load_index();
    if let Some(entry) = list.iter().find(|s| s.id == id) {
        std::fs::remove_file(skins_dir().join(&entry.file)).ok();
    }
    let remaining: Vec<SavedSkin> = list.into_iter().filter(|s| s.id != id).collect();
    save_index(&remaining)
}
