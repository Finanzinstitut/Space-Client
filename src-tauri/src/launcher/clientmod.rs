use crate::launcher::instance::Instance;
use crate::launcher::progress::{emit_progress, InstallProgress};
use tauri::AppHandle;
use tokio::fs;
use tokio::io::AsyncWriteExt;

/// Where the in-game companion mod is published.
const MOD_REPO: &str = "Finanzinstitut/Space-Client-Mod";

/// Fixed file name, so installing a new build replaces the old one instead of
/// leaving two versions in the folder fighting each other.
/// Public so the server profiles can refuse to park it: switching off the
/// client mod would take the menu that configures the profiles with it.
pub const FILE_NAME: &str = "spaceclient.jar";

fn http() -> anyhow::Result<reqwest::Client> {
    Ok(reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .build()?)
}

/// Every loader the mod is built for. Quilt loads the Fabric build.
pub fn supports_loader(loader: &str) -> bool {
    flavour(loader).is_some()
}

/// Which build of the mod an instance's loader takes: "fabric" for Fabric and
/// Quilt, "neoforge", "forge", or None for vanilla.
pub fn flavour(loader: &str) -> Option<&'static str> {
    match loader {
        "fabric" | "quilt" => Some("fabric"),
        "neoforge" => Some("neoforge"),
        "forge" => Some("forge"),
        _ => None,
    }
}

/// Installs (or refreshes) the companion mod in this instance.
/// Returns the version tag that was installed, or None if there was nothing to do.
pub async fn install_client_mod(app: &AppHandle, instance: &Instance) -> anyhow::Result<Option<String>> {
    if !instance.install_client_mod {
        return Ok(None);
    }
    if !supports_loader(&instance.loader) {
        return Ok(None);
    }

    emit_progress(app, InstallProgress {
        stage: "clientmod".into(),
        current: 0,
        total: 1,
        file: "Space Client mod".into(),
    });

    let client = http()?;

    // Every release, newest first, rather than only the latest: the newest
    // build is made for the newest Minecraft, and an instance on an older
    // version needs the newest build that was made for *its* version. A 26.2
    // instance keeps getting the last 26.2 build after the mod has moved on
    // to 26.3, instead of getting nothing.
    let releases = list_releases(&client).await?;
    if releases.is_empty() {
        // No release published yet is a normal state early on, not an error
        // worth failing the whole instance install over.
        return Ok(None);
    }

    let mods_dir = instance.mods_dir();
    let dest = mods_dir.join(FILE_NAME);
    // Only counts as installed if it also fits: an instance moved to another
    // Minecraft version keeps the old build's jar, with the same mod version
    // as the new release but built for the version it left.
    let installed = installed_version(&dest).filter(|_| {
        std::fs::read(&dest)
            .map(|bytes| matches!(jar_fits(&bytes, instance), Fit::Yes))
            .unwrap_or(false)
    });

    // Older releases name their jar without the Minecraft version, so the only
    // way to know what they are for is to look inside. Capped, so a long run of
    // releases for some other version cannot turn into a long run of downloads.
    let mut unlabelled_checked = 0;
    let mut skipped: Option<String> = None;

    for release in &releases {
        let wanted = flavour(&instance.loader).unwrap_or("fabric");
        let Some(asset) = mod_asset(release, &instance.mc_version, wanted) else { continue };
        let version = release_version(release);

        if asset.flavour != wanted {
            // The release has no build for this loader at all; an older one may
            skipped.get_or_insert(format!("no {} build of the Space Client mod yet", instance.loader));
            continue;
        }
        match asset.mc_version.as_deref() {
            Some(mc) if !same_series(mc, &instance.mc_version) => {
                // Labelled for another version: no download needed to know
                skipped.get_or_insert(format!(
                    "built for Minecraft {}, this instance is {}",
                    mc, instance.mc_version
                ));
                continue;
            }
            _ => {}
        }

        // Already on this build. Checked before anything is downloaded, so an
        // instance that is current costs one small API call, not a few
        // megabytes and a rewritten file. The jar on disk was checked when it
        // was put there, so its version alone is enough.
        if installed.as_deref() == Some(version.as_str()) {
            emit_progress(app, InstallProgress {
                stage: "clientmod".into(),
                current: 1,
                total: 1,
                file: format!("Space Client mod {} already installed", version),
            });
            return Ok(Some(version));
        }

        if asset.mc_version.is_none() {
            if unlabelled_checked >= 3 {
                continue;
            }
            unlabelled_checked += 1;
        }

        let bytes = client
            .get(&asset.url)
            .send()
            .await?
            .error_for_status()?
            .bytes()
            .await?;

        // Checked against what the jar says about itself as well, even when
        // the name carries a version: fabric.mod.json is what the loader will
        // actually read, so it is what decides.
        match jar_fits(&bytes, instance) {
            Fit::Yes => {}
            Fit::No(reason) => {
                skipped.get_or_insert(reason);
                continue;
            }
        }

        fs::create_dir_all(&mods_dir).await?;
        let mut f = fs::File::create(&dest).await?;
        f.write_all(&bytes).await?;
        f.flush().await?;

        emit_progress(app, InstallProgress {
            stage: "clientmod".into(),
            current: 1,
            total: 1,
            file: format!("Space Client mod {} for Minecraft {}", version, instance.mc_version),
        });
        return Ok(Some(version));
    }

    emit_progress(app, InstallProgress {
        stage: "clientmod".into(),
        current: 1,
        total: 1,
        file: format!(
            "Space Client mod skipped: {}",
            skipped.unwrap_or_else(|| format!("no build for Minecraft {}", instance.mc_version))
        ),
    });
    Ok(None)
}

