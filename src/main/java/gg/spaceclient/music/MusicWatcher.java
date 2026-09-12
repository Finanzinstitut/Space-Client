package gg.spaceclient.music;

import gg.spaceclient.SpaceClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Reads what the local Spotify or Amazon Music app is playing.
 *
 * Both apps put the current track in their window title, which is the only
 * thing available without signing into a web API. That has a deliberate side
 * effect: music playing in a browser tab is not picked up, because no such
 * process exists - which is exactly the wanted behaviour here.
 *
 * The lookup runs through PowerShell rather than a native library, so nothing
 * has to be shipped alongside the mod. It is therefore Windows only; on other
 * systems the module reports that and stays quiet.
 */
public final class MusicWatcher {
    /** Titles these apps show when nothing is playing. */
    private static final String[] IDLE_TITLES = {
            "spotify", "spotify premium", "spotify free", "amazon music", "advertisement"
    };

    private static final long POLL_MS = 2500;

    private static volatile NowPlaying current = NowPlaying.NOTHING;
    private static volatile boolean polling = false;
    private static volatile boolean supported = isWindows();
    private static volatile String status = supported ? "waiting" : "only available on Windows";

    private static long lastPoll = 0;

    /**
     * How many polls in a row have come back with nothing.
     *
     * A track is not dropped on the first empty answer. Both lookups go through
     * a freshly spawned PowerShell, and one of those occasionally comes back
     * slow or empty while the music keeps playing - which showed up as the
     * overlay blinking out for a couple of seconds at a time. Two agreeing
     * answers are needed before the track is considered gone.
     */
    private static int emptyReads = 0;

    public static NowPlaying current() { return current; }
    public static boolean isSupported() { return supported; }
    public static String status() { return status; }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Called every client tick; does its work at most every few seconds. */
    public static void tick() {
        if (!supported || polling) return;

        long now = System.currentTimeMillis();
        if (now - lastPoll < POLL_MS) return;
        lastPoll = now;

        polling = true;
        CompletableFuture.runAsync(() -> {
            try {
                // Windows' own media session list is asked first: it carries the
                // track for every player, including the ones that never put it
                // in their window title. The title scan stays as a fallback for
                // when that interface is unavailable.
                NowPlaying session = MediaSession.read();

                if (session != null && !session.isEmpty()) {
                    status = "media session: " + session.source();
                    apply(session);
                } else if (session != null) {
                    // The interface worked and reported nothing playing
                    status = "nothing playing";
                    apply(NowPlaying.NOTHING);
                } else {
                    // The interface could not be reached at all, so the window
                    // titles get their turn - Spotify still puts the track there
                    apply(read());
                }
            } catch (Throwable t) {
                status = "lookup failed: " + t.getMessage();
                apply(NowPlaying.NOTHING);
            } finally {
                polling = false;
            }
        });
    }

    /**
     * Takes a fresh reading, holding the old one through a single empty answer.
     */
    private static void apply(NowPlaying reading) {
        if (reading != null && !reading.isEmpty()) {
            emptyReads = 0;
            current = reading;
            return;
        }

        emptyReads++;
        if (emptyReads >= 2) current = NowPlaying.NOTHING;
    }

    /**
     * Asks Windows for the window titles of the two players.
     *
     * Only these two process names are considered, so a browser playing music
     * is ignored no matter what its tab is called.
     */
    /** Process names both players have shipped under. */
    private static final String[] SPOTIFY_NAMES = {"spotify"};
    private static final String[] AMAZON_NAMES = {
            "amazon music", "amazonmusic", "amazon music for pc", "amazonmusichelper"
    };

    /** What the last scan saw, for the diagnostics page. */
    private static volatile String seenProcesses = "nothing scanned yet";

    public static String seenProcesses() { return seenProcesses; }

    private static NowPlaying read() throws Exception {
        // Every process with a window is listed, then matched here rather than
        // filtered by name in PowerShell. Amazon Music has shipped under more
        // than one process name, and asking for a name that does not exist
        // returns nothing at all - which looked exactly like "not playing".
        String script =
                "Get-Process | Where-Object { $_.MainWindowTitle -ne '' } " +
                "| ForEach-Object { $_.ProcessName + '|' + $_.MainWindowTitle }";

        ProcessBuilder builder = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        process.waitFor();

        StringBuilder players = new StringBuilder();

        for (String line : output.toString().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.contains("|")) continue;

            String process_name = trimmed.substring(0, trimmed.indexOf('|')).trim();
            String windowTitle = trimmed.substring(trimmed.indexOf('|') + 1).trim();

            String source = sourceOf(process_name);
            if (source == null) continue;

            if (players.length() > 0) players.append(", ");
            players.append(process_name);
            seenProcesses = players.toString();

            // An idle title means the app is open but paused or stopped
            boolean idle = false;
            for (String candidate : IDLE_TITLES) {
                if (windowTitle.equalsIgnoreCase(candidate)) idle = true;
            }
            if (idle) {
                status = source + " open, nothing playing";
                continue;
            }

            status = "reading " + source;
            return parse(source, windowTitle);
        }

