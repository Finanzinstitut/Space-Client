use serde::Serialize;
use tauri::{AppHandle, Emitter};

#[derive(Debug, Clone, Serialize)]
pub struct InstallProgress {
    /// "manifest" | "java" | "client" | "libraries" | "assets" | "done"
    pub stage: String,
    pub current: u64,
    pub total: u64,
    pub file: String,
}

pub fn emit_progress(app: &AppHandle, p: InstallProgress) {
    let _ = app.emit("install://progress", p);
}
