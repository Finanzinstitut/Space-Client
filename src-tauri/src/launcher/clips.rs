//! Clips: the last few seconds of play, kept and written out on a key press.
//!
//! # Why this lives in the launcher and not in the mod
//!
//! The ask includes "the whole computer's sound". A Fabric mod runs inside the
//! game's JVM, and Java has no way to record what the speakers are playing -
//! there is no loopback in the audio API, on any platform. It also has no cheap
//! way to get the rendered frame out: reading the framebuffer back every frame
//! costs more than the recording is worth.
//!
//! The launcher is a native process that is already running while the game is
//! open, so it can do what every clipping tool does: keep FFmpeg recording the
//! screen and the sound into a ring of short segments, and when a clip is
//! asked for, stitch the last few together. The mod's job is the key press.
//!
//! # The ring
//!
//! FFmpeg's segment muxer with `-segment_wrap` writes seg000, seg001, ... and
//! then starts again at seg000, so the folder never grows. Segments are MPEG-TS
//! rather than MP4 on purpose: the newest one is always half written, and a
//! half written MP4 has no index and cannot be read at all, while a half
//! written TS is simply shorter. That one choice is the difference between a
//! clip that ends at the key press and a clip that ends four seconds earlier.
//!
//! # What is asked of the person
//!
//! Recording the desktop's sound needs a loopback input. Windows has one built
//! in - "Stereo Mix" - but it ships disabled on most machines, so it is looked
//! for by name and, when it is missing, the clip is recorded with the
//! microphone alone and the interface says exactly that. Guessing silently and
//! producing silent clips would be worse.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;

// ---------------------------------------------------------------- layout

/// Where clips live, for both sides of the link.
///
/// Deliberately not under the configurable install path: the mod has to find
/// this folder without being told, and the one thing both sides can work out
/// on their own is the platform's data directory.
pub fn clip_dir() -> PathBuf {
    dirs::data_dir()
        .unwrap_or_else(std::env::temp_dir)
        .join("SpaceClient")
        .join("clips")
}

pub fn buffer_dir() -> PathBuf { clip_dir().join("buffer") }
pub fn request_dir() -> PathBuf { clip_dir().join("requests") }
pub fn out_dir() -> PathBuf { clip_dir().join("out") }

fn ensure_dirs() {
    for dir in [clip_dir(), buffer_dir(), request_dir(), out_dir()] {
        let _ = std::fs::create_dir_all(dir);
    }
}

// ---------------------------------------------------------------- settings

/// What the launcher owns: how the recording is made.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CaptureSettings {
    /// Record even when the game is not asking for it.
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_seconds")]
    pub seconds: u32,
    #[serde(default = "default_fps")]
    pub fps: u32,
    /// 18 is near lossless and large, 28 is small and soft. 23 is the default.
    #[serde(default = "default_quality")]
    pub quality: u32,
    #[serde(default = "default_true")]
    pub microphone: bool,
    #[serde(default = "default_true")]
    pub noise_cancel: bool,
    /// Empty means "find one", which is what almost everybody wants.
    #[serde(default)]
    pub system_device: String,
    #[serde(default)]
    pub mic_device: String,
}

fn default_seconds() -> u32 { 30 }
fn default_fps() -> u32 { 60 }
fn default_quality() -> u32 { 23 }
fn default_true() -> bool { true }

impl Default for CaptureSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            seconds: default_seconds(),
            fps: default_fps(),
            quality: default_quality(),
            microphone: true,
            noise_cancel: true,
            system_device: String::new(),
            mic_device: String::new(),
        }
    }
}

fn capture_file() -> PathBuf { clip_dir().join("capture.json") }

/// What the mod owns: the switch in the menu and the length behind it.
///
/// A separate file with one writer each, rather than one file with two. Two
/// processes writing the same settings is how a setting changed in the game
/// gets wiped by the launcher half a second later.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ModWish {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub seconds: u32,
}

fn wish_file() -> PathBuf { clip_dir().join("mod.json") }

