use anyhow::{anyhow, Result};
use serde::Serialize;
use std::path::{Path, PathBuf};

use crate::launcher::instance;
use crate::launcher::mods;

/// A single thing mclo.gs recognised, with whatever it suggests doing about it.
#[derive(Serialize, Clone)]
pub struct Finding {
    pub message: String,
    pub solutions: Vec<String>,
    /// The log line it was found on, when there is one worth showing.
    pub excerpt: Option<String>,
}

#[derive(Serialize, Clone)]
pub struct CrashReport {
    /// Where the log now lives, so the player can hand the link to someone.
    pub url: String,
    /// What the service made of the log: "Fabric 26.2 Client Log" and so on.
    pub title: String,
    pub problems: Vec<Finding>,
    pub information: Vec<String>,
    /// Which file was sent, so it is obvious when the wrong one was picked up.
    pub source: String,
    /// Installed mods whose own code appears in the log, most mentioned first.
    pub suspects: Vec<Suspect>,
}

/// A mod that turns up in the log's own stack traces.
///
/// Deliberately called a suspect and not a cause. A mod appears in a trace
/// whenever it is anywhere on the call stack, which includes every mod that was
/// merely passing the call along when something further down broke. What this
/// buys is a place to start: "Sodium is all over this" is a different morning
/// from four thousand lines naming classes you have never heard of.
#[derive(Debug, Serialize, Clone)]
pub struct Suspect {
    pub name: String,
    pub filename: String,
    /// How often its own package prefix appears.
    pub mentions: usize,
}


/// The package each installed mod's code lives under, read from its own jar.
///
/// Taken from the entrypoints, because that is the one place a mod states which
/// classes are its own. Matching on the mod's id instead would find its name in
/// every "Loading mod x" line in the header and rank the whole load order as
/// suspects.
fn mod_packages(instance_id: &str) -> Vec<(String, String, Vec<String>)> {
    let Some(inst) = instance::get(instance_id) else { return Vec::new() };
    let dir = inst.content_dir("mod");
    let mut out = Vec::new();

    for (filename, enabled) in mods::scan_content(&inst, "mod") {
        if !enabled || !filename.ends_with(".jar") {
            continue;
        }
        let path = dir.join(&filename);
        let Ok(file) = std::fs::File::open(&path) else { continue };
        let Ok(mut archive) = zip::ZipArchive::new(file) else { continue };

        let mut text = String::new();
        {
            let Ok(mut entry) = archive.by_name("fabric.mod.json") else { continue };
            use std::io::Read;
            if entry.read_to_string(&mut text).is_err() {
                continue;
            }
        }
        let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) else { continue };

        let name = json
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or(&filename)
            .to_string();

        let mut packages: Vec<String> = Vec::new();
        if let Some(entries) = json.get("entrypoints").and_then(|v| v.as_object()) {
            for list in entries.values() {
                let Some(items) = list.as_array() else { continue };
                for item in items {
                    // An entrypoint is either "a.b.C" or { "value": "a.b.C" }.
                    let class = item
                        .as_str()
                        .or_else(|| item.get("value").and_then(|v| v.as_str()));
                    let Some(class) = class else { continue };

                    // Two segments is the sweet spot: "gg.spaceclient" belongs
                    // to one mod, while "gg" alone would belong to half of them
                    // and the full class name would only match its own crash.
                    let prefix: Vec<&str> = class.split('.').take(2).collect();
                    if prefix.len() == 2 {
                        let prefix = prefix.join(".");
                        if !packages.contains(&prefix) {
                            packages.push(prefix);
                        }
                    }
                }
            }
        }
        if !packages.is_empty() {
            out.push((name, filename, packages));
        }
    }
    out
}

/// Which installed mods appear in this log, most mentioned first.
fn find_suspects(instance_id: &str, log: &str) -> Vec<Suspect> {
    let mut found: Vec<Suspect> = Vec::new();

    for (name, filename, packages) in mod_packages(instance_id) {
        let mentions: usize = packages.iter().map(|p| log.matches(p.as_str()).count()).sum();
        if mentions > 0 {
            found.push(Suspect { name, filename, mentions });
        }
    }

    found.sort_by(|a, b| b.mentions.cmp(&a.mentions));
    // Past a handful this stops being a lead and becomes a second log to read.
    found.truncate(5);
    found
}

