#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod launcher;

use launcher::auth::{self, Account, AccountInfo, AccountStore, DeviceCodeInfo};
use launcher::config::LauncherConfig;
use launcher::crash;
use launcher::instance::{self, Instance};
use launcher::loader::{self, LoaderVersion};
use launcher::manifest::{fetch_version_manifest, VersionEntry};
use launcher::modpack::{self, ImportResult};
use launcher::mods::{self, InstalledMod, ModHit, ModUpdate, ProjectVersion, RepairReport};
use launcher::skin::{self, SkinProfile};
use launcher::skinlib::{self, SavedSkin};
use launcher::update::{self, UpdateInfo};
use serde::Serialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tauri::{Emitter, State};

struct AppState {
    config: Mutex<LauncherConfig>,
    /// Games currently running, so the live console can stop a stuck instance.
    running: Arc<Mutex<HashMap<String, std::process::Child>>>,
}

#[derive(Clone, Serialize)]
struct GameExit {
    instance_id: String,
    code: i32,
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
    live_logs: bool,
    state: State<'_, AppState>,
) -> Result<LauncherConfig, String> {
    let mut cfg = state.config.lock().unwrap();
    cfg.max_ram_mb = max_ram_mb;
    cfg.custom_java_path = custom_java_path;
    cfg.language = language;
    cfg.check_updates = check_updates;
    cfg.live_logs = live_logs;
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
        active: true,
    }))
}

/// Every signed-in account, with the active one flagged.
#[tauri::command]
async fn list_accounts() -> Result<Vec<AccountInfo>, String> {
    let store = AccountStore::load();
    Ok(store
        .accounts
        .iter()
        .map(|a| AccountInfo {
            username: a.username.clone(),
            uuid: a.uuid.clone(),
            offline: a.offline,
            active: a.uuid == store.active_uuid,
        })
        .collect())
}

#[tauri::command]
async fn switch_account(uuid: String) -> Result<AccountInfo, String> {
    let mut store = AccountStore::load();
    let account = store
        .accounts
        .iter()
        .find(|a| a.uuid == uuid)
        .cloned()
        .ok_or_else(|| "That account is not signed in.".to_string())?;

    store.active_uuid = uuid;
    store.save().map_err(|e| e.to_string())?;

    Ok(AccountInfo {
        username: account.username,
        uuid: account.uuid,
        offline: account.offline,
        active: true,
    })
}

#[tauri::command]
async fn remove_account(uuid: String) -> Result<Option<AccountInfo>, String> {
    let mut store = AccountStore::load();
    store.remove(&uuid);
    store.save().map_err(|e| e.to_string())?;

    Ok(store.active().map(|a| AccountInfo {
        username: a.username,
        uuid: a.uuid,
        offline: a.offline,
        active: true,
    }))
}

#[tauri::command]
async fn login_offline(username: String) -> Result<AccountInfo, String> {
    let account = auth::login_offline(&username).map_err(|e| e.to_string())?;
    Ok(AccountInfo {
        username: account.username,
        uuid: account.uuid,
        offline: account.offline,
        active: true,
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
        active: true,
    })
}

// ---------------- skins & capes ----------------

#[tauri::command]
async fn get_skin_profile() -> Result<SkinProfile, String> {
    skin::get_profile().await.map_err(|e| e.to_string())
}

/// Uploads a skin and keeps a copy in the local library.
///
/// The copy is written first: if Mojang rejects the file the user still has it
/// in the grid, and if the upload succeeds they can get back to this exact skin
/// later without hunting for the PNG again.
#[tauri::command]
async fn upload_skin(path: String, variant: String) -> Result<SkinProfile, String> {
    if let Err(e) = skinlib::store_file(&path, &variant).await {
        eprintln!("Could not add the skin to the library: {}", e);
    }
    skin::upload_skin(path, variant).await.map_err(|e| e.to_string())
}

// ---------------- saved skins ----------------

#[tauri::command]
async fn list_saved_skins() -> Result<Vec<SavedSkin>, String> {
    Ok(skinlib::list())
}

