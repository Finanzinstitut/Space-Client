use crate::launcher::config::config_dir;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

/// ===================================================================
/// IMPORTANT - YOU MUST SET THIS BEFORE LOGIN WILL WORK
/// ===================================================================
/// Mojang requires every launcher to use its own Azure application.
/// Register one for free at https://portal.azure.com:
///   1. Azure Active Directory -> App registrations -> New registration
///   2. Supported account types: "Personal Microsoft accounts only"
///   3. No redirect URI needed for the device code flow
///   4. Authentication -> "Allow public client flows" -> YES
///   5. Copy the "Application (client) ID" and paste it below
///
/// Without this, Microsoft will reject the login with "unauthorized_client".
pub const AZURE_CLIENT_ID: &str = "PUT-YOUR-AZURE-CLIENT-ID-HERE";

const DEVICE_CODE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
const TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
const XBL_AUTH_URL: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS_AUTH_URL: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
const MC_LOGIN_URL: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const MC_PROFILE_URL: &str = "https://api.minecraftservices.com/minecraft/profile";
const SCOPE: &str = "XboxLive.signin offline_access";

fn account_file() -> PathBuf {
    config_dir().join("account.json")
}

/// The signed-in Minecraft account. The refresh token lets us log back in
/// silently on the next launcher start.
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Account {
    pub username: String,
    pub uuid: String,
    pub access_token: String,
    pub refresh_token: String,
    /// Unix seconds when the Minecraft token stops being valid.
    pub expires_at: i64,
}

impl Account {
    pub fn load() -> Option<Account> {
        let data = fs::read_to_string(account_file()).ok()?;
        serde_json::from_str(&data).ok()
    }

    pub fn save(&self) -> anyhow::Result<()> {
        fs::write(account_file(), serde_json::to_string_pretty(self)?)?;
        Ok(())
    }

    pub fn clear() -> anyhow::Result<()> {
        let f = account_file();
        if f.exists() {
            fs::remove_file(f)?;
        }
        Ok(())
    }

    pub fn is_expired(&self) -> bool {
        now_seconds() >= self.expires_at - 60
    }
}

/// Public-facing account info (never exposes tokens to the frontend).
#[derive(Debug, Serialize, Clone)]
pub struct AccountInfo {
    pub username: String,
    pub uuid: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct DeviceCodeInfo {
    pub device_code: String,
    pub user_code: String,
    pub verification_uri: String,
    pub interval: u64,
    pub expires_in: u64,
}

fn now_seconds() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn http() -> anyhow::Result<reqwest::Client> {
    Ok(reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .build()?)
}

fn check_client_id() -> anyhow::Result<()> {
    if AZURE_CLIENT_ID.starts_with("PUT-YOUR") || AZURE_CLIENT_ID.trim().is_empty() {
        anyhow::bail!(
            "No Azure client ID configured. Open src-tauri/src/launcher/auth.rs and set AZURE_CLIENT_ID - see the comment there for the 5 steps."
        );
    }
    Ok(())
}

/// Step 1: ask Microsoft for a device code the user types into their browser.
pub async fn start_device_login() -> anyhow::Result<DeviceCodeInfo> {
    check_client_id()?;
    let client = http()?;
    let resp: serde_json::Value = client
        .post(DEVICE_CODE_URL)
        .form(&[("client_id", AZURE_CLIENT_ID), ("scope", SCOPE)])
        .send()
        .await?
        .json()
        .await?;

    if let Some(err) = resp.get("error").and_then(|v| v.as_str()) {
        let desc = resp
            .get("error_description")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        anyhow::bail!("Microsoft rejected the request ({}): {}", err, desc);
    }

    Ok(DeviceCodeInfo {
        device_code: resp
            .get("device_code")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        user_code: resp
            .get("user_code")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string(),
        verification_uri: resp
            .get("verification_uri")
            .and_then(|v| v.as_str())
            .unwrap_or("https://www.microsoft.com/link")
            .to_string(),
        interval: resp.get("interval").and_then(|v| v.as_u64()).unwrap_or(5),
        expires_in: resp.get("expires_in").and_then(|v| v.as_u64()).unwrap_or(900),
    })
}

/// Step 2: poll until the user finished signing in, then run the full
/// Microsoft -> Xbox Live -> XSTS -> Minecraft token chain.
pub async fn poll_device_login(info: DeviceCodeInfo) -> anyhow::Result<Account> {
    check_client_id()?;
    let client = http()?;
    let deadline = now_seconds() + info.expires_in as i64;
    let mut interval = info.interval.max(1);

    loop {
        if now_seconds() > deadline {
            anyhow::bail!("Login timed out. Please try again.");
        }
        tokio::time::sleep(std::time::Duration::from_secs(interval)).await;

        let resp: serde_json::Value = client
            .post(TOKEN_URL)
            .form(&[
                ("client_id", AZURE_CLIENT_ID),
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("device_code", &info.device_code),
            ])
            .send()
            .await?
            .json()
            .await?;

        if let Some(err) = resp.get("error").and_then(|v| v.as_str()) {
            match err {
                "authorization_pending" => continue,
                "slow_down" => {
                    interval += 5;
                    continue;
                }
                "expired_token" => anyhow::bail!("The login code expired. Please try again."),
                "authorization_declined" | "access_denied" => {
                    anyhow::bail!("Login was cancelled in the browser.")
                }
                other => {
                    let desc = resp
                        .get("error_description")
                        .and_then(|v| v.as_str())
                        .unwrap_or("");
                    anyhow::bail!("Microsoft login failed ({}): {}", other, desc);
                }
            }
        }

        let ms_access = resp
            .get("access_token")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Microsoft returned no access token"))?
            .to_string();
        let refresh = resp
            .get("refresh_token")
            .and_then(|v| v.as_str())
            .unwrap_or_default()
            .to_string();

        return finish_minecraft_auth(&ms_access, refresh).await;
    }
}

/// Silent re-login using the stored refresh token.
pub async fn refresh_account(account: &Account) -> anyhow::Result<Account> {
    check_client_id()?;
    if account.refresh_token.is_empty() {
        anyhow::bail!("No refresh token stored - please sign in again.");
    }
    let client = http()?;
    let resp: serde_json::Value = client
        .post(TOKEN_URL)
        .form(&[
            ("client_id", AZURE_CLIENT_ID),
            ("grant_type", "refresh_token"),
            ("refresh_token", &account.refresh_token),
            ("scope", SCOPE),
        ])
        .send()
        .await?
        .json()
        .await?;

    if let Some(err) = resp.get("error").and_then(|v| v.as_str()) {
        anyhow::bail!("Session could not be refreshed ({}) - please sign in again.", err);
    }

    let ms_access = resp
        .get("access_token")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow::anyhow!("No access token in refresh response"))?
        .to_string();
    let new_refresh = resp
        .get("refresh_token")
        .and_then(|v| v.as_str())
        .unwrap_or(&account.refresh_token)
        .to_string();

