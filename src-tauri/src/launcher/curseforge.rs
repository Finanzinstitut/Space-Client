//! CurseForge as a second place to get mods from.
//!
//! Deliberately shaped to hand back the same `ModHit` and `ProjectVersion` the
//! Modrinth side does, so switching source is a switch in one dropdown rather
//! than a second screen with its own habits.
//!
//! Two things here are discovered rather than written down. CurseForge sorts
//! projects by a numeric class id and loaders by a numeric enum, and both are
//! the kind of number that is easy to find quoted wrongly. The class ids are
//! asked for once and matched on their slugs; the loader number is sent as a
//! hint and the answer is then filtered again on the loader NAMES the API
//! returns with each file. So a wrong number costs a wider first answer, never
//! a wrong install.

use crate::launcher::mods::{InstalledMod, ModHit, ProjectVersion};

use serde::Deserialize;
use std::collections::HashMap;
use std::sync::Mutex;

const API: &str = "https://api.curseforge.com/v1";

/// Minecraft, in CurseForge's numbering. The one number that is safe to write
/// down: it is in every example in their documentation.
const GAME_MINECRAFT: u32 = 432;

/// The key this build ships with, if it was given one.
///
/// CurseForge issues a key per approved application, and an application is
/// meant to carry its own - that is the whole point of the third party
/// programme, and why every launcher that offers CurseForge does this. It is
/// put in at build time from a secret rather than written in the source,
/// because this repository is public and a key in public source lasts about a
/// day.
///
/// It is extractable from the binary by anybody willing to look, and there is
/// no way around that for a program that runs on someone else's machine. The
/// answer to a key being abused is to replace it, not to pretend it is hidden.
fn baked_key() -> &'static str {
    option_env!("CURSEFORGE_KEY").unwrap_or("")
}

/// The key to use: the one somebody typed in, or the one this build carries.
///
/// A typed key wins, which matters twice - somebody who would rather spend
/// their own quota can, and if the shipped key is ever exhausted or withdrawn,
/// there is a way out that does not need a new release.
pub fn effective_key(user: &str) -> String {
    if user.trim().is_empty() {
        baked_key().to_string()
    } else {
        user.trim().to_string()
    }
}

/// Whether this install can reach CurseForge at all.
pub fn have_key(user: &str) -> bool {
    !effective_key(user).is_empty()
}

fn http(key: &str) -> anyhow::Result<reqwest::Client> {
    if key.trim().is_empty() {
        anyhow::bail!("This build carries no CurseForge key, and none is set in Settings.");
    }
    let mut headers = reqwest::header::HeaderMap::new();
    headers.insert(
        "x-api-key",
        reqwest::header::HeaderValue::from_str(key.trim())?,
    );
    Ok(reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .default_headers(headers)
        .build()?)
}

// ---------------------------------------------------------------------------
// class ids
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct ClassEntry {
    id: u32,
    slug: String,
}

#[derive(Deserialize)]
struct ClassResponse {
    data: Vec<ClassEntry>,
}

fn class_cache() -> &'static Mutex<HashMap<String, u32>> {
    static CACHE: std::sync::OnceLock<Mutex<HashMap<String, u32>>> = std::sync::OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// The class id for one of our three project types, asked for once.
async fn class_id(key: &str, project_type: &str) -> anyhow::Result<u32> {
    // Their slugs, not ours. These are stable strings in the site's own urls,
    // which is a far better thing to depend on than a number.
    let slug = match project_type {
        "resourcepack" => "texture-packs",
        "shader" => "shaders",
        _ => "mc-mods",
    };

    if let Some(found) = class_cache().lock().unwrap().get(slug) {
        return Ok(*found);
    }

    let response = http(key)?
        .get(format!("{}/categories", API))
        .query(&[
            ("gameId", GAME_MINECRAFT.to_string()),
            ("classesOnly", "true".to_string()),
        ])
        .send()
        .await?;

    if !response.status().is_success() {
        anyhow::bail!("{}", explain(response.status().as_u16()));
    }

    let parsed: ClassResponse = response.json().await?;
    let mut cache = class_cache().lock().unwrap();
    for entry in parsed.data {
        cache.insert(entry.slug, entry.id);
    }

    cache
        .get(slug)
        .copied()
        .ok_or_else(|| anyhow::anyhow!("CurseForge lists no category called {}", slug))
}

/// The loader hint. Sent to narrow the search; never trusted on its own.
fn loader_hint(loader: &str) -> Option<u32> {
    match loader {
        "forge" => Some(1),
        "fabric" => Some(4),
        "quilt" => Some(5),
        "neoforge" => Some(6),
        _ => None,
    }
}

