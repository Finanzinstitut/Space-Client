//! Horizon: Minecraft: Java Edition 26.3 rewritten in Rust
//! (https://github.com/horizon-rust/horizon, MIT).
//!
//! An instance with the loader "horizon" plays that game instead of Java's.
//! The launcher does here what Horizon's own launcher does:
//!
//! 1. **The game.** Horizon's current Nightly for this platform with Space
//!    Client built in. Horizon runs no WebAssembly mods any more: a mod is
//!    compiled into the game. Space Client's CI (Space-Client-Mod,
//!    `.github/workflows/horizon.yml`) builds each Nightly, from the source
//!    that Nightly ships and checked against Horizon's signature, with the
//!    Space Client bundle in it, and publishes Horizon's own archives with
//!    only their game program replaced, under the release tag `horizon`.
//!    Until that release exists, or where it cannot be reached, the plain
//!    Nightly comes from Horizon's GitHub releases instead: its checksum
//!    list must carry a signature by Horizon's release key for the nightly
//!    channel. Either way the archive must match its checksum. Kept once, in
//!    `<install dir>/horizon/game`, for every Horizon instance, and brought
//!    up to date each time a Horizon instance is played.
//! 2. **Its resources.** Horizon ships no Minecraft files: `horizon-prepare`
//!    builds them from the player's own copy - here, the 26.3 the launcher
//!    installs for a Java instance anyway, with the version file as Mojang
//!    serves it, which the preparer checks - and from the 26.3 server jar.
//!    It needs Java 25, the runtime 26.3 itself uses. Done again only when
//!    an update brings a different preparer.
//! 3. **Space Client.** In the game itself (1.); per instance it is turned
//!    on or off in `horizon-mods.txt`. The old WebAssembly mod is removed
//!    from the instance's `horizon-mods/`, where Horizon would only list it
//!    as stopped.
//! 4. **Playing.** The game starts in the instance's own folder with the
//!    signed-in account handed over in a session file it reads once and
//!    deletes, never on the command line.

use crate::launcher::config::LauncherConfig;
use crate::launcher::instance::Instance;
use crate::launcher::auth::Account;
use crate::launcher::progress::{emit_progress, InstallProgress};

use base64::Engine;
use sha1::Sha1;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::process::{Child, Command};
use tauri::AppHandle;

pub const LOADER: &str = "horizon";
/// The Minecraft version Horizon plays. One version: Horizon is 26.3.
pub const MC_VERSION: &str = "26.3";
const REPO: &str = "horizon-rust/horizon";
/// The tag the current Nightly is published under; Horizon ships nothing else.
const CHANNEL: &str = "nightly";
const SUMS: &str = "SHA256SUMS";
const SIGNATURE: &str = "SHA256SUMS.sig";
/// Horizon's release keys (crates/horizon-release-sign/release-keys.txt):
/// one base64 Ed25519 public key each.
const RELEASE_KEYS: [&str; 1] = ["MU4oiIQcCxkZvbwSjjXs7SySCuM6ywRQ3nGaJYZG0gw="];

/// Where Space Client's build of Horizon is published: Space-Client-Mod's
/// release `horizon` (see the module comment).
const MOD_REPO: &str = "Finanzinstitut/Space-Client-Mod";
const MOD_CHANNEL: &str = "horizon";
const MOD_ID: &str = "spaceclient";

#[cfg(all(target_os = "linux", target_arch = "x86_64"))]
const ASSET: Option<&str> = Some("horizon-linux-x86_64.tar.gz");
#[cfg(all(target_os = "macos", target_arch = "aarch64"))]
const ASSET: Option<&str> = Some("horizon-macos-arm64.zip");
#[cfg(all(target_os = "windows", target_arch = "x86_64"))]
const ASSET: Option<&str> = Some("horizon-windows-x64.zip");
#[cfg(not(any(
    all(target_os = "linux", target_arch = "x86_64"),
    all(target_os = "macos", target_arch = "aarch64"),
    all(target_os = "windows", target_arch = "x86_64")
)))]
const ASSET: Option<&str> = None;

