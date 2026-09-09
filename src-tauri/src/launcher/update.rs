use serde::Serialize;

/// Where the launcher looks for new releases.
pub const REPO: &str = "Finanzinstitut/Space-Client";
pub const CURRENT_VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Debug, Serialize, Clone)]
pub struct UpdateInfo {
    pub update_available: bool,
    pub current_version: String,
    pub latest_version: String,
    pub release_url: String,
    pub notes: String,
}

/// Compares two dotted version strings numerically ("0.10.0" > "0.9.0").
fn is_newer(latest: &str, current: &str) -> bool {
    let parse = |s: &str| -> Vec<u32> {
        s.trim_start_matches('v')
            .split(['.', '-'])
            .filter_map(|p| p.parse::<u32>().ok())
            .collect()
    };
    let l = parse(latest);
    let c = parse(current);
    for i in 0..l.len().max(c.len()) {
        let lv = l.get(i).copied().unwrap_or(0);
        let cv = c.get(i).copied().unwrap_or(0);
        if lv != cv {
            return lv > cv;
        }
    }
    false
}

/// Asks GitHub for the newest published release.
/// Returns update_available = false on any network error - a failed update
/// check should never block the user from playing.
pub async fn check_for_update() -> UpdateInfo {
    let fallback = UpdateInfo {
        update_available: false,
        current_version: CURRENT_VERSION.to_string(),
        latest_version: CURRENT_VERSION.to_string(),
        release_url: format!("https://github.com/{}/releases", REPO),
        notes: String::new(),
    };

    let url = format!("https://api.github.com/repos/{}/releases/latest", REPO);
    let client = match reqwest::Client::builder().user_agent("SpaceClient/0.1").build() {
        Ok(c) => c,
        Err(_) => return fallback,
    };

    let resp = match client.get(&url).send().await {
        Ok(r) => r,
        Err(_) => return fallback,
    };
    if !resp.status().is_success() {
        return fallback;
    }
    let json: serde_json::Value = match resp.json().await {
        Ok(j) => j,
        Err(_) => return fallback,
    };

    let tag = json
        .get("tag_name")
        .and_then(|v| v.as_str())
        .unwrap_or(CURRENT_VERSION)
        .to_string();
    let html_url = json
        .get("html_url")
        .and_then(|v| v.as_str())
        .unwrap_or(&fallback.release_url)
        .to_string();
    let notes = json
        .get("body")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .chars()
        .take(500)
        .collect::<String>();

    UpdateInfo {
        update_available: is_newer(&tag, CURRENT_VERSION),
        current_version: CURRENT_VERSION.to_string(),
        latest_version: tag.trim_start_matches('v').to_string(),
        release_url: html_url,
        notes,
    }
}

/// Downloads the installer for the newest release and returns where it landed.
///
/// The launcher cannot replace itself while it is running, so this fetches the
/// installer and hands back a path; starting it is the caller's job. That split
/// is deliberate - it keeps the download restartable and lets the user decide
/// when to be interrupted, rather than closing the launcher out from under a
/// running game.
pub async fn download_update() -> anyhow::Result<String> {
    let url = format!("https://api.github.com/repos/{}/releases/latest", REPO);
    let client = reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .build()?;

    let release: serde_json::Value = client.get(&url).send().await?.json().await?;

    let assets = release
        .get("assets")
        .and_then(|a| a.as_array())
        .ok_or_else(|| anyhow::anyhow!("That release lists no files to download"))?;

    // Setup installers only. A release can also carry the plain executable and
    // the updater bundle, and running the wrong one either does nothing useful
    // or leaves the old version in place.
    let asset = assets
        .iter()
        .find(|a| {
            a.get("name")
                .and_then(|n| n.as_str())
                .map(|n| {
                    let lower = n.to_lowercase();
                    lower.ends_with(".exe") && lower.contains("setup")
                })
                .unwrap_or(false)
        })
        .or_else(|| {
            assets.iter().find(|a| {
                a.get("name")
                    .and_then(|n| n.as_str())
                    .map(|n| n.to_lowercase().ends_with(".exe"))
                    .unwrap_or(false)
            })
        })
        .ok_or_else(|| {
            anyhow::anyhow!("That release has no Windows installer attached")
        })?;

    let name = asset
        .get("name")
        .and_then(|n| n.as_str())
        .unwrap_or("SpaceClient-setup.exe");
    let link = asset
        .get("browser_download_url")
        .and_then(|u| u.as_str())
        .ok_or_else(|| anyhow::anyhow!("The installer has no download link"))?;

    let bytes = client.get(link).send().await?.bytes().await?;

    // Checked before it is written, not after. An installer that fails its
    // hash should never exist on disk in the first place - once it is there,
    // something else can start it.
    verify_download(&client, assets, name, &bytes).await?;

    // Written into a directory of our own inside temp rather than temp itself.
    // Temp is world writable, so a file placed there under a predictable name
    // can be swapped between the write and the launch by anything else running
    // on the machine.
    let dir = std::env::temp_dir().join("space-client-update");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir)?;

    let target = dir.join(name);
    std::fs::write(&target, &bytes)?;

    // Remembered here rather than handed to the page and taken back. The
    // caller used to pass the path in, which meant anything able to reach the
    // command could ask the launcher to execute an arbitrary file.
    *staged().lock().unwrap() = Some(target.clone());

    Ok(target.to_string_lossy().to_string())
}