        seenProcesses = players.length() == 0 ? "no player process with a window" : players.toString();
        status = "no player running";
        return NowPlaying.NOTHING;
    }

    /** Which player a process belongs to, or null when it is neither. */
    private static String sourceOf(String processName) {
        String lower = processName.toLowerCase();

        for (String candidate : SPOTIFY_NAMES) {
            if (lower.equals(candidate)) return "Spotify";
        }
        for (String candidate : AMAZON_NAMES) {
            if (lower.equals(candidate)) return "Amazon Music";
        }
        // Some builds append a version or suffix to the process name
        if (lower.startsWith("amazon music") || lower.startsWith("amazonmusic")) {
            return "Amazon Music";
        }
        return null;
    }

    /**
     * Splits the window title into artist and track.
     *
     * Spotify writes "Artist - Title". Amazon Music has used both that order and
     * the reverse across versions, so the split is kept simple and the whole
     * title is shown when it does not match.
     */
    private static NowPlaying parse(String source, String windowTitle) {
        String cleaned = windowTitle;

        // Amazon Music appends its own name on some builds
        for (String suffix : new String[]{" - Amazon Music", " | Amazon Music"}) {
            if (cleaned.endsWith(suffix)) {
                cleaned = cleaned.substring(0, cleaned.length() - suffix.length());
            }
        }

        int separator = cleaned.indexOf(" - ");
        if (separator > 0 && separator < cleaned.length() - 3) {
            String left = cleaned.substring(0, separator).trim();
            String right = cleaned.substring(separator + 3).trim();
            return new NowPlaying(source, left, right, true);
        }
        return new NowPlaying(source, "", cleaned.trim(), true);
    }

    // ---------------- controls ----------------

    /** Virtual key codes for the media keys Windows listens to. */
    private static final int NEXT = 0xB0;
    private static final int PREVIOUS = 0xB1;
    private static final int PLAY_PAUSE = 0xB3;

    public static void next() { sendMediaKey(NEXT); }
    public static void previous() { sendMediaKey(PREVIOUS); }
    public static void playPause() { sendMediaKey(PLAY_PAUSE); }

    /**
     * Presses a media key system wide.
     *
     * Windows routes these to whichever app is currently the media session, so
     * the controls reach the player that is actually making sound rather than
     * needing to talk to it directly.
     */
    private static void sendMediaKey(int virtualKey) {
        if (!supported) return;

        CompletableFuture.runAsync(() -> {
            try {
                java.nio.file.Path script = mediaKeyScript();
                if (script == null) {
                    SpaceClient.LOGGER.warn("Media key script could not be written");
                    return;
                }

                Process process = new ProcessBuilder(
                        "powershell", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass",
                        "-File", script.toString(),
                        String.valueOf(virtualKey))
                        .redirectErrorStream(true)
                        .start();

                // Read the output, or a full pipe would leave the process hanging
                StringBuilder result = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) result.append(line).append(' ');
                }
                int exit = process.waitFor();

                if (exit != 0) {
                    status = "media key failed: " + result.toString().trim();
                    SpaceClient.LOGGER.warn("Media key exit {}: {}", exit, result.toString().trim());
                } else {
                    // Give the player a moment, then read the new track
                    Thread.sleep(600);
                    lastPoll = 0;
                }

            } catch (Throwable t) {
                status = "media key failed: " + t.getMessage();
                SpaceClient.LOGGER.warn("Media key failed", t);
            }
        });
    }

    /**
     * Writes the key press helper to a file once and reuses it.
     *
     * Passing this as an inline -Command was the mistake: the script needs
     * double quotes around the DLL name, and those do not survive being handed
     * through as a single argument - PowerShell then saw a broken statement,
     * exited without doing anything, and the button clicked to no effect.
     */
    private static java.nio.file.Path mediaKeyScript() {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(
                    System.getProperty("java.io.tmpdir"), "spaceclient-mediakey.ps1");

            if (!java.nio.file.Files.exists(path)) {
                String script = String.join("\n",
                        "param([int]$Key)",
                        "$definition = @'",
                        "using System;",
                        "using System.Runtime.InteropServices;",
                        "public static class SpaceClientMedia {",
                        "  [DllImport(\"user32.dll\")]",
                        "  public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, IntPtr dwExtraInfo);",
                        "  public static void Press(byte key) {",
                        "    keybd_event(key, 0, 0, IntPtr.Zero);",
                        "    keybd_event(key, 0, 2, IntPtr.Zero);",
                        "  }",
                        "}",
                        "'@",
                        "Add-Type -TypeDefinition $definition -Language CSharp | Out-Null",
                        "[SpaceClientMedia]::Press([byte]$Key)");

                java.nio.file.Files.writeString(path, script, StandardCharsets.UTF_8);
            }
            return path;

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Could not write the media key script: {}", t.getMessage());
            return null;
        }
    }

    private MusicWatcher() {}
}