#[cfg(windows)]
const EXE: &str = ".exe";
#[cfg(not(windows))]
const EXE: &str = "";

/// Whether Horizon has a build for this computer.
pub fn supported() -> bool {
    ASSET.is_some()
}

fn root(cfg: &LauncherConfig) -> PathBuf {
    cfg.install_dir().join("horizon")
}

pub fn game_dir(cfg: &LauncherConfig) -> PathBuf {
    root(cfg).join("game")
}

fn resources(cfg: &LauncherConfig) -> PathBuf {
    game_dir(cfg).join(".local").join("horizon-client").join(MC_VERSION)
}

/// The SHA-256 of the archive the installed game came from.
fn installed_marker(cfg: &LauncherConfig) -> PathBuf {
    root(cfg).join("installed.txt")
}

/// Whether the installed game is Space Client's build ("spaceclient") or
/// Horizon's plain Nightly ("nightly").
fn flavour_marker(cfg: &LauncherConfig) -> PathBuf {
    root(cfg).join("flavour.txt")
}

/// Whether the installed game has Space Client built in.
pub fn has_space_client(cfg: &LauncherConfig) -> bool {
    read_marker(&flavour_marker(cfg)) == "spaceclient"
}

/// The SHA-256 of the preparer its resources were prepared with.
fn prepared_marker(cfg: &LauncherConfig) -> PathBuf {
    root(cfg).join("prepared.txt")
}

fn read_marker(path: &Path) -> String {
    std::fs::read_to_string(path).map(|s| s.trim().to_string()).unwrap_or_default()
}

/// Where progress goes: done, total and a line of text. The window's
/// progress bar in the launcher, the console in tests.
pub type Progress = std::sync::Arc<dyn Fn(u64, u64, String) + Send + Sync>;

/// The launcher window's progress bar, as stage "horizon".
pub fn progress_bar(app: &AppHandle) -> Progress {
    let app = app.clone();
    std::sync::Arc::new(move |current, total, file| {
        emit_progress(&app, InstallProgress { stage: "horizon".into(), current, total: total.max(1), file })
    })
}

fn say(progress: &Progress, line: impl Into<String>) {
    progress(0, 1, line.into());
}

fn http() -> anyhow::Result<reqwest::Client> {
    Ok(reqwest::Client::builder().user_agent("SpaceClient-Launcher").build()?)
}

fn asset_url(name: &str) -> String {
    format!("https://github.com/{REPO}/releases/download/{CHANNEL}/{name}")
}

fn mod_asset_url(name: &str) -> String {
    format!("https://github.com/{MOD_REPO}/releases/download/{MOD_CHANNEL}/{name}")
}

/// A checksum list, `<sha256>  <file>` per line: `(file, hex)` pairs.
/// Horizon's is checked for its signature first (verify_sums); Space
/// Client's build comes from its own repository's release.
pub fn parse_sums(sums: &[u8]) -> anyhow::Result<Vec<(String, String)>> {
    let text = std::str::from_utf8(sums).map_err(|_| anyhow::anyhow!("{SUMS} is not text"))?;
    text.lines()
        .filter(|l| !l.trim().is_empty())
        .map(|line| {
            let (hex, name) = line
                .split_once(' ')
                .ok_or_else(|| anyhow::anyhow!("{SUMS}: {line:?} is not `<sha256>  <file>`"))?;
            let name = name.trim_start_matches([' ', '*']);
            if hex.len() != 64 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) || name.is_empty() {
                anyhow::bail!("{SUMS}: {line:?} is not `<sha256>  <file>`");
            }
            Ok((name.to_string(), hex.to_ascii_lowercase()))
        })
        .collect()
}

