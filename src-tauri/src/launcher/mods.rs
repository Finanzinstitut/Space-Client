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
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct InstalledMod {
    pub project_id: String,
    pub version_id: String,
    pub title: String,
    pub filename: String,
    pub version_number: String,
}

/// The per-instance record of what was installed from Modrinth. Files dropped
/// into the mods folder by hand still work, they just aren't listed here.
fn manifest_path(inst: &instance::Instance) -> PathBuf {
    inst.dir().join("modrinth.json")
}

fn load_manifest(inst: &instance::Instance) -> Vec<InstalledMod> {
    std::fs::read_to_string(manifest_path(inst))
        .ok()
        .and_then(|d| serde_json::from_str(&d).ok())
        .unwrap_or_default()
}

fn save_manifest(inst: &instance::Instance, mods: &[InstalledMod]) -> anyhow::Result<()> {
    std::fs::write(manifest_path(inst), serde_json::to_string_pretty(mods)?)?;
    Ok(())
}

/// Which Modrinth loader facets apply to an instance. Quilt can load most
/// Fabric mods, so Quilt instances search for both.
fn loader_facets(loader: &str) -> Vec<String> {
    match loader {
        "fabric" => vec!["categories:fabric".into()],
        "quilt" => vec!["categories:quilt".into(), "categories:fabric".into()],
        _ => vec![],
    }
}

fn loader_names(loader: &str) -> Vec<String> {
    match loader {
        "fabric" => vec!["fabric".into()],
        "quilt" => vec!["quilt".into(), "fabric".into()],
        _ => vec![],
    }
}

/// Searches Modrinth, restricted to mods that fit this instance's
/// Minecraft version and loader.
pub async fn search(
    query: String,
    mc_version: String,
    loader: String,
    offset: u32,
) -> anyhow::Result<Vec<ModHit>> {
    if loader == "vanilla" {
        anyhow::bail!("This instance has no mod loader. Create a Fabric or Quilt instance to use mods.");
    }

    let mut facets: Vec<Vec<String>> = vec![vec!["project_type:mod".into()]];
    let lf = loader_facets(&loader);
    if !lf.is_empty() {
        facets.push(lf);
    }
    facets.push(vec![format!("versions:{}", mc_version)]);
    let facets_json = serde_json::to_string(&facets)?;

    let resp: serde_json::Value = http()?
        .get(format!("{}/search", MODRINTH_API))
        .query(&[
            ("query", query.as_str()),
            ("facets", facets_json.as_str()),
            ("limit", "30"),
            ("offset", &offset.to_string()),
            ("index", "relevance"),
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
            });
        }
    }
    Ok(out)
}

/// Picks the newest version of a project that matches the instance.
async fn best_version(
    project_id: &str,
    mc_version: &str,
    loader: &str,
) -> anyhow::Result<serde_json::Value> {
    let loaders_json = serde_json::to_string(&loader_names(loader))?;
    let versions_json = serde_json::to_string(&vec![mc_version])?;

    let list: serde_json::Value = http()?
        .get(format!("{}/project/{}/version", MODRINTH_API, project_id))
        .query(&[
            ("loaders", loaders_json.as_str()),
            ("game_versions", versions_json.as_str()),
        ])
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let arr = list
        .as_array()
        .ok_or_else(|| anyhow::anyhow!("Unexpected response from Modrinth"))?;

    // Modrinth returns newest first; prefer a release over beta/alpha if present.
    let chosen = arr
        .iter()
        .find(|v| v.get("version_type").and_then(|t| t.as_str()) == Some("release"))
        .or_else(|| arr.first())
        .ok_or_else(|| {
            anyhow::anyhow!(
                "No version of this mod exists for Minecraft {} with {}",
                mc_version,
                loader
            )
        })?;

    Ok(chosen.clone())
}

