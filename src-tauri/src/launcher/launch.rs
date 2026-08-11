use crate::launcher::auth::Account;
use crate::launcher::config::LauncherConfig;
use crate::launcher::instance::Instance;
use crate::launcher::java;
use crate::launcher::loader::maven_to_path;
use std::fs;
use std::io::Read;
use std::path::PathBuf;
use std::process::Stdio;

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
    if cfg!(target_os = "windows") { ";" } else { ":" }
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

/// Loads a version JSON and, if it inherits from another version (as all
/// Fabric/Quilt profiles do), merges the parent into it.
fn load_version_chain(cfg: &LauncherConfig, version_id: &str) -> anyhow::Result<serde_json::Value> {
    let path = cfg
        .versions_dir()
        .join(version_id)
        .join(format!("{}.json", version_id));
    let mut current: serde_json::Value = serde_json::from_slice(&fs::read(&path)?)?;

    let mut depth = 0;
    while let Some(parent_id) = current
        .get("inheritsFrom")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
    {
        depth += 1;
        if depth > 8 {
            anyhow::bail!("Version inheritance chain too deep");
        }
        let parent_path = cfg
            .versions_dir()
            .join(&parent_id)
            .join(format!("{}.json", parent_id));
        if !parent_path.exists() {
            anyhow::bail!(
                "Base version {} is missing. Please reinstall the instance.",
                parent_id
            );
        }
        let parent: serde_json::Value = serde_json::from_slice(&fs::read(&parent_path)?)?;
        current = merge_version(parent, current);
    }
    Ok(current)
}

/// Child values win; library lists and argument lists are concatenated with
/// the child's entries first, which is what mod loaders expect.
fn merge_version(parent: serde_json::Value, child: serde_json::Value) -> serde_json::Value {
    let mut out = parent.clone();

    if let (Some(out_obj), Some(child_obj)) = (out.as_object_mut(), child.as_object()) {
        for (key, child_val) in child_obj {
            match key.as_str() {
                "inheritsFrom" => {
                    out_obj.remove("inheritsFrom");
                }
                "libraries" => {
                    let mut merged = child_val.as_array().cloned().unwrap_or_default();
                    if let Some(parent_libs) = parent.get("libraries").and_then(|v| v.as_array()) {
                        merged.extend(parent_libs.iter().cloned());
                    }
                    out_obj.insert("libraries".into(), serde_json::Value::Array(merged));
                }
                "arguments" => {
                    let mut merged = serde_json::Map::new();
                    for section in ["game", "jvm"] {
                        let mut items = parent
                            .pointer(&format!("/arguments/{}", section))
                            .and_then(|v| v.as_array())
                            .cloned()
                            .unwrap_or_default();
                        if let Some(child_items) =
                            child_val.get(section).and_then(|v| v.as_array())
                        {
                            items.extend(child_items.iter().cloned());
                        }
                        merged.insert(section.into(), serde_json::Value::Array(items));
                    }
                    out_obj.insert("arguments".into(), serde_json::Value::Object(merged));
                }
                _ => {
                    out_obj.insert(key.clone(), child_val.clone());
                }
            }
        }
    }
    out
}

/// Resolves the jar path for a library entry, whether it has a downloads
/// block (vanilla) or only a maven name (Fabric/Quilt).
fn library_path(cfg: &LauncherConfig, lib: &serde_json::Value) -> Option<PathBuf> {
    if let Some(path) = lib.pointer("/downloads/artifact/path").and_then(|v| v.as_str()) {
        return Some(cfg.libraries_dir().join(path));
    }
    let name = lib.get("name").and_then(|v| v.as_str())?;
    let rel = maven_to_path(name)?;
    Some(cfg.libraries_dir().join(rel))
}

