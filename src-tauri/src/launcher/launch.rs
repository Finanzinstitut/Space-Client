use crate::launcher::config::LauncherConfig;
use std::fs;
use std::io::Read;
use std::path::PathBuf;
use std::process::Stdio;
use uuid::Uuid;

fn current_os_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "osx"
    } else {
        "linux"
    }
}

fn classpath_separator() -> &'static str {
    if cfg!(target_os = "windows") {
        ";"
    } else {
        ":"
    }
}

fn rule_allows(rules: &serde_json::Value) -> bool {
    let os_name = current_os_name();
    let mut allowed = false;
    if let Some(arr) = rules.as_array() {
        for rule in arr {
            let action = rule.get("action").and_then(|v| v.as_str()).unwrap_or("allow");
            let os_ok = rule
                .get("os")
                .and_then(|os| os.get("name"))
                .and_then(|v| v.as_str())
                .map(|n| n == os_name)
                .unwrap_or(true);
            if os_ok {
                allowed = action == "allow";
            }
        }
    }
    allowed
}

/// Offline-mode identity. NOTE: this does not perform real Microsoft account
/// authentication - it generates a local "offline" UUID like the vanilla
/// launcher does when you play without logging in. Real MS-auth (needed for
/// servers with online-mode=true) is a separate module to add later.
fn offline_uuid(username: &str) -> String {
    // Deterministic UUID v3-ish from "OfflinePlayer:<name>" (Mojang's own scheme),
    // simplified here to a stable v4 derived from a hash of the name.
    use sha1::{Digest, Sha1};
    let mut hasher = Sha1::new();
    hasher.update(format!("OfflinePlayer:{}", username).as_bytes());
    let digest = hasher.finalize();
    let mut bytes = [0u8; 16];
    bytes.copy_from_slice(&digest[0..16]);
    // Set UUID version/variant bits like the vanilla launcher does
    bytes[6] = (bytes[6] & 0x0f) | 0x30;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    Uuid::from_bytes(bytes).to_string()
}