/// Applies a skin from the library. An empty `variant` uses the model the skin
/// was saved with.
#[tauri::command]
async fn apply_saved_skin(id: String, variant: String) -> Result<SkinProfile, String> {
    let path = skinlib::path_of(&id).map_err(|e| e.to_string())?;
    let variant = if variant.trim().is_empty() {
        skinlib::variant_of(&id)
    } else {
        variant
    };
    skin::upload_skin(path.to_string_lossy().to_string(), variant)
        .await
        .map_err(|e| e.to_string())
}

/// Stores the skin currently on the account, before it gets replaced.
#[tauri::command]
async fn save_current_skin(name: String) -> Result<SavedSkin, String> {
    let profile = skin::get_profile().await.map_err(|e| e.to_string())?;
    let variant = if profile.variant.eq_ignore_ascii_case("slim") { "slim" } else { "classic" };
    skinlib::store_url(&profile.skin_url, &name, variant)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn rename_saved_skin(id: String, name: String) -> Result<(), String> {
    skinlib::rename(&id, &name).map_err(|e| e.to_string())
}

#[tauri::command]
async fn delete_saved_skin(id: String) -> Result<(), String> {
    skinlib::delete(&id).map_err(|e| e.to_string())
}

#[tauri::command]
async fn set_skin_variant(variant: String) -> Result<SkinProfile, String> {
    skin::set_variant(variant).await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn set_cape(cape_id: String) -> Result<SkinProfile, String> {
    skin::set_cape(cape_id).await.map_err(|e| e.to_string())
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
#[allow(clippy::too_many_arguments)]
async fn create_instance(
    name: String,
    mc_version: String,
    loader_name: String,
    loader_version: String,
    ram_mb: u32,
    parent_path: String,
    install_client_mod: bool,
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
        install_client_mod,
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
    app: tauri::AppHandle,
    id: String,
    name: String,
    ram_mb: u32,
    loader_version: String,
    install_client_mod: bool,
) -> Result<InstanceUpdateResult, String> {
    let (instance, needs_reinstall) =
        instance::update(&id, name, ram_mb, loader_version, install_client_mod)
            .map_err(|e| e.to_string())?;

    // Reflect the toggle immediately rather than waiting for a reinstall.
    if install_client_mod {
        launcher::clientmod::install_client_mod(&app, &instance)
            .await
            .ok();
    } else {
        launcher::clientmod::remove_client_mod(&instance).ok();
    }

    Ok(InstanceUpdateResult { instance, needs_reinstall })
}

#[tauri::command]
async fn import_modpack(
    app: tauri::AppHandle,
    archive_path: String,
    parent_path: String,
    install_client_mod: bool,
    install_cosmetica: bool,
    state: State<'_, AppState>,
) -> Result<ImportResult, String> {
    let cfg = state.config.lock().unwrap().clone();
    modpack::import_modpack(
        &app,
        &cfg,
        archive_path,
        parent_path,
        install_client_mod,
        install_cosmetica,
    )
    .await
    .map_err(|e| e.to_string())
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

    // The companion mod goes in last, after the loader exists. Cosmetica is
    // not touched here - install_instance also runs on reinstall, and a user
    // who removed Cosmetica should not get it back.
    launcher::clientmod::install_extras(&app, &inst, false).await;

    Ok(inst)
}

#[tauri::command]
async fn launch_instance(
    app: tauri::AppHandle,
    id: String,
    state: State<'_, AppState>,
) -> Result<(), String> {
    // Signing in is mandatory - no offline fallback.
    let account = auth::current_account()
        .await
        .map_err(|e| format!("Please sign in with Microsoft first. ({})", e))?;

    let cfg = state.config.lock().unwrap().clone();
    let inst = instance::get(&id).ok_or_else(|| "Instance not found".to_string())?;

    let child = launcher::launch::launch_instance(&app, &cfg, &inst, &account)
        .map_err(|e| e.to_string())?;

    state.running.lock().unwrap().insert(id.clone(), child);

    // Poll for the process ending so the console can close itself and the
    // entry does not linger in the registry.
    let running = state.running.clone();
    let app_clone = app.clone();
    let watch_id = id.clone();
    std::thread::spawn(move || loop {
        std::thread::sleep(std::time::Duration::from_millis(500));
        let mut map = match running.lock() {
            Ok(m) => m,
            Err(_) => return,
        };
        match map.get_mut(&watch_id) {
            Some(child) => match child.try_wait() {
                Ok(Some(status)) => {
                    map.remove(&watch_id);
                    let _ = app_clone.emit(
                        "game://exit",
                        GameExit {
                            instance_id: watch_id.clone(),
                            code: status.code().unwrap_or(-1),
                        },
                    );
                    return;
                }
                Ok(None) => {}
                Err(_) => {
                    map.remove(&watch_id);
                    return;
                }
            },
            None => return,
        }
    });

    Ok(())
}

#[tauri::command]
async fn kill_instance(id: String, state: State<'_, AppState>) -> Result<(), String> {
    let mut map = state.running.lock().unwrap();
    match map.get_mut(&id) {
        Some(child) => {
            child.kill().map_err(|e| format!("Could not stop the game: {}", e))?;
            map.remove(&id);
            Ok(())
        }
        None => Err("This instance is not running.".to_string()),
    }
}

#[tauri::command]
async fn is_running(id: String, state: State<'_, AppState>) -> Result<bool, String> {
    Ok(state.running.lock().unwrap().contains_key(&id))
}

/// Uploads this instance's newest crash report or log and returns what mclo.gs
/// makes of it.
#[tauri::command]
async fn analyse_crash(id: String) -> Result<crash::CrashReport, String> {
    crash::analyse(&id).await.map_err(|e| e.to_string())
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
    mods::list_installed(&instance_id, &project_type)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn remove_mod(
    instance_id: String,
    filename: String,
    project_type: String,
) -> Result<(), String> {
    mods::remove_mod(&instance_id, &filename, &project_type).map_err(|e| e.to_string())
}

/// Switches a mod, resource pack or shader on or off without deleting it.
#[tauri::command]
async fn set_mod_enabled(
    instance_id: String,
    filename: String,
    project_type: String,
    enabled: bool,
) -> Result<(), String> {
    mods::set_mod_enabled(&instance_id, &filename, &project_type, enabled)
        .map_err(|e| e.to_string())
}

/// Pulls in every required dependency that is not installed yet.
#[tauri::command]
async fn install_missing_dependencies(
    app: tauri::AppHandle,
    instance_id: String,
) -> Result<Vec<InstalledMod>, String> {
    mods::install_missing_dependencies(&app, &instance_id)
        .await
        .map_err(|e| e.to_string())
}

/// Puts every installed mod onto a version that fits this instance, and parks
/// the ones that have none.
#[tauri::command]
async fn repair_instance_mods(
    app: tauri::AppHandle,
    instance_id: String,
) -> Result<RepairReport, String> {
    mods::repair_instance(&app, instance_id)
        .await
        .map_err(|e| e.to_string())
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
            running: Arc::new(Mutex::new(HashMap::new())),
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
            list_accounts,
            switch_account,
            remove_account,
            get_skin_profile,
            upload_skin,
            list_saved_skins,
            apply_saved_skin,
            save_current_skin,
            rename_saved_skin,
            delete_saved_skin,
            set_skin_variant,
            set_cape,
            list_versions,
            list_loaders,
            list_instances,
            create_instance,
            update_instance,
            import_modpack,
            delete_instance,
            open_instance_folder,
            install_instance,
            launch_instance,
            kill_instance,
            is_running,
            analyse_crash,
            search_mods,
            list_project_versions,
            install_mod,
            install_project_version,
            list_installed_mods,
            remove_mod,
            set_mod_enabled,
            install_missing_dependencies,
            repair_instance_mods,
            check_mod_updates,
            update_mod,
            update_all_mods,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Space Client");
}