pub fn load_capture() -> CaptureSettings {
    std::fs::read_to_string(capture_file())
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

pub fn save_capture(settings: &CaptureSettings) -> Result<(), String> {
    ensure_dirs();
    let text = serde_json::to_string_pretty(settings).map_err(|e| e.to_string())?;
    std::fs::write(capture_file(), text).map_err(|e| e.to_string())
}

pub fn load_wish() -> ModWish {
    std::fs::read_to_string(wish_file())
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

/// The length that actually applies.
///
/// The menu in the game owns this number, because that is where it is set. A
/// game that never ran leaves the launcher's own value in charge.
pub fn effective_seconds(wish: &ModWish, capture: &CaptureSettings) -> u32 {
    let wanted = if wish.seconds > 0 { wish.seconds } else { capture.seconds };
    wanted.clamp(5, 300)
}

/// Whether anything wants the recorder running.
pub fn wants_recording(wish: &ModWish, capture: &CaptureSettings) -> bool {
    wish.enabled || capture.enabled
}

// ---------------------------------------------------------------- ffmpeg

fn exe_name(stem: &str) -> String {
    if cfg!(windows) { format!("{stem}.exe") } else { stem.to_string() }
}

fn bundled_dir() -> PathBuf {
    dirs::data_dir()
        .unwrap_or_else(std::env::temp_dir)
        .join("SpaceClient")
        .join("bin")
}

/// FFmpeg, from the copy this launcher fetched or from the system.
pub fn ffmpeg_path() -> Option<PathBuf> {
    tool_path("ffmpeg")
}

pub fn ffprobe_path() -> Option<PathBuf> {
    tool_path("ffprobe")
}

fn tool_path(stem: &str) -> Option<PathBuf> {
    let bundled = bundled_dir().join(exe_name(stem));
    if bundled.is_file() {
        return Some(bundled);
    }
    which_in_path(&exe_name(stem))
}

fn which_in_path(name: &str) -> Option<PathBuf> {
    let paths = std::env::var_os("PATH")?;
    std::env::split_paths(&paths)
        .map(|dir| dir.join(name))
        .find(|candidate| candidate.is_file())
}

/// Where a Windows build of FFmpeg is fetched from when there is none.
///
/// Only Windows, and deliberately so. The other two platforms have a package
/// manager and a one line install, while Windows has neither and a person who
/// wanted to press a key in a game now has a zip to find. Downloading a
/// seventy megabyte binary is worth avoiding where it can be avoided.
const FFMPEG_WINDOWS_ZIP: &str =
    "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";

/// Fetches FFmpeg if it is missing. Says what happened either way.
pub async fn ensure_ffmpeg() -> Result<String, String> {
    if ffmpeg_path().is_some() {
        return Ok("FFmpeg is already here".into());
    }
    if !cfg!(windows) {
        return Err("FFmpeg is missing. Install it with your package manager \
                    (apt install ffmpeg, brew install ffmpeg) and try again.".into());
    }

    let target = bundled_dir();
    std::fs::create_dir_all(&target).map_err(|e| e.to_string())?;

    let bytes = reqwest::get(FFMPEG_WINDOWS_ZIP)
        .await
        .map_err(|e| format!("could not reach the download: {e}"))?
        .bytes()
        .await
        .map_err(|e| format!("the download broke off: {e}"))?;

    let reader = std::io::Cursor::new(bytes);
    let mut archive = zip::ZipArchive::new(reader).map_err(|e| format!("not a readable zip: {e}"))?;

    // The zip carries a whole build; two files out of it are enough, and they
    // are taken by name rather than by position because the folder inside
    // carries the version number and changes with every release.
    let mut taken = 0;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(|e| e.to_string())?;
        let name = entry.name().to_string();
        let wanted = name.ends_with("/ffmpeg.exe") || name.ends_with("/ffprobe.exe");
        if !wanted {
            continue;
        }
        let leaf = name.rsplit('/').next().unwrap_or("ffmpeg.exe").to_string();
        let mut file = std::fs::File::create(target.join(leaf)).map_err(|e| e.to_string())?;
        std::io::copy(&mut entry, &mut file).map_err(|e| e.to_string())?;
        taken += 1;
    }

    if taken < 2 {
        return Err("the download did not contain FFmpeg".into());
    }
    Ok(format!("FFmpeg installed to {}", target.to_string_lossy()))
}

// ---------------------------------------------------------------- devices

/// One capture input the machine offers.
#[derive(Debug, Clone, Serialize)]
pub struct AudioDevice {
    pub name: String,
    /// Whether this looks like it carries what the speakers are playing.
    pub loopback: bool,
}

/// Names that mean "what the computer is playing", across drivers and locales.
const LOOPBACK_HINTS: &[&str] = &[
    "stereo mix", "stereomix", "stereomischung", "what u hear", "what you hear",
    "loopback", "virtual-audio-capturer", "wave out", "speakers (", "monitor of",
];

/// Picks the input most likely to carry the desktop's sound.
///
/// Pure, and separate from the listing, because this is the part with an
/// opinion in it - and an opinion worth being able to check.
pub fn pick_loopback(names: &[String]) -> Option<String> {
    for hint in LOOPBACK_HINTS {
        if let Some(found) = names
            .iter()
            .find(|name| name.to_lowercase().contains(hint))
        {
            return Some(found.clone());
        }
    }
    None
}

/// A microphone, which is anything that is not a loopback.
pub fn pick_microphone(names: &[String]) -> Option<String> {
    names
        .iter()
        .find(|name| pick_loopback(std::slice::from_ref(name)).is_none())
        .cloned()
}

/// Reads the audio inputs out of what `-list_devices` prints.
///
/// FFmpeg writes this to stderr as prose, one device per line in quotes. Parsed
/// rather than guessed, and kept apart from the process call so the parsing can
/// be checked against real output without a machine that has the devices.
pub fn parse_devices(stderr: &str) -> Vec<AudioDevice> {
    let mut found: Vec<AudioDevice> = Vec::new();
    let mut in_audio = false;

    for line in stderr.lines() {
        let lower = line.to_lowercase();
        if lower.contains("directshow video devices") {
            in_audio = false;
            continue;
        }
        if lower.contains("directshow audio devices") {
            in_audio = true;
            continue;
        }
        if !in_audio {
            continue;
        }
        // Alternative names are the device path, not something to show
        if lower.contains("alternative name") {
            continue;
        }
        let Some(start) = line.find('"') else { continue };
        let rest = &line[start + 1..];
        let Some(end) = rest.find('"') else { continue };
        let name = rest[..end].to_string();
        if name.is_empty() {
            continue;
        }
        let loopback = pick_loopback(std::slice::from_ref(&name)).is_some();
        if !found.iter().any(|d| d.name == name) {
            found.push(AudioDevice { name, loopback });
        }
    }
    found
}

pub fn list_devices() -> Vec<AudioDevice> {
    if !cfg!(windows) {
        return Vec::new();
    }
    let Some(ffmpeg) = ffmpeg_path() else {
        return Vec::new();
    };

    let output = Command::new(ffmpeg)
        .args(["-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"])
        .stdin(Stdio::null())
        .output();

    match output {
        Ok(done) => parse_devices(&String::from_utf8_lossy(&done.stderr)),
        Err(_) => Vec::new(),
    }
}

// ---------------------------------------------------------------- the command

/// How long one segment of the ring is. Also how far the start of a clip can
/// be out, since a stitched clip is cut on a segment boundary.
pub const SEGMENT_SECONDS: u32 = 2;

/// Which platform's capture inputs to build for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Platform {
    Windows,
    Linux,
    Mac,
}