/// Downloads one mod plus everything it requires.
pub async fn install_mod(
    app: &AppHandle,
    instance_id: String,
    project_id: String,
) -> anyhow::Result<Vec<InstalledMod>> {
    let inst = instance::get(&instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    if inst.loader == "vanilla" {
        anyhow::bail!("This instance has no mod loader.");
    }

    let mods_dir = inst.mods_dir();
    fs::create_dir_all(&mods_dir).await?;

    let mut manifest = load_manifest(&inst);
    let mut installed_now: Vec<InstalledMod> = Vec::new();
    let mut visited: HashSet<String> = HashSet::new();
    let mut queue: Vec<String> = vec![project_id];

    while let Some(pid) = queue.pop() {
        if !visited.insert(pid.clone()) {
            continue;
        }
        // Already present in this instance? Then skip it.
        if manifest.iter().any(|m| m.project_id == pid) {
            continue;
        }

        let version = match best_version(&pid, &inst.mc_version, &inst.loader).await {
            Ok(v) => v,
            Err(e) => {
                // A missing optional dependency should not abort the whole install
                eprintln!("Skipping {}: {}", pid, e);
                continue;
            }
        };

        let file = version
            .get("files")
            .and_then(|f| f.as_array())
            .and_then(|arr| {
                arr.iter()
                    .find(|f| f.get("primary").and_then(|p| p.as_bool()).unwrap_or(false))
                    .or_else(|| arr.first())
            })
            .ok_or_else(|| anyhow::anyhow!("Version has no downloadable file"))?;

        let url = file.get("url").and_then(|v| v.as_str()).unwrap_or_default().to_string();
        let filename = file
            .get("filename")
            .and_then(|v| v.as_str())
            .unwrap_or("mod.jar")
            .to_string();

        emit_progress(app, InstallProgress {
            stage: "mods".into(),
            current: installed_now.len() as u64,
            total: (installed_now.len() + queue.len() + 1) as u64,
            file: filename.clone(),
        });

        let bytes = http()?.get(&url).send().await?.error_for_status()?.bytes().await?;
        let dest = mods_dir.join(&filename);
        let mut f = fs::File::create(&dest).await?;
        f.write_all(&bytes).await?;

        let entry = InstalledMod {
            project_id: pid.clone(),
            version_id: version.get("id").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
            title: version
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or(&filename)
                .to_string(),
            filename,
            version_number: version
                .get("version_number")
                .and_then(|v| v.as_str())
                .unwrap_or_default()
                .to_string(),
        };
        manifest.push(entry.clone());
        installed_now.push(entry);

        // Queue required dependencies
        if let Some(deps) = version.get("dependencies").and_then(|d| d.as_array()) {
            for dep in deps {
                let kind = dep.get("dependency_type").and_then(|v| v.as_str()).unwrap_or("");
                if kind != "required" {
                    continue;
                }
                if let Some(dep_id) = dep.get("project_id").and_then(|v| v.as_str()) {
                    queue.push(dep_id.to_string());
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

/// Lists what Modrinth installed, plus any jars the user dropped in manually.
pub fn list_installed(instance_id: &str) -> anyhow::Result<Vec<InstalledMod>> {
    let inst = instance::get(instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    let manifest = load_manifest(&inst);
    let mut out = manifest.clone();

    if let Ok(entries) = std::fs::read_dir(inst.mods_dir()) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if !name.ends_with(".jar") {
                continue;
            }
            if manifest.iter().any(|m| m.filename == name) {
                continue;
            }
            out.push(InstalledMod {
                project_id: String::new(),
                version_id: String::new(),
                title: name.clone(),
                filename: name,
                version_number: "manual".into(),
            });
        }
    }
    Ok(out)
}

pub fn remove_mod(instance_id: &str, filename: &str) -> anyhow::Result<()> {
    let inst = instance::get(instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;

    // Guard against path traversal from a crafted filename
    if filename.contains('/') || filename.contains('\\') || filename.contains("..") {
        anyhow::bail!("Invalid file name");
    }

    let path = inst.mods_dir().join(filename);
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
}

/// Asks Modrinth whether a newer version exists for each installed mod.
/// Mods added by hand have no project id and are skipped.
pub async fn check_updates(instance_id: String) -> anyhow::Result<Vec<ModUpdate>> {
    let inst = instance::get(&instance_id).ok_or_else(|| anyhow::anyhow!("Instance not found"))?;
    let manifest = load_manifest(&inst);
    let mut out = Vec::new();

    for entry in manifest {
        if entry.project_id.is_empty() {
            continue;
        }
        let latest = match best_version(&entry.project_id, &inst.mc_version, &inst.loader).await {
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
        });
    }
    Ok(out)
}

/// Replaces one installed mod with its newest matching version.
/// The old jar is only deleted once the new one is safely written.
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
        .ok_or_else(|| anyhow::anyhow!("This mod is not installed"))?;
    let old_filename = manifest[old_index].filename.clone();

    let version = best_version(&project_id, &inst.mc_version, &inst.loader).await?;
    let file = version
        .get("files")
        .and_then(|f| f.as_array())
        .and_then(|arr| {
            arr.iter()
                .find(|f| f.get("primary").and_then(|p| p.as_bool()).unwrap_or(false))
                .or_else(|| arr.first())
        })
        .ok_or_else(|| anyhow::anyhow!("Version has no downloadable file"))?;

    let url = file.get("url").and_then(|v| v.as_str()).unwrap_or_default().to_string();
    let filename = file
        .get("filename")
        .and_then(|v| v.as_str())
        .unwrap_or("mod.jar")
        .to_string();

    emit_progress(app, InstallProgress {
        stage: "mods".into(),
        current: 0,
        total: 1,
        file: filename.clone(),
    });

    let mods_dir = inst.mods_dir();
    fs::create_dir_all(&mods_dir).await?;
    let bytes = http()?.get(&url).send().await?.error_for_status()?.bytes().await?;
    let dest = mods_dir.join(&filename);
    let mut f = fs::File::create(&dest).await?;
    f.write_all(&bytes).await?;
    f.flush().await?;
    drop(f);

    // Remove the superseded jar, unless the file name happened to stay the same
    if old_filename != filename {
        let old_path = mods_dir.join(&old_filename);
        if old_path.exists() {
            std::fs::remove_file(old_path).ok();
        }
    }

    let entry = InstalledMod {
        project_id: project_id.clone(),
        version_id: version.get("id").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
        title: manifest[old_index].title.clone(),
        filename,
        version_number: version
            .get("version_number")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
    };
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

/// Updates every mod that has a newer version. Returns how many were replaced.
pub async fn update_all(app: &AppHandle, instance_id: String) -> anyhow::Result<u32> {
    let updates = check_updates(instance_id.clone()).await?;
    let mut count = 0;
    for u in updates {
        // One failure should not stop the rest
        match update_mod(app, instance_id.clone(), u.project_id).await {
            Ok(_) => count += 1,
            Err(e) => eprintln!("Update failed for {}: {}", u.title, e),
        }
    }
    Ok(count)
}
