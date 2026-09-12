use crate::launcher::auth;
use serde::Serialize;

const PROFILE_URL: &str = "https://api.minecraftservices.com/minecraft/profile";
const SKINS_URL: &str = "https://api.minecraftservices.com/minecraft/profile/skins";
const ACTIVE_CAPE_URL: &str = "https://api.minecraftservices.com/minecraft/profile/capes/active";

#[derive(Debug, Serialize, Clone)]
pub struct CapeInfo {
    pub id: String,
    pub alias: String,
    pub url: String,
    pub active: bool,
}

#[derive(Debug, Serialize, Clone)]
pub struct SkinProfile {
    pub username: String,
    pub uuid: String,
    /// URL of the currently applied skin texture.
    pub skin_url: String,
    /// "CLASSIC" or "SLIM"
    pub variant: String,
    pub capes: Vec<CapeInfo>,
}

fn http() -> anyhow::Result<reqwest::Client> {
    Ok(reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .build()?)
}

fn parse_profile(json: &serde_json::Value) -> SkinProfile {
    let raw_uuid = json.get("id").and_then(|v| v.as_str()).unwrap_or_default();

    // The active skin is the one with state ACTIVE; there is usually one.
    let active_skin = json
        .get("skins")
        .and_then(|v| v.as_array())
        .and_then(|arr| {
            arr.iter()
                .find(|s| s.get("state").and_then(|v| v.as_str()) == Some("ACTIVE"))
                .or_else(|| arr.first())
        });

    let skin_url = active_skin
        .and_then(|s| s.get("url"))
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string();

    let variant = active_skin
        .and_then(|s| s.get("variant"))
        .and_then(|v| v.as_str())
        .unwrap_or("CLASSIC")
        .to_string();

    let mut capes = Vec::new();
    if let Some(arr) = json.get("capes").and_then(|v| v.as_array()) {
        for cape in arr {
            capes.push(CapeInfo {
                id: cape.get("id").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
                alias: cape
                    .get("alias")
                    .and_then(|v| v.as_str())
                    .unwrap_or("Cape")
                    .to_string(),
                url: cape.get("url").and_then(|v| v.as_str()).unwrap_or_default().to_string(),
                active: cape.get("state").and_then(|v| v.as_str()) == Some("ACTIVE"),
            });
        }
    }

    SkinProfile {
        username: json
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        uuid: raw_uuid.to_string(),
        skin_url,
        variant,
        capes,
    }
}

/// Reads the signed-in account's skin and the capes it owns.
pub async fn get_profile() -> anyhow::Result<SkinProfile> {
    let account = auth::current_account().await?;
    if account.offline {
        anyhow::bail!("Skins need a Microsoft account. Offline profiles cannot change them.");
    }

    let json: serde_json::Value = http()?
        .get(PROFILE_URL)
        .bearer_auth(&account.access_token)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    Ok(parse_profile(&json))
}

/// Uploads a PNG skin file. `variant` is "classic" or "slim".
pub async fn upload_skin(path: String, variant: String) -> anyhow::Result<SkinProfile> {
    let account = auth::current_account().await?;
    if account.offline {
        anyhow::bail!("Skins need a Microsoft account. Offline profiles cannot change them.");
    }

    let variant = if variant.eq_ignore_ascii_case("slim") { "slim" } else { "classic" };

    let bytes = tokio::fs::read(&path).await?;

    // Mojang rejects anything that is not a 64x64 (or legacy 64x32) PNG, so
    // check the signature here to give a clearer error than a bare HTTP 400.
    if bytes.len() < 8 || &bytes[0..8] != b"\x89PNG\r\n\x1a\n" {
        anyhow::bail!("That file is not a PNG image.");
    }

    let file_name = std::path::Path::new(&path)
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "skin.png".to_string());

    let part = reqwest::multipart::Part::bytes(bytes)
        .file_name(file_name)
        .mime_str("image/png")?;

    let form = reqwest::multipart::Form::new()
        .text("variant", variant)
        .part("file", part);

    let response = http()?
        .post(SKINS_URL)
        .bearer_auth(&account.access_token)
        .multipart(form)
        .send()
        .await?;

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        anyhow::bail!("Mojang rejected the skin ({}): {}", status, body);
    }

    let json: serde_json::Value = response.json().await?;
    Ok(parse_profile(&json))
}

/// Switches the variant without changing the image, by re-uploading the
/// current skin URL under the other model.
pub async fn set_variant(variant: String) -> anyhow::Result<SkinProfile> {
    let account = auth::current_account().await?;
    if account.offline {
        anyhow::bail!("Skins need a Microsoft account.");
    }

    let current = get_profile().await?;
    if current.skin_url.is_empty() {
        anyhow::bail!("There is no skin to switch. Upload one first.");
    }

    let variant = if variant.eq_ignore_ascii_case("slim") { "slim" } else { "classic" };

    let response = http()?
        .post(SKINS_URL)
        .bearer_auth(&account.access_token)
        .json(&serde_json::json!({
            "variant": variant,
            "url": current.skin_url
        }))
        .send()
        .await?;

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        anyhow::bail!("Could not switch the model ({}): {}", status, body);
    }

    get_profile().await
}

/// Sets the active cape, or clears it when `cape_id` is empty.
pub async fn set_cape(cape_id: String) -> anyhow::Result<SkinProfile> {
    let account = auth::current_account().await?;
    if account.offline {
        anyhow::bail!("Capes need a Microsoft account.");
    }

    let client = http()?;
    let response = if cape_id.trim().is_empty() {
        client
            .delete(ACTIVE_CAPE_URL)
            .bearer_auth(&account.access_token)
            .send()
            .await?
    } else {
        client
            .put(ACTIVE_CAPE_URL)
            .bearer_auth(&account.access_token)
            .json(&serde_json::json!({ "capeId": cape_id }))
            .send()
            .await?
    };

    if !response.status().is_success() {
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        anyhow::bail!("Could not change the cape ({}): {}", status, body);
    }

    get_profile().await
}