/// Where the game comes from: Space Client's build when it is published,
/// else Horizon's signed Nightly. (download URL, SHA-256, flavour)
async fn pick_source(client: &reqwest::Client, asset: &str) -> anyhow::Result<(String, String, &'static str)> {
    match client.get(mod_asset_url(SUMS)).send().await.and_then(|r| r.error_for_status()) {
        Ok(response) => {
            let sums = response.bytes().await?;
            if let Some((_, hex)) = parse_sums(&sums)?.into_iter().find(|(name, _)| name == asset) {
                return Ok((mod_asset_url(asset), hex, "spaceclient"));
            }
            eprintln!("horizon: Space Client's build has no {asset}; taking Horizon's Nightly");
        }
        Err(e) => eprintln!("horizon: no Space Client build of Horizon reachable ({e}); taking Horizon's Nightly"),
    }
    let sums = client.get(asset_url(SUMS)).send().await?.error_for_status()?.bytes().await?;
    let signature = client.get(asset_url(SIGNATURE)).send().await?.error_for_status()?.text().await?;
    let digest = verify_sums(&sums, &signature)?
        .into_iter()
        .find(|(name, _)| name == asset)
        .map(|(_, hex)| hex)
        .ok_or_else(|| anyhow::anyhow!("{asset} is not in Horizon's signed checksums."))?;
    Ok((asset_url(asset), digest, "nightly"))
}

/// The checksum list, once one of Horizon's keys is found to have signed it
/// for the nightly channel: `(file name, SHA-256 hex)` pairs. The signed
/// message is `horizon release nightly\n` and the list's bytes, so a list
/// signed for a release, or one replayed from elsewhere, does not pass.
pub fn verify_sums(sums: &[u8], signature: &str) -> anyhow::Result<Vec<(String, String)>> {
    use ed25519_dalek::{Signature, Verifier, VerifyingKey};
    let mut message = format!("horizon release {CHANNEL}\n").into_bytes();
    message.extend_from_slice(sums);
    let engine = base64::engine::general_purpose::STANDARD;
    let keys: Vec<VerifyingKey> = RELEASE_KEYS
        .iter()
        .filter_map(|k| engine.decode(k).ok())
        .filter_map(|b| <[u8; 32]>::try_from(b.as_slice()).ok())
        .filter_map(|b| VerifyingKey::from_bytes(&b).ok())
        .collect();
    let signed = signature
        .lines()
        .filter_map(|line| engine.decode(line.trim()).ok())
        .filter_map(|b| <[u8; 64]>::try_from(b.as_slice()).ok())
        .map(|b| Signature::from_bytes(&b))
        .any(|sig| keys.iter().any(|k| k.verify(&message, &sig).is_ok()));
    if !signed {
        anyhow::bail!(
            "Horizon's checksums are not signed with Horizon's release key, so nothing was installed. \
             The download may have been tampered with."
        );
    }
    parse_sums(sums)
}

