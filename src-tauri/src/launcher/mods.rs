use crate::launcher::instance;
use crate::launcher::progress::{emit_progress, InstallProgress};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::path::PathBuf;
use tauri::AppHandle;
use tokio::fs;
use tokio::io::AsyncWriteExt;

const MODRINTH_API: &str = "https://api.modrinth.com/v2";

/// Modrinth asks API users to identify themselves with a descriptive agent.
const AGENT: &str = "Finanzinstitut/SpaceClient/0.1 (Minecraft launcher)";

fn http() -> anyhow::Result<reqwest::Client> {
    Ok(reqwest::Client::builder().user_agent(AGENT).build()?)
}

#[derive(Debug, Serialize, Clone)]
pub struct ModHit {
    pub project_id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub author: String,
    pub downloads: u64,
    pub icon_url: String,
    pub categories: Vec<String>,
    pub project_type: String,
}

/// One downloadable release of a project.
#[derive(Debug, Serialize, Clone)]
pub struct ProjectVersion {
    pub id: String,
    pub name: String,
    pub version_number: String,
    /// "release" | "beta" | "alpha"
    pub version_type: String,
    pub loaders: Vec<String>,
    pub game_versions: Vec<String>,
    pub filename: String,
    pub downloads: u64,
    pub date_published: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct InstalledMod {
    pub project_id: String,
    pub version_id: String,
    pub title: String,
    pub filename: String,
    pub version_number: String,
    /// "mod" | "resourcepack" | "shader"
    #[serde(default = "default_type")]
    pub project_type: String,
}

fn default_type() -> String {
    "mod".to_string()
}

fn manifest_path(inst: &instance::Instance) -> PathBuf {
    inst.dir().join("modrinth.json")
}

fn load_manifest(inst: &instance::Instance) -> Vec<InstalledMod> {
    std::fs::read_to_string(manifest_path(inst))
        .ok()
        .and_then(|d| serde_json::from_str(&d).ok())
        .unwrap_or_default()
}

/// Lets the modpack importer register everything it downloaded, so imported
/// packs take part in update checks like anything else.
pub fn write_manifest(inst: &instance::Instance, mods: &[InstalledMod]) -> anyhow::Result<()> {
    save_manifest(inst, mods)
}

fn save_manifest(inst: &instance::Instance, mods: &[InstalledMod]) -> anyhow::Result<()> {
    std::fs::write(manifest_path(inst), serde_json::to_string_pretty(mods)?)?;
    Ok(())
}

/// Quilt can load most Fabric mods, so Quilt instances search for both.
fn loader_names(loader: &str) -> Vec<String> {
    match loader {
        "fabric" => vec!["fabric".into()],
        "quilt" => vec!["quilt".into(), "fabric".into()],
        "forge" => vec!["forge".into()],
        "neoforge" => vec!["neoforge".into(), "forge".into()],
        _ => vec![],
    }
}

fn loader_facets(loader: &str) -> Vec<String> {
    loader_names(loader)
        .into_iter()
        .map(|l| format!("categories:{}", l))
        .collect()
}

/// Searches Modrinth. An empty query returns Modrinth's own popular listing,
/// which is what the browser shows when it first opens.
pub async fn search(
    query: String,
    mc_version: String,
    loader: String,
    project_type: String,
    categories: Vec<String>,
    offset: u32,
) -> anyhow::Result<Vec<ModHit>> {
    if project_type == "mod" && loader == "vanilla" {
        anyhow::bail!("This instance has no mod loader. Create a Fabric, Quilt, Forge or NeoForge instance to use mods.");
    }

    let mut facets: Vec<Vec<String>> = vec![vec![format!("project_type:{}", project_type)]];

    // Resource packs and shaders are not tied to a mod loader.
    if project_type == "mod" {
        let lf = loader_facets(&loader);
        if !lf.is_empty() {
            facets.push(lf);
        }
    }
    facets.push(vec![format!("versions:{}", mc_version)]);

    // Each selected category is its own AND group, so picking two narrows down
    // to projects carrying both tags.
    for cat in &categories {
        facets.push(vec![format!("categories:{}", cat)]);
    }

    let facets_json = serde_json::to_string(&facets)?;
    // With no search term, sort by downloads so the popular projects lead.
    let index = if query.trim().is_empty() { "downloads" } else { "relevance" };

    let resp: serde_json::Value = http()?
        .get(format!("{}/search", MODRINTH_API))
        .query(&[
            ("query", query.as_str()),
            ("facets", facets_json.as_str()),
            ("limit", "30"),
            ("offset", &offset.to_string()),
            ("index", index),
        ])
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let mut out = Vec::new();
    if let Some(hits) = resp.get("hits").and_then(|v| v.as_array()) {
        for h in hits {
            out.push(ModHit {
                project_id: h.get("project_id").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
                slug: h.get("slug").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
                title: h.get("title").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
                description: h.get("description").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
                author: h.get("author").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
                downloads: h.get("downloads").and_then(|v| v.as_u64()).unwrap_or(0),
                icon_url: h.get("icon_url").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
                categories: h
                    .get("categories")
                    .and_then(|v| v.as_array())
                    .map(|a| a.iter().filter_map(|c| c.as_str().map(String::from)).collect())
                    .unwrap_or_default(),
                project_type: h
                    .get("project_type")
                    .and_then(|v| v.as_str())
                    .unwrap_or(&project_type)
                    .to_string(),
            });
        }
    }
    Ok(out)
}

/// Raw version list from Modrinth, already filtered to this instance.
async fn fetch_versions(
    project_id: &str,
    mc_version: &str,
    loader: &str,
    project_type: &str,
) -> anyhow::Result<Vec<serde_json::Value>> {
    let versions_json = serde_json::to_string(&vec![mc_version])?;
    let mut req = http()?
        .get(format!("{}/project/{}/version", MODRINTH_API, project_id))
        .query(&[("game_versions", versions_json.as_str())]);

    // Only mods are loader-specific. Filtering shaders by loader would hide
    // most of them, since they are tagged iris/optifine rather than fabric.
    if project_type == "mod" {
        let loaders_json = serde_json::to_string(&loader_names(loader))?;
        req = req.query(&[("loaders", loaders_json.as_str())]);
    }

    let list: serde_json::Value = req.send().await?.error_for_status()?.json().await?;
    Ok(list.as_array().cloned().unwrap_or_default())
}

/// Every version of a project that fits this instance, newest first, so the
/// user can pick a specific one instead of always getting the latest.
pub async fn list_versions(
    project_id: String,
    instance_id: String,
    project_type: String,
) -> anyhow::Result<Vec<ProjectVersion>> {
    let inst = instance::get(&instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    let raw = fetch_versions(&project_id, &inst.mc_version, &inst.loader, &project_type).await?;

    let mut out = Vec::new();
    for v in raw {
        let file = v
            .get("files")
            .and_then(|f| f.as_array())
            .and_then(|arr| {
                arr.iter()
                    .find(|f| f.get("primary").and_then(|p| p.as_bool()).unwrap_or(false))
                    .or_else(|| arr.first())
            });
        let filename = file
            .and_then(|f| f.get("filename"))
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string();

        out.push(ProjectVersion {
            id: v.get("id").and_then(|x| x.as_str()).unwrap_or_default().to_string(),
            name: v.get("name").and_then(|x| x.as_str()).unwrap_or_default().to_string(),
            version_number: v
                .get("version_number")
                .and_then(|x| x.as_str())
                .unwrap_or_default()
                .to_string(),
            version_type: v
                .get("version_type")
                .and_then(|x| x.as_str())
                .unwrap_or("release")
                .to_string(),
            loaders: v
                .get("loaders")
                .and_then(|x| x.as_array())
                .map(|a| a.iter().filter_map(|l| l.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            game_versions: v
                .get("game_versions")
                .and_then(|x| x.as_array())
                .map(|a| a.iter().filter_map(|g| g.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            filename,
            downloads: v.get("downloads").and_then(|x| x.as_u64()).unwrap_or(0),
            date_published: v
                .get("date_published")
                .and_then(|x| x.as_str())
                .unwrap_or_default()
                .to_string(),
        });
    }
    Ok(out)
}

/// Picks the newest suitable version, preferring a stable release.
async fn best_version(
    project_id: &str,
    mc_version: &str,
    loader: &str,
    project_type: &str,
) -> anyhow::Result<serde_json::Value> {
    let arr = fetch_versions(project_id, mc_version, loader, project_type).await?;
    arr.iter()
        .find(|v| v.get("version_type").and_then(|t| t.as_str()) == Some("release"))
        .or_else(|| arr.first())
        .cloned()
        .ok_or_else(|| {
            anyhow::anyhow!(
                "Nothing available for Minecraft {} with {}",
                mc_version,
                loader
            )
        })
}

async fn fetch_version_by_id(version_id: &str) -> anyhow::Result<serde_json::Value> {
    Ok(http()?
        .get(format!("{}/version/{}", MODRINTH_API, version_id))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?)
}

/// Writes one version's primary file into the right folder for its type.
async fn download_version(
    inst: &instance::Instance,
    version: &serde_json::Value,
    project_id: &str,
    project_type: &str,
    fallback_title: &str,
) -> anyhow::Result<InstalledMod> {
    let file = version
        .get("files")
        .and_then(|f| f.as_array())
        .and_then(|arr| {
            arr.iter()
                .find(|f| f.get("primary").and_then(|p| p.as_bool()).unwrap_or(false))
                .or_else(|| arr.first())
        })
        .ok_or_else(|| anyhow::anyhow!("This version has no downloadable file"))?;

    let url = file.get("url").and_then(|v| v.as_str()).unwrap_or_default().to_string();
    let filename = file
        .get("filename")
        .and_then(|v| v.as_str())
        .unwrap_or("download.jar")
        .to_string();

    let dir = inst.content_dir(project_type);
    fs::create_dir_all(&dir).await?;

    let bytes = http()?.get(&url).send().await?.error_for_status()?.bytes().await?;
    let dest = dir.join(&filename);
    let mut f = fs::File::create(&dest).await?;
    f.write_all(&bytes).await?;
    f.flush().await?;

    Ok(InstalledMod {
        project_id: project_id.to_string(),
        version_id: version.get("id").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
        title: version
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or(fallback_title)
            .to_string(),
        filename,
        version_number: version
            .get("version_number")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        project_type: project_type.to_string(),
    })
}

/// Installs the newest suitable version of a project, plus required
/// dependencies (mods only - packs and shaders have none).
pub async fn install_mod(
    app: &AppHandle,
    instance_id: String,
    project_id: String,
    project_type: String,
) -> anyhow::Result<Vec<InstalledMod>> {
    let inst = instance::get(&instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    if project_type == "mod" && inst.loader == "vanilla" {
        anyhow::bail!("This instance has no mod loader.");
    }

    let mut manifest = load_manifest(&inst);
    let mut installed_now: Vec<InstalledMod> = Vec::new();
    let mut visited: HashSet<String> = HashSet::new();
    let mut queue: Vec<String> = vec![project_id];

    while let Some(pid) = queue.pop() {
        if !visited.insert(pid.clone()) {
            continue;
        }
        if manifest.iter().any(|m| m.project_id == pid) {
            continue;
        }

        let version = match best_version(&pid, &inst.mc_version, &inst.loader, &project_type).await {
            Ok(v) => v,
            Err(e) => {
                // A missing optional dependency must not abort the whole install
                eprintln!("Skipping {}: {}", pid, e);
                continue;
            }
        };

        emit_progress(app, InstallProgress {
            stage: "mods".into(),
            current: installed_now.len() as u64,
            total: (installed_now.len() + queue.len() + 1) as u64,
            file: version
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
        });

        let entry = download_version(&inst, &version, &pid, &project_type, "").await?;
        manifest.push(entry.clone());
        installed_now.push(entry);

        if project_type == "mod" {
            if let Some(deps) = version.get("dependencies").and_then(|d| d.as_array()) {
                for dep in deps {
                    if dep.get("dependency_type").and_then(|v| v.as_str()) != Some("required") {
                        continue;
                    }
                    if let Some(dep_id) = dep.get("project_id").and_then(|v| v.as_str()) {
                        queue.push(dep_id.to_string());
                    }
                }
            }
        }
    }

    save_manifest(&inst, &manifest)?;
    emit_progress(app, InstallProgress {
        stage: "done".into(),
        current: 1,
        total: 1,
        file: String::new(),
    });
    Ok(installed_now)
}

/// Installs one specific version the user picked from the version list.
pub async fn install_specific_version(
    app: &AppHandle,
    instance_id: String,
    project_id: String,
    version_id: String,
    project_type: String,
) -> anyhow::Result<InstalledMod> {
    let inst = instance::get(&instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    let version = fetch_version_by_id(&version_id).await?;

    emit_progress(app, InstallProgress {
        stage: "mods".into(),
        current: 0,
        total: 1,
        file: version.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string(),
    });

    let mut manifest = load_manifest(&inst);

    // Replacing an existing entry: drop the old file first.
    if let Some(pos) = manifest.iter().position(|m| m.project_id == project_id) {
        let old = manifest.remove(pos);
        let old_path = inst.content_dir(&old.project_type).join(&old.filename);
        if old_path.exists() {
            std::fs::remove_file(old_path).ok();
        }
    }

    let entry = download_version(&inst, &version, &project_id, &project_type, "").await?;
    manifest.push(entry.clone());

    // Required dependencies of the chosen version still need to be present.
    if project_type == "mod" {
        if let Some(deps) = version.get("dependencies").and_then(|d| d.as_array()) {
            for dep in deps {
                if dep.get("dependency_type").and_then(|v| v.as_str()) != Some("required") {
                    continue;
                }
                let dep_id = match dep.get("project_id").and_then(|v| v.as_str()) {
                    Some(d) => d.to_string(),
                    None => continue,
                };
                if manifest.iter().any(|m| m.project_id == dep_id) {
                    continue;
                }
                if let Ok(dep_version) =
                    best_version(&dep_id, &inst.mc_version, &inst.loader, "mod").await
                {
                    if let Ok(dep_entry) =
                        download_version(&inst, &dep_version, &dep_id, "mod", "").await
                    {
                        manifest.push(dep_entry);
                    }
                }
            }
        }
    }

    save_manifest(&inst, &manifest)?;
    emit_progress(app, InstallProgress {
        stage: "done".into(),
        current: 1,
        total: 1,
        file: String::new(),
    });
    Ok(entry)
}

/// Lists installed content of one type, including files added by hand.
pub fn list_installed(instance_id: &str, project_type: &str) -> anyhow::Result<Vec<InstalledMod>> {
    let inst = instance::get(instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    let manifest = load_manifest(&inst);

    let mut out: Vec<InstalledMod> = manifest
        .iter()
        .filter(|m| m.project_type == project_type)
        .cloned()
        .collect();

    if let Ok(entries) = std::fs::read_dir(inst.content_dir(project_type)) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            let is_content = name.ends_with(".jar") || name.ends_with(".zip");
            if !is_content || manifest.iter().any(|m| m.filename == name) {
                continue;
            }
            out.push(InstalledMod {
                project_id: String::new(),
                version_id: String::new(),
                title: name.clone(),
                filename: name,
                version_number: "manual".into(),
                project_type: project_type.to_string(),
            });
        }
    }
    Ok(out)
}

pub fn remove_mod(instance_id: &str, filename: &str, project_type: &str) -> anyhow::Result<()> {
    let inst = instance::get(instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;

    // Guard against path traversal from a crafted file name
    if filename.contains('/') || filename.contains('\\') || filename.contains("..") {
        anyhow::bail!("Invalid file name");
    }

    let path = inst.content_dir(project_type).join(filename);
    if path.exists() {
        std::fs::remove_file(&path)?;
    }

    let manifest: Vec<InstalledMod> = load_manifest(&inst)
        .into_iter()
        .filter(|m| m.filename != filename)
        .collect();
    save_manifest(&inst, &manifest)?;
    Ok(())
}

#[derive(Debug, Serialize, Clone)]
pub struct ModUpdate {
    pub project_id: String,
    pub title: String,
    pub current_version: String,
    pub new_version: String,
    pub filename: String,
    pub project_type: String,
}

/// Checks every installed project against Modrinth. Manually added files have
/// no project id and are skipped.
pub async fn check_updates(instance_id: String) -> anyhow::Result<Vec<ModUpdate>> {
    let inst = instance::get(&instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    let manifest = load_manifest(&inst);
    let mut out = Vec::new();

    for entry in manifest {
        if entry.project_id.is_empty() {
            continue;
        }
        let latest = match best_version(
            &entry.project_id,
            &inst.mc_version,
            &inst.loader,
            &entry.project_type,
        )
        .await
        {
            Ok(v) => v,
            Err(_) => continue,
        };
        let latest_id = latest.get("id").and_then(|v| v.as_str()).unwrap_or_default();
        if latest_id.is_empty() || latest_id == entry.version_id {
            continue;
        }
        out.push(ModUpdate {
            project_id: entry.project_id.clone(),
            title: entry.title.clone(),
            current_version: entry.version_number.clone(),
            new_version: latest
                .get("version_number")
                .and_then(|v| v.as_str())
                .unwrap_or_default()
                .to_string(),
            filename: entry.filename.clone(),
            project_type: entry.project_type.clone(),
        });
    }
    Ok(out)
}

/// Replaces one installed project with its newest matching version.
/// The old file is only deleted once the new one is safely written.
pub async fn update_mod(
    app: &AppHandle,
    instance_id: String,
    project_id: String,
) -> anyhow::Result<InstalledMod> {
    let inst = instance::get(&instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    let mut manifest = load_manifest(&inst);

    let old_index = manifest
        .iter()
        .position(|m| m.project_id == project_id)
        .ok_or_else(|| anyhow::anyhow!("This project is not installed"))?;
    let old = manifest[old_index].clone();

    let version = best_version(&project_id, &inst.mc_version, &inst.loader, &old.project_type).await?;

    emit_progress(app, InstallProgress {
        stage: "mods".into(),
        current: 0,
        total: 1,
        file: old.title.clone(),
    });

    let entry = download_version(&inst, &version, &project_id, &old.project_type, &old.title).await?;

    if old.filename != entry.filename {
        let old_path = inst.content_dir(&old.project_type).join(&old.filename);
        if old_path.exists() {
            std::fs::remove_file(old_path).ok();
        }
    }

    manifest[old_index] = entry.clone();
    save_manifest(&inst, &manifest)?;

    emit_progress(app, InstallProgress {
        stage: "done".into(),
        current: 1,
        total: 1,
        file: String::new(),
    });
    Ok(entry)
}

/// Updates everything with a newer version. Returns how many were replaced.
pub async fn update_all(app: &AppHandle, instance_id: String) -> anyhow::Result<u32> {
    let updates = check_updates(instance_id.clone()).await?;
    let mut count = 0;
    for u in updates {
        match update_mod(app, instance_id.clone(), u.project_id).await {
            Ok(_) => count += 1,
            Err(e) => eprintln!("Update failed for {}: {}", u.title, e),
        }
    }
    Ok(count)
}

#[derive(Debug, Serialize, Clone)]
pub struct RepairReport {
    pub replaced: Vec<String>,
    /// Files with no version at all for this Minecraft version and loader.
    pub incompatible: Vec<String>,
    pub checked: u32,
}

/// Brings every installed project onto a version that actually fits this
/// instance.
///
/// This is the difference between "update" and "repair": the update check only
/// looks for something *newer*, so a mod pinned to the wrong Minecraft version
/// stays wrong. Repair asks Modrinth for the newest version that matches this
/// instance and swaps it in, whichever direction that is.
///
/// Anything with no compatible version is moved into an `incompatible` folder
/// rather than deleted, so nothing is lost and the game can still start.
pub async fn repair_instance(app: &AppHandle, instance_id: String) -> anyhow::Result<RepairReport> {
    let inst = instance::get(&instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    let mut manifest = load_manifest(&inst);

    let mut replaced = Vec::new();
    let mut incompatible = Vec::new();
    let total = manifest.len() as u64;

    for index in 0..manifest.len() {
        let entry = manifest[index].clone();

        emit_progress(app, InstallProgress {
            stage: "repair".into(),
            current: index as u64,
            total,
            file: entry.title.clone(),
        });

        // Hand-added files have no project to look up
        if entry.project_id.is_empty() {
            continue;
        }

        let best = best_version(
            &entry.project_id,
            &inst.mc_version,
            &inst.loader,
            &entry.project_type,
        )
        .await;

        let dir = inst.content_dir(&entry.project_type);
        let current_path = dir.join(&entry.filename);

        match best {
            Ok(version) => {
                let version_id = version
                    .get("id")
                    .and_then(|v| v.as_str())
                    .unwrap_or_default()
                    .to_string();

                // Already on the right version, and the file is really there
                if version_id == entry.version_id && current_path.exists() {
                    continue;
                }

                match download_version(&inst, &version, &entry.project_id, &entry.project_type, &entry.title).await {
                    Ok(new_entry) => {
                        if new_entry.filename != entry.filename && current_path.exists() {
                            std::fs::remove_file(&current_path).ok();
                        }
                        replaced.push(format!(
                            "{} {} -> {}",
                            entry.title, entry.version_number, new_entry.version_number
                        ));
                        manifest[index] = new_entry;
                    }
                    Err(e) => {
                        eprintln!("Repair failed for {}: {}", entry.title, e);
                    }
                }
            }
            Err(_) => {
                // Nothing fits: park the file so the game can start without it
                if current_path.exists() {
                    let parked = dir.join("incompatible");
                    std::fs::create_dir_all(&parked).ok();
                    std::fs::rename(&current_path, parked.join(&entry.filename)).ok();
                }
                incompatible.push(entry.title.clone());
            }
        }
    }

    // Drop the parked ones from the manifest so update checks skip them
    let parked: std::collections::HashSet<String> = incompatible.iter().cloned().collect();
    manifest.retain(|m| !parked.contains(&m.title));

    save_manifest(&inst, &manifest)?;

    emit_progress(app, InstallProgress {
        stage: "done".into(),
        current: 1,
        total: 1,
        file: String::new(),
    });

    Ok(RepairReport {
        replaced,
        incompatible,
        checked: total as u32,
    })
}
