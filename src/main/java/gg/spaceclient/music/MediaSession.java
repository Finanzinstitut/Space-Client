package gg.spaceclient.music;

import gg.spaceclient.SpaceClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Asks Windows itself what is playing, through the media transport controls.
 *
 * This is the same information the volume overlay shows: every app that plays
 * audio registers a session with a title, an artist and the app it belongs to.
 *
 * It exists because reading window titles only works for players that put the
 * track there. Spotify does; Amazon Music's newer app does not - its window is
 * simply called "Amazon Music" whatever is playing, which is why it looked
 * permanently idle. The session list has the track regardless.
 */
public final class MediaSession {

    /** Why the last lookup failed, for the diagnostics page. */
    private static volatile String status = "not run yet";

    /**
     * Where the current track is, in seconds, or -1 when Windows did not say.
     *
     * Reported separately from NowPlaying on purpose: a track that plays is
     * useful on its own, and this may well be missing. Whether it is here at
     * all decides whether a synced lyric line is even possible - without a
     * position there is nothing to sync to.
     */
    private static volatile double position = -1;
    private static volatile double duration = -1;

    /** When the position above was measured, so it can be advanced between polls. */
    private static volatile long measuredAt = 0;

    /** The previous raw reading, to tell a running timeline from a frozen one. */
    private static volatile double previousRaw = -1;
    private static volatile int stuckReadings = 0;

    public static String status() { return status; }

    /**
     * The playback position right now, in seconds, or -1 when unknown.
     *
     * Windows only updates the timeline when the player pushes it, which can be
     * seconds apart. So the last reading is carried forward by the time since
     * it was taken - close enough for picking a lyric line, and far better than
     * a value that jumps in steps.
     */
    public static double position() {
        if (position < 0 || stuckReadings >= 3) return -1;
        return position + (System.currentTimeMillis() - measuredAt) / 1000.0;
    }

    public static double duration() { return duration; }

    /** What the timeline lookup produced, in words, for the diagnostics page. */
    public static String timelineStatus() {
        if (position < 0) return "no position reported";
        if (stuckReadings >= 3) {
            return "position reported but frozen - unusable for syncing";
        }
        return String.format("%.0fs of %.0fs", position(), duration);
    }

    /** Which apps count. Anything else, a browser included, is ignored. */
    private static boolean isWanted(String appId) {
        String lower = appId.toLowerCase();
        return lower.contains("spotify") || lower.contains("amazon");
    }

    private static String sourceOf(String appId) {
        return appId.toLowerCase().contains("spotify") ? "Spotify" : "Amazon Music";
    }

