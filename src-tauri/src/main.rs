#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod launcher;

use launcher::auth::{self, Account, AccountInfo, DeviceCodeInfo};
use launcher::config::LauncherConfig;
use launcher::instance::{self, Instance};
use launcher::loader::{self, LoaderVersion};
use launcher::manifest::{fetch_version_manifest, VersionEntry};
use launcher::mods::{self, InstalledMod, ModHit, ModUpdate, ProjectVersion};
use launcher::update::{self, UpdateInfo};
use serde::Serialize;
use std::sync::Mutex;
use tauri::State;

struct AppState {
    config: Mutex<LauncherConfig>,
}

#[derive(Serialize)]
struct VersionListResponse {
    latest_release: String,
    latest_snapshot: String,
    versions: Vec<VersionEntry>,
}

// ---------------- settings ----------------

#[tauri::command]
async fn get_config(state: State<'_, AppState>) -> Result<LauncherConfig, String> {
    Ok(state.config.lock().unwrap().clone())
}

#[tauri::command]
async fn set_install_path(path: String, state: State<'_, AppState>) -> Result<LauncherConfig, String> {
    let mut cfg = state.config.lock().unwrap();
    cfg.install_path = path;
    cfg.save().map_err(|e| e.to_string())?;
    cfg.ensure_dirs().map_err(|e| e.to_string())?;
    Ok(cfg.clone())
}

#[tauri::command]
async fn set_settings(
    max_ram_mb: u32,
    custom_java_path: String,
    language: String,
    check_updates: bool,
    state: State<'_, AppState>,
) -> Result<LauncherConfig, String> {
    let mut cfg = state.config.lock().unwrap();
    cfg.max_ram_mb = max_ram_mb;
    cfg.custom_java_path = custom_java_path;
    cfg.language = language;
    cfg.check_updates = check_updates;
    cfg.save().map_err(|e| e.to_string())?;
    Ok(cfg.clone())
}

// ---------------- updates ----------------

#[tauri::command]
async fn check_update(state: State<'_, AppState>) -> Result<UpdateInfo, String> {
    let enabled = state.config.lock().unwrap().check_updates;
    if !enabled {
        return Ok(UpdateInfo {
            update_available: false,
            current_version: update::CURRENT_VERSION.to_string(),
            latest_version: update::CURRENT_VERSION.to_string(),
            release_url: String::new(),
            notes: String::new(),
        });
    }
    Ok(update::check_for_update().await)
}

// ---------------- accounts ----------------

#[tauri::command]
async fn get_account() -> Result<Option<AccountInfo>, String> {
    Ok(Account::load().map(|a| AccountInfo {
        username: a.username,
        uuid: a.uuid,
        offline: a.offline,
    }))
}

#[tauri::command]
async fn login_offline(username: String) -> Result<AccountInfo, String> {
    let account = auth::login_offline(&username).map_err(|e| e.to_string())?;
    Ok(AccountInfo {
        username: account.username,
        uuid: account.uuid,
        offline: account.offline,
    })
}