/// The newest build (see the module comment), unless the installed game
/// already is it. Returns whether a new build went in.
pub async fn install_game(app: &Progress, cfg: &LauncherConfig) -> anyhow::Result<bool> {
    let asset = ASSET.ok_or_else(|| anyhow::anyhow!("Horizon has no build for this computer."))?;
    let client = http()?;
    say(app, "Checking for the newest Horizon build");
    let (url, digest, flavour) = pick_source(&client, asset).await?;

    let game = game_dir(cfg);
    let client_bin = game.join(format!("horizon-client{EXE}"));
    if read_marker(&installed_marker(cfg)) == digest && client_bin.exists() {
        return Ok(false);
    }

    say(app, format!("Downloading Horizon ({asset})"));
    let root = root(cfg);
    std::fs::create_dir_all(&root)?;
    let archive = root.join("download.partial");
    let mut response = client.get(&url).send().await?.error_for_status()?;
    let total = response.content_length().unwrap_or(0);
    let mut file = std::fs::File::create(&archive)?;
    let mut hash = Sha256::new();
    let mut done = 0u64;
    while let Some(chunk) = response.chunk().await? {
        hash.update(&chunk);
        std::io::Write::write_all(&mut file, &chunk)?;
        done += chunk.len() as u64;
        app(done, total, asset.into());
    }
    drop(file);
    if hex::encode(hash.finalize()) != digest {
        let _ = std::fs::remove_file(&archive);
        anyhow::bail!("The Horizon download does not match its signed checksum, so it was not installed. Try again.");
    }

    say(app, "Unpacking Horizon");
    let unpacked = root.join("unpacked.partial");
    let _ = std::fs::remove_dir_all(&unpacked);
    std::fs::create_dir_all(&unpacked)?;
    unpack(&archive, asset, &unpacked)?;
    let _ = std::fs::remove_file(&archive);
    // The archives hold one top folder (horizon-linux-x86_64/...)
    let entries: Vec<PathBuf> = std::fs::read_dir(&unpacked)?.flatten().map(|e| e.path()).collect();
    let top = match entries.as_slice() {
        [only] if only.is_dir() => only.clone(),
        _ => unpacked.clone(),
    };
    if !top.join(format!("horizon-client{EXE}")).exists() {
        anyhow::bail!("The Horizon download has no game in it.");
    }
    // What the last build imported from Minecraft carries over: the new
    // build's preparation reuses it instead of importing everything again
    if game.join(".local").is_dir() && !top.join(".local").exists() {
        let _ = std::fs::rename(game.join(".local"), top.join(".local"));
    }
    let _ = std::fs::remove_dir_all(&game);
    std::fs::rename(&top, &game)?;
    let _ = std::fs::remove_dir_all(&unpacked);
    std::fs::write(installed_marker(cfg), &digest)?;
    std::fs::write(flavour_marker(cfg), flavour)?;
    Ok(true)
}

/// Before a Horizon instance plays: the newest build and its resources, so
/// an instance made before an update needs nothing done. Offline or a failed
/// update plays the game that is there; only a game that is missing stops
/// the start. `others_running`: another Horizon game is open, whose files
/// are not replaced under it.
pub async fn update_before_launch(app: &AppHandle, cfg: &LauncherConfig, others_running: bool) -> anyhow::Result<()> {
    let installed = game_dir(cfg).join(format!("horizon-client{EXE}")).exists();
    if others_running && installed {
        return Ok(());
    }
    let progress = progress_bar(app);
    let result = async {
        install_game(&progress, cfg).await?;
        prepare(&progress, cfg).await
    }
    .await;
    emit_progress(app, InstallProgress { stage: "done".into(), current: 1, total: 1, file: "Horizon".into() });
    match result {
        Ok(()) => Ok(()),
        Err(e) if installed && resources(cfg).is_dir() => {
            eprintln!("horizon: not updated, playing the installed build - {e}");
            Ok(())
        }
        Err(e) => Err(e),
    }
}

fn unpack(archive: &Path, name: &str, to: &Path) -> anyhow::Result<()> {
    let file = std::fs::File::open(archive)?;
    if name.ends_with(".tar.gz") {
        tar::Archive::new(flate2::read::GzDecoder::new(file)).unpack(to)?;
    } else {
        zip::ZipArchive::new(file)?.extract(to)?;
    }
    Ok(())
}

