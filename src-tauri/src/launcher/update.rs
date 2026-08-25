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

    // Written beside the temp directory rather than into the install folder,
    // which the running launcher holds open on Windows.
    let target = std::env::temp_dir().join(name);
    std::fs::write(&target, &bytes)?;

    Ok(target.to_string_lossy().to_string())
}