pub fn launch_version(cfg: &LauncherConfig, version_id: &str, username: &str) -> anyhow::Result<()> {
    let version_dir = cfg.versions_dir().join(version_id);
    let version_json_path = version_dir.join(format!("{}.json", version_id));
    let details: serde_json::Value = serde_json::from_slice(&fs::read(&version_json_path)?)?;

    let main_class = details
        .get("mainClass")
        .and_then(|v| v.as_str())
        .unwrap_or("net.minecraft.client.main.Main")
        .to_string();

    // --- classpath + natives ---
    let natives_dir = cfg.natives_dir(version_id);
    fs::create_dir_all(&natives_dir)?;

    let mut classpath: Vec<String> = Vec::new();
    let empty = vec![];
    let libraries = details.get("libraries").and_then(|v| v.as_array()).unwrap_or(&empty);

    for lib in libraries {
        if let Some(rules) = lib.get("rules") {
            if !rule_allows(rules) {
                continue;
            }
        }
        if let Some(path) = lib.pointer("/downloads/artifact/path").and_then(|v| v.as_str()) {
            let full = cfg.libraries_dir().join(path);
            if full.exists() {
                classpath.push(full.to_string_lossy().to_string());
            }
        }
        // Extract natives jar (old-style) into natives dir
        if let Some(natives_map) = lib.get("natives") {
            if let Some(classifier_key) = natives_map.get(current_os_name()).and_then(|v| v.as_str()) {
                let classifier_key = classifier_key.replace("${arch}", "64");
                if let Some(path) = lib
                    .pointer(&format!("/downloads/classifiers/{}/path", classifier_key))
                    .and_then(|v| v.as_str())
                {
                    let jar_path = cfg.libraries_dir().join(path);
                    if jar_path.exists() {
                        extract_natives_jar(&jar_path, &natives_dir).ok();
                    }
                }
            }
        }
    }

    let client_jar = version_dir.join(format!("{}.jar", version_id));
    classpath.push(client_jar.to_string_lossy().to_string());
    let classpath_str = classpath.join(classpath_separator());

    // --- instance / game directory (this is where saves, resourcepacks etc. live) ---
    let game_dir = cfg.instances_dir().join("default").join(version_id);
    fs::create_dir_all(&game_dir)?;

    let assets_dir = cfg.assets_dir();
    let asset_index_id = details
        .pointer("/assetIndex/id")
        .and_then(|v| v.as_str())
        .unwrap_or("legacy")
        .to_string();

    let uuid = offline_uuid(username);
    let access_token = "0"; // offline mode placeholder

    let placeholders: Vec<(&str, String)> = vec![
        ("${auth_player_name}", username.to_string()),
        ("${version_name}", version_id.to_string()),
        ("${game_directory}", game_dir.to_string_lossy().to_string()),
        ("${assets_root}", assets_dir.to_string_lossy().to_string()),
        ("${game_assets}", assets_dir.to_string_lossy().to_string()),
        ("${assets_index_name}", asset_index_id.clone()),
        ("${auth_uuid}", uuid.clone()),
        ("${auth_access_token}", access_token.to_string()),
        ("${user_type}", "legacy".to_string()),
        ("${version_type}", details.get("type").and_then(|v| v.as_str()).unwrap_or("release").to_string()),
        ("${natives_directory}", natives_dir.to_string_lossy().to_string()),
        ("${launcher_name}", "SpaceClient".to_string()),
        ("${launcher_version}", "0.1.0".to_string()),
        ("${classpath}", classpath_str.clone()),
        ("${clientid}", "-".to_string()),
        ("${auth_xuid}", "-".to_string()),
    ];

    fn substitute(s: &str, placeholders: &[(&str, String)]) -> String {
        let mut out = s.to_string();
        for (k, v) in placeholders {
            out = out.replace(k, v);
        }
        out
    }

    // Game arguments: modern (`arguments.game`) or legacy (`minecraftArguments`)
    let mut game_args: Vec<String> = Vec::new();
    if let Some(arr) = details.pointer("/arguments/game").and_then(|v| v.as_array()) {
        for item in arr {
            if let Some(s) = item.as_str() {
                game_args.push(substitute(s, &placeholders));
            }
            // skip conditional-rule objects for simplicity (mostly demo/quickplay flags)
        }
    } else if let Some(legacy) = details.get("minecraftArguments").and_then(|v| v.as_str()) {
        game_args = legacy.split_whitespace().map(|s| substitute(s, &placeholders)).collect();
    }

    // JVM arguments: modern (`arguments.jvm`) or a sane fallback for legacy versions
    let mut jvm_args: Vec<String> = Vec::new();
    if let Some(arr) = details.pointer("/arguments/jvm").and_then(|v| v.as_array()) {
        for item in arr {
            if let Some(s) = item.as_str() {
                jvm_args.push(substitute(s, &placeholders));
            }
        }
    } else {
        jvm_args.push(format!("-Djava.library.path={}", natives_dir.to_string_lossy()));
        jvm_args.push("-cp".into());
        jvm_args.push(classpath_str.clone());
    }

    jvm_args.push(format!("-Xmx{}M", cfg.max_ram_mb));

    let java_bin = "java"; // must be on PATH; auto-download of a matching JRE can be added later

    let mut cmd = std::process::Command::new(java_bin);
    cmd.args(&jvm_args);
    cmd.arg(&main_class);
    cmd.args(&game_args);
    cmd.current_dir(&game_dir);
    cmd.stdout(Stdio::piped());
    cmd.stderr(Stdio::piped());

    cmd.spawn()?;
    Ok(())
}

fn extract_natives_jar(jar_path: &PathBuf, dest_dir: &PathBuf) -> anyhow::Result<()> {
    let file = fs::File::open(jar_path)?;
    let mut archive = zip::ZipArchive::new(file)?;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i)?;
        let name = entry.name().to_string();
        if name.starts_with("META-INF") {
            continue;
        }
        if name.ends_with('/') {
            continue;
        }
        // Only extract native libs (dll/so/dylib) at the jar root
        if !(name.ends_with(".dll") || name.ends_with(".so") || name.ends_with(".dylib")) {
            continue;
        }
        let out_path = dest_dir.join(PathBuf::from(&name).file_name().unwrap());
        let mut buf = Vec::new();
        entry.read_to_end(&mut buf)?;
        fs::write(out_path, buf)?;
    }
    Ok(())
}
