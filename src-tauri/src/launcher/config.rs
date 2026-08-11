use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// Small settings file in the OS config dir. It only ever holds pointers to the
/// real data locations the user picked, never game data itself.
pub fn config_dir() -> PathBuf {
    let base = dirs::config_dir().unwrap_or_else(std::env::temp_dir);
    let dir = base.join("space-client");
    let _ = fs::create_dir_all(&dir);
    dir
}

fn settings_file() -> PathBuf {
    config_dir().join("settings.json")
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct LauncherConfig {
    /// Shared cache root: versions/, libraries/, assets/, runtimes/.
    /// Individual instances live wherever the user puts them.
    pub install_path: String,
    pub max_ram_mb: u32,
    #[serde(default)]
    pub custom_java_path: String,
    /// UI language. Always defaults to English; the user can switch to "de".
    #[serde(default = "default_language")]
    pub language: String,
    #[serde(default = "default_true")]
    pub check_updates: bool,
    /// Opens a live console when a game starts, so a hanging instance can be
    /// watched and killed without digging through log files.
    #[serde(default)]
    pub live_logs: bool,
}

fn default_language() -> String {
    "en".to_string()
}

fn default_true() -> bool {
    true
}

impl Default for LauncherConfig {
    fn default() -> Self {
        let default_path = dirs::data_dir()
            .unwrap_or_else(std::env::temp_dir)
            .join("SpaceClient");
        Self {
            install_path: default_path.to_string_lossy().to_string(),
            max_ram_mb: 4096,
            custom_java_path: String::new(),
            language: default_language(),
            check_updates: true,
            live_logs: false,
        }
    }
}

impl LauncherConfig {
    pub fn load() -> Self {
        if let Ok(data) = fs::read_to_string(settings_file()) {
            if let Ok(cfg) = serde_json::from_str::<LauncherConfig>(&data) {
                return cfg;
            }
        }
        let cfg = LauncherConfig::default();
        cfg.save().ok();
        cfg
    }

    pub fn save(&self) -> anyhow::Result<()> {
        fs::write(settings_file(), serde_json::to_string_pretty(self)?)?;
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
    pub fn runtimes_dir(&self) -> PathBuf {
        self.install_dir().join("runtimes")
    }
    pub fn natives_dir(&self, version_id: &str) -> PathBuf {
        self.install_dir().join("natives").join(version_id)
    }
    /// Default parent folder suggested for new instances.
    pub fn default_instances_dir(&self) -> PathBuf {
        self.install_dir().join("instances")
    }

    pub fn ensure_dirs(&self) -> anyhow::Result<()> {
        fs::create_dir_all(self.versions_dir())?;
        fs::create_dir_all(self.libraries_dir())?;
        fs::create_dir_all(self.assets_dir().join("objects"))?;
        fs::create_dir_all(self.assets_dir().join("indexes"))?;
        fs::create_dir_all(self.runtimes_dir())?;
        fs::create_dir_all(self.default_instances_dir())?;
        Ok(())
    }
}
