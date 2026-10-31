//! Now Playing for Horizon.
//!
//! Horizon runs its mods in a sandbox with no network, no files of their own
//! beyond a small storage folder, and no other programs - so Space Client for
//! Horizon cannot ask Spotify what is playing, nor LRCLIB for the words. The
//! launcher can, and it is running anyway while the game is. So for as long
//! as a Horizon instance runs, this bridge:
//!
//! - asks the player what is playing: on Windows through the media transport
//!   controls, with the same PowerShell host the Java client uses; on a Mac
//!   Spotify through AppleScript; on Linux through playerctl;
//! - looks the synced lyrics up on LRCLIB and works out the line and the word
//!   being sung, as the Java client's Lyrics does;
//! - writes all of it, several times a second, into the mod's storage folder
//!   (`horizon-mods/.horizon-mod-data/spaceclient/nowplaying`), which the mod
//!   reads like any of its settings;
//! - and carries the mod's play, pause, next and previous back to the player
//!   through `music-command` in the same folder.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// What the player is playing, as last read.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Track {
    pub source: String,
    pub artist: String,
    pub title: String,
    pub playing: bool,
    /// Seconds into the song when read, -1 when unknown.
    pub position: f64,
    pub duration: f64,
}

/// One timed line of lyrics, with its words (char offsets) and their times.
#[derive(Clone, Debug, PartialEq)]
pub struct Line {
    pub at: f64,
    pub text: String,
    pub words: Vec<(usize, usize)>,
    pub word_at: Vec<f64>,
}

const MAX_LINE: usize = 120;

/// mm:ss.xx, or None for brackets holding metadata.
fn seconds(stamp: &str) -> Option<f64> {
    let (m, s) = stamp.split_once(':')?;
    Some(m.trim().parse::<u32>().ok()? as f64 * 60.0 + s.trim().parse::<f64>().ok()?)
}

/// An LRC block as timed lines, as Lyrics.parse in the Java client: several
/// stamps on one line repeat it, empty lines are kept (the gaps), and enhanced
/// LRC's <mm:ss.xx> word stamps time the words; lines without them get word
/// times estimated from their length.
pub fn parse(lrc: &str) -> Vec<Line> {
    let mut out: Vec<(f64, String, Vec<(usize, f64)>)> = Vec::new();
    for raw in lrc.lines() {
        let mut rest = raw;
        let mut stamps = Vec::new();
        while let Some(stripped) = rest.strip_prefix('[') {
            let Some(close) = stripped.find(']') else { break };
            if let Some(s) = seconds(&stripped[..close]) {
                stamps.push(s);
            }
            rest = &stripped[close + 1..];
        }
        if stamps.is_empty() {
            continue;
        }
        // Word stamps out of the text, remembering where (in chars) each stood
        let body = rest.trim();
        let mut clean = String::new();
        let mut marks: Vec<(usize, f64)> = Vec::new();
        let mut i = 0;
        let chars: Vec<char> = body.chars().collect();
        while i < chars.len() {
            if chars[i] == '<' {
                if let Some(len) = chars[i + 1..].iter().position(|c| *c == '>') {
                    let inner: String = chars[i + 1..i + 1 + len].iter().collect();
                    if let Some(at) = seconds(&inner) {
                        marks.push((clean.chars().count(), at));
                        i += len + 2;
                        continue;
                    }
                }
            }
            clean.push(chars[i]);
            i += 1;
        }
        let collapsed: String = clean.split_whitespace().collect::<Vec<_>>().join(" ");
        let text: String = collapsed.chars().take(MAX_LINE).collect();
        // Word stamps only count when collapsing changed nothing they point at
        let marks = if clean.trim() == text { marks } else { Vec::new() };
        for at in stamps {
            out.push((at, text.clone(), marks.clone()));
        }
    }
    out.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));

    let mut lines = Vec::with_capacity(out.len());
    for n in 0..out.len() {
        let (at, text, marks) = &out[n];
        let words = words_of(text);
        let mut word_at = Vec::new();
        if !words.is_empty() && !marks.is_empty() {
            for (w, (start, _)) in words.iter().enumerate() {
                let previous_end = if w == 0 { 0 } else { words[w - 1].1 };
                let found = marks
                    .iter()
                    .filter(|(pos, _)| *pos <= *start && (w == 0 || *pos + 1 > previous_end))
                    .map(|(_, t)| *t)
                    .last();
                match found {
                    Some(t) => word_at.push(t),
                    None => {
                        word_at.clear();
                        break;
                    }
                }
            }
        }
        if !words.is_empty() && word_at.is_empty() {
            let next = out[n + 1..].iter().map(|l| l.0).find(|t| *t > *at).unwrap_or(at + 5.0);
            word_at = estimate(*at, next, text, &words);
        }
        lines.push(Line { at: *at, text: text.clone(), words, word_at });
    }
    lines
}