/// Which Minecraft versions and loaders the newest mod release has a build
/// for, read from its jar names: [("26.3", "fabric"), ("26.3", "neoforge"), ...].
///
/// Asked of the release rather than written into the launcher, so a mod
/// release that adds a Minecraft version is supported by every launcher
/// already installed - no launcher update in between.
pub async fn supported_builds() -> anyhow::Result<Vec<(String, String)>> {
    let client = http()?;
    let releases = list_releases(&client).await?;
    let Some(newest) = releases.first() else { return Ok(Vec::new()) };
    let mut out: Vec<(String, String)> = newest
        .get("assets")
        .and_then(|a| a.as_array())
        .map(|assets| {
            assets
                .iter()
                .filter_map(|a| a.get("name").and_then(|n| n.as_str()))
                .filter_map(|name| {
                    let (mc, flavour) = parse_name(name);
                    mc.map(|mc| (mc, flavour.to_string()))
                })
                .collect()
        })
        .unwrap_or_default();
    out.sort();
    out.dedup();
    Ok(out)
}

/// Published releases, newest first, drafts and pre-releases left out.
/// Up to three pages: far more history than any instance will reach back for.
async fn list_releases(client: &reqwest::Client) -> anyhow::Result<Vec<serde_json::Value>> {
    let mut out = Vec::new();
    for page in 1..=3 {
        let url = format!(
            "https://api.github.com/repos/{}/releases?per_page=50&page={}",
            MOD_REPO, page
        );
        let resp = client.get(&url).send().await?;
        if !resp.status().is_success() {
            break;
        }
        let batch: Vec<serde_json::Value> = resp.json().await?;
        let done = batch.len() < 50;
        out.extend(batch.into_iter().filter(|r| {
            !r.get("draft").and_then(|v| v.as_bool()).unwrap_or(false)
                && !r.get("prerelease").and_then(|v| v.as_bool()).unwrap_or(false)
        }));
        if done {
            break;
        }
    }
    Ok(out)
}

