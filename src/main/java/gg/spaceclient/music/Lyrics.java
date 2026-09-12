package gg.spaceclient.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The line of the song that is playing right now.
 *
 * Lyrics come from LRCLIB, which is free and needs no account. They are
 * contributed by users rather than licensed from publishers, which is worth
 * knowing before switching this on - hence the warning on the setting.
 *
 * No lyrics pass through the Space Client backend at all. Each client fetches
 * the words for whatever the people around it are playing and works out the
 * current line itself, from a playback position. That is what keeps the line
 * in step: it is computed locally every frame instead of arriving on a poll,
 * and it also means the backend never stores anybody else's text.
 */
public final class Lyrics {

    /** Longer than this and it would not fit over a head anyway. */
    private static final int MAX_LINE = 64;

    private static final String USER_AGENT =
            "SpaceClient/1.0 (https://github.com/Finanzinstitut)";

    /** How many tracks are kept. Enough for everyone in sight, not unbounded. */
    private static final int MAX_TRACKS = 24;

    private record Line(double at, String text) {}

    private record Track(List<Line> lines, String status) {}

    /**
     * Lyrics per track, not just for the local one.
     *
     * Every player in sight may be playing something different, and each of
     * their lines is worked out here rather than sent over the wire - so this
     * holds several songs at once.
     */
    private static final Map<String, Track> cache = new ConcurrentHashMap<>();

    /** Tracks currently being fetched, so a miss does not start ten requests. */
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();

    private static volatile String status = "off";

    public static String status() { return status; }

    /**
     * The line for the current moment, or empty.
     *
     * Deliberately blank rather than falling back to the previous line when the
     * position runs past the end - a song that has finished should show nothing
     * rather than freeze on its last words.
     */
    public static String line(String artist, String title, double position) {
        if (title == null || title.isEmpty() || position < 0) return "";

        String key = key(artist, title);
        Track track = cache.get(key);

        if (track == null) {
            request(artist, title, key);
            return "";
        }

        String found = "";
        for (Line line : track.lines()) {
            if (line.at() > position) break;
            found = line.text();
        }
        return found;
    }

    /** Drops everything, for when the setting goes off. */
    public static void clear() {
        cache.clear();
        pending.clear();
        status = "off";
    }

    private static String key(String artist, String title) {
        return (artist == null ? "" : artist) + "\u0000" + title;
    }

    /** Starts a lookup for a track nobody has fetched yet. */
    private static void request(String artist, String title, String key) {
        if (!pending.add(key)) return;

        // A negative result is cached like any other, so a track with no
        // lyrics is asked about once rather than on every single frame
        if (cache.size() >= MAX_TRACKS) cache.clear();

        status = "looking up...";

        CompletableFuture.runAsync(() -> {
            try {
                fetch(artist, title, key);
            } catch (Throwable t) {
                cache.put(key, new Track(List.of(), "lookup failed"));
                status = "lookup failed: " + t.getMessage();
            } finally {
                pending.remove(key);
            }
        });
    }

    // ---------------- fetching ----------------

    private static void fetch(String artist, String title, String key) throws Exception {
        String url = "https://lrclib.net/api/get"
                + "?artist_name=" + encode(artist)
                + "&track_name=" + encode(title);

        String body = get(url);

        // The exact match endpoint is strict about spelling, so a miss falls
        // back to a search - which is what makes live and remastered versions
        // resolve at all
        if (body == null) {
            body = searchFor(artist, title);
            if (body == null) {
                cache.put(key, new Track(List.of(), "no lyrics found"));
                status = "no lyrics found";
                return;
            }
        }

        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        if (!json.has("syncedLyrics") || json.get("syncedLyrics").isJsonNull()) {
            // Plain lyrics exist for many tracks but carry no timestamps, and
            // an unsynced block cannot be shown one line at a time
            cache.put(key, new Track(List.of(), "found, but not synced"));
            status = "found, but not synced";
            return;
        }

        List<Line> parsed = parse(json.get("syncedLyrics").getAsString());
        cache.put(key, new Track(parsed, parsed.size() + " lines"));
        status = parsed.isEmpty() ? "synced lyrics were empty" : parsed.size() + " lines";
    }

    private static String searchFor(String artist, String title) throws Exception {
        String url = "https://lrclib.net/api/search"
                + "?q=" + encode(artist + " " + title);

        String body = get(url);
        if (body == null) return null;

        JsonArray results = JsonParser.parseString(body).getAsJsonArray();

        for (int i = 0; i < results.size(); i++) {
            JsonObject candidate = results.get(i).getAsJsonObject();
            if (candidate.has("syncedLyrics") && !candidate.get("syncedLyrics").isJsonNull()) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(Duration.ofSeconds(8))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        return response.statusCode() == 200 ? response.body() : null;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }

    // ---------------- LRC format ----------------

    /**
     * Turns an LRC block into timed lines.
     *
     * A line can carry several timestamps when the same words repeat, so each
     * one becomes its own entry. Entries with no text are kept: those are the
     * instrumental gaps, and dropping them would leave the previous line
     * hanging on screen through the whole solo.
     */
    private static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();

        for (String raw : lrc.split("\n")) {
            String rest = raw;
            List<Double> stamps = new ArrayList<>();

            while (rest.startsWith("[")) {
                int close = rest.indexOf(']');
                if (close < 0) break;

                String stamp = rest.substring(1, close);
                rest = rest.substring(close + 1);

                Double seconds = seconds(stamp);
                if (seconds != null) stamps.add(seconds);
            }

            if (stamps.isEmpty()) continue;

            String text = rest.trim();
            if (text.length() > MAX_LINE) text = text.substring(0, MAX_LINE);

            for (double at : stamps) out.add(new Line(at, text));
        }

        out.sort((a, b) -> Double.compare(a.at(), b.at()));
        return out;
    }

    /** Reads mm:ss.xx, or null when the brackets held metadata instead. */
    private static Double seconds(String stamp) {
        try {
            int colon = stamp.indexOf(':');
            if (colon < 0) return null;

            int minutes = Integer.parseInt(stamp.substring(0, colon).trim());
            double rest = Double.parseDouble(stamp.substring(colon + 1).trim());
            return minutes * 60.0 + rest;

        } catch (Throwable ignored) {
            // [ar: ...] and friends land here, which is expected
            return null;
        }
    }

    private Lyrics() {}
}