/// The log most likely to explain a crash that just happened.
///
/// A crash report is preferred over the launch log because it is the file the
/// game itself wrote about its own death, and it is far shorter. The launch log
/// is the fallback for the cases with no crash report at all - a silent exit,
/// or a failure early enough that nothing got written.
fn pick_log(dir: &Path) -> Result<(PathBuf, String)> {
    let crash_dir = dir.join(".minecraft").join("crash-reports");
    if let Ok(entries) = std::fs::read_dir(&crash_dir) {
        let mut newest: Option<(std::time::SystemTime, PathBuf)> = None;
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("txt") {
                continue;
            }
            let modified = entry.metadata().and_then(|m| m.modified()).ok();
            if let Some(time) = modified {
                if newest.as_ref().map_or(true, |(best, _)| time > *best) {
                    newest = Some((time, path));
                }
            }
        }
        if let Some((_, path)) = newest {
            let name = path
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("crash report")
                .to_string();
            return Ok((path, name));
        }
    }

    let launch = dir.join("latest-launch.log");
    if launch.exists() {
        return Ok((launch, "latest-launch.log".to_string()));
    }

    let latest = dir.join(".minecraft").join("logs").join("latest.log");
    if latest.exists() {
        return Ok((latest, "latest.log".to_string()));
    }

    Err(anyhow!(
        "No crash report or log found for this instance yet. Start it once, and if it \
         crashes the log will be here."
    ))
}

/// Uploads the log and asks what is wrong with it.
///
/// Two calls rather than one: the upload gives an id and a link worth keeping,
/// and the insights call turns that id into something readable. The link is
/// half the value on its own - it is what you paste when you end up asking a
/// human anyway.
pub async fn analyse(instance_id: &str) -> Result<CrashReport> {
    let inst = instance::get(instance_id)
        .ok_or_else(|| anyhow!("No instance with id {}", instance_id))?;
    let (path, source) = pick_log(&inst.dir())?;

    let content = std::fs::read_to_string(&path)
        .map_err(|e| anyhow!("Could not read {}: {}", source, e))?;

    if content.trim().is_empty() {
        return Err(anyhow!("{} is empty, so there is nothing to analyse.", source));
    }

    // Worked out from the whole log and before anything is sent, so this part
    // still answers when the upload service is unreachable - which is exactly
    // the evening you most want a name to start from.
    let suspects = find_suspects(instance_id, &content);

    // The service caps uploads, and the end of a log is where a crash is. A
    // truncated tail beats a rejected upload.
    const LIMIT: usize = 8 * 1024 * 1024;
    let trimmed = if content.len() > LIMIT {
        let start = content.len() - LIMIT;
        format!("[trimmed by Space Client]\n{}", &content[start..])
    } else {
        content
    };

    let client = reqwest::Client::builder()
        .user_agent("SpaceClient-Launcher/0.1.0")
        .build()?;

    let upload: serde_json::Value = client
        .post("https://api.mclo.gs/1/log")
        .form(&[("content", trimmed.as_str())])
        .send()
        .await?
        .json()
        .await?;

    if upload.get("success").and_then(|s| s.as_bool()) != Some(true) {
        let why = upload
            .get("error")
            .and_then(|e| e.as_str())
            .unwrap_or("the service refused the upload");
        return Err(anyhow!("Upload failed: {}", why));
    }

    let id = upload
        .get("id")
        .and_then(|i| i.as_str())
        .ok_or_else(|| anyhow!("The service returned no log id"))?;
    let url = upload
        .get("url")
        .and_then(|u| u.as_str())
        .unwrap_or("https://mclo.gs")
        .to_string();

    let insights: serde_json::Value = client
        .get(format!("https://api.mclo.gs/1/insights/{}", id))
        .send()
        .await?
        .json()
        .await?;

    let title = insights
        .get("title")
        .and_then(|t| t.as_str())
        .unwrap_or("Log")
        .to_string();

    let analysis = insights.get("analysis");

    let problems = analysis
        .and_then(|a| a.get("problems"))
        .and_then(|p| p.as_array())
        .map(|items| items.iter().map(read_finding).collect())
        .unwrap_or_default();

    let information = analysis
        .and_then(|a| a.get("information"))
        .and_then(|i| i.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|item| item.get("message").and_then(|m| m.as_str()))
                .map(|s| s.to_string())
                .collect()
        })
        .unwrap_or_default();

    Ok(CrashReport {
        url,
        title,
        problems,
        information,
        source,
        suspects,
    })
}

fn read_finding(item: &serde_json::Value) -> Finding {
    let message = item
        .get("message")
        .and_then(|m| m.as_str())
        .unwrap_or("Unnamed problem")
        .to_string();

    let solutions = item
        .get("solutions")
        .and_then(|s| s.as_array())
        .map(|list| {
            list.iter()
                .filter_map(|s| s.get("message").and_then(|m| m.as_str()))
                .map(|s| s.to_string())
                .collect()
        })
        .unwrap_or_default();

    // One line is enough to recognise where it came from; the full log is a
    // click away and pasting more here would bury the message.
    let excerpt = item
        .get("entry")
        .and_then(|e| e.get("lines"))
        .and_then(|l| l.as_array())
        .and_then(|lines| lines.first())
        .and_then(|line| line.get("content"))
        .and_then(|c| c.as_str())
        .map(|s| s.trim().chars().take(160).collect());

    Finding {
        message,
        solutions,
        excerpt,
    }
}
