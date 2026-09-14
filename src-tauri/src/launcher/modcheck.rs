//! Whether the mods sitting in an instance can actually load in it.
//!
//! The failure this exists to prevent is the least informative one there is: a
//! mod built for another Minecraft version does not refuse politely at startup.
//! It loads, and then dies on a missing class somewhere in the middle of the
//! log - so what you get is a crash that names an internal class you have never
//! heard of, several thousand lines in, and no hint that the actual problem is
//! a file with the wrong number in its name.
//!
//! Everything needed to see it coming is already on disk before Java starts.
//! The jar says which Minecraft version it was built for, the instance knows
//! which one it is, and the two can simply be compared. That is all this does.
//!
//! It reports and never blocks. A launcher that refuses to start the game
//! because it disagrees with a version range is worse than the crash: version
//! ranges are frequently conservative, mods frequently work outside them, and
//! being told "this looks wrong" is enough to act on.

use crate::launcher::instance;
use crate::launcher::mods;

use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
pub struct ModIssue {
    /// The file on disk, so it can be found and dealt with.
    pub filename: String,
    /// What the mod calls itself, which is rarely what the file is called.
    pub name: String,
    pub version: String,
    /// What the jar asks for, exactly as it declared it.
    pub wanted: String,
    /// What this instance actually has.
    pub have: String,
    /// "minecraft", "loader", or "unreadable".
    ///
    /// Returned as three fields rather than one finished sentence so the screen
    /// can write it in the language the rest of the launcher is speaking. A
    /// message assembled here would always have come out English, which is the
    /// one part of this feature the user actually reads.
    pub kind: String,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct ModCheck {
    /// How many enabled jars were looked at.
    pub checked: usize,
    pub issues: Vec<ModIssue>,
    /// Set when the check could not run at all, rather than ran and found
    /// nothing - the two must never look the same on screen.
    pub note: String,
}

/// What a declared dependency says about this instance.
enum Verdict {
    Fits,
    Conflicts,
    /// Syntax this does not read. Never reported: guessing at a range nobody
    /// here understands would produce warnings about working setups, and one
    /// false alarm costs more trust than a missed case.
    Unknown,
}

/// Compares two dotted versions a segment at a time.
///
/// Not as strings. "9.0" sorts after "10.0" alphabetically, and Minecraft has
/// spent its whole life in exactly the range where that goes wrong.
fn compare(a: &str, b: &str) -> std::cmp::Ordering {
    let mut left = a.split(['.', '-', '+']);
    let mut right = b.split(['.', '-', '+']);
    loop {
        match (left.next(), right.next()) {
            (None, None) => return std::cmp::Ordering::Equal,
            (Some(x), None) => {
                return if x.parse::<u64>().unwrap_or(0) == 0 {
                    std::cmp::Ordering::Equal
                } else {
                    std::cmp::Ordering::Greater
                }
            }
            (None, Some(y)) => {
                return if y.parse::<u64>().unwrap_or(0) == 0 {
                    std::cmp::Ordering::Equal
                } else {
                    std::cmp::Ordering::Less
                }
            }
            (Some(x), Some(y)) => {
                let (Ok(x), Ok(y)) = (x.parse::<u64>(), y.parse::<u64>()) else {
                    // A segment like "rc1" - past here the two are no longer
                    // comparable as numbers, so stop rather than invent an order.
                    return std::cmp::Ordering::Equal;
                };
                if x != y {
                    return x.cmp(&y);
                }
            }
        }
    }
}

fn looks_like_version(text: &str) -> bool {
    !text.is_empty() && text.chars().all(|c| c.is_ascii_digit() || c == '.')
}

/// Whether one range admits this version.
///
/// Deliberately not clientmod's version_matches. That one decides whether to
/// refuse a download and leans towards letting it through, which is right
/// there and useless here: a bare "1.21.4" against a 26.2 instance fell
/// straight into its unknown-syntax fallback and came back as fine. That is
/// the single most common version mistake there is, so leaning the other way
/// on exactly this case is the entire point of the check.
fn admits(range: &str, version: &str) -> Verdict {
    let range = range.trim();

    if range == "*" || range.is_empty() || range == version {
        return Verdict::Fits;
    }

    if let Some(base) = range.strip_prefix('~') {
        let base = base.trim();
        if version == base || version.starts_with(&format!("{base}.")) {
            return Verdict::Fits;
        }
        return if looks_like_version(base) { Verdict::Conflicts } else { Verdict::Unknown };
    }

    for (prefix, ok) in [
        (">=", vec![std::cmp::Ordering::Greater, std::cmp::Ordering::Equal]),
        ("<=", vec![std::cmp::Ordering::Less, std::cmp::Ordering::Equal]),
        (">", vec![std::cmp::Ordering::Greater]),
        ("<", vec![std::cmp::Ordering::Less]),
    ] {
        if let Some(base) = range.strip_prefix(prefix) {
            let base = base.trim();
            if !looks_like_version(base) {
                return Verdict::Unknown;
            }
            return if ok.contains(&compare(version, base)) {
                Verdict::Fits
            } else {
                Verdict::Conflicts
            };
        }
    }

    // A plain version that is not this one. The case the whole check exists for.
    if looks_like_version(range) {
        return Verdict::Conflicts;
    }

    Verdict::Unknown
}

/// A dependency may list alternatives, and any one of them being met is enough.
fn admits_any(value: &serde_json::Value, version: &str) -> (Verdict, String) {
    let ranges: Vec<String> = match value {
        serde_json::Value::String(text) => vec![text.clone()],
        serde_json::Value::Array(list) => list
            .iter()
            .filter_map(|v| v.as_str().map(String::from))
            .collect(),
        _ => return (Verdict::Unknown, String::new()),
    };
    if ranges.is_empty() {
        return (Verdict::Unknown, String::new());
    }

    let mut any_unknown = false;
    for range in &ranges {
        match admits(range, version) {
            Verdict::Fits => return (Verdict::Fits, range.clone()),
            Verdict::Unknown => any_unknown = true,
            Verdict::Conflicts => {}
        }
    }
    let shown = ranges.join(" or ");
    if any_unknown { (Verdict::Unknown, shown) } else { (Verdict::Conflicts, shown) }
}

/// Reads one jar's fabric.mod.json.
fn read_metadata(path: &std::path::Path) -> Option<serde_json::Value> {
    let file = std::fs::File::open(path).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let mut entry = archive.by_name("fabric.mod.json").ok()?;

    let mut text = String::new();
    use std::io::Read;
    entry.read_to_string(&mut text).ok()?;
    serde_json::from_str(&text).ok()
}

/// The file as it sits on disk right now, enabled or parked.
fn on_disk(dir: &std::path::Path, base: &str) -> Option<std::path::PathBuf> {
    let enabled = dir.join(base);
    if enabled.exists() {
        return Some(enabled);
    }
    let parked = dir.join(format!("{base}.disabled"));
    if parked.exists() {
        return Some(parked);
    }
    None
}

pub fn check(instance_id: &str) -> ModCheck {
    let Some(inst) = instance::get(instance_id) else {
        return ModCheck { note: "instance not found".into(), ..Default::default() };
    };

    // Vanilla has no loader to disagree with, and a Forge instance is a
    // different world entirely - saying nothing is the honest answer for both.
    if inst.loader != "fabric" && inst.loader != "quilt" {
        return ModCheck {
            note: format!("{} instances load no Fabric mods", inst.loader),
            ..Default::default()
        };
    }

    let dir = inst.content_dir("mod");
    let mut out = ModCheck::default();

    for (filename, enabled) in mods::scan_content(&inst, "mod") {
        // A parked file is not loaded, so whatever is wrong with it is not
        // wrong with this instance. Reporting it would be noise about a file
        // the user has already switched off.
        if !enabled || !filename.ends_with(".jar") {
            continue;
        }
        out.checked += 1;

        let Some(path) = on_disk(&dir, &filename) else { continue };

        let Some(meta) = read_metadata(&path) else {
            out.issues.push(ModIssue {
                filename: filename.clone(),
                name: filename.clone(),
                version: String::new(),
                wanted: String::new(),
                have: String::new(),
                kind: "unreadable".into(),
            });
            continue;
        };

        let name = meta
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or(&filename)
            .to_string();
        let version = meta
            .get("version")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        let depends = meta.get("depends");

        if let Some(declared) = depends.and_then(|d| d.get("minecraft")) {
            let (verdict, shown) = admits_any(declared, &inst.mc_version);
            if matches!(verdict, Verdict::Conflicts) {
                out.issues.push(ModIssue {
                    filename: filename.clone(),
                    name: name.clone(),
                    version: version.clone(),
                    wanted: shown,
                    have: inst.mc_version.clone(),
                    kind: "minecraft".into(),
                });
                continue;
            }
        }

        if !inst.loader_version.is_empty() {
            if let Some(declared) = depends.and_then(|d| d.get("fabricloader")) {
                let (verdict, shown) = admits_any(declared, &inst.loader_version);
                if matches!(verdict, Verdict::Conflicts) {
                    out.issues.push(ModIssue {
                        filename: filename.clone(),
                        name,
                        version,
                        wanted: shown,
                        have: inst.loader_version.clone(),
                        kind: "loader".into(),
                    });
                }
            }
        }
    }

    out
}

