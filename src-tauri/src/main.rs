#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod launcher;

use launcher::config::LauncherConfig;
use launcher::manifest::{fetch_version_manifest, VersionEntry};
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
    username: String,
    max_ram_mb: u32,
    custom_java_path: String,
    state: State<'_, AppState>,
) -> Result<LauncherConfig, String> {
    let mut cfg = state.config.lock().unwrap();
    cfg.default_username = username;
    cfg.max_ram_mb = max_ram_mb;
    cfg.custom_java_path = custom_java_path;
    cfg.save().map_err(|e| e.to_string())?;
    Ok(cfg.clone())
}

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
async fn is_installed(version_id: String, state: State<'_, AppState>) -> Result<bool, String> {
    let cfg = state.config.lock().unwrap().clone();
    Ok(launcher::download::is_version_installed(&cfg, &version_id))
}

#[tauri::command]
async fn install_version(app: tauri::AppHandle, version_id: String, state: State<'_, AppState>) -> Result<(), String> {
    let cfg = state.config.lock().unwrap().clone();
    launcher::download::install_version(app, cfg, version_id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn launch_version(version_id: String, username: String, state: State<'_, AppState>) -> Result<(), String> {
    let cfg = state.config.lock().unwrap().clone();
    let name = if username.trim().is_empty() { cfg.default_username.clone() } else { username };
    launcher::launch::launch_version(&cfg, &version_id, &name).map_err(|e| e.to_string())
}

fn main() {
    let config = LauncherConfig::load();
    config.ensure_dirs().ok();

    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_fs::init())
        .manage(AppState { config: Mutex::new(config) })
        .invoke_handler(tauri::generate_handler![
            get_config,
            set_install_path,
            set_settings,
            list_versions,
            is_installed,
            install_version,
            launch_version,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Space Client");
}