/// The mod jar of one release.
struct ModAsset {
    url: String,
    /// From a name like spaceclient-1.47.0-mc26.3.jar; None on older releases
    mc_version: Option<String>,
    /// "fabric", "neoforge" or "forge", from the name's suffix; unlabelled and
    /// unsuffixed jars are Fabric builds, which is all there used to be.
    flavour: &'static str,
}

/// The jar of one release that suits `instance_mc`.
///
/// A release carries one jar per supported Minecraft version, so the first jar
/// is not the right one: the one labelled for the instance's series is. Failing
/// that, an unlabelled jar (older releases), whose fabric.mod.json then decides.
/// Failing that, any labelled jar, only so the caller can say what it skipped.
fn mod_asset(release: &serde_json::Value, instance_mc: &str, wanted: &str) -> Option<ModAsset> {
    let assets = release.get("assets")?.as_array()?;
    let jars: Vec<ModAsset> = assets
        .iter()
        .filter_map(|a| {
            let name = a.get("name")?.as_str()?;
            // Skipping the sources and dev jars Loom also produces
            if !name.ends_with(".jar")
                || name.contains("sources")
                || name.contains("dev")
                || name.contains("shadow")
            {
                return None;
            }
            let (mc_version, flavour) = parse_name(name);
            Some(ModAsset {
                url: a.get("browser_download_url")?.as_str()?.to_string(),
                mc_version,
                flavour,
            })
        })
        .collect();

    let pick = jars
        .iter()
        .position(|j| {
            j.flavour == wanted
                && j.mc_version.as_deref().is_some_and(|mc| same_series(mc, instance_mc))
        })
        .or_else(|| jars.iter().position(|j| j.flavour == wanted && j.mc_version.is_none()))
        .or_else(|| jars.iter().position(|j| j.flavour == wanted))
        .or(if jars.is_empty() { None } else { Some(0) })?;
    jars.into_iter().nth(pick)
}

/// "spaceclient-1.47.0-mc26.3.jar" -> "26.3".
fn mc_from_name(name: &str) -> Option<String> {
    parse_name(name).0
}

/// The Minecraft version and loader a jar's name says it is for:
/// "spaceclient-1.57.0-mc26.3.jar" -> (26.3, fabric),
/// "spaceclient-1.57.0-mc26.3-neoforge.jar" -> (26.3, neoforge).
fn parse_name(name: &str) -> (Option<String>, &'static str) {
    let Some(stem) = name.strip_suffix(".jar") else { return (None, "fabric") };
    let (stem, flavour) = if let Some(rest) = stem.strip_suffix("-neoforge") {
        (rest, "neoforge")
    } else if let Some(rest) = stem.strip_suffix("-forge") {
        (rest, "forge")
    } else {
        (stem, "fabric")
    };
    let mc = stem.rfind("-mc").map(|at| &stem[at + 3..]).filter(|mc| {
        !mc.is_empty() && mc.chars().all(|c| c.is_ascii_digit() || c == '.')
    });
    (mc.map(String::from), flavour)
}

/// The version the release's jar carries, from its tag: "v.1.47.0" -> "1.47.0".
/// Compared with the version inside the installed jar, which has neither the
/// v nor the dot.
fn release_version(release: &serde_json::Value) -> String {
    let tag = release.get("tag_name").and_then(|v| v.as_str()).unwrap_or("unknown");
    tag.trim_start_matches('v').trim_start_matches('.').to_string()
}

/// Whether a build made for `built` runs on `instance`: the same release
/// series, so a 26.3 build also covers 26.3.1, but not 26.2 or 26.4.
fn same_series(built: &str, instance: &str) -> bool {
    instance == built || instance.starts_with(&format!("{}.", built))
}

/// Whether a downloaded jar belongs in this instance.
enum Fit {
    Yes,
    No(String),
}

