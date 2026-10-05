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

/// The mod is a Fabric mod; Quilt loads it too. Forge and NeoForge cannot.
pub fn supports_loader(loader: &str) -> bool {
    matches!(loader, "fabric" | "quilt")
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
    let installed = installed_version(&dest);

    // Older releases name their jar without the Minecraft version, so the only
    // way to know what they are for is to look inside. Capped, so a long run of
    // releases for some other version cannot turn into a long run of downloads.
    let mut unlabelled_checked = 0;
    let mut skipped: Option<String> = None;

    for release in &releases {
        let Some(asset) = mod_asset(release) else { continue };
        let version = release_version(release);

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
}

fn mod_asset(release: &serde_json::Value) -> Option<ModAsset> {
    let assets = release.get("assets")?.as_array()?;
    let asset = assets.iter().find(|a| {
        let name = a.get("name").and_then(|n| n.as_str()).unwrap_or("");
        // Skipping the sources and dev jars Loom also produces
        name.ends_with(".jar")
            && !name.contains("sources")
            && !name.contains("dev")
            && !name.contains("shadow")
    })?;
    let name = asset.get("name")?.as_str()?;
    Some(ModAsset {
        url: asset.get("browser_download_url")?.as_str()?.to_string(),
        mc_version: mc_from_name(name),
    })
}

/// "spaceclient-1.47.0-mc26.3.jar" -> "26.3".
fn mc_from_name(name: &str) -> Option<String> {
    let stem = name.strip_suffix(".jar")?;
    let at = stem.rfind("-mc")?;
    let mc = &stem[at + 3..];
    if !mc.is_empty() && mc.chars().all(|c| c.is_ascii_digit() || c == '.') {
        Some(mc.to_string())
    } else {
        None
    }
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
    let file = std::fs::File::open(path).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let mut entry = archive.by_name("fabric.mod.json").ok()?;

    let mut text = String::new();
    use std::io::Read;
    entry.read_to_string(&mut text).ok()?;

    let json: serde_json::Value = serde_json::from_str(&text).ok()?;
    json.get("version")?.as_str().map(String::from)
}

/// Whether the jar's own declared dependencies match this instance.
///
/// The two things the user asked for, taken from the only place that cannot
/// disagree with reality: what the loader itself will read. A mod built for a
/// different Minecraft version does not fail politely - it crashes on a missing
/// class halfway through loading a world, which is a far worse way to find out.
fn jar_fits(bytes: &[u8], instance: &Instance) -> Fit {
    let reader = std::io::Cursor::new(bytes);

    let mut archive = match zip::ZipArchive::new(reader) {
        Ok(a) => a,
        Err(_) => return Fit::No("the download is not a readable jar".into()),
    };

    let mut text = String::new();
    {
        let mut entry = match archive.by_name("fabric.mod.json") {
            Ok(e) => e,
            Err(_) => return Fit::No("the jar carries no fabric.mod.json".into()),
        };
        use std::io::Read;
        if entry.read_to_string(&mut text).is_err() {
            return Fit::No("the jar's fabric.mod.json could not be read".into());
        }
    }

    let json: serde_json::Value = match serde_json::from_str(&text) {
        Ok(v) => v,
        Err(_) => return Fit::No("the jar's fabric.mod.json is malformed".into()),
    };

    // Loader first: a Forge instance has no business with this file at all,
    // and the caller has already checked, so this is the belt to that braces.
    if !supports_loader(&instance.loader) {
        return Fit::No(format!("{} cannot load a Fabric mod", instance.loader));
    }

    let depends = json.get("depends");

    let wanted_mc = depends
        .and_then(|d| d.get("minecraft"))
        .and_then(|v| v.as_str())
        .unwrap_or("*");

    if !version_matches(wanted_mc, &instance.mc_version) {
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
                "The Space Client mod needs Fabric or Quilt, so it was skipped on this {} instance.",
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
        let asset = mod_asset(&release).unwrap();
        assert_eq!(asset.url, "https://x/mod");
        assert_eq!(asset.mc_version.as_deref(), Some("26.3"));
    }
}