/// The 26.3 version file exactly as Mojang serves it. The launcher keeps its
/// own copy re-written; Horizon's preparer pins Mojang's bytes by SHA-1.
async fn raw_version_json(cfg: &LauncherConfig) -> anyhow::Result<serde_json::Value> {
    let client = http()?;
    let manifest: serde_json::Value = client
        .get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    let entry = manifest["versions"]
        .as_array()
        .and_then(|v| v.iter().find(|e| e["id"] == MC_VERSION))
        .ok_or_else(|| anyhow::anyhow!("Minecraft {MC_VERSION} is not in Mojang's version list"))?;
    let url = entry["url"].as_str().unwrap_or_default();
    let sha1 = entry["sha1"].as_str().unwrap_or_default();
    let bytes = client.get(url).send().await?.error_for_status()?.bytes().await?;
    if hex::encode(Sha1::digest(&bytes)) != sha1 {
        anyhow::bail!("Mojang's {MC_VERSION} version file did not match its checksum");
    }
    let dir = cfg.versions_dir().join(MC_VERSION);
    std::fs::create_dir_all(&dir)?;
    std::fs::write(dir.join(format!("{MC_VERSION}.json")), &bytes)?;
    Ok(serde_json::from_slice(&bytes)?)
}

/// Builds Horizon's resources from the launcher's own Minecraft 26.3, once
/// per game build. Each line the preparer prints goes to the progress bar.
pub async fn prepare(app: &Progress, cfg: &LauncherConfig) -> anyhow::Result<()> {
    let preparer = game_dir(cfg).join(format!("horizon-prepare{EXE}"));
    // The preparer's own hash: a build that changes only the game (Space
    // Client's, or a Nightly with the same preparer) keeps the resources
    let installed = std::fs::read(&preparer).map(|b| hex::encode(Sha256::digest(&b))).unwrap_or_default();
    if !installed.is_empty() && read_marker(&prepared_marker(cfg)) == installed && resources(cfg).is_dir() {
        return Ok(());
    }
    let details = raw_version_json(cfg).await?;

    // The server jar, from the same version file
    let jar = root(cfg).join(format!("server-{MC_VERSION}.jar"));
    let server = &details["downloads"]["server"];
    let want = server["sha1"].as_str().unwrap_or_default();
    let have = std::fs::read(&jar).map(|b| hex::encode(Sha1::digest(&b))).unwrap_or_default();
    if have != want {
        say(app, format!("Downloading the {MC_VERSION} server jar from Mojang"));
        let url = server["url"].as_str().ok_or_else(|| anyhow::anyhow!("{MC_VERSION} has no server download"))?;
        let bytes = http()?.get(url).send().await?.error_for_status()?.bytes().await?;
        if hex::encode(Sha1::digest(&bytes)) != want {
            anyhow::bail!("The {MC_VERSION} server jar did not match its checksum");
        }
        std::fs::write(&jar, &bytes)?;
    }

    let java = crate::launcher::java::resolve_java_binary(cfg, &details);
    let game = game_dir(cfg);
    let preparer = game.join(format!("horizon-prepare{EXE}"));
    let minecraft = cfg.install_dir();

    say(app, "Preparing Horizon from your Minecraft (a few minutes, once)");
    let mut cmd = Command::new(&preparer);
    cmd.current_dir(&game)
        .arg("--root").arg(&game)
        .arg("--prepare")
        .arg("--server-jar").arg(&jar)
        .arg("--minecraft-dir").arg(&minecraft);
    with_java(&mut cmd, &java);
    run_logged(app, cmd).await?;

    // Sound is optional: without it the game is silent, not broken
    let mut sounds = Command::new(&preparer);
    sounds.current_dir(&game)
        .arg("sounds")
        .arg("--root").arg(&game)
        .arg("--minecraft-dir").arg(&minecraft);
    if let Err(e) = run_logged(app, sounds).await {
        say(app, format!("No sound in Horizon: {e}"));
    }

    std::fs::write(prepared_marker(cfg), installed)?;
    Ok(())
}

/// Points a command at the Java the launcher installed for 26.3: its home is
/// two folders above the binary (`<home>/bin/java`).
fn with_java(cmd: &mut Command, java: &str) {
    let bin = Path::new(java);
    let Some(home) = bin.parent().and_then(|p| p.parent()).filter(|_| bin.is_absolute()) else {
        return;
    };
    cmd.env("JAVA_HOME", home);
    let mut path = vec![home.join("bin")];
    path.extend(std::env::var_os("PATH").iter().flat_map(std::env::split_paths));
    if let Ok(joined) = std::env::join_paths(path) {
        cmd.env("PATH", joined);
    }
}