    finish_minecraft_auth(&ms_access, new_refresh).await
}

/// Xbox Live -> XSTS -> Minecraft services -> profile.
async fn finish_minecraft_auth(ms_access_token: &str, refresh_token: String) -> anyhow::Result<Account> {
    let client = http()?;

    // --- Xbox Live ---
    let xbl: serde_json::Value = client
        .post(XBL_AUTH_URL)
        .header("Accept", "application/json")
        .json(&serde_json::json!({
            "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": format!("d={}", ms_access_token)
            },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT"
        }))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let xbl_token = xbl
        .get("Token")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow::anyhow!("Xbox Live returned no token"))?
        .to_string();

    // --- XSTS ---
    let xsts_resp = client
        .post(XSTS_AUTH_URL)
        .header("Accept", "application/json")
        .json(&serde_json::json!({
            "Properties": {
                "SandboxId": "RETAIL",
                "UserTokens": [xbl_token]
            },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT"
        }))
        .send()
        .await?;

    let status = xsts_resp.status();
    let xsts: serde_json::Value = xsts_resp.json().await?;

    if !status.is_success() {
        // Mojang's well-known XSTS error codes, translated into something readable
        let xerr = xsts.get("XErr").and_then(|v| v.as_i64()).unwrap_or(0);
        let msg = match xerr {
            2148916233 => "This Microsoft account has no Xbox profile. Create one at xbox.com and try again.",
            2148916235 => "Xbox Live is not available in the country this account is registered to.",
            2148916236 | 2148916237 => "This account needs adult verification.",
            2148916238 => "This is a child account. It must be added to a family by an adult first.",
            _ => "Xbox authentication failed.",
        };
        anyhow::bail!("{}", msg);
    }

    let xsts_token = xsts
        .get("Token")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow::anyhow!("XSTS returned no token"))?
        .to_string();
    let uhs = xsts
        .pointer("/DisplayClaims/xui/0/uhs")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow::anyhow!("XSTS returned no user hash"))?
        .to_string();

    // --- Minecraft services ---
    let mc: serde_json::Value = client
        .post(MC_LOGIN_URL)
        .header("Accept", "application/json")
        .json(&serde_json::json!({
            "identityToken": format!("XBL3.0 x={};{}", uhs, xsts_token)
        }))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let mc_token = mc
        .get("access_token")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow::anyhow!("Minecraft services returned no token"))?
        .to_string();
    let expires_in = mc.get("expires_in").and_then(|v| v.as_i64()).unwrap_or(86400);

    // --- Profile (also proves the account actually owns the game) ---
    let profile_resp = client
        .get(MC_PROFILE_URL)
        .bearer_auth(&mc_token)
        .send()
        .await?;

    if profile_resp.status() == reqwest::StatusCode::NOT_FOUND {
        anyhow::bail!("This Microsoft account does not own Minecraft: Java Edition.");
    }
    let profile: serde_json::Value = profile_resp.error_for_status()?.json().await?;

    let name = profile
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or("Player")
        .to_string();
    let raw_uuid = profile.get("id").and_then(|v| v.as_str()).unwrap_or_default();
    let uuid = format_uuid(raw_uuid);

    let account = Account {
        username: name,
        uuid,
        access_token: mc_token,
        refresh_token,
        expires_at: now_seconds() + expires_in,
    };
    account.save()?;
    Ok(account)
}

/// Mojang returns UUIDs without dashes; the game wants them dashed.
fn format_uuid(raw: &str) -> String {
    if raw.len() != 32 {
        return raw.to_string();
    }
    format!(
        "{}-{}-{}-{}-{}",
        &raw[0..8],
        &raw[8..12],
        &raw[12..16],
        &raw[16..20],
        &raw[20..32]
    )
}

/// Returns a valid account, refreshing the token if needed.
pub async fn current_account() -> anyhow::Result<Account> {
    let account = Account::load().ok_or_else(|| anyhow::anyhow!("Not signed in."))?;
    if account.is_expired() {
        return refresh_account(&account).await;
    }
    Ok(account)
}