/// Whether a file's own list of versions mentions this loader.
///
/// CurseForge puts loader names into the same array as game versions, so
/// "1.21.4" and "Fabric" sit side by side. Reading the names is what makes the
/// numeric enum above a hint rather than a dependency.
fn file_fits_loader(versions: &[String], loader: &str) -> bool {
    if loader == "vanilla" {
        return true;
    }
    let wanted = match loader {
        "forge" => "forge",
        "fabric" => "fabric",
        "quilt" => "quilt",
        "neoforge" => "neoforge",
        _ => return true,
    };

    let mentions_any_loader = versions.iter().any(|v| {
        let low = v.to_lowercase();
        low == "forge" || low == "fabric" || low == "quilt" || low == "neoforge"
    });

    // A file that names no loader at all is a resource pack, a shader, or an
    // older mod from before the field existed. Refusing those would empty the
    // list for exactly the project types that have no loader.
    if !mentions_any_loader {
        return true;
    }

    versions.iter().any(|v| {
        let low = v.to_lowercase();
        low == wanted || (wanted == "fabric" && low == "quilt")
    })
}

// ---------------------------------------------------------------------------
// search
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct SearchResponse {
    data: Vec<CfMod>,
}

#[derive(Deserialize)]
struct CfMod {
    id: u32,
    name: String,
    slug: String,
    summary: String,
    #[serde(rename = "downloadCount")]
    download_count: f64,
    #[serde(default)]
    logo: Option<CfLogo>,
    #[serde(default)]
    authors: Vec<CfAuthor>,
    #[serde(default)]
    categories: Vec<CfCategory>,
}

#[derive(Deserialize)]
struct CfLogo {
    #[serde(rename = "thumbnailUrl")]
    thumbnail_url: String,
}

#[derive(Deserialize)]
struct CfAuthor {
    name: String,
}

#[derive(Deserialize)]
struct CfCategory {
    slug: String,
}

pub async fn search(
    key: &str,
    query: String,
    mc_version: String,
    loader: String,
    project_type: String,
    offset: u32,
) -> anyhow::Result<Vec<ModHit>> {
    if project_type == "mod" && loader == "vanilla" {
        anyhow::bail!("This instance has no mod loader. Create a Fabric, Quilt, Forge or NeoForge instance to use mods.");
    }

    let class = class_id(key, &project_type).await?;

    let mut params: Vec<(String, String)> = vec![
        ("gameId".into(), GAME_MINECRAFT.to_string()),
        ("classId".into(), class.to_string()),
        ("gameVersion".into(), mc_version),
        ("index".into(), offset.to_string()),
        ("pageSize".into(), "20".into()),
        // 2 is the popularity sort in their enum; with no search term that is
        // the useful order, and with one the server's relevance wins anyway.
        ("sortField".into(), "2".into()),
        ("sortOrder".into(), "desc".into()),
    ];
    if !query.trim().is_empty() {
        params.push(("searchFilter".into(), query));
    }
    if project_type == "mod" {
        if let Some(hint) = loader_hint(&loader) {
            params.push(("modLoaderType".into(), hint.to_string()));
        }
    }

    let response = http(key)?
        .get(format!("{}/mods/search", API))
        .query(&params)
        .send()
        .await?;

    if !response.status().is_success() {
        anyhow::bail!("{}", explain(response.status().as_u16()));
    }

    let parsed: SearchResponse = response.json().await?;

    Ok(parsed
        .data
        .into_iter()
        .map(|m| ModHit {
            project_id: m.id.to_string(),
            slug: m.slug,
            title: m.name,
            description: m.summary,
            author: m
                .authors
                .first()
                .map(|a| a.name.clone())
                .unwrap_or_default(),
            downloads: m.download_count.max(0.0) as u64,
            icon_url: m.logo.map(|l| l.thumbnail_url).unwrap_or_default(),
            categories: m.categories.into_iter().map(|c| c.slug).collect(),
            project_type: project_type.clone(),
        })
        .collect())
}

// ---------------------------------------------------------------------------
// files
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct FilesResponse {
    data: Vec<CfFile>,
}

#[derive(Deserialize, Clone)]
struct CfFile {
    id: u32,
    #[serde(rename = "displayName")]
    display_name: String,
    #[serde(rename = "fileName")]
    file_name: String,
    #[serde(rename = "releaseType")]
    release_type: u32,
    #[serde(rename = "fileDate")]
    file_date: String,
    #[serde(rename = "downloadCount", default)]
    download_count: f64,
    #[serde(rename = "gameVersions", default)]
    game_versions: Vec<String>,
    #[serde(rename = "downloadUrl", default)]
    download_url: Option<String>,
}

fn release_name(kind: u32) -> &'static str {
    match kind {
        1 => "release",
        2 => "beta",
        _ => "alpha",
    }
}

