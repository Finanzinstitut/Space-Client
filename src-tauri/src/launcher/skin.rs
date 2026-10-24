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

/// One client for every skin request, so switching skins in a row reuses the
/// open connection to Mojang instead of starting a new one each time. With
/// timeouts: without them a stalled request held the screen indefinitely.
fn http() -> anyhow::Result<reqwest::Client> {
    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    if let Some(client) = CLIENT.get() {
        return Ok(client.clone());
    }
    let client = reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .connect_timeout(std::time::Duration::from_secs(15))
        .timeout(std::time::Duration::from_secs(60))
        .build()?;
    Ok(CLIENT.get_or_init(|| client).clone())
}

/// Sends a request, once more if the connection itself failed - a dropped or
/// reset connection is what "error sending request" means, and it is usually
/// gone on the second try. An answer from Mojang, even an error, is not
/// retried.
async fn send(build: impl Fn() -> reqwest::RequestBuilder) -> anyhow::Result<reqwest::Response> {
    match build().send().await {
        Ok(response) => Ok(response),
        Err(first) if first.is_connect() || first.is_timeout() || first.is_request() => {
            tokio::time::sleep(std::time::Duration::from_millis(800)).await;
            build().send().await.map_err(unreachable_error)
        }
        Err(other) => Err(unreachable_error(other)),
    }
}

fn unreachable_error(e: reqwest::Error) -> anyhow::Error {
    if e.is_timeout() {
        anyhow::anyhow!("Mojang's skin server did not answer in time. Try again in a moment.")
    } else {
        anyhow::anyhow!(
            "Mojang's skin server could not be reached. Check the internet connection and try again. ({})",
            std::error::Error::source(&e).map(|s| s.to_string()).unwrap_or_else(|| e.to_string())
        )
    }
}

/// Mojang's answer to a change, read as the profile it returns - or as an
/// error that says what went wrong.
async fn profile_from(response: reqwest::Response, what: &str) -> anyhow::Result<SkinProfile> {
    let status = response.status();
    if status == reqwest::StatusCode::TOO_MANY_REQUESTS {
        anyhow::bail!("Mojang only allows a few skin changes per minute. Wait a moment, then try again.");
    }
    if status == reqwest::StatusCode::UNAUTHORIZED {
        anyhow::bail!("The sign-in has expired. Sign in again under Accounts.");
    }
    if !status.is_success() {
        let body = response.text().await.unwrap_or_default();
        anyhow::bail!("{} ({}): {}", what, status, body);
    }
    let json: serde_json::Value = response.json().await?;
    Ok(parse_profile(&json))
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

    let client = http()?;
    let response = send(|| client.get(PROFILE_URL).bearer_auth(&account.access_token)).await?;
    profile_from(response, "Could not read the skin").await
}

/// Uploads a PNG skin file. `variant` is "classic" or "slim".
pub async fn upload_skin(path: String, variant: String) -> anyhow::Result<SkinProfile> {
    let bytes = tokio::fs::read(&path).await?;
    let file_name = std::path::Path::new(&path)
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "skin.png".to_string());
    upload_bytes(bytes, file_name, &variant).await
}

/// Uploads a skin image as it is in memory.
pub async fn upload_bytes(bytes: Vec<u8>, file_name: String, variant: &str) -> anyhow::Result<SkinProfile> {
    let account = auth::current_account().await?;
    if account.offline {
        anyhow::bail!("Skins need a Microsoft account. Offline profiles cannot change them.");
    }

    let variant = if variant.eq_ignore_ascii_case("slim") { "slim" } else { "classic" };

    // Mojang rejects anything that is not a 64x64 (or legacy 64x32) PNG, so
    // check the signature here to give a clearer error than a bare HTTP 400.
    if bytes.len() < 8 || &bytes[0..8] != b"\x89PNG\r\n\x1a\n" {
        anyhow::bail!("That file is not a PNG image.");
    }

    let client = http()?;
    let response = send(|| {
        // A form can be sent only once, so each try builds its own
        let part = reqwest::multipart::Part::bytes(bytes.clone())
            .file_name(file_name.clone())
            .mime_str("image/png")
            .expect("image/png is a valid type");
        let form = reqwest::multipart::Form::new().text("variant", variant).part("file", part);
        client.post(SKINS_URL).bearer_auth(&account.access_token).multipart(form)
    })
    .await?;

    profile_from(response, "Mojang rejected the skin").await
}

/// Switches the model of the skin on the account between classic and slim.
///
/// Mojang has no call for only the model, so the skin goes up again with the
/// other one. It used to be sent as the texture's address for Mojang to fetch
/// itself, which often failed with "error sending request"; now the image is
/// fetched here and uploaded like any other skin.
pub async fn set_variant(variant: String) -> anyhow::Result<SkinProfile> {
    let current = get_profile().await?;
    if current.skin_url.is_empty() {
        anyhow::bail!("There is no skin to switch. Upload one first.");
    }
    // Mojang hands the texture out as http; the same file is served over https
    let url = current.skin_url.replacen("http://", "https://", 1);
    let client = http()?;
    let response = send(|| client.get(&url)).await?;
    if !response.status().is_success() {
        anyhow::bail!("Could not fetch the current skin ({}).", response.status());
    }
    let bytes = response.bytes().await?.to_vec();
    upload_bytes(bytes, "skin.png".into(), &variant).await
}

/// Sets the active cape, or clears it when `cape_id` is empty.
pub async fn set_cape(cape_id: String) -> anyhow::Result<SkinProfile> {
    let account = auth::current_account().await?;
    if account.offline {
        anyhow::bail!("Capes need a Microsoft account.");
    }

    let client = http()?;
    let response = if cape_id.trim().is_empty() {
        send(|| client.delete(ACTIVE_CAPE_URL).bearer_auth(&account.access_token)).await?
    } else {
        send(|| {
            client
                .put(ACTIVE_CAPE_URL)
                .bearer_auth(&account.access_token)
                .json(&serde_json::json!({ "capeId": cape_id }))
        })
        .await?
    };

    let status = response.status();
    if !status.is_success() {
        // The same reading of the error as for skins
        return profile_from(response, "Could not change the cape").await;
    }
    get_profile().await
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A connection that drops before answering is tried once more; the
    /// second answer is the one that counts.
    #[tokio::test]
    async fn a_dropped_connection_is_tried_again() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/", listener.local_addr().unwrap());
        tokio::spawn(async move {
            // First: hang up without a word
            let (first, _) = listener.accept().await.unwrap();
            drop(first);
            // Then: a proper answer
            let (mut second, _) = listener.accept().await.unwrap();
            let mut buf = [0u8; 1024];
            let _ = second.read(&mut buf).await;
            second
                .write_all(b"HTTP/1.1 200 OK\r\ncontent-length: 2\r\nconnection: close\r\n\r\nok")
                .await
                .unwrap();
        });
        let client = reqwest::Client::builder().no_proxy().build().unwrap();
        let response = send(|| client.get(&url)).await.unwrap();
        assert_eq!(response.text().await.unwrap(), "ok");
    }

    #[tokio::test]
    async fn too_many_changes_says_so() {
        let response: reqwest::Response =
            tauri::http::Response::builder().status(429).body("").unwrap().into();
        let error = profile_from(response, "x").await.unwrap_err().to_string();
        assert!(error.contains("few skin changes per minute"), "{error}");
    }
}