/// Reads the version out of an installed jar, so a reinstall can be skipped.
///
/// From the jar rather than from a note kept beside it: a note goes stale the
/// moment somebody replaces the file by hand, and then the launcher insists
/// everything is current while the folder says otherwise.
fn installed_version(path: &std::path::Path) -> Option<String> {
    let bytes = std::fs::read(path).ok()?;
    read_metadata(&bytes).ok()?.version
}

/// What a jar says about itself, whichever loader it was built for.
#[derive(Debug, Default)]
struct JarMeta {
    flavour: &'static str,
    version: Option<String>,
    /// The Minecraft requirement as written: a Fabric range for Fabric,
    /// a Maven range ("[26.3,26.4)") for NeoForge and Forge.
    minecraft: Option<String>,
}

fn read_metadata(bytes: &[u8]) -> Result<JarMeta, String> {
    use std::io::Read;
    let mut archive = zip::ZipArchive::new(std::io::Cursor::new(bytes))
        .map_err(|_| "the download is not a readable jar".to_string())?;

    let mut read = |name: &str| -> Option<String> {
        let mut entry = archive.by_name(name).ok()?;
        let mut text = String::new();
        entry.read_to_string(&mut text).ok()?;
        Some(text)
    };

    if let Some(text) = read("fabric.mod.json") {
        let json: serde_json::Value = serde_json::from_str(&text)
            .map_err(|_| "the jar's fabric.mod.json is malformed".to_string())?;
        return Ok(JarMeta {
            flavour: "fabric",
            version: json.get("version").and_then(|v| v.as_str()).map(String::from),
            minecraft: json
                .pointer("/depends/minecraft")
                .and_then(|v| v.as_str())
                .map(String::from),
        });
    }
    for (file, flavour) in [("META-INF/neoforge.mods.toml", "neoforge"), ("META-INF/mods.toml", "forge")] {
        if let Some(text) = read(file) {
            return Ok(JarMeta {
                flavour,
                version: toml_mod_version(&text),
                minecraft: toml_dependency_range(&text, "minecraft"),
            });
        }
    }
    Err("the jar carries no mod metadata".to_string())
}

/// The version="..." of the first [[mods]] entry. A full TOML parser for two
/// keys would be more code than the file it reads.
fn toml_mod_version(text: &str) -> Option<String> {
    let mods = text.find("[[mods]]")?;
    toml_string(&text[mods..], "version")
}

/// The versionRange of the [[dependencies.*]] block whose modId is `id`.
fn toml_dependency_range(text: &str, id: &str) -> Option<String> {
    text.split("[[dependencies.")
        .skip(1)
        .find(|block| toml_string(block, "modId").as_deref() == Some(id))
        .and_then(|block| toml_string(block, "versionRange"))
}

fn toml_string(block: &str, key: &str) -> Option<String> {
    for line in block.lines() {
        let line = line.trim();
        if line.starts_with("[[") && !line.starts_with("[[mods]]") && !line.starts_with("[[dependencies") {
            continue;
        }
        let Some(rest) = line.strip_prefix(key) else { continue };
        let rest = rest.trim_start();
        let Some(rest) = rest.strip_prefix('=') else { continue };
        let rest = rest.trim();
        let value = rest.trim_matches('"');
        if rest.starts_with('"') {
            return Some(value.split('"').next().unwrap_or("").to_string());
        }
    }
    None
}

/// A Maven version range as NeoForge and Forge write it: "[26.3,26.4)",
/// "[61,)", "26.3". Unreadable ranges count as a match, as with Fabric's.
pub(crate) fn maven_range_matches(range: &str, version: &str) -> bool {
    let range = range.trim();
    if range.is_empty() || range == "*" {
        return true;
    }
    let (Some(open), Some(close)) = (range.chars().next(), range.chars().last()) else { return true };
    if !matches!(open, '[' | '(') || !matches!(close, ']' | ')') {
        return compare(version, range) == 0;
    }
    let inner = &range[1..range.len() - 1];
    let Some((low, high)) = inner.split_once(',') else {
        return compare(version, inner) == 0;
    };
    let (low, high) = (low.trim(), high.trim());
    let low_ok = low.is_empty() || {
        let c = compare(version, low);
        if open == '[' { c >= 0 } else { c > 0 }
    };
    let high_ok = high.is_empty() || {
        let c = compare(version, high);
        if close == ']' { c <= 0 } else { c < 0 }
    };
    low_ok && high_ok
}