/// Runs a helper to the end, each line of its output to the progress bar.
async fn run_logged(app: &Progress, mut cmd: Command) -> anyhow::Result<()> {
    use std::io::{BufRead, BufReader};
    cmd.stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::piped());
    hide_window(&mut cmd);
    let app = app.clone();
    tokio::task::spawn_blocking(move || -> anyhow::Result<()> {
        let mut child = cmd.spawn()?;
        let stderr = child.stderr.take();
        let errors = std::thread::spawn(move || {
            stderr
                .map(|e| BufReader::new(e).lines().map_while(Result::ok).collect::<Vec<_>>())
                .unwrap_or_default()
        });
        if let Some(out) = child.stdout.take() {
            for line in BufReader::new(out).lines().map_while(Result::ok) {
                say(&app, line);
            }
        }
        let status = child.wait()?;
        let errors = errors.join().unwrap_or_default();
        if !status.success() {
            let tail = errors.iter().rev().take(6).rev().cloned().collect::<Vec<_>>().join("\n");
            anyhow::bail!("{}", if tail.is_empty() { status.to_string() } else { tail });
        }
        Ok(())
    })
    .await?
}

#[cfg(windows)]
fn hide_window(cmd: &mut Command) {
    use std::os::windows::process::CommandExt;
    cmd.creation_flags(0x0800_0000);
}
#[cfg(not(windows))]
fn hide_window(_cmd: &mut Command) {}

/// Space Client in an instance: on in `horizon-mods.txt` when the game has
/// it built in, and the old WebAssembly mod out of `horizon-mods/`, where
/// Horizon only lists it as stopped. Returns a line for the player when
/// the game is not Space Client's build yet.
pub fn install_mod(cfg: &LauncherConfig, instance: &Instance) -> anyhow::Result<Option<String>> {
    let mods = instance.game_dir().join("horizon-mods");
    for old in [format!("{MOD_ID}.hmod"), format!("{MOD_ID}.hmod.partial"), ".spaceclient-release".into()] {
        let path = mods.join(old);
        if path.exists() {
            std::fs::remove_file(&path)?;
        }
    }
    set_mod(&instance.game_dir(), instance.install_client_mod)?;
    Ok((!has_space_client(cfg)).then(|| {
        "Horizon is Horizon's own Nightly for now: Space Client's build of it is not published yet.".to_string()
    }))
}

/// Turns Space Client on or off in `horizon-mods.txt`, keeping every other
/// line.
pub fn set_mod(game_dir: &Path, on: bool) -> anyhow::Result<()> {
    std::fs::create_dir_all(game_dir)?;
    let file = game_dir.join("horizon-mods.txt");
    let text = std::fs::read_to_string(&file).unwrap_or_default();
    let line = format!("mod.{MOD_ID}:{on}");
    let mut lines: Vec<&str> = text
        .lines()
        .filter(|l| !l.trim_start().starts_with(&format!("mod.{MOD_ID}:")))
        .collect();
    lines.push(&line);
    std::fs::write(&file, lines.join("\n") + "\n")?;
    Ok(())
}

