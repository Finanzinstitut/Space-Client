use crate::launcher::config::{config_dir, LauncherConfig};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

fn default_true() -> bool {
    true
}

fn registry_file() -> PathBuf {
    config_dir().join("instances.json")
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Instance {
    pub id: String,
    pub name: String,
    /// Absolute path to this instance's own folder. Fully user-chosen, so
    /// instances can live on completely different drives from each other.
    pub path: String,
    pub mc_version: String,
    /// "vanilla" | "fabric" | "quilt"
    pub loader: String,
    /// Empty for vanilla. Set once the loader has been installed.
    #[serde(default)]
    pub loader_version: String,
    /// The version id actually launched. For vanilla this equals mc_version,
    /// for Fabric it looks like "fabric-loader-0.16.9-1.21.1".
    #[serde(default)]
    pub version_id: String,
    pub ram_mb: u32,
    /// Whether the Space Client companion mod is kept in this instance.
    /// On by default, since it is what makes this a client rather than a
    /// plain launcher - but any instance can opt out.
    #[serde(default = "default_true")]
    pub install_client_mod: bool,
    #[serde(default)]
    pub created: String,
}

impl Instance {
    pub fn dir(&self) -> PathBuf {
        PathBuf::from(&self.path)
    }
    /// Game directory - saves, options.txt, resourcepacks, mods.
    pub fn game_dir(&self) -> PathBuf {
        self.dir().join(".minecraft")
    }
    pub fn mods_dir(&self) -> PathBuf {
        self.game_dir().join("mods")
    }
    pub fn resourcepacks_dir(&self) -> PathBuf {
        self.game_dir().join("resourcepacks")
    }
    pub fn shaderpacks_dir(&self) -> PathBuf {
        self.game_dir().join("shaderpacks")
    }
    /// Where a Modrinth project of the given type belongs.
    pub fn content_dir(&self, project_type: &str) -> PathBuf {
        match project_type {
            "resourcepack" => self.resourcepacks_dir(),
            "shader" => self.shaderpacks_dir(),
            _ => self.mods_dir(),
        }
    }
}

pub fn load_all() -> Vec<Instance> {
    if let Ok(data) = fs::read_to_string(registry_file()) {
        if let Ok(list) = serde_json::from_str::<Vec<Instance>>(&data) {
            return list;
        }
    }
    Vec::new()
}

pub fn save_all(list: &[Instance]) -> anyhow::Result<()> {
    fs::write(registry_file(), serde_json::to_string_pretty(list)?)?;
    Ok(())
}

pub fn get(id: &str) -> Option<Instance> {
    load_all().into_iter().find(|i| i.id == id)
}

/// Rewrites the instance.json inside the instance folder so the folder stays
/// self-describing even after edits.
pub fn save_meta(instance: &Instance) -> anyhow::Result<()> {
    let dir = instance.dir();
    if dir.exists() {
        fs::write(
            dir.join("instance.json"),
            serde_json::to_string_pretty(instance)?,
        )?;
    }
    Ok(())
}

/// Applies user edits. Changing the loader version clears version_id, which
/// marks the instance as needing a reinstall before it can be launched.
pub fn update(
    id: &str,
    name: String,
    ram_mb: u32,
    loader_version: String,
    install_client_mod: bool,
) -> anyhow::Result<(Instance, bool)> {
    let mut list = load_all();
    let inst = list
        .iter_mut()
        .find(|i| i.id == id)
        .ok_or_else(|| anyhow::anyhow!("Instance not found"))?;

    if name.trim().is_empty() {
        anyhow::bail!("Please enter a name for the instance.");
    }

    let loader_changed = inst.loader != "vanilla" && loader_version != inst.loader_version;

    inst.name = name.trim().to_string();
    inst.ram_mb = ram_mb.max(512);
    inst.install_client_mod = install_client_mod;
    if loader_changed {
        inst.loader_version = loader_version;
        // Force a reinstall - the old profile no longer matches.
        inst.version_id = String::new();
    }

    let updated = inst.clone();
    save_all(&list)?;
    save_meta(&updated)?;
    Ok((updated, loader_changed))
}

pub fn upsert(instance: Instance) -> anyhow::Result<()> {
    let mut list = load_all();
    match list.iter_mut().find(|i| i.id == instance.id) {
        Some(existing) => *existing = instance.clone(),
        None => list.push(instance.clone()),
    }
    save_all(&list)?;
    save_meta(&instance)
}

/// Turns "My Fabric Pack!" into "my-fabric-pack" for use as a folder name.
fn slugify(name: &str) -> String {
    let s: String = name
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() {
                c.to_ascii_lowercase()
            } else {
                '-'
            }
        })
        .collect();
    let trimmed = s.trim_matches('-').to_string();
    if trimmed.is_empty() {
        "instance".to_string()
    } else {
        trimmed
    }
}

/// Creates a new instance. `parent_path` is where the instance folder is
/// created - empty means "use the default instances folder".
///
/// `install_client_mod` is a real parameter rather than a hardcoded `true`, so
/// an instance can be created without the Space Client companion mod - for
/// example a plain vanilla-feel Fabric instance, or an imported pack the user
/// wants to keep untouched.
#[allow(clippy::too_many_arguments)]
pub fn create(
    cfg: &LauncherConfig,
    name: String,
    mc_version: String,
    loader: String,
    loader_version: String,
    ram_mb: u32,
    parent_path: String,
    install_client_mod: bool,
) -> anyhow::Result<Instance> {
    if name.trim().is_empty() {
        anyhow::bail!("Please enter a name for the instance.");
    }

    let parent = if parent_path.trim().is_empty() {
        cfg.default_instances_dir()
    } else {
        PathBuf::from(parent_path)
    };

    let slug = slugify(&name);
    let mut dir = parent.join(&slug);
    let mut counter = 2;
    while dir.exists() {
        dir = parent.join(format!("{}-{}", slug, counter));
        counter += 1;
    }

    fs::create_dir_all(dir.join(".minecraft").join("mods"))?;

    let instance = Instance {
        id: uuid::Uuid::new_v4().to_string(),
        name,
        path: dir.to_string_lossy().to_string(),
        mc_version: mc_version.clone(),
        loader,
        loader_version,
        version_id: mc_version,
        ram_mb,
        install_client_mod,
        created: format!("{}", chrono_now()),
    };

    // A copy of the metadata lives inside the folder too, so an instance
    // stays self-describing if it is moved to another machine.
    fs::write(
        dir.join("instance.json"),
        serde_json::to_string_pretty(&instance)?,
    )?;

    upsert(instance.clone())?;
    Ok(instance)
}

/// Removes the instance from the launcher. Optionally deletes its files too.
pub fn delete(id: &str, delete_files: bool) -> anyhow::Result<()> {
    let list = load_all();
    let target = list.iter().find(|i| i.id == id).cloned();
    let remaining: Vec<Instance> = list.into_iter().filter(|i| i.id != id).collect();
    save_all(&remaining)?;

    if delete_files {
        if let Some(inst) = target {
            let dir = inst.dir();
            // Only delete if it really looks like one of our instance folders.
            if dir.join("instance.json").exists() {
                fs::remove_dir_all(dir).ok();
            }
        }
    }
    Ok(())
}

fn chrono_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}