fn words_of(text: &str) -> Vec<(usize, usize)> {
    let mut out = Vec::new();
    let mut start = None;
    let chars: Vec<char> = text.chars().collect();
    for i in 0..=chars.len() {
        let space = i == chars.len() || chars[i].is_whitespace();
        match (space, start) {
            (false, None) => start = Some(i),
            (true, Some(s)) => {
                out.push((s, i));
                start = None;
            }
            _ => {}
        }
    }
    out
}

/// Word times for a line without them: about thirteen characters a second
/// from its start, never past the next line, shared out by word length.
fn estimate(at: f64, next: f64, text: &str, words: &[(usize, usize)]) -> Vec<f64> {
    let gap = (next - at).max(0.2);
    let sung = (gap * 0.92).min((0.3 + text.chars().count() as f64 * 0.075).max(0.6));
    let total: f64 = words.iter().map(|(s, e)| (e - s + 1) as f64).sum();
    let mut t = at;
    words
        .iter()
        .map(|(s, e)| {
            let this = t;
            t += sung * (e - s + 1) as f64 / total;
            this
        })
        .collect()
}

/// The line being sung at `position` and the word in it (char offsets).
pub fn moment(lines: &[Line], position: f64) -> Option<(String, Option<(usize, usize)>)> {
    let line = lines.iter().take_while(|l| l.at <= position).last()?;
    if line.text.is_empty() {
        return None;
    }
    let word = line
        .word_at
        .iter()
        .take_while(|t| **t <= position)
        .count()
        .checked_sub(1)
        .and_then(|w| line.words.get(w).copied());
    Some((line.text.clone(), word))
}

/// Synced lyrics from LRCLIB, or an empty list when there are none.
async fn fetch_lyrics(client: &reqwest::Client, artist: &str, title: &str) -> Vec<Line> {
    let get = |url: String| async move {
        let r = client.get(url).timeout(Duration::from_secs(8)).send().await.ok()?;
        if !r.status().is_success() {
            return None;
        }
        r.json::<serde_json::Value>().await.ok()
    };
    let exact = get(format!(
        "https://lrclib.net/api/get?artist_name={}&track_name={}",
        urlencode(artist),
        urlencode(title)
    ))
    .await;
    let synced = |v: &serde_json::Value| v["syncedLyrics"].as_str().map(str::to_string);
    let text = match exact.as_ref().and_then(synced) {
        Some(t) => Some(t),
        None => get(format!("https://lrclib.net/api/search?q={}", urlencode(&format!("{artist} {title}"))))
            .await
            .and_then(|v| v.as_array().and_then(|a| a.iter().find_map(synced))),
    };
    text.map(|t| parse(&t)).unwrap_or_default()
}