pub fn host_platform() -> Platform {
    if cfg!(windows) {
        Platform::Windows
    } else if cfg!(target_os = "macos") {
        Platform::Mac
    } else {
        Platform::Linux
    }
}

/// Everything the recorder was told to use, after the guessing is done.
#[derive(Debug, Clone, Default)]
pub struct Inputs {
    pub system: Option<String>,
    pub microphone: Option<String>,
}

/// The whole FFmpeg command line for the ring recorder.
///
/// A pure function returning the arguments, which is the only honest way to
/// test this: the command cannot be run here - there is no screen and no sound
/// card - but every mistake worth making is a mistake in these strings.
pub fn record_args(
    platform: Platform,
    settings: &CaptureSettings,
    inputs: &Inputs,
    seconds: u32,
    buffer: &Path,
) -> Vec<String> {
    let mut args: Vec<String> = vec!["-hide_banner".into(), "-loglevel".into(), "error".into(), "-y".into()];

    // --- the screen ---
    match platform {
        Platform::Windows => {
            args.extend([
                "-f".into(), "gdigrab".into(),
                "-framerate".into(), settings.fps.to_string(),
                "-draw_mouse".into(), "0".into(),
                "-i".into(), "desktop".into(),
            ]);
        }
        Platform::Linux => {
            let display = std::env::var("DISPLAY").unwrap_or_else(|_| ":0.0".into());
            args.extend([
                "-f".into(), "x11grab".into(),
                "-framerate".into(), settings.fps.to_string(),
                "-i".into(), display,
            ]);
        }
        Platform::Mac => {
            args.extend([
                "-f".into(), "avfoundation".into(),
                "-framerate".into(), settings.fps.to_string(),
                "-i".into(), "1:none".into(),
            ]);
        }
    }

    // --- the sound ---
    let mut audio_inputs = 0;
    for source in [inputs.system.as_ref(), inputs.microphone.as_ref()] {
        let Some(name) = source else { continue };
        match platform {
            Platform::Windows => args.extend([
                "-f".into(), "dshow".into(),
                "-i".into(), format!("audio={name}"),
            ]),
            Platform::Linux => args.extend([
                "-f".into(), "pulse".into(),
                "-i".into(), name.clone(),
            ]),
            Platform::Mac => args.extend([
                "-f".into(), "avfoundation".into(),
                "-i".into(), format!(":{name}"),
            ]),
        }
        audio_inputs += 1;
    }

    // --- mixing, and the noise the microphone brings with it ---
    if audio_inputs > 0 {
        let mic_index = if inputs.system.is_some() { 2 } else { 1 };
        let clean = settings.noise_cancel && inputs.microphone.is_some();

        let filter = if audio_inputs == 2 && clean {
            // The microphone is cleaned on its own and only then mixed in -
            // running the denoiser over the game's sound as well would chew
            // the top off the music.
            format!("[{mic_index}:a]highpass=f=90,afftdn=nf=-25[mic];[1:a][mic]amix=inputs=2:duration=longest:dropout_transition=0[a]")
        } else if audio_inputs == 2 {
            "[1:a][2:a]amix=inputs=2:duration=longest:dropout_transition=0[a]".to_string()
        } else if clean {
            "[1:a]highpass=f=90,afftdn=nf=-25[a]".to_string()
        } else {
            "[1:a]anull[a]".to_string()
        };

        args.extend(["-filter_complex".into(), filter]);
        args.extend(["-map".into(), "0:v".into(), "-map".into(), "[a]".into()]);
        args.extend(["-c:a".into(), "aac".into(), "-b:a".into(), "160k".into()]);
    } else {
        args.extend(["-map".into(), "0:v".into()]);
    }

    // --- the picture ---
    //
    // A keyframe every segment, because a stitched clip can only be cut on one.
    let keyint = (settings.fps * SEGMENT_SECONDS).to_string();
    args.extend([
        "-c:v".into(), "libx264".into(),
        "-preset".into(), "veryfast".into(),
        "-crf".into(), settings.quality.to_string(),
        "-pix_fmt".into(), "yuv420p".into(),
        "-g".into(), keyint.clone(),
        "-keyint_min".into(), keyint,
        "-sc_threshold".into(), "0".into(),
    ]);

    // --- the ring ---
    //
    // Two segments more than the clip needs: one is always half written, and
    // one is about to be overwritten.
    let wrap = seconds.div_ceil(SEGMENT_SECONDS) + 2;
    args.extend([
        "-f".into(), "segment".into(),
        "-segment_format".into(), "mpegts".into(),
        "-segment_time".into(), SEGMENT_SECONDS.to_string(),
        "-segment_wrap".into(), wrap.to_string(),
        "-reset_timestamps".into(), "1".into(),
        buffer.join("seg%03d.ts").to_string_lossy().to_string(),
    ]);

    args
}

// ---------------------------------------------------------------- stitching

/// One segment of the ring, as the stitcher sees it.
#[derive(Debug, Clone, PartialEq)]
pub struct Segment {
    pub file: PathBuf,
    /// Seconds since the epoch, as a float, so ordering survives a wrap.
    pub modified: f64,
}

/// The segments that make up the last `seconds`, oldest first.
///
/// Chosen by age rather than by name, which is the whole point: the ring
/// overwrites seg000 after seg005, so the names run backwards half the time and
/// sorting by name would hand back a clip with the middle at the end.
///
/// One segment more than the arithmetic needs, because the oldest one in the
/// set is only partly inside the window and the surplus is trimmed off later.
pub fn choose_segments(mut segments: Vec<Segment>, seconds: u32) -> Vec<Segment> {
    segments.sort_by(|a, b| a.modified.partial_cmp(&b.modified).unwrap_or(std::cmp::Ordering::Equal));

    let wanted = (seconds.div_ceil(SEGMENT_SECONDS) + 1) as usize;
    if segments.len() > wanted {
        segments.drain(..segments.len() - wanted);
    }
    segments
}