/// The installer this process downloaded and checked, if any.
fn staged() -> &'static std::sync::Mutex<Option<std::path::PathBuf>> {
    static STAGED: std::sync::OnceLock<std::sync::Mutex<Option<std::path::PathBuf>>> =
        std::sync::OnceLock::new();
    STAGED.get_or_init(|| std::sync::Mutex::new(None))
}

/// Checks the download against published hashes.
///
/// GitHub does not sign release assets, so the strongest thing available
/// without running a signing key is a checksum file published alongside them.
/// If one is there the download has to match it; if none is there this says so
/// and continues, because refusing every update until the release process
/// changes would leave people stranded on old builds.
///
/// Real signing is the proper answer and this is not it. It does close the
/// case where a release asset is replaced without the checksum being updated
/// too, which is the difference between one thing to compromise and two.
async fn verify_download(
    client: &reqwest::Client,
    assets: &[serde_json::Value],
    name: &str,
    bytes: &[u8],
) -> anyhow::Result<()> {
    let sums = assets.iter().find(|a| {
        a.get("name")
            .and_then(|n| n.as_str())
            .map(|n| {
                let lower = n.to_lowercase();
                lower == "sha256sums" || lower == "sha256sums.txt"
            })
            .unwrap_or(false)
    });

    let sums = match sums {
        Some(entry) => entry,
        None => {
            eprintln!(
                "space-client: release publishes no SHA256SUMS, installing {} unverified",
                name
            );
            return Ok(());
        }
    };

    let link = sums
        .get("browser_download_url")
        .and_then(|u| u.as_str())
        .ok_or_else(|| anyhow::anyhow!("The checksum file has no download link"))?;

    let text = client.get(link).send().await?.text().await?;

    let expected = text
        .lines()
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let hash = parts.next()?;
            let file = parts.next()?.trim_start_matches('*');
            Some((file.to_string(), hash.to_lowercase()))
        })
        .find(|(file, _)| file == name)
        .map(|(_, hash)| hash);

    let expected = match expected {
        Some(hash) => hash,
        None => anyhow::bail!(
            "The release publishes checksums but none for {} - refusing to install it",
            name
        ),
    };

    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    let actual = hasher
        .finalize()
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect::<String>();

    if actual != expected {
        anyhow::bail!(
            "The downloaded installer does not match its published checksum - not installing it"
        );
    }

    Ok(())
}

/// Starts the installer this process downloaded and verified.
///
/// Done here rather than through the shell plugin because that plugin only
/// opens things that look like URLs - a local path fails its scope check, which
/// is exactly the sort of guard you want on a call that can open anything a web
/// page hands it. Launching a file we just downloaded ourselves is a different
/// matter, and belongs on this side of the boundary.
pub fn run_installer() -> anyhow::Result<()> {
    // No path argument on purpose. It used to take one from the page, which
    // made this a command for running any file on the machine - the download
    // step was only a suggestion. Now it can start exactly one thing: the
    // installer this process fetched and checked in this session.
    let staged = staged().lock().unwrap().clone();

    let file = match staged {
        Some(path) => path,
        None => anyhow::bail!("There is no checked installer to start - download it first"),
    };

    if !file.exists() {
        anyhow::bail!("The downloaded installer is no longer there");
    }

    std::process::Command::new(&file)
        .spawn()
        .map_err(|e| anyhow::anyhow!("Could not start the installer: {}", e))?;

    Ok(())
}