/// Whether the jar's own declared dependencies match this instance.
///
/// The two things the user asked for, taken from the only place that cannot
/// disagree with reality: what the loader itself will read. A mod built for a
/// different Minecraft version does not fail politely - it crashes on a missing
/// class halfway through loading a world, which is a far worse way to find out.
fn jar_fits(bytes: &[u8], instance: &Instance) -> Fit {
    let meta = match read_metadata(bytes) {
        Ok(m) => m,
        Err(reason) => return Fit::No(reason),
    };

    // Loader first: a jar for another loader would not even be read
    let Some(wanted) = flavour(&instance.loader) else {
        return Fit::No(format!("{} cannot load mods", instance.loader));
    };
    if meta.flavour != wanted {
        return Fit::No(format!("a {} build, this instance runs {}", meta.flavour, instance.loader));
    }

    let wanted_mc = meta.minecraft.unwrap_or_else(|| "*".into());
    let fits = if meta.flavour == "fabric" {
        version_matches(&wanted_mc, &instance.mc_version)
    } else {
        maven_range_matches(&wanted_mc, &instance.mc_version)
    };
    if !fits {
        return Fit::No(format!(
            "built for Minecraft {}, this instance is {}",
            wanted_mc, instance.mc_version
        ));
    }

    Fit::Yes
}

/// A deliberately small reading of Fabric's version ranges.
///
/// Only the shapes this project actually publishes: a wildcard, an exact
/// version, and the tilde range Fabric documents for a release series. Anything
/// more elaborate is treated as a match rather than guessed at, because
/// refusing to install over a range this cannot parse would be a worse failure
/// than installing something that then declines to load and says why.
pub(crate) fn version_matches(range: &str, version: &str) -> bool {
    let range = range.trim();

    if range == "*" || range.is_empty() {
        return true;
    }
    if range == version {
        return true;
    }

    // Several conditions separated by spaces all have to hold: ">=26.2 <26.4"
    if range.contains(' ') {
        return range
            .split_whitespace()
            .all(|part| version_matches(part, version));
    }

    if let Some(base) = range.strip_prefix('~') {
        // ~26.2 covers the 26.2 series: 26.2, 26.2.1 and so on
        return version == base || version.starts_with(&format!("{}.", base));
    }
    if let Some(base) = range.strip_prefix('^') {
        // Same major and at least this version
        let major = base.split('.').next().unwrap_or("");
        return version.split('.').next() == Some(major) && compare(version, base) >= 0;
    }

    for (prefix, test) in [
        (">=", (|o: i32| o >= 0) as fn(i32) -> bool),
        ("<=", |o| o <= 0),
        (">", |o| o > 0),
        ("<", |o| o < 0),
        ("=", |o| o == 0),
    ] {
        if let Some(base) = range.strip_prefix(prefix) {
            return test(compare(version, base.trim()));
        }
    }

    // A plain version, possibly with an x or * for one part: "26.2", "26.2.x"
    if range.chars().all(|c| c.is_ascii_digit() || c == '.' || c == 'x' || c == 'X' || c == '*') {
        let wanted: Vec<&str> = range.split('.').collect();
        let have: Vec<&str> = version.split('.').collect();
        if let Some(wild) = wanted.iter().position(|p| matches!(*p, "x" | "X" | "*")) {
            return have.len() >= wild && wanted[..wild] == have[..wild];
        }
        // Exact: 26.2 is 26.2 and also 26.2.0, but not 26.3
        return compare(version, range) == 0;
    }

    // Something this does not understand. Say yes and let the loader have the
    // final word - it will refuse with a message naming the real requirement.
    true
}