/// The list file the concat demuxer reads.
pub fn concat_list(segments: &[Segment]) -> String {
    segments
        .iter()
        .map(|segment| format!("file '{}'\n", segment.file.to_string_lossy().replace('\'', "'\\''")))
        .collect()
}

/// How far into the stitched material the clip starts.
///
/// Never negative: a ring that has not filled up yet simply gives a shorter
/// clip, which is the right answer for "press the key two seconds after you
/// started the game".
pub fn trim_start(total: f64, seconds: u32) -> f64 {
    let extra = total - seconds as f64;
    if extra > 0.0 { extra } else { 0.0 }
}

fn read_segments(dir: &Path) -> Vec<Segment> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Vec::new();
    };

    let mut found = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("ts") {
            continue;
        }
        let Ok(meta) = entry.metadata() else { continue };
        if meta.len() == 0 {
            continue;
        }
        let modified = meta
            .modified()
            .ok()
            .and_then(|time| time.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs_f64())
            .unwrap_or(0.0);
        found.push(Segment { file: path, modified });
    }
    found
}

/// Asks ffprobe how long a file is.
fn duration_of(file: &Path) -> Option<f64> {
    let probe = ffprobe_path()?;
    let output = Command::new(probe)
        .args([
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
        ])
        .arg(file)
        .stdin(Stdio::null())
        .output()
        .ok()?;

    String::from_utf8_lossy(&output.stdout).trim().parse::<f64>().ok()
}

// ---------------------------------------------------------------- the clips

#[derive(Debug, Clone, Serialize)]
pub struct Clip {
    pub name: String,
    pub file: String,
    pub poster: String,
    pub seconds: f64,
    pub bytes: u64,
    /// Seconds since the epoch, for sorting newest first on screen.
    pub created: f64,
}

pub fn list_clips() -> Vec<Clip> {
    ensure_dirs();
    let Ok(entries) = std::fs::read_dir(out_dir()) else {
        return Vec::new();
    };

    let mut clips = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("mp4") {
            continue;
        }
        let Ok(meta) = entry.metadata() else { continue };
        let created = meta
            .modified()
            .ok()
            .and_then(|time| time.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs_f64())
            .unwrap_or(0.0);

        let poster = path.with_extension("jpg");
        clips.push(Clip {
            name: path.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default(),
            file: path.to_string_lossy().to_string(),
            poster: if poster.is_file() { poster.to_string_lossy().to_string() } else { String::new() },
            seconds: duration_of(&path).unwrap_or(0.0),
            bytes: meta.len(),
            created,
        });
    }

    clips.sort_by(|a, b| b.created.partial_cmp(&a.created).unwrap_or(std::cmp::Ordering::Equal));
    clips
}

/// A name that sorts by time and says nothing a stranger could not guess.
pub fn clip_name(now: std::time::SystemTime) -> String {
    let secs = now
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    // Plain arithmetic rather than a date library, which this crate does not
    // carry. Good enough for a filename and wrong for nothing else.
    let days = secs / 86_400;
    let time = secs % 86_400;
    let (year, month, day) = civil_from_days(days as i64);
    format!(
        "clip-{year:04}-{month:02}-{day:02}-{:02}{:02}{:02}",
        time / 3600,
        (time % 3600) / 60,
        time % 60
    )
}

/// Days since 1970 back into a date. Howard Hinnant's algorithm.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// Stitches the ring into one clip and returns where it landed.
pub fn save_clip(seconds: u32) -> Result<String, String> {
    ensure_dirs();
    let ffmpeg = ffmpeg_path().ok_or("FFmpeg is not installed")?;

    let segments = choose_segments(read_segments(&buffer_dir()), seconds);
    if segments.is_empty() {
        return Err("Nothing recorded yet - is the recorder running?".into());
    }

    let name = clip_name(std::time::SystemTime::now());
    let target = out_dir().join(format!("{name}.mp4"));
    stitch(&ffmpeg, &segments, seconds, &clip_dir().join("work"), &target)?;

    // A still from a second in, so the poster is not the black frame that a
    // cut often starts on
    let poster = out_dir().join(format!("{name}.jpg"));
    let _ = run(&ffmpeg, &[
        "-hide_banner".into(), "-loglevel".into(), "error".into(), "-y".into(),
        "-ss".into(), "1".into(),
        "-i".into(), target.to_string_lossy().to_string(),
        "-frames:v".into(), "1".into(),
        "-vf".into(), "scale=480:-2".into(),
        poster.to_string_lossy().to_string(),
    ]);

    Ok(target.to_string_lossy().to_string())
}