async fn files(
    key: &str,
    project_id: &str,
    mc_version: &str,
    loader: &str,
) -> anyhow::Result<Vec<CfFile>> {
    let mut params: Vec<(String, String)> = vec![
        ("gameVersion".into(), mc_version.to_string()),
        ("pageSize".into(), "50".into()),
    ];
    if let Some(hint) = loader_hint(loader) {
        params.push(("modLoaderType".into(), hint.to_string()));
    }

    let response = http(key)?
        .get(format!("{}/mods/{}/files", API, project_id))
        .query(&params)
        .send()
        .await?;

    if !response.status().is_success() {
        anyhow::bail!("{}", explain(response.status().as_u16()));
    }

    let parsed: FilesResponse = response.json().await?;

    Ok(parsed
        .data
        .into_iter()
        .filter(|f| file_fits_loader(&f.game_versions, loader))
        .collect())
}

pub async fn list_versions(
    key: &str,
    project_id: String,
    mc_version: String,
    loader: String,
) -> anyhow::Result<Vec<ProjectVersion>> {
    Ok(files(key, &project_id, &mc_version, &loader)
        .await?
        .into_iter()
        .map(|f| ProjectVersion {
            id: f.id.to_string(),
            name: f.display_name,
            version_number: f.file_name.clone(),
            version_type: release_name(f.release_type).to_string(),
            loaders: f
                .game_versions
                .iter()
                .filter(|v| {
                    let low = v.to_lowercase();
                    low == "forge" || low == "fabric" || low == "quilt" || low == "neoforge"
                })
                .cloned()
                .collect(),
            game_versions: f
                .game_versions
                .iter()
                .filter(|v| v.chars().next().is_some_and(|c| c.is_ascii_digit()))
                .cloned()
                .collect(),
            filename: f.file_name,
            downloads: f.download_count.max(0.0) as u64,
            date_published: f.file_date,
        })
        .collect())
}

// ---------------------------------------------------------------------------
// install
// ---------------------------------------------------------------------------

/// Installs one file into an instance and records it in the manifest.
///
/// No dependency walk, unlike the Modrinth side. CurseForge states a file's
/// requirements as numeric ids that need another lookup each, and pulling that
/// in unchecked would be guessing at a second shape. Missing requirements show
/// up as the loader's own error, which names them.
pub async fn install(
    key: &str,
    instance_id: String,
    project_id: String,
    file_id: String,
    project_type: String,
    title: String,
    icon_url: String,
) -> anyhow::Result<InstalledMod> {
    let inst = crate::launcher::instance::get(&instance_id)
        .ok_or_else(|| anyhow::anyhow!("Instance not found"))?;

    let response = http(key)?
        .get(format!("{}/mods/{}/files/{}", API, project_id, file_id))
        .send()
        .await?;

    if !response.status().is_success() {
        anyhow::bail!("{}", explain(response.status().as_u16()));
    }

    #[derive(Deserialize)]
    struct One {
        data: CfFile,
    }
    let file: CfFile = response.json::<One>().await?.data;

    // Authors can forbid third party downloads, and then this field is empty.
    // Saying so is the only honest option: there is no other url to try.
    let url = file.download_url.clone().ok_or_else(|| {
        anyhow::anyhow!(
            "The author of this project does not allow downloads outside the CurseForge app."
        )
    })?;

    let bytes = http(key)?
        .get(&url)
        .send()
        .await?
        .error_for_status()?
        .bytes()
        .await?;

    let dir = match project_type.as_str() {
        "resourcepack" => std::path::PathBuf::from(&inst.path).join("resourcepacks"),
        "shader" => std::path::PathBuf::from(&inst.path).join("shaderpacks"),
        _ => inst.mods_dir(),
    };
    std::fs::create_dir_all(&dir)?;
    std::fs::write(dir.join(&file.file_name), &bytes)?;

    let entry = InstalledMod {
        project_id: project_id.clone(),
        version_id: file_id,
        title,
        filename: file.file_name,
        version_number: file.display_name,
        project_type,
        icon_url,
        enabled: true,
    };

    let mut manifest = crate::launcher::mods::load_manifest(&inst);
    manifest.retain(|m| m.project_id != project_id);
    manifest.push(entry.clone());
    crate::launcher::mods::save_manifest(&inst, &manifest)?;

    Ok(entry)
}

fn explain(code: u16) -> String {
    match code {
        401 | 403 => "CurseForge rejected the key. Check it in Settings.".into(),
        404 => "CurseForge has nothing at that address.".into(),
        429 => "Too many requests to CurseForge. Wait a moment.".into(),
        other => format!("CurseForge answered with {}.", other),
    }
}