/// Numeric comparison of dotted versions; missing parts count as 0 and
/// anything after a dash (pre-release tags) is ignored.
fn compare(a: &str, b: &str) -> i32 {
    let parse = |v: &str| -> Vec<u64> {
        v.split('-')
            .next()
            .unwrap_or("")
            .split('.')
            .map(|p| p.parse::<u64>().unwrap_or(0))
            .collect()
    };
    let (x, y) = (parse(a), parse(b));
    for i in 0..x.len().max(y.len()) {
        let (p, q) = (x.get(i).copied().unwrap_or(0), y.get(i).copied().unwrap_or(0));
        if p != q {
            return if p < q { -1 } else { 1 };
        }
    }
    0
}

/// Modrinth slug of the cosmetics mod offered alongside the client.
pub const COSMETICA_PROJECT: &str = "cosmetica";

/// What a round of extras installation produced, so the UI can say something
/// useful instead of failing the whole operation over a cosmetics mod.
#[derive(Debug, Default, serde::Serialize, Clone)]
pub struct ExtrasReport {
    pub client_mod: Option<String>,
    pub cosmetica: bool,
    /// Human readable reasons for anything that did not happen.
    pub notes: Vec<String>,
}

/// Installs the optional extras into an instance: the Space Client companion
/// mod and, if asked for, Cosmetica.
///
/// Both instance creation and modpack import go through here. Previously the
/// client mod was only installed as a side effect of `install_instance`, and
/// Cosmetica only from the create-instance button in the frontend - which is
/// why an imported pack ended up with neither.
///
/// Nothing in here is fatal: the instance is already usable, and losing a
/// cosmetics mod is not a reason to leave the user with a failed import.
pub async fn install_extras(
    app: &AppHandle,
    instance: &Instance,
    want_cosmetica: bool,
) -> ExtrasReport {
    let mut report = ExtrasReport::default();

    if instance.install_client_mod {
        if supports_loader(&instance.loader) {
            match install_client_mod(app, instance).await {
                Ok(tag) => report.client_mod = tag,
                Err(e) => report
                    .notes
                    .push(format!("The Space Client mod could not be installed: {}", e)),
            }
        } else {
            report.notes.push(format!(
                "The Space Client mod needs a mod loader, so it was skipped on this {} instance.",
                instance.loader
            ));
        }
    } else {
        // The toggle may have been switched off after an earlier install.
        remove_client_mod(instance).ok();
    }

    if want_cosmetica {
        if instance.loader == "vanilla" {
            report
                .notes
                .push("Cosmetica needs a mod loader, so it was skipped.".to_string());
        } else {
            match crate::launcher::mods::install_mod(
                app,
                instance.id.clone(),
                COSMETICA_PROJECT.to_string(),
                "mod".to_string(),
            )
            .await
            {
                Ok(_) => report.cosmetica = true,
                Err(e) => report
                    .notes
                    .push(format!("Cosmetica could not be installed: {}", e)),
            }
        }
    }

    report
}