/// Joins the chosen segments and cuts the result down to the last `seconds`.
///
/// Given its folders rather than reaching for the fixed ones, so that this -
/// the half that actually touches video - can be run against real files in a
/// test instead of only being reasoned about.
///
/// Two passes, both stream copies: joining cannot trim and trimming cannot
/// join. Copying means the cut lands on a keyframe, which is why the recorder
/// puts one at the start of every segment.
pub fn stitch(
    ffmpeg: &Path,
    segments: &[Segment],
    seconds: u32,
    work: &Path,
    target: &Path,
) -> Result<(), String> {
    if segments.is_empty() {
        return Err("nothing to stitch".into());
    }
    std::fs::create_dir_all(work).map_err(|e| e.to_string())?;
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }

    let list = work.join("segments.txt");
    std::fs::write(&list, concat_list(segments)).map_err(|e| e.to_string())?;

    let joined = work.join("joined.ts");
    let _ = std::fs::remove_file(&joined);

    run(ffmpeg, &[
        "-hide_banner".into(), "-loglevel".into(), "error".into(), "-y".into(),
        "-f".into(), "concat".into(), "-safe".into(), "0".into(),
        "-i".into(), list.to_string_lossy().to_string(),
        "-c".into(), "copy".into(),
        joined.to_string_lossy().to_string(),
    ])?;

    let total = duration_of(&joined).unwrap_or(seconds as f64);
    let start = trim_start(total, seconds);

    run(ffmpeg, &[
        "-hide_banner".into(), "-loglevel".into(), "error".into(), "-y".into(),
        "-ss".into(), format!("{start:.3}"),
        "-i".into(), joined.to_string_lossy().to_string(),
        "-t".into(), seconds.to_string(),
        "-c".into(), "copy".into(),
        "-movflags".into(), "+faststart".into(),
        target.to_string_lossy().to_string(),
    ])?;

    let _ = std::fs::remove_file(&joined);
    Ok(())
}

fn run(program: &Path, args: &[String]) -> Result<(), String> {
    let output = Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .output()
        .map_err(|e| format!("could not start FFmpeg: {e}"))?;

    if output.status.success() {
        return Ok(());
    }

    let stderr = String::from_utf8_lossy(&output.stderr);
    let last = stderr.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("no output");
    Err(format!("FFmpeg failed: {last}"))
}

pub fn delete_clip(name: &str) -> Result<(), String> {
    // Only ever a name from this folder, never a path handed in from outside
    let safe: String = name.chars().filter(|c| c.is_alphanumeric() || *c == '-' || *c == '_').collect();
    if safe.is_empty() {
        return Err("no such clip".into());
    }
    let _ = std::fs::remove_file(out_dir().join(format!("{safe}.jpg")));
    std::fs::remove_file(out_dir().join(format!("{safe}.mp4"))).map_err(|e| e.to_string())
}

// ---------------------------------------------------------------- the recorder

static RECORDER: Mutex<Option<Child>> = Mutex::new(None);

#[derive(Debug, Clone, Serialize)]
pub struct ClipStatus {
    pub recording: bool,
    pub ffmpeg: bool,
    pub seconds: u32,
    /// Which sound situation this machine is in, as a word the interface
    /// turns into a sentence in whichever language it is speaking.
    pub audio_state: String,
    pub system_device: String,
    pub mic_device: String,
    pub wanted_by_game: bool,
    pub clip_folder: String,
}

pub fn resolve_inputs(settings: &CaptureSettings, devices: &[AudioDevice]) -> Inputs {
    let names: Vec<String> = devices.iter().map(|d| d.name.clone()).collect();

    let system = if !settings.system_device.is_empty() {
        Some(settings.system_device.clone())
    } else {
        pick_loopback(&names)
    };

    let microphone = if !settings.microphone {
        None
    } else if !settings.mic_device.is_empty() {
        Some(settings.mic_device.clone())
    } else {
        pick_microphone(&names)
    };

    Inputs { system, microphone }
}

/// Which of the four sound situations this machine is in.
///
/// A word rather than a sentence, because the sentence belongs on the other
/// side of the wall where the translations live - the launcher speaks two
/// languages and this file speaks none of them.
pub fn audio_state(inputs: &Inputs, settings: &CaptureSettings) -> &'static str {
    match (&inputs.system, &inputs.microphone) {
        (Some(_), Some(_)) if settings.noise_cancel => "both_clean",
        (Some(_), Some(_)) => "both",
        (Some(_), None) => "system_only",
        (None, Some(_)) => "mic_only",
        (None, None) => "none",
    }
}

pub fn status() -> ClipStatus {
    let capture = load_capture();
    let wish = load_wish();
    let devices = list_devices();
    let inputs = resolve_inputs(&capture, &devices);

    ClipStatus {
        recording: RECORDER.lock().map(|r| r.is_some()).unwrap_or(false),
        ffmpeg: ffmpeg_path().is_some(),
        seconds: effective_seconds(&wish, &capture),
        audio_state: audio_state(&inputs, &capture).to_string(),
        system_device: inputs.system.unwrap_or_default(),
        mic_device: inputs.microphone.unwrap_or_default(),
        wanted_by_game: wish.enabled,
        clip_folder: out_dir().to_string_lossy().to_string(),
    }
}

pub fn start() -> Result<(), String> {
    ensure_dirs();
    stop();

    let ffmpeg = ffmpeg_path().ok_or("FFmpeg is not installed")?;
    let capture = load_capture();
    let wish = load_wish();
    let seconds = effective_seconds(&wish, &capture);

    // A ring from an older, longer setting would otherwise be stitched into a
    // clip alongside the new one
    for segment in read_segments(&buffer_dir()) {
        let _ = std::fs::remove_file(segment.file);
    }

    let inputs = resolve_inputs(&capture, &list_devices());
    let args = record_args(host_platform(), &capture, &inputs, seconds, &buffer_dir());

    let child = Command::new(ffmpeg)
        .args(&args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("could not start the recorder: {e}"))?;

    if let Ok(mut slot) = RECORDER.lock() {
        *slot = Some(child);
    }
    Ok(())
}