    /**
     * @return what is playing, or null when the lookup could not run at all
     *
     * The difference matters more than it looks. Null sends the caller to the
     * window title fallback; NOTHING tells it the interface answered and there
     * really is no music. Conflating the two was the bug that made this work
     * only sometimes: a failed WinRT call came back looking exactly like
     * silence, the fallback never ran, and the overlay went blank until the
     * next poll happened to succeed.
     */
    public static NowPlaying read() {
        Path script = scriptFile();
        if (script == null) {
            status = "script could not be written";
            return null;
        }

        try {
            Process process = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString())
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exit = process.waitFor();

            String text = output.toString();

            // The script reports its own failures on a line of this shape
            if (text.contains("ERROR|")) {
                int start = text.indexOf("ERROR|") + 6;
                int end = text.indexOf('\n', start);
                status = "session interface failed: "
                        + text.substring(start, end < 0 ? text.length() : end).trim();
                return null;
            }

            if (exit != 0) {
                status = "session lookup exited " + exit;
                return null;
            }

            if (text.isBlank()) {
                // No sessions at all reads the same as a silently broken call,
                // so this goes to the fallback rather than claiming silence
                status = "session list came back empty";
                return null;
            }

            NowPlaying paused = null;

            for (String line : output.toString().split("\n")) {
                // appId | status | artist | title | position | duration
                String[] parts = line.split("\\|", -1);
                if (parts.length < 4) continue;

                String appId = parts[0].trim();
                String playback = parts[1].trim();
                String artist = parts[2].trim();
                String title = parts[3].trim();

                if (title.isEmpty() || !isWanted(appId)) continue;

                NowPlaying track = new NowPlaying(
                        sourceOf(appId), artist, title, playback.equalsIgnoreCase("Playing"));

                // A playing session wins; a paused one is kept in case nothing
                // is actually playing right now.
                if (track.playing()) {
                    readTimeline(parts);
                    return track;
                }
                if (paused == null) {
                    paused = track;
                    readTimeline(parts);
                }
            }

            status = "ok";
            return paused != null ? paused : NowPlaying.NOTHING;

        } catch (Throwable t) {
            status = "session lookup threw: " + t.getMessage();
            SpaceClient.LOGGER.warn("Media session lookup failed: {}", t.getMessage());
            return null;
        }
    }

    /** Picks the timeline fields out of a line, if the script managed to read them. */
    private static void readTimeline(String[] parts) {
        try {
            if (parts.length < 6) {
                position = -1;
                duration = -1;
                return;
            }
            double raw = Double.parseDouble(parts[4].trim());
            double length = Double.parseDouble(parts[5].trim());

            // A track with no length is a player that fills the fields in
            // without meaning them. Better to call that unknown than to hand
            // out a position with nothing to measure it against.
            if (length <= 0) {
                position = -1;
                duration = -1;
                return;
            }

            // Some players report a timeline that never moves. Carrying that
            // forward produces a number that rises convincingly and means
            // nothing, so a value standing still across polls is treated as
            // no timeline at all.
            if (raw == previousRaw) {
                if (stuckReadings < 10) stuckReadings++;
            } else {
                stuckReadings = 0;
            }
            previousRaw = raw;

            position = raw;
            duration = length;
            measuredAt = System.currentTimeMillis();

        } catch (Throwable ignored) {
            // A player that reports no timeline is normal, not an error
            position = -1;
            duration = -1;
        }
    }

    /**
     * Writes the query script once and reuses it.
     *
     * The transport controls are a WinRT interface, and its calls are
     * asynchronous. PowerShell has no await, so the returned operation is turned
     * into a Task through the runtime extensions and waited on - the usual way
     * of reaching WinRT from a script.
     */
    private static Path scriptFile() {
        try {
            // Versioned: the file is cached in temp and only written when missing,
            // so a fixed script would never reach a machine that already had the
            // broken one. Bump this whenever the script below changes.
            Path path = Path.of(System.getProperty("java.io.tmpdir"), "spaceclient-media-3.ps1");
            if (Files.exists(path)) return path;

            String script = String.join("\n",
                    "$ErrorActionPreference = 'Stop'",
                    "try {",
                    "  Add-Type -AssemblyName System.Runtime.WindowsRuntime",
                    "  $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() |",
                    "    Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and",
                    "      $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
                    "",
                    "  function Await($operation, $resultType) {",
                    "    $method = $asTask.MakeGenericMethod($resultType)",
                    "    $task = $method.Invoke($null, @($operation))",
                    "    if (-not $task.Wait(4000)) { throw 'media session call timed out' }",
                    "    $task.Result",
                    "  }",
                    "",
                    "  $managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager," +
                            "Windows.Media.Control,ContentType=WindowsRuntime]",
                    "  $manager = Await ($managerType::RequestAsync()) ([Windows.Media.Control." +
                            "GlobalSystemMediaTransportControlsSessionManager])",
                    "",
                    "  foreach ($session in $manager.GetSessions()) {",
                    "    $appId = $session.SourceAppUserModelId",
                    "    $status = $session.GetPlaybackInfo().PlaybackStatus",
                    "    $properties = Await ($session.TryGetMediaPropertiesAsync()) " +
                            "([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
                    "",
                    "    # The timeline is optional - a player may report none at all,",
                    "    # so this must never take the whole line down with it",
                    "    $pos = -1",
                    "    $dur = -1",
                    "    try {",
                    "      $timeline = $session.GetTimelineProperties()",
                    "      if ($timeline -ne $null) {",
                    "        $pos = $timeline.Position.TotalSeconds",
                    "        $dur = $timeline.EndTime.TotalSeconds",
                    "      }",
                    "    } catch { }",
                    "",
                    "    Write-Output (\"$appId|$status|\" + $properties.Artist + '|' + " +
                            "$properties.Title + '|' + $pos + '|' + $dur)",
                    "  }",
                    "} catch {",
                    "  Write-Output ('ERROR|' + $_.Exception.Message)",
                    "}");

            Files.writeString(path, script, StandardCharsets.UTF_8);
            return path;

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Could not write the media session script: {}", t.getMessage());
            return null;
        }
    }

    private MediaSession() {}
}