pub fn launch_instance(
    cfg: &LauncherConfig,
    instance: &Instance,
    account: &Account,
) -> anyhow::Result<()> {
    let version_id = if instance.version_id.is_empty() {
        instance.mc_version.clone()
    } else {
        instance.version_id.clone()
    };

    let details = load_version_chain(cfg, &version_id)?;

    let main_class = details
        .get("mainClass")
        .and_then(|v| v.as_str())
        .unwrap_or("net.minecraft.client.main.Main")
        .to_string();

    // --- classpath + natives ---
    let natives_dir = cfg.natives_dir(&version_id);
    fs::create_dir_all(&natives_dir)?;

    let mut classpath: Vec<String> = Vec::new();
    let mut seen: Vec<String> = Vec::new();
    let empty = vec![];
    let libraries = details.get("libraries").and_then(|v| v.as_array()).unwrap_or(&empty);

    for lib in libraries {
        if let Some(rules) = lib.get("rules") {
            if !rule_allows(rules) {
                continue;
            }
        }
        // Skip duplicate artifacts (loader profiles often repeat vanilla libs)
        if let Some(name) = lib.get("name").and_then(|v| v.as_str()) {
            let key: String = name.rsplitn(2, ':').nth(1).unwrap_or(name).to_string();
            if seen.contains(&key) {
                continue;
            }
            seen.push(key);
        }

        if let Some(full) = library_path(cfg, lib) {
            if full.exists() {
                classpath.push(full.to_string_lossy().to_string());
            }
        }

        // Old-style natives jars need extracting next to the game
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

    // The client jar always comes from the base (vanilla) version
    let base_id = details
        .get("jar")
        .and_then(|v| v.as_str())
        .unwrap_or(&instance.mc_version)
        .to_string();
    let client_jar = cfg
        .versions_dir()
        .join(&base_id)
        .join(format!("{}.jar", base_id));
    classpath.push(client_jar.to_string_lossy().to_string());
    let classpath_str = classpath.join(classpath_separator());

    // --- this instance's own game directory ---
    let game_dir = instance.game_dir();
    fs::create_dir_all(game_dir.join("mods"))?;

    let assets_dir = cfg.assets_dir();
    let asset_index_id = details
        .pointer("/assetIndex/id")
        .and_then(|v| v.as_str())
        .unwrap_or("legacy")
        .to_string();

    let placeholders: Vec<(&str, String)> = vec![
        ("${auth_player_name}", account.username.clone()),
        ("${version_name}", version_id.clone()),
        ("${game_directory}", game_dir.to_string_lossy().to_string()),
        ("${assets_root}", assets_dir.to_string_lossy().to_string()),
        ("${game_assets}", assets_dir.to_string_lossy().to_string()),
        ("${assets_index_name}", asset_index_id.clone()),
        ("${auth_uuid}", account.uuid.clone()),
        ("${auth_access_token}", account.access_token.clone()),
        ("${auth_session}", format!("token:{}:{}", account.access_token, account.uuid)),
        (
            "${user_type}",
            if account.offline { "legacy".to_string() } else { "msa".to_string() },
        ),
        ("${user_properties}", "{}".to_string()),
        (
            "${version_type}",
            details.get("type").and_then(|v| v.as_str()).unwrap_or("release").to_string(),
        ),
        ("${natives_directory}", natives_dir.to_string_lossy().to_string()),
        ("${launcher_name}", "SpaceClient".to_string()),
        ("${launcher_version}", env!("CARGO_PKG_VERSION").to_string()),
        ("${classpath}", classpath_str.clone()),
        ("${clientid}", "-".to_string()),
        ("${auth_xuid}", "-".to_string()),
        ("${library_directory}", cfg.libraries_dir().to_string_lossy().to_string()),
        ("${classpath_separator}", classpath_separator().to_string()),
    ];

    fn substitute(s: &str, placeholders: &[(&str, String)]) -> String {
        let mut out = s.to_string();
        for (k, v) in placeholders {
            out = out.replace(k, v);
        }
        out
    }

    let mut game_args: Vec<String> = Vec::new();
    if let Some(arr) = details.pointer("/arguments/game").and_then(|v| v.as_array()) {
        for item in arr {
            if let Some(s) = item.as_str() {
                game_args.push(substitute(s, &placeholders));
            }
        }
    } else if let Some(legacy) = details.get("minecraftArguments").and_then(|v| v.as_str()) {
        game_args = legacy
            .split_whitespace()
            .map(|s| substitute(s, &placeholders))
            .collect();
    }

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

    // Per-instance RAM
    jvm_args.push(format!("-Xmx{}M", instance.ram_mb.max(512)));

    let java_bin = java::resolve_java_binary(cfg, &details);

    let mut cmd = std::process::Command::new(&java_bin);
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
        if name.starts_with("META-INF") || name.ends_with('/') {
            continue;
        }
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