/// The session file the game reads once at start and deletes: the account
/// stays off the command line, where other programs could read it.
fn session_file(game_dir: &Path, account: &Account) -> anyhow::Result<PathBuf> {
    let dir = game_dir.join("sessions");
    std::fs::create_dir_all(&dir)?;
    // Leftovers of games that never started (the game deletes its own at once)
    if let Ok(entries) = std::fs::read_dir(&dir) {
        for e in entries.flatten() {
            let old = e
                .metadata()
                .and_then(|m| m.modified())
                .is_ok_and(|m| m.elapsed().is_ok_and(|age| age.as_secs() > 120));
            if old {
                let _ = std::fs::remove_file(e.path());
            }
        }
    }
    let path = dir.join(format!("session-{}.json", uuid::Uuid::new_v4().simple()));
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    let mut file = options.open(&path)?;
    let body = serde_json::json!({
        "name": account.username,
        "uuid": account.uuid,
        "access_token": account.access_token,
        "user_type": "msa",
        "xuid": serde_json::Value::Null,
    });
    std::io::Write::write_all(&mut file, body.to_string().as_bytes())?;
    Ok(path)
}

/// Starts Horizon for an instance: its own folder for saves and settings, the
/// shared game and resources, the account in a session file.
pub fn launch(
    app: &AppHandle,
    cfg: &LauncherConfig,
    instance: &Instance,
    account: &Account,
    join: Option<&str>,
) -> anyhow::Result<Child> {
    let game = game_dir(cfg);
    let client = game.join(format!("horizon-client{EXE}"));
    if !client.exists() || !resources(cfg).is_dir() {
        anyhow::bail!("Horizon is not installed yet. Install the instance first.");
    }
    let home = instance.game_dir();
    std::fs::create_dir_all(&home)?;
    set_mod(&home, instance.install_client_mod)?;

    let mut cmd = Command::new(&client);
    cmd.current_dir(&home)
        .env("HORIZON_HOME", &home)
        .arg("--resources")
        .arg(resources(cfg));
    if account.offline {
        cmd.arg("--name").arg(&account.username);
    } else {
        cmd.arg("--session-file").arg(session_file(&home, account)?);
    }
    if let Some(address) = join {
        cmd.arg("--server").arg(address);
    }
    if std::env::var_os("PIPEWIRE_PROPS").is_none() {
        cmd.env("PIPEWIRE_PROPS", "{ application.name = \"Horizon\" media.role = \"Game\" }");
    }
    crate::launcher::launch::spawn_logged(app, instance, cmd, "Horizon")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The Nightly's real checksum list and signature, from 2026-10-28.
    const NIGHTLY_SUMS: &[u8] = include_bytes!("../../tests/horizon-nightly-SHA256SUMS");
    const NIGHTLY_SIG: &str = include_str!("../../tests/horizon-nightly.sig");

    #[test]
    fn a_tampered_list_is_refused() {
        let forged = String::from_utf8_lossy(NIGHTLY_SUMS).replace("2971abbb", "0000abbb");
        assert!(verify_sums(forged.as_bytes(), NIGHTLY_SIG).is_err());
    }

    #[test]
    fn the_real_nightly_list_verifies() {
        let sums = verify_sums(NIGHTLY_SUMS, NIGHTLY_SIG).expect("signed");
        assert!(sums.iter().any(|(n, h)| n == "horizon-linux-x86_64.tar.gz"
            && h == "2971abbb78cd35417c91ad3a9c05e5bf9d3f8be1c7585949d5e3731ea04cf29d"));
    }

    /// The whole install against the real Nightly: download, signature,
    /// unpacking and preparation from a Minecraft 26.3 folder. Minutes and
    /// hundreds of megabytes, so only on request:
    /// `HORIZON_TEST_MINECRAFT=<folder with versions/ libraries/ assets/>
    ///  HORIZON_TEST_JAVA=<java 25 binary> cargo test -- --ignored horizon_install`
    #[tokio::test]
    #[ignore]
    async fn horizon_install_end_to_end() {
        let minecraft = std::env::var("HORIZON_TEST_MINECRAFT").expect("HORIZON_TEST_MINECRAFT");
        let java = std::env::var("HORIZON_TEST_JAVA").unwrap_or_default();
        let mut cfg = LauncherConfig::default();
        cfg.install_path = minecraft;
        cfg.custom_java_path = java;
        let log: Progress = std::sync::Arc::new(|done, total, line| {
            if total <= 1 { println!("{line}") } else if done == total { println!("{line}: {done} bytes") }
        });
        assert!(install_game(&log, &cfg).await.expect("install"));
        // A second look finds the same build and downloads nothing
        assert!(!install_game(&log, &cfg).await.expect("again"));
        prepare(&log, &cfg).await.expect("prepare");
        assert!(resources(&cfg).join("assets").is_dir() || resources(&cfg).is_dir());
        // Prepared for this build's preparer: a second look prepares nothing
        let preparer = std::fs::read(game_dir(&cfg).join(format!("horizon-prepare{EXE}"))).unwrap();
        assert_eq!(read_marker(&prepared_marker(&cfg)), hex::encode(Sha256::digest(&preparer)));
    }

    #[test]
    fn turning_the_mod_on_keeps_other_lines() {
        let dir = std::env::temp_dir().join(format!("sc-horizon-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("horizon-mods.txt"), "mod.zoom:true\nmod.spaceclient:false\nkey.zoom.zoom:key.keyboard.v\n").unwrap();
        set_mod(&dir, true).unwrap();
        let text = std::fs::read_to_string(dir.join("horizon-mods.txt")).unwrap();
        assert!(text.contains("mod.zoom:true"));
        assert!(text.contains("key.zoom.zoom:key.keyboard.v"));
        assert!(text.contains("mod.spaceclient:true"));
        assert!(!text.contains("mod.spaceclient:false"));
        set_mod(&dir, false).unwrap();
        let text = std::fs::read_to_string(dir.join("horizon-mods.txt")).unwrap();
        assert!(text.contains("mod.spaceclient:false") && !text.contains("mod.spaceclient:true"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_old_webassembly_mod_is_taken_out() {
        let root = std::env::temp_dir().join(format!("sc-horizon-{}", uuid::Uuid::new_v4()));
        let mut cfg = LauncherConfig::default();
        cfg.install_path = root.join("mc").to_string_lossy().into_owned();
        let inst = Instance {
            id: "hz".into(),
            name: "hz".into(),
            path: root.join("hz").to_string_lossy().into_owned(),
            mc_version: MC_VERSION.into(),
            loader: LOADER.into(),
            loader_version: String::new(),
            version_id: LOADER.into(),
            ram_mb: 4096,
            install_client_mod: true,
            created: String::new(),
            last_played: 0,
            play_count: 0,
        };
        let mods = inst.game_dir().join("horizon-mods");
        std::fs::create_dir_all(&mods).unwrap();
        std::fs::write(mods.join("spaceclient.hmod"), b"old").unwrap();
        std::fs::write(mods.join(".spaceclient-release"), b"v.1.63.0").unwrap();
        std::fs::write(mods.join("other.hmod"), b"theirs").unwrap();
        // Not Space Client's build yet: said so
        assert!(install_mod(&cfg, &inst).unwrap().is_some());
        assert!(!mods.join("spaceclient.hmod").exists() && !mods.join(".spaceclient-release").exists());
        assert!(mods.join("other.hmod").exists());
        std::fs::create_dir_all(super::root(&cfg)).unwrap();
        std::fs::write(flavour_marker(&cfg), "spaceclient").unwrap();
        assert_eq!(install_mod(&cfg, &inst).unwrap(), None);
        let text = std::fs::read_to_string(inst.game_dir().join("horizon-mods.txt")).unwrap();
        assert!(text.contains("mod.spaceclient:true"));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn space_clients_checksums_read() {
        let hex = "ab".repeat(32);
        let list = format!("{hex}  horizon-linux-x86_64.tar.gz\n{hex}  horizon-windows-x64.zip\n");
        let sums = parse_sums(list.as_bytes()).unwrap();
        assert_eq!(sums[1], ("horizon-windows-x64.zip".to_string(), hex.clone()));
        assert!(parse_sums(b"nonsense line\n").is_err());
    }
}
