use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// Where the Space Client itself stores its small settings file.
/// This is always a tiny JSON file in the OS config dir - it only
/// ever contains a pointer to the REAL install path chosen by the user.
fn settings_file() -> PathBuf {
    let base = dirs::config_dir().unwrap_or_else(std::env::temp_dir);
    let dir = base.join("space-client");
    let _ = fs::create_dir_all(&dir);
    dir.join("settings.json")
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct LauncherConfig {
    /// Root folder where versions/, libraries/, assets/, instances/ live.
    /// Defaults to the OS data dir, but the user can point this at any
    /// drive/folder (e.g. D:\SpaceClient) so nothing is forced onto C:.
    pub install_path: String,
    pub default_username: String,
    pub max_ram_mb: u32,
    /// Optional manual override. Empty = use the automatically downloaded
    /// Mojang runtime, falling back to `java` on PATH.
    #[serde(default)]
    pub custom_java_path: String,
}

impl Default for LauncherConfig {
    fn default() -> Self {
        let default_path = dirs::data_dir()
            .unwrap_or_else(std::env::temp_dir)
            .join("SpaceClient");
        Self {
            install_path: default_path.to_string_lossy().to_string(),
            default_username: "Player".to_string(),
            max_ram_mb: 4096,
            custom_java_path: String::new(),
        }
    }
}

impl LauncherConfig {
    pub fn load() -> Self {
        let path = settings_file();
        if let Ok(data) = fs::read_to_string(&path) {
            if let Ok(cfg) = serde_json::from_str::<LauncherConfig>(&data) {
                return cfg;
            }
        }
        let cfg = LauncherConfig::default();
        cfg.save().ok();
        cfg
    }

    pub fn save(&self) -> anyhow::Result<()> {
        let path = settings_file();
        let data = serde_json::to_string_pretty(self)?;
        fs::write(path, data)?;
        Ok(())
    }

    pub fn install_dir(&self) -> PathBuf {
        PathBuf::from(&self.install_path)
    }

    pub fn versions_dir(&self) -> PathBuf {
        self.install_dir().join("versions")
    }

    pub fn libraries_dir(&self) -> PathBuf {
        self.install_dir().join("libraries")
    }

    pub fn assets_dir(&self) -> PathBuf {
        self.install_dir().join("assets")
    }

    pub fn natives_dir(&self, version_id: &str) -> PathBuf {
        self.install_dir().join("natives").join(version_id)
    }

    pub fn instances_dir(&self) -> PathBuf {
        self.install_dir().join("instances")
    }

    /// Downloaded Java runtimes live here, inside the user-chosen install path.
    pub fn runtimes_dir(&self) -> PathBuf {
        self.install_dir().join("runtimes")
    }

    pub fn ensure_dirs(&self) -> anyhow::Result<()> {
        fs::create_dir_all(self.versions_dir())?;
        fs::create_dir_all(self.libraries_dir())?;
        fs::create_dir_all(self.assets_dir().join("objects"))?;
        fs::create_dir_all(self.assets_dir().join("indexes"))?;
        fs::create_dir_all(self.instances_dir())?;
        fs::create_dir_all(self.runtimes_dir())?;
        Ok(())
    }
}