pub fn stop() {
    if let Ok(mut slot) = RECORDER.lock() {
        if let Some(mut child) = slot.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

/// Keeps the recorder in step with what the game and the launcher want.
///
/// Also the only place a finished process is noticed: FFmpeg exits on its own
/// if the screen goes away, and a recorder that died silently would look like
/// one that was running right up until somebody pressed the key.
pub fn tend() {
    let capture = load_capture();
    let wish = load_wish();
    let wanted = wants_recording(&wish, &capture);

    let alive = {
        let mut slot = match RECORDER.lock() {
            Ok(slot) => slot,
            Err(_) => return,
        };
        match slot.as_mut() {
            Some(child) => match child.try_wait() {
                Ok(Some(_)) => { *slot = None; false }
                Ok(None) => true,
                Err(_) => { *slot = None; false }
            },
            None => false,
        }
    };

    if wanted && !alive {
        let _ = start();
    } else if !wanted && alive {
        stop();
    }
}

/// Picks up the key presses the mod left behind.
///
/// A file in a folder rather than a socket: no port to be taken, no firewall
/// prompt, and a request that arrives while the launcher is closed is still
/// there when it opens. Returns the clips it made.
pub fn take_requests() -> Vec<String> {
    ensure_dirs();
    let Ok(entries) = std::fs::read_dir(request_dir()) else {
        return Vec::new();
    };

    let mut made = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }

        let seconds = std::fs::read_to_string(&path)
            .ok()
            .and_then(|text| serde_json::from_str::<serde_json::Value>(&text).ok())
            .and_then(|value| value.get("seconds").and_then(|s| s.as_u64()))
            .unwrap_or(0) as u32;

        // Taken before it is acted on: a request that fails must not be tried
        // again on every pass, or one bad press becomes a loop
        let _ = std::fs::remove_file(&path);

        let wanted = if seconds > 0 {
            seconds.clamp(5, 300)
        } else {
            effective_seconds(&load_wish(), &load_capture())
        };

        match save_clip(wanted) {
            Ok(file) => made.push(file),
            Err(reason) => eprintln!("clip failed: {reason}"),
        }
    }
    made
}

#[cfg(test)]
mod tests {
    use super::*;

    fn settings() -> CaptureSettings {
        CaptureSettings { fps: 60, quality: 23, ..Default::default() }
    }

    #[test]
    fn the_loopback_is_found_by_any_of_its_names() {
        let names: Vec<String> = ["Microphone (Realtek)", "Stereo Mix (Realtek High Definition)"]
            .iter().map(|s| s.to_string()).collect();
        assert_eq!(pick_loopback(&names).unwrap(), "Stereo Mix (Realtek High Definition)");

        let german: Vec<String> = ["Mikrofon (USB)", "Stereomischung (Realtek)"]
            .iter().map(|s| s.to_string()).collect();
        assert_eq!(pick_loopback(&german).unwrap(), "Stereomischung (Realtek)");

        let none: Vec<String> = vec!["Microphone (USB)".into()];
        assert!(pick_loopback(&none).is_none());
    }

    #[test]
    fn the_microphone_is_whatever_is_not_the_loopback() {
        let names: Vec<String> = ["Stereo Mix (Realtek)", "Microphone (USB)"]
            .iter().map(|s| s.to_string()).collect();
        assert_eq!(pick_microphone(&names).unwrap(), "Microphone (USB)");
    }

    #[test]
    fn devices_are_read_out_of_ffmpegs_prose() {
        let stderr = r#"
[dshow @ 000001] "Integrated Camera" (video)
[dshow @ 000001]   Alternative name "@device_pnp_\\?\usb#vid"
[dshow @ 000001] DirectShow audio devices
[dshow @ 000001]  "Microphone (Realtek High Definition Audio)"
[dshow @ 000001]   Alternative name "@device_cm_{33D9A762}"
[dshow @ 000001]  "Stereo Mix (Realtek High Definition Audio)"
"#;
        let devices = parse_devices(stderr);
        assert_eq!(devices.len(), 2);
        assert_eq!(devices[0].name, "Microphone (Realtek High Definition Audio)");
        assert!(!devices[0].loopback);
        assert!(devices[1].loopback);
    }

    #[test]
    fn the_video_device_above_is_not_mistaken_for_audio() {
        let stderr = r#"
[dshow @ 1] DirectShow video devices
[dshow @ 1]  "Integrated Camera"
[dshow @ 1] DirectShow audio devices
[dshow @ 1]  "Microphone (USB)"
"#;
        let devices = parse_devices(stderr);
        assert_eq!(devices.len(), 1);
        assert_eq!(devices[0].name, "Microphone (USB)");
    }

    #[test]
    fn the_ring_holds_the_clip_and_a_little_more() {
        let args = record_args(
            Platform::Windows, &settings(),
            &Inputs { system: Some("Stereo Mix".into()), microphone: Some("Microphone".into()) },
            30, Path::new("C:\\buf"),
        );
        let wrap = args.iter().position(|a| a == "-segment_wrap").unwrap();
        // 30 seconds at 2 per segment is 15, plus one being written and one
        // about to be overwritten
        assert_eq!(args[wrap + 1], "17");

        let time = args.iter().position(|a| a == "-segment_time").unwrap();
        assert_eq!(args[time + 1], "2");
    }

    #[test]
    fn a_keyframe_every_segment_so_the_cut_can_land() {
        let args = record_args(Platform::Windows, &settings(), &Inputs::default(), 30, Path::new("/b"));
        let g = args.iter().position(|a| a == "-g").unwrap();
        assert_eq!(args[g + 1], "120"); // 60 fps times 2 seconds
    }

    #[test]
    fn both_sounds_are_mixed_and_only_the_microphone_is_cleaned() {
        let args = record_args(
            Platform::Windows, &settings(),
            &Inputs { system: Some("Stereo Mix".into()), microphone: Some("Mic".into()) },
            30, Path::new("/b"),
        );
        let filter = args.iter().position(|a| a == "-filter_complex").unwrap();
        let graph = &args[filter + 1];
        assert!(graph.contains("[2:a]"), "the microphone is the third input: {graph}");
        assert!(graph.contains("afftdn"), "the microphone is denoised: {graph}");
        assert!(graph.starts_with("[2:a]"), "only the microphone is denoised: {graph}");
        assert!(graph.contains("amix=inputs=2"), "both are mixed: {graph}");
    }