/// Removes the companion mod, used when the per-instance toggle is switched off.
pub fn remove_client_mod(instance: &Instance) -> anyhow::Result<()> {
    let path = instance.mods_dir().join(FILE_NAME);
    if path.exists() {
        std::fs::remove_file(path)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_minecraft_version_from_the_name() {
        assert_eq!(mc_from_name("spaceclient-1.47.0-mc26.3.jar").as_deref(), Some("26.3"));
        assert_eq!(mc_from_name("spaceclient-2.0.0-mc26.3.1.jar").as_deref(), Some("26.3.1"));
        // Releases from before the name carried it
        assert_eq!(mc_from_name("spaceclient-1.46.0.jar"), None);
        assert_eq!(mc_from_name("spaceclient-1.46.0-mcbeta.jar"), None);
    }

    #[test]
    fn release_version_drops_the_tag_prefix() {
        let release = serde_json::json!({ "tag_name": "v.1.47.0" });
        assert_eq!(release_version(&release), "1.47.0");
        let release = serde_json::json!({ "tag_name": "v1.2.3" });
        assert_eq!(release_version(&release), "1.2.3");
    }

    #[test]
    fn version_ranges_read_like_fabric() {
        assert!(version_matches("26.2", "26.2"));
        assert!(!version_matches("26.2", "26.3"));
        assert!(version_matches("26.2.x", "26.2.4"));
        assert!(!version_matches("26.2.x", "26.3"));
        assert!(version_matches(">=26.2", "26.3"));
        assert!(!version_matches(">=26.3", "26.2"));
        assert!(version_matches(">=26.2 <26.4", "26.3"));
        assert!(!version_matches(">=26.2 <26.3", "26.3"));
        assert!(version_matches("~26.3", "26.3.1"));
        assert!(version_matches("*", "26.3"));
        assert!(version_matches(">=26.10", "26.10"));
        assert!(!version_matches(">=26.10", "26.9"));
    }

    #[test]
    fn a_build_covers_its_own_series_only() {
        assert!(same_series("26.3", "26.3"));
        assert!(same_series("26.3", "26.3.1"));
        assert!(!same_series("26.3", "26.2"));
        assert!(!same_series("26.3", "26.4"));
        assert!(!same_series("26.3", "26.31"));
        assert!(same_series("26.2", "26.2"));
    }

    #[test]
    fn picks_the_jar_and_its_version() {
        let release = serde_json::json!({
            "tag_name": "v.1.47.0",
            "assets": [
                { "name": "spaceclient-1.47.0-sources.jar", "browser_download_url": "https://x/sources" },
                { "name": "spaceclient-1.47.0-mc26.3.jar", "browser_download_url": "https://x/mod" }
            ]
        });
        let asset = mod_asset(&release, "26.3", "fabric").unwrap();
        assert_eq!(asset.url, "https://x/mod");
        assert_eq!(asset.mc_version.as_deref(), Some("26.3"));
    }

    #[test]
    fn picks_the_jar_for_the_instance_version() {
        let release = serde_json::json!({
            "tag_name": "v.1.55.0",
            "assets": [
                { "name": "spaceclient-1.55.0-mc1.21.11.jar", "browser_download_url": "https://x/1.21.11" },
                { "name": "spaceclient-1.55.0-mc26.1.jar", "browser_download_url": "https://x/26.1" },
                { "name": "spaceclient-1.55.0-mc26.2.jar", "browser_download_url": "https://x/26.2" },
                { "name": "spaceclient-1.55.0-mc26.3.jar", "browser_download_url": "https://x/26.3" }
            ]
        });
        let url = |mc: &str| mod_asset(&release, mc, "fabric").unwrap().url;
        assert_eq!(url("1.21.11"), "https://x/1.21.11");
        assert_eq!(url("26.1"), "https://x/26.1");
        assert_eq!(url("26.1.2"), "https://x/26.1");
        assert_eq!(url("26.2"), "https://x/26.2");
        assert_eq!(url("26.3"), "https://x/26.3");
        // Nothing for this version: a labelled jar comes back, and the caller
        // skips it by its label without downloading
        let other = mod_asset(&release, "1.21.10", "fabric").unwrap();
        assert!(!same_series(other.mc_version.as_deref().unwrap(), "1.21.10"));
    }

    #[test]
    fn the_26_1_jar_accepts_every_26_1_patch() {
        assert!(version_matches(">=26.1 <26.2", "26.1"));
        assert!(version_matches(">=26.1 <26.2", "26.1.2"));
        assert!(!version_matches(">=26.1 <26.2", "26.2"));
        assert!(version_matches("~1.21.11", "1.21.11"));
    }

    #[test]
    fn the_name_says_minecraft_version_and_loader() {
        assert_eq!(parse_name("spaceclient-1.57.0-mc26.3.jar"), (Some("26.3".into()), "fabric"));
        assert_eq!(parse_name("spaceclient-1.57.0-mc26.3-neoforge.jar"), (Some("26.3".into()), "neoforge"));
        assert_eq!(parse_name("spaceclient-1.57.0-mc1.21.11-forge.jar"), (Some("1.21.11".into()), "forge"));
        assert_eq!(parse_name("spaceclient-1.46.0.jar"), (None, "fabric"));
    }

    #[test]
    fn each_loader_gets_its_own_build() {
        let release = serde_json::json!({
            "tag_name": "v.1.57.0",
            "assets": [
                { "name": "spaceclient-1.57.0-mc26.3.jar", "browser_download_url": "https://x/fabric" },
                { "name": "spaceclient-1.57.0-mc26.3-forge.jar", "browser_download_url": "https://x/forge" },
                { "name": "spaceclient-1.57.0-mc26.3-neoforge.jar", "browser_download_url": "https://x/neoforge" },
                { "name": "spaceclient-1.57.0-mc26.2-neoforge.jar", "browser_download_url": "https://x/neoforge262" }
            ]
        });
        assert_eq!(mod_asset(&release, "26.3", "fabric").unwrap().url, "https://x/fabric");
        assert_eq!(mod_asset(&release, "26.3", "neoforge").unwrap().url, "https://x/neoforge");
        assert_eq!(mod_asset(&release, "26.3", "forge").unwrap().url, "https://x/forge");
        assert_eq!(mod_asset(&release, "26.2", "neoforge").unwrap().url, "https://x/neoforge262");
        assert_eq!(flavour("quilt"), Some("fabric"));
        assert_eq!(flavour("vanilla"), None);
    }

    #[test]
    fn an_old_release_has_nothing_for_neoforge() {
        let release = serde_json::json!({
            "tag_name": "v.1.56.3",
            "assets": [{ "name": "spaceclient-1.56.3-mc26.3.jar", "browser_download_url": "https://x/fabric" }]
        });
        // Something comes back, but labelled as the Fabric build it is, so the
        // install loop skips it rather than putting it into a NeoForge folder
        assert_eq!(mod_asset(&release, "26.3", "neoforge").unwrap().flavour, "fabric");
    }

    #[test]
    fn maven_ranges_read_like_neoforge() {
        assert!(maven_range_matches("[26.3,26.4)", "26.3"));
        assert!(maven_range_matches("[26.3,26.4)", "26.3.1"));
        assert!(!maven_range_matches("[26.3,26.4)", "26.4"));
        assert!(maven_range_matches("[26.1,26.2)", "26.1.2"));
        assert!(!maven_range_matches("[26.1,26.2)", "26.2"));
        assert!(maven_range_matches("[1.21.11,1.21.12)", "1.21.11"));
        assert!(maven_range_matches("[61,)", "66.0.10"));
        assert!(!maven_range_matches("(26.3,26.4]", "26.3"));
    }

    #[test]
    fn toml_metadata_is_read() {
        let toml = r#"modLoader="javafml"
loaderVersion="[1,)"
[[mods]]
modId="spaceclient"
version="1.57.0"
[[dependencies.spaceclient]]
modId="neoforge"
versionRange="[26.3,26.4)"
[[dependencies.spaceclient]]
modId="minecraft"
type="required"
versionRange="[26.3,26.4)"
"#;
        assert_eq!(toml_mod_version(toml).as_deref(), Some("1.57.0"));
        assert_eq!(toml_dependency_range(toml, "minecraft").as_deref(), Some("[26.3,26.4)"));
    }
}
