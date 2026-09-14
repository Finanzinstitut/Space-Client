//! Writing the rank list straight to the repository, so nobody has to carry a
//! file between two windows.
//!
//! The mod reads `badges.json` from the default branch. Everything that wants
//! to change who wears which mark therefore has to end up as a commit on that
//! branch, and the only two ways to make one are a person with a browser or a
//! token. This is the token.
//!
//! Hidden unless one is configured. The launcher goes to every player and only
//! one person has any business writing this file, so the section does not
//! exist until there is a credential that could make the call succeed.

use serde::{Deserialize, Serialize};

/// Where the list lives. The same path the mod reads.
const REPO: &str = "Finanzinstitut/Space-Client-Mod";
const PATH: &str = "src/main/resources/assets/spaceclient/badges.json";
const BRANCH: &str = "main";

/// One person and the mark they wear.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BadgeEntry {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub uuid: String,
    pub rank: String,
}

/// The list as it stands, plus what a write has to quote back.
#[derive(Debug, Serialize)]
pub struct BadgeList {
    pub entries: Vec<BadgeEntry>,
    /// The blob's sha. GitHub refuses a write that does not name the version
    /// it is replacing, which is exactly the guard wanted here: two people
    /// editing at once get a refusal instead of one silently losing.
    pub sha: String,
}

#[derive(Deserialize)]
struct ContentsResponse {
    content: String,
    sha: String,
}

#[derive(Deserialize)]
struct FileBody {
    #[serde(default)]
    badges: Vec<BadgeEntry>,
}

fn client() -> anyhow::Result<reqwest::Client> {
    Ok(reqwest::Client::builder()
        .user_agent("SpaceClient/0.1")
        .build()?)
}

fn url() -> String {
    format!("https://api.github.com/repos/{}/contents/{}", REPO, PATH)
}

/// The comment block the file carries, so a write from here does not strip the
/// explanation a reader of the repository would otherwise find.
fn comment() -> Vec<String> {
    vec![
        "Who wears which mark in front of their name.".into(),
        "".into(),
        "This copy is the one inside the jar and is only the starting point. The".into(),
        "client refreshes it from the same file on the repository's main branch, so".into(),
        "changing who has what needs a commit rather than a release.".into(),
        "".into(),
        "rank is one of: standard, dev, owner, vip.".into(),
        "".into(),
        "uuid wins where it is given, because a name can be changed by anybody and".into(),
        "a uuid cannot. An entry with only a name still works and is matched case".into(),
        "insensitively - it is the convenient form, not the safe one.".into(),
    ]
}

/// Reads the list off the branch.
pub async fn load(token: &str) -> anyhow::Result<BadgeList> {
    if token.trim().is_empty() {
        anyhow::bail!("No GitHub token is set.");
    }

    let response = client()?
        .get(url())
        .query(&[("ref", BRANCH)])
        .header("Accept", "application/vnd.github+json")
        .bearer_auth(token.trim())
        .send()
        .await?;

    let status = response.status();
    if !status.is_success() {
        anyhow::bail!("{}", explain(status.as_u16()));
    }

    let body: ContentsResponse = response.json().await?;

    // The API returns base64 with newlines in it, which the decoder rejects.
    let cleaned: String = body.content.chars().filter(|c| !c.is_whitespace()).collect();
    let bytes = base64_decode(&cleaned)?;
    let text = String::from_utf8(bytes)?;

    let parsed: FileBody = serde_json::from_str(&text)?;

    Ok(BadgeList {
        entries: parsed.badges,
        sha: body.sha,
    })
}

/// Writes the list back as one commit.
pub async fn save(token: &str, entries: Vec<BadgeEntry>, sha: &str) -> anyhow::Result<String> {
    if token.trim().is_empty() {
        anyhow::bail!("No GitHub token is set.");
    }

    let mut document = serde_json::Map::new();
    document.insert("_comment".into(), serde_json::to_value(comment())?);
    document.insert("badges".into(), serde_json::to_value(&entries)?);

    let mut text = serde_json::to_string_pretty(&document)?;
    text.push('\n');

    let message = format!(
        "Raenge: {} Eintrag{}",
        entries.len(),
        if entries.len() == 1 { "" } else { "e" }
    );

    let mut payload = serde_json::Map::new();
    payload.insert("message".into(), serde_json::Value::String(message));
    payload.insert(
        "content".into(),
        serde_json::Value::String(base64_encode(text.as_bytes())),
    );
    payload.insert("branch".into(), serde_json::Value::String(BRANCH.into()));
    if !sha.is_empty() {
        payload.insert("sha".into(), serde_json::Value::String(sha.into()));
    }

    let response = client()?
        .put(url())
        .header("Accept", "application/vnd.github+json")
        .bearer_auth(token.trim())
        .json(&payload)
        .send()
        .await?;

    let status = response.status();
    if !status.is_success() {
        anyhow::bail!("{}", explain(status.as_u16()));
    }

    let body: serde_json::Value = response.json().await?;
    Ok(body
        .get("content")
        .and_then(|c| c.get("sha"))
        .and_then(|s| s.as_str())
        .unwrap_or("")
        .to_string())
}

/// Turns a status code into something worth reading.
fn explain(code: u16) -> String {
    match code {
        401 => "GitHub rejected the token. Check that it is still valid.".into(),
        403 => "The token is not allowed to write this file. It needs Contents: read and write on the mod repository.".into(),
        404 => "The file or the repository was not found - a token without access to it looks the same as a missing repository.".into(),
        409 => "Somebody changed the list in between. Reload it and make the change again.".into(),
        422 => "GitHub refused the write. The list may have been changed elsewhere since it was loaded.".into(),
        other => format!("GitHub answered with {}.", other),
    }
}

// ---------------------------------------------------------------------------
// base64
//
// By hand rather than by crate: this is two small tables and the one place in
// the launcher that needs them, against another dependency to audit and keep.
// ---------------------------------------------------------------------------

const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

fn base64_encode(input: &[u8]) -> String {
    let mut out = String::with_capacity((input.len() + 2) / 3 * 4);

    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let triple = (b0 << 16) | (b1 << 8) | b2;

        out.push(ALPHABET[((triple >> 18) & 0x3F) as usize] as char);
        out.push(ALPHABET[((triple >> 12) & 0x3F) as usize] as char);
        out.push(if chunk.len() > 1 {
            ALPHABET[((triple >> 6) & 0x3F) as usize] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            ALPHABET[(triple & 0x3F) as usize] as char
        } else {
            '='
        });
    }

    out
}

fn base64_decode(input: &str) -> anyhow::Result<Vec<u8>> {
    let value = |c: u8| -> anyhow::Result<u32> {
        Ok(match c {
            b'A'..=b'Z' => (c - b'A') as u32,
            b'a'..=b'z' => (c - b'a') as u32 + 26,
            b'0'..=b'9' => (c - b'0') as u32 + 52,
            b'+' => 62,
            b'/' => 63,
            _ => anyhow::bail!("The response was not readable base64."),
        })
    };

    let bytes: Vec<u8> = input.bytes().filter(|b| *b != b'=').collect();
    let mut out = Vec::with_capacity(bytes.len() / 4 * 3);

    for chunk in bytes.chunks(4) {
        let mut packed: u32 = 0;
        for (i, b) in chunk.iter().enumerate() {
            packed |= value(*b)? << (18 - 6 * i);
        }
        out.push((packed >> 16) as u8);
        if chunk.len() > 2 {
            out.push((packed >> 8) as u8);
        }
        if chunk.len() > 3 {
            out.push(packed as u8);
        }
    }

    Ok(out)
}