    #[test]
    fn with_the_microphone_off_nothing_is_denoised() {
        let mut quiet = settings();
        quiet.microphone = false;
        let args = record_args(
            Platform::Windows, &quiet,
            &Inputs { system: Some("Stereo Mix".into()), microphone: None },
            30, Path::new("/b"),
        );
        let filter = args.iter().position(|a| a == "-filter_complex").unwrap();
        assert!(!args[filter + 1].contains("afftdn"));
        assert!(!args.iter().any(|a| a.starts_with("audio=Mic")));
    }

    #[test]
    fn no_sound_at_all_still_records_a_picture() {
        let args = record_args(Platform::Windows, &settings(), &Inputs::default(), 30, Path::new("/b"));
        assert!(!args.iter().any(|a| a == "-filter_complex"));
        assert!(args.iter().any(|a| a == "0:v"));
        assert!(!args.iter().any(|a| a == "-c:a"));
    }

    #[test]
    fn each_platform_grabs_its_own_screen() {
        let win = record_args(Platform::Windows, &settings(), &Inputs::default(), 30, Path::new("/b"));
        assert!(win.iter().any(|a| a == "gdigrab"));

        let linux = record_args(Platform::Linux, &settings(), &Inputs::default(), 30, Path::new("/b"));
        assert!(linux.iter().any(|a| a == "x11grab"));

        let mac = record_args(Platform::Mac, &settings(), &Inputs::default(), 30, Path::new("/b"));
        assert!(mac.iter().any(|a| a == "avfoundation"));
    }

    fn segment(name: &str, modified: f64) -> Segment {
        Segment { file: PathBuf::from(name), modified }
    }

    #[test]
    fn segments_are_chosen_by_age_not_by_name() {
        // The ring has wrapped: seg000 and seg001 are the newest on disk
        let ring = vec![
            segment("seg000.ts", 1_000.0),
            segment("seg001.ts", 1_002.0),
            segment("seg002.ts", 990.0),
            segment("seg003.ts", 992.0),
            segment("seg004.ts", 994.0),
            segment("seg005.ts", 996.0),
            segment("seg006.ts", 998.0),
        ];
        let chosen = choose_segments(ring, 8);
        let names: Vec<String> = chosen.iter()
            .map(|s| s.file.file_name().unwrap().to_string_lossy().to_string())
            .collect();

        // Eight seconds is four segments, plus one to trim off the front
        assert_eq!(names, vec!["seg004.ts", "seg005.ts", "seg006.ts", "seg000.ts", "seg001.ts"]);
    }

    #[test]
    fn a_ring_that_has_not_filled_up_gives_what_there_is() {
        let ring = vec![segment("seg000.ts", 10.0), segment("seg001.ts", 12.0)];
        assert_eq!(choose_segments(ring, 30).len(), 2);
    }

    #[test]
    fn the_front_is_trimmed_only_when_there_is_too_much() {
        assert!((trim_start(34.0, 30) - 4.0).abs() < 1e-9);
        assert_eq!(trim_start(12.0, 30), 0.0);
    }

    #[test]
    fn the_concat_list_quotes_every_path() {
        let list = concat_list(&[segment("/tmp/a b.ts", 1.0), segment("/tmp/c.ts", 2.0)]);
        assert_eq!(list, "file '/tmp/a b.ts'\nfile '/tmp/c.ts'\n");
    }

    #[test]
    fn the_game_owns_the_length_once_it_has_said_one() {
        let capture = CaptureSettings { seconds: 30, ..Default::default() };
        assert_eq!(effective_seconds(&ModWish { enabled: true, seconds: 45 }, &capture), 45);
        assert_eq!(effective_seconds(&ModWish::default(), &capture), 30);
        // Out of range is brought back rather than passed to FFmpeg
        assert_eq!(effective_seconds(&ModWish { enabled: true, seconds: 9999 }, &capture), 300);
        assert_eq!(effective_seconds(&ModWish { enabled: true, seconds: 1 }, &capture), 5);
    }

    #[test]
    fn either_side_can_ask_for_the_recorder() {
        let off = CaptureSettings { enabled: false, ..Default::default() };
        let on = CaptureSettings { enabled: true, ..Default::default() };
        assert!(wants_recording(&ModWish { enabled: true, seconds: 30 }, &off));
        assert!(wants_recording(&ModWish::default(), &on));
        assert!(!wants_recording(&ModWish::default(), &off));
    }

    #[test]
    fn the_note_about_sound_says_what_is_actually_happening() {
        let mut s = settings();
        let both = Inputs { system: Some("x".into()), microphone: Some("y".into()) };
        assert_eq!(audio_state(&both, &s), "both_clean");

        s.noise_cancel = false;
        assert_eq!(audio_state(&both, &s), "both");

        assert_eq!(audio_state(&Inputs { system: Some("x".into()), microphone: None }, &s), "system_only");
        assert_eq!(audio_state(&Inputs { system: None, microphone: Some("y".into()) }, &s), "mic_only");
        assert_eq!(audio_state(&Inputs::default(), &s), "none");
    }

    #[test]
    fn a_clip_is_named_after_the_moment_it_was_taken() {
        let noon = std::time::UNIX_EPOCH + std::time::Duration::from_secs(1_767_225_600);
        assert_eq!(clip_name(noon), "clip-2026-01-01-000000");
    }

    /// Makes one segment of solid colour, so the content can be checked later.
    fn paint(ffmpeg: &Path, file: &Path, colour: &str, seconds: u32) {
        let status = Command::new(ffmpeg)
            .args([
                "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi",
                "-i", &format!("color=c={colour}:s=160x120:r=30:d={seconds}"),
                "-c:v", "libx264", "-preset", "ultrafast",
                "-g", "60", "-keyint_min", "60", "-sc_threshold", "0",
                "-pix_fmt", "yuv420p", "-f", "mpegts",
            ])
            .arg(file)
            .stdin(Stdio::null())
            .output()
            .expect("ffmpeg should run");
        assert!(status.status.success(), "{}", String::from_utf8_lossy(&status.stderr));
    }

