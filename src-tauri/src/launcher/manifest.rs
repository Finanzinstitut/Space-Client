use serde::{Deserialize, Serialize};

const MANIFEST_URL: &str = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct VersionEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String, // "release" | "snapshot" | "old_beta" | "old_alpha"
    pub url: String,
    #[serde(rename = "releaseTime")]
    pub release_time: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct VersionManifest {
    pub latest: LatestVersions,
    pub versions: Vec<VersionEntry>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct LatestVersions {
    pub release: String,
    pub snapshot: String,
}

pub async fn fetch_version_manifest() -> anyhow::Result<VersionManifest> {
    let client = reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .build()?;
    let resp = client.get(MANIFEST_URL).send().await?.error_for_status()?;
    let manifest: VersionManifest = resp.json().await?;
    Ok(manifest)
}

/// Fetches the full per-version JSON (libraries, downloads, main class, arguments...)
pub async fn fetch_version_details(url: &str) -> anyhow::Result<serde_json::Value> {
    let client = reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .build()?;
    let resp = client.get(url).send().await?.error_for_status()?;
    let details: serde_json::Value = resp.json().await?;
    Ok(details)
}