#[tauri::command]
async fn start_login() -> Result<DeviceCodeInfo, String> {
    auth::start_device_login().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn complete_login(info: DeviceCodeInfo) -> Result<AccountInfo, String> {
    let account = auth::poll_device_login(info).await.map_err(|e| e.to_string())?;
    Ok(AccountInfo {
        username: account.username,
        uuid: account.uuid,
        offline: account.offline,
    })
}

#[tauri::command]
async fn logout() -> Result<(), String> {
    Account::clear().map_err(|e| e.to_string())
}

// ---------------- versions & loaders ----------------

#[tauri::command]
async fn list_versions() -> Result<VersionListResponse, String> {
    let manifest = fetch_version_manifest().await.map_err(|e| e.to_string())?;
    Ok(VersionListResponse {
        latest_release: manifest.latest.release,
        latest_snapshot: manifest.latest.snapshot,
        versions: manifest.versions,
    })
}

#[tauri::command]
async fn list_loaders(loader_name: String, mc_version: String) -> Result<Vec<LoaderVersion>, String> {
    loader::list_versions_for(&loader_name, &mc_version)
        .await
        .map_err(|e| e.to_string())
}

// ---------------- instances ----------------

#[tauri::command]
async fn list_instances() -> Result<Vec<Instance>, String> {
    Ok(instance::load_all())
}

#[tauri::command]
async fn create_instance(
    name: String,
    mc_version: String,
    loader_name: String,
    loader_version: String,
    ram_mb: u32,
    parent_path: String,
    state: State<'_, AppState>,
) -> Result<Instance, String> {
    let cfg = state.config.lock().unwrap().clone();
    instance::create(
        &cfg,
        name,
        mc_version,
        loader_name,
        loader_version,
        ram_mb,
        parent_path,
    )
    .map_err(|e| e.to_string())
}

#[derive(Serialize)]
struct InstanceUpdateResult {
    instance: Instance,
    /// True when the loader version changed, so the UI knows a reinstall is due.
    needs_reinstall: bool,
}

#[tauri::command]
async fn update_instance(
    id: String,
    name: String,
    ram_mb: u32,
    loader_version: String,
) -> Result<InstanceUpdateResult, String> {
    let (instance, needs_reinstall) =
        instance::update(&id, name, ram_mb, loader_version).map_err(|e| e.to_string())?;
    Ok(InstanceUpdateResult { instance, needs_reinstall })
}

#[tauri::command]
async fn delete_instance(id: String, delete_files: bool) -> Result<(), String> {
    instance::delete(&id, delete_files).map_err(|e| e.to_string())
}

#[tauri::command]
async fn open_instance_folder(id: String) -> Result<(), String> {
    let inst = instance::get(&id).ok_or_else(|| "Instance not found".to_string())?;
    let dir = inst.dir();
    if !dir.exists() {
        return Err(format!("Folder no longer exists: {}", dir.display()));
    }

    let result = if cfg!(target_os = "windows") {
        std::process::Command::new("explorer").arg(&dir).spawn()
    } else if cfg!(target_os = "macos") {
        std::process::Command::new("open").arg(&dir).spawn()
    } else {
        std::process::Command::new("xdg-open").arg(&dir).spawn()
    };

    // Windows explorer.exe returns a non-zero exit code even on success, so we
    // only treat a failure to spawn at all as an error.
    result.map_err(|e| format!("Could not open the folder: {}", e))?;
    Ok(())
}

/// Downloads everything this instance needs: vanilla version, Java runtime,
/// and - if selected - the mod loader.
#[tauri::command]
async fn install_instance(
    app: tauri::AppHandle,
    id: String,
    state: State<'_, AppState>,
) -> Result<Instance, String> {
    let cfg = state.config.lock().unwrap().clone();
    let mut inst = instance::get(&id).ok_or_else(|| "Instance not found".to_string())?;

    // 1. vanilla base (client jar, libraries, assets, java)
    launcher::download::install_version(app.clone(), cfg.clone(), inst.mc_version.clone())
        .await
        .map_err(|e| e.to_string())?;

    // 2. mod loader on top
    if inst.loader != "vanilla" {
        let loaders = loader::list_versions_for(&inst.loader, &inst.mc_version)
            .await
            .map_err(|e| e.to_string())?;
        // A version the user pinned wins; otherwise take the newest stable one.
        let chosen = if !inst.loader_version.is_empty() {
            loaders
                .iter()
                .find(|l| l.version == inst.loader_version)
                .cloned()
                .unwrap_or(loader::LoaderVersion {
                    version: inst.loader_version.clone(),
                    stable: true,
                })
        } else {
            loaders
                .iter()
                .find(|l| l.stable)
                .or_else(|| loaders.first())
                .cloned()
                .ok_or_else(|| {
                    format!(
                        "No {} version available for Minecraft {}",
                        inst.loader, inst.mc_version
                    )
                })?
        };

        let version_id = match inst.loader.as_str() {
            "fabric" | "quilt" => loader::install_loader(
                &app,
                &cfg,
                &inst.loader,
                &inst.mc_version,
                &chosen.version,
            )
            .await,
            "forge" | "neoforge" => loader::install_forge_like(
                &app,
                &cfg,
                &inst.loader,
                &inst.mc_version,
                &chosen.version,
            )
            .await,
            other => Err(anyhow::anyhow!("Unsupported loader: {}", other)),
        }
        .map_err(|e| e.to_string())?;

        inst.loader_version = chosen.version.clone();
        inst.version_id = version_id;
    } else {
        inst.version_id = inst.mc_version.clone();
    }

    instance::upsert(inst.clone()).map_err(|e| e.to_string())?;
    Ok(inst)
}

#[tauri::command]
async fn launch_instance(id: String, state: State<'_, AppState>) -> Result<(), String> {
    // Signing in is mandatory - no offline fallback.
    let account = auth::current_account()
        .await
        .map_err(|e| format!("Please sign in with Microsoft first. ({})", e))?;

    let cfg = state.config.lock().unwrap().clone();
    let inst = instance::get(&id).ok_or_else(|| "Instance not found".to_string())?;

    launcher::launch::launch_instance(&cfg, &inst, &account).map_err(|e| e.to_string())
}

// ---------------- mods (Modrinth) ----------------

#[tauri::command]
async fn search_mods(
    query: String,
    instance_id: String,
    project_type: String,
    categories: Vec<String>,
    offset: u32,
) -> Result<Vec<ModHit>, String> {
    let inst = instance::get(&instance_id).ok_or_else(|| "Instance not found".to_string())?;
    mods::search(
        query,
        inst.mc_version,
        inst.loader,
        project_type,
        categories,
        offset,
    )
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
async fn list_project_versions(
    project_id: String,
    instance_id: String,
    project_type: String,
) -> Result<Vec<ProjectVersion>, String> {
    mods::list_versions(project_id, instance_id, project_type)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn install_mod(
    app: tauri::AppHandle,
    instance_id: String,
    project_id: String,
    project_type: String,
) -> Result<Vec<InstalledMod>, String> {
    mods::install_mod(&app, instance_id, project_id, project_type)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn install_project_version(
    app: tauri::AppHandle,
    instance_id: String,
    project_id: String,
    version_id: String,
    project_type: String,
) -> Result<InstalledMod, String> {
    mods::install_specific_version(&app, instance_id, project_id, version_id, project_type)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn list_installed_mods(
    instance_id: String,
    project_type: String,
) -> Result<Vec<InstalledMod>, String> {
    mods::list_installed(&instance_id, &project_type).map_err(|e| e.to_string())
}

#[tauri::command]
async fn remove_mod(
    instance_id: String,
    filename: String,
    project_type: String,
) -> Result<(), String> {
    mods::remove_mod(&instance_id, &filename, &project_type).map_err(|e| e.to_string())
}

#[tauri::command]
async fn check_mod_updates(instance_id: String) -> Result<Vec<ModUpdate>, String> {
    mods::check_updates(instance_id).await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn update_mod(
    app: tauri::AppHandle,
    instance_id: String,
    project_id: String,
) -> Result<InstalledMod, String> {
    mods::update_mod(&app, instance_id, project_id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn update_all_mods(app: tauri::AppHandle, instance_id: String) -> Result<u32, String> {
    mods::update_all(&app, instance_id).await.map_err(|e| e.to_string())
}

fn main() {
    let config = LauncherConfig::load();
    config.ensure_dirs().ok();

    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_fs::init())
        .manage(AppState {
            config: Mutex::new(config),
        })
        .invoke_handler(tauri::generate_handler![
            get_config,
            set_install_path,
            set_settings,
            check_update,
            get_account,
            start_login,
            complete_login,
            logout,
            login_offline,
            list_versions,
            list_loaders,
            list_instances,
            create_instance,
            update_instance,
            delete_instance,
            open_instance_folder,
            install_instance,
            launch_instance,
            search_mods,
            list_project_versions,
            install_mod,
            install_project_version,
            list_installed_mods,
            remove_mod,
            check_mod_updates,
            update_mod,
            update_all_mods,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Space Client");
}