    /// The colour of one frame, as ffprobe sees it.
    fn colour_at(file: &Path, at: &str) -> (u8, u8, u8) {
        let ffmpeg = ffmpeg_path().unwrap();
        let raw = std::env::temp_dir().join(format!("frame-{at}.rawvideo"));
        let done = Command::new(ffmpeg)
            .args(["-hide_banner", "-loglevel", "error", "-y", "-ss", at])
            .arg("-i").arg(file)
            .args(["-frames:v", "1", "-vf", "scale=1:1", "-f", "rawvideo", "-pix_fmt", "rgb24"])
            .arg(&raw)
            .stdin(Stdio::null())
            .output()
            .expect("ffmpeg should run");
        assert!(done.status.success(), "{}", String::from_utf8_lossy(&done.stderr));

        let bytes = std::fs::read(&raw).expect("a frame");
        let _ = std::fs::remove_file(&raw);
        assert!(bytes.len() >= 3, "one pixel of colour");
        (bytes[0], bytes[1], bytes[2])
    }

    fn near(got: (u8, u8, u8), want: (u8, u8, u8)) -> bool {
        let d = |a: u8, b: u8| (a as i16 - b as i16).abs();
        d(got.0, want.0) < 40 && d(got.1, want.1) < 40 && d(got.2, want.2) < 40
    }

    /// The one test here that really cuts video.
    ///
    /// Everything else in this file reasons about arguments; this runs FFmpeg
    /// over a ring that has wrapped and checks the clip that comes out - that
    /// it is the right length, and that it holds the *last* few seconds rather
    /// than whichever segments happened to sort first by name.
    #[test]
    fn a_wrapped_ring_really_stitches_into_the_last_seconds() {
        let (Some(ffmpeg), Some(_probe)) = (ffmpeg_path(), ffprobe_path()) else {
            eprintln!("no FFmpeg here - skipping the one test that needs it");
            return;
        };

        let room = std::env::temp_dir().join("spaceclient-stitch-test");
        let _ = std::fs::remove_dir_all(&room);
        std::fs::create_dir_all(&room).unwrap();

        // Six two-second segments. The names have wrapped: seg000 and seg001
        // were written last, so by name they look like the beginning.
        let colours = ["red", "green", "blue", "yellow", "magenta", "cyan"];
        let files = ["seg002.ts", "seg003.ts", "seg004.ts", "seg005.ts", "seg000.ts", "seg001.ts"];

        let mut segments = Vec::new();
        for (index, (colour, name)) in colours.iter().zip(files.iter()).enumerate() {
            let file = room.join(name);
            paint(&ffmpeg, &file, colour, SEGMENT_SECONDS);
            segments.push(Segment { file, modified: 1_000.0 + index as f64 * 2.0 });
        }

        let chosen = choose_segments(segments, 6);
        let target = room.join("clip.mp4");
        stitch(&ffmpeg, &chosen, 6, &room.join("work"), &target).expect("a clip");

        let length = duration_of(&target).expect("a length");
        assert!((length - 6.0).abs() < 0.5, "six seconds, got {length}");

        // Six seconds is the last three segments: yellow, magenta, cyan
        let first = colour_at(&target, "0.5");
        let last = colour_at(&target, "5.5");
        assert!(near(first, (255, 255, 0)), "starts on yellow, got {first:?}");
        assert!(near(last, (0, 255, 255)), "ends on cyan, got {last:?}");

        let _ = std::fs::remove_dir_all(&room);
    }

    /// The two programs meeting in the middle.
    ///
    /// Skipped unless something has just run the mod's side against the same
    /// data folder - normally nothing has, and a test that passed because the
    /// folder was empty would be worse than no test. The runner that sets this
    /// lives with the mod's probes.
    #[test]
    fn what_the_mod_writes_is_what_this_reads() {
        if std::env::var("SPACECLIENT_LINK_TEST").is_err() {
            eprintln!("no mod run to check against - skipping the link test");
            return;
        }

        let wish = load_wish();
        assert!(wish.enabled, "the mod said clips are on");
        assert_eq!(wish.seconds, 45, "and that they are 45 seconds long");
        assert_eq!(effective_seconds(&wish, &CaptureSettings::default()), 45);

        let requests: Vec<_> = std::fs::read_dir(request_dir())
            .expect("a requests folder")
            .flatten()
            .filter(|e| e.path().extension().and_then(|x| x.to_str()) == Some("json"))
            .collect();
        assert_eq!(requests.len(), 1, "one key press, one request");

        let text = std::fs::read_to_string(requests[0].path()).unwrap();
        let value: serde_json::Value = serde_json::from_str(&text).expect("readable json");
        assert_eq!(value["seconds"].as_u64(), Some(45));
        assert!(value["at"].as_u64().unwrap_or(0) > 1_600_000_000_000, "a timestamp in millis");

        // Nothing half written is ever left behind for the watcher to trip on
        let leftovers: Vec<_> = std::fs::read_dir(request_dir())
            .unwrap()
            .flatten()
            .filter(|e| e.path().to_string_lossy().ends_with(".tmp"))
            .collect();
        assert!(leftovers.is_empty(), "no half written files left behind");
    }

    #[test]
    fn deleting_takes_a_name_and_never_a_path() {
        assert!(delete_clip("../../secrets").is_err() || !Path::new("../../secrets.mp4").exists());
        assert!(delete_clip("").is_err());
    }
}