fn urlencode(text: &str) -> String {
    text.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

// ---------------------------------------------------------------- the player

#[cfg(windows)]
mod player {
    //! The media transport controls through one long-lived PowerShell, the
    //! Java client's MusicHost script.
    use super::Track;
    use std::io::{BufRead, BufReader, Write};
    use std::process::{Child, ChildStdin, Command, Stdio};
    use std::sync::mpsc::{channel, Receiver};
    use std::time::Duration;

    const SCRIPT: &str = include_str!("music_host.ps1");

    pub struct Player {
        child: Child,
        stdin: ChildStdin,
        lines: Receiver<String>,
    }

    impl Player {
        pub fn start() -> Option<Player> {
            use std::os::windows::process::CommandExt;
            let path = std::env::temp_dir().join("spaceclient-launcher-musichost.ps1");
            std::fs::write(&path, SCRIPT).ok()?;
            let mut child = Command::new("powershell")
                .args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File"])
                .arg(&path)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .creation_flags(0x0800_0000)
                .spawn()
                .ok()?;
            let stdin = child.stdin.take()?;
            let stdout = child.stdout.take()?;
            let (tx, rx) = channel();
            std::thread::spawn(move || {
                for line in BufReader::new(stdout).lines().map_while(Result::ok) {
                    if tx.send(line).is_err() {
                        break;
                    }
                }
            });
            let mut player = Player { child, stdin, lines: rx };
            match player.next() {
                Some(l) if l.starts_with("ready") => Some(player),
                _ => None,
            }
        }

        fn next(&mut self) -> Option<String> {
            self.lines.recv_timeout(Duration::from_secs(6)).ok()
        }

        fn ask(&mut self, line: &str) -> Option<()> {
            writeln!(self.stdin, "{line}").ok()?;
            self.stdin.flush().ok()
        }

        pub fn read(&mut self) -> Option<Track> {
            self.ask("read")?;
            let mut best: Option<Track> = None;
            loop {
                let line = self.next()?;
                if line == "end" || line.starts_with("err|") {
                    break;
                }
                let f: Vec<&str> = line.split('|').collect();
                if f.len() < 8 {
                    continue;
                }
                let id = f[0].to_lowercase();
                let source = if id.contains("spotify") {
                    "Spotify"
                } else if id.contains("amazon") {
                    "Amazon Music"
                } else {
                    continue;
                };
                let playing = f[1] == "Playing";
                let mut position: f64 = f[4].parse().unwrap_or(-1.0);
                let age: f64 = f[6].parse().unwrap_or(0.0);
                if playing && position >= 0.0 && age > 0.0 && age < 60_000.0 {
                    position += age / 1000.0;
                }
                let track = Track {
                    source: source.into(),
                    artist: f[2].trim().into(),
                    title: f[3].trim().into(),
                    playing,
                    position,
                    duration: f[5].parse().unwrap_or(-1.0),
                };
                if best.as_ref().is_none_or(|b| !b.playing && track.playing) {
                    best = Some(track);
                }
            }
            Some(best.unwrap_or_default())
        }

        pub fn command(&mut self, what: &str) {
            if self.ask(what).is_some() {
                let _ = self.next();
            }
        }
    }

    impl Drop for Player {
        fn drop(&mut self) {
            let _ = self.child.kill();
        }
    }
}

#[cfg(target_os = "macos")]
mod player {
    //! Spotify through AppleScript; nothing is started that is not running.
    use super::Track;
    use std::process::Command;

    pub struct Player;

    fn osascript(script: &str) -> Option<String> {
        let out = Command::new("osascript").arg("-e").arg(script).output().ok()?;
        out.status.success().then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
    }

    impl Player {
        pub fn start() -> Option<Player> {
            Some(Player)
        }

        pub fn read(&mut self) -> Option<Track> {
            let running = osascript("application \"Spotify\" is running")?;
            if running != "true" {
                return Some(Track::default());
            }
            let line = osascript(
                "tell application \"Spotify\" to return (player state as string) & \"|\" & (artist of current track) \
                 & \"|\" & (name of current track) & \"|\" & (player position as string) & \"|\" & ((duration of current track) as string)",
            )?;
            let f: Vec<&str> = line.split('|').collect();
            if f.len() < 5 {
                return Some(Track::default());
            }
            let number = |s: &str| s.replace(',', ".").parse::<f64>().unwrap_or(-1.0);
            Some(Track {
                source: "Spotify".into(),
                playing: f[0] == "playing",
                artist: f[1].trim().into(),
                title: f[2].trim().into(),
                position: number(f[3]),
                duration: number(f[4]) / 1000.0,
            })
        }

        pub fn command(&mut self, what: &str) {
            let verb = match what {
                "toggle" => "playpause",
                "next" => "next track",
                "prev" => "previous track",
                _ => return,
            };
            let _ = osascript(&format!("tell application \"Spotify\" to {verb}"));
        }
    }
}

#[cfg(not(any(windows, target_os = "macos")))]
mod player {
    //! MPRIS through playerctl, when it is installed.
    use super::Track;
    use std::process::Command;

    pub struct Player;

    fn playerctl(args: &[&str]) -> Option<String> {
        let out = Command::new("playerctl").args(args).output().ok()?;
        out.status.success().then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
    }

    impl Player {
        pub fn start() -> Option<Player> {
            playerctl(&["--version"]).map(|_| Player)
        }

        pub fn read(&mut self) -> Option<Track> {
            let Some(line) = playerctl(&[
                "--player=spotify,%any",
                "metadata",
                "--format",
                "{{status}}|{{artist}}|{{title}}|{{position}}|{{mpris:length}}|{{playerName}}",
            ]) else {
                return Some(Track::default());
            };
            let f: Vec<&str> = line.split('|').collect();
            if f.len() < 6 {
                return Some(Track::default());
            }
            let micros = |s: &str| s.parse::<f64>().map(|v| v / 1_000_000.0).unwrap_or(-1.0);
            Some(Track {
                source: if f[5].to_lowercase().contains("spotify") { "Spotify".into() } else { f[5].into() },
                playing: f[0] == "Playing",
                artist: f[1].trim().into(),
                title: f[2].trim().into(),
                position: micros(f[3]),
                duration: micros(f[4]),
            })
        }

        pub fn command(&mut self, what: &str) {
            let verb = match what {
                "toggle" => "play-pause",
                "next" => "next",
                "prev" => "previous",
                _ => return,
            };
            let _ = playerctl(&["--player=spotify,%any", verb]);
        }
    }
}

// ---------------------------------------------------------------- the bridge

/// The mod's storage folder in a Horizon instance's game folder.
pub fn mod_data(game_dir: &Path) -> PathBuf {
    game_dir.join("horizon-mods").join(".horizon-mod-data").join("spaceclient")
}

/// What the mod reads: one `key=value` per line, values without line breaks.
pub fn render(track: &Track, position: f64, lyric: Option<&(String, Option<(usize, usize)>)>, state: &str) -> String {
    let clean = |s: &str| s.replace(['\n', '\r'], " ");
    let mut out = String::from("v=1\n");
    out.push_str(&format!("state={state}\n"));
    out.push_str(&format!("source={}\n", clean(&track.source)));
    out.push_str(&format!("title={}\n", clean(&track.title)));
    out.push_str(&format!("artist={}\n", clean(&track.artist)));
    out.push_str(&format!("position={position:.2}\n"));
    out.push_str(&format!("duration={:.2}\n", track.duration));
    if let Some((line, word)) = lyric {
        out.push_str(&format!("line={}\n", clean(line)));
        if let Some((start, end)) = word {
            out.push_str(&format!("word={start},{end}\n"));
        }
    }
    out
}

fn write_atomically(dir: &Path, name: &str, text: &str) {
    let _ = std::fs::create_dir_all(dir);
    let temp = dir.join(format!("{name}.tmp"));
    if std::fs::write(&temp, text).is_ok() {
        let _ = std::fs::rename(&temp, dir.join(name));
    }
}

/// Runs for as long as `alive` says the instance is running.
pub fn spawn_bridge(game_dir: PathBuf, alive: Arc<dyn Fn() -> bool + Send + Sync>) {
    tauri::async_runtime::spawn(async move {
        let dir = mod_data(&game_dir);
        let client = reqwest::Client::builder().user_agent("SpaceClient-Launcher (Horizon now playing)").build();
        let Ok(client) = client else { return };
        let player = tokio::task::spawn_blocking(player::Player::start).await.ok().flatten();
        let Some(player) = player else {
            write_atomically(&dir, "nowplaying", "v=1\nstate=unsupported\n");
            return;
        };
        let player = Arc::new(Mutex::new(player));
        let lyrics: Arc<Mutex<HashMap<String, Vec<Line>>>> = Arc::new(Mutex::new(HashMap::new()));
        let pending: Arc<Mutex<std::collections::HashSet<String>>> = Arc::default();

        let mut track = Track::default();
        let mut read_at = Instant::now();
        let mut last_read = Instant::now() - Duration::from_secs(10);
        let mut last_written = String::new();
        while alive() {
            // Commands from the mod first: a press should not wait for a read
            let command_file = dir.join("music-command");
            if let Ok(command) = std::fs::read_to_string(&command_file) {
                let _ = std::fs::remove_file(&command_file);
                let command = command.trim().to_string();
                if ["toggle", "next", "prev"].contains(&command.as_str()) {
                    let p = player.clone();
                    let _ = tokio::task::spawn_blocking(move || p.lock().unwrap().command(&command)).await;
                    last_read = Instant::now() - Duration::from_secs(10);
                }
            }
            if last_read.elapsed() >= Duration::from_millis(1000) {
                let p = player.clone();
                if let Ok(Some(read)) = tokio::task::spawn_blocking(move || p.lock().unwrap().read()).await {
                    track = read;
                    read_at = Instant::now();
                }
                last_read = Instant::now();
            }

            let state = if track.title.is_empty() {
                "idle"
            } else if track.playing {
                "playing"
            } else {
                "paused"
            };
            let position = if track.position < 0.0 {
                -1.0
            } else if track.playing {
                track.position + read_at.elapsed().as_secs_f64()
            } else {
                track.position
            };

            let mut lyric = None;
            if !track.title.is_empty() {
                let key = format!("{}\u{0}{}", track.artist, track.title);
                let known = lyrics.lock().unwrap().get(&key).cloned();
                match known {
                    Some(lines) => lyric = if position >= 0.0 { moment(&lines, position) } else { None },
                    None => {
                        if pending.lock().unwrap().insert(key.clone()) {
                            let (client, lyrics, pending) = (client.clone(), lyrics.clone(), pending.clone());
                            let (artist, title) = (track.artist.clone(), track.title.clone());
                            tauri::async_runtime::spawn(async move {
                                let lines = fetch_lyrics(&client, &artist, &title).await;
                                let mut cache = lyrics.lock().unwrap();
                                if cache.len() > 50 {
                                    cache.clear();
                                }
                                cache.insert(key.clone(), lines);
                                pending.lock().unwrap().remove(&key);
                            });
                        }
                    }
                }
            }

            let text = render(&track, position, lyric.as_ref(), state);
            if text != last_written {
                write_atomically(&dir, "nowplaying", &text);
                last_written = text;
            }
            tokio::time::sleep(Duration::from_millis(120)).await;
        }
        let _ = std::fs::remove_file(dir.join("nowplaying"));
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_lines_get_estimated_words() {
        let lines = parse("[00:10.00]Über den Wolken\n[00:13.00]Wohl grenzenlos sein\n[00:30.00]\n");
        assert_eq!(lines.len(), 3);
        assert_eq!(lines[0].words, vec![(0, 4), (5, 8), (9, 15)]);
        let (text, word) = moment(&lines, 10.05).unwrap();
        assert_eq!(text, "Über den Wolken");
        assert_eq!(word, Some((0, 4)));
        let (_, word) = moment(&lines, 10.5).unwrap();
        assert_eq!(word, Some((5, 8)));
        assert!(moment(&lines, 31.0).is_none());
        assert!(moment(&lines, 5.0).is_none());
    }

    #[test]
    fn word_stamps_are_used() {
        let lines = parse("[00:01.00]<00:01.00>Hello <00:02.50>world\n");
        assert_eq!(lines[0].text, "Hello world");
        assert_eq!(lines[0].word_at, vec![1.0, 2.5]);
        assert_eq!(moment(&lines, 2.0).unwrap().1, Some((0, 5)));
        assert_eq!(moment(&lines, 2.6).unwrap().1, Some((6, 11)));
    }

    #[test]
    fn repeated_stamps_repeat_the_line() {
        let lines = parse("[00:05.00][00:20.00]Again\n[ti:Title]\n");
        assert_eq!(lines.iter().map(|l| l.at).collect::<Vec<_>>(), vec![5.0, 20.0]);
    }

    #[test]
    fn the_file_the_mod_reads() {
        let track = Track {
            source: "Spotify".into(),
            artist: "A\nB".into(),
            title: "T".into(),
            playing: true,
            position: 1.0,
            duration: 200.0,
        };
        let text = render(&track, 12.345, Some(&("Line".into(), Some((0, 4)))), "playing");
        assert_eq!(
            text,
            "v=1\nstate=playing\nsource=Spotify\ntitle=T\nartist=A B\nposition=12.35\nduration=200.00\nline=Line\nword=0,4\n"
        );
    }
}
