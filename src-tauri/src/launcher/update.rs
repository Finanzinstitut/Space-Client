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
