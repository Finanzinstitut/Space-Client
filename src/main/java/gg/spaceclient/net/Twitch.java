package gg.spaceclient.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import gg.spaceclient.SpaceClient;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * The Twitch link, and the numbers behind the follower readout.
 *
 * No Twitch credentials live in this class, or anywhere else in the mod. The
 * mod is distributed publicly, so anything embedded in it is readable by
 * everyone who downloads it - the client id sits on the worker and every call
 * to Twitch is made from there.
 *
 * Linking uses Twitch's device code grant: the worker asks Twitch for a short
 * code, the player types it into twitch.tv/activate in a browser, and the
 * worker swaps it for a token once they have. That flow exists precisely for
 * clients that cannot host a redirect URL, which a Minecraft mod cannot, and it
 * has the pleasant side effect of never showing this mod a password.
 */
public final class Twitch {

    /**
     * How often the follower numbers are refreshed.
     *
     * A minute. The worker caches Twitch's answer for the same period, so most
     * of these calls are answered at the edge without troubling Twitch's rate
     * limit, and a follower count that is up to a minute stale has never
     * mattered to anyone.
     */
    private static final long POLL_MS = 60 * 1000L;

    /** How often the pending device code is checked while the player links. */
    private static final long LINK_POLL_MS = 5 * 1000L;

    private static volatile boolean linked = false;
    private static volatile String login = "";
    private static volatile int followers = -1;
    private static volatile String lastFollower = "";

    private static volatile String userCode = "";
    private static volatile String verifyUrl = "";
    private static volatile boolean linking = false;
    private static volatile String status = "not linked";

    private static volatile long nextPoll = 0;
    private static volatile long nextLinkPoll = 0;
    private static volatile boolean busy = false;

    public static boolean isLinked() { return linked; }
    public static String login() { return login; }
    public static int followers() { return followers; }
    public static String lastFollower() { return lastFollower; }
    public static boolean isLinking() { return linking; }
    public static String userCode() { return userCode; }
    public static String verifyUrl() { return verifyUrl; }
    public static String status() { return status; }

    /** Begins the device flow; the screen then shows the code to type. */
    public static void startLink() {
        if (busy) return;
        busy = true;
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject response = post("/stream/twitch/start", null);
                if (response == null) return;

                userCode = optString(response, "user_code");
                verifyUrl = optString(response, "verification_uri");
                linking = !userCode.isEmpty();
                nextLinkPoll = System.currentTimeMillis() + LINK_POLL_MS;
                status = linking ? "waiting for you to approve" : "could not start";
            } finally {
                busy = false;
            }
        });
    }

    public static void cancelLink() {
        linking = false;
        userCode = "";
        verifyUrl = "";
        status = linked ? "linked" : "not linked";
    }

    public static void unlink() {
        CompletableFuture.runAsync(() -> {
            post("/stream/twitch/unlink", null);
            linked = false;
            login = "";
            followers = -1;
            lastFollower = "";
            status = "not linked";
        });
    }

    /** Called every client tick. */
    public static void tick() {
        try {
            long now = System.currentTimeMillis();

            if (linking && !busy && now >= nextLinkPoll) {
                busy = true;
                nextLinkPoll = now + LINK_POLL_MS;
                CompletableFuture.runAsync(() -> {
                    try {
                        JsonObject response = post("/stream/twitch/poll", null);
                        if (response == null) return;

                        // "pending" is the normal answer while the player is
                        // still typing the code, not a failure
                        if (response.has("pending")
                                && response.get("pending").getAsBoolean()) {
                            return;
                        }
                        if (response.has("login")) {
                            linked = true;
                            linking = false;
                            userCode = "";
                            login = optString(response, "login");
                            status = "linked";
                            nextPoll = 0;
                        } else if (response.has("error")) {
                            linking = false;
                            status = optString(response, "error");
                        }
                    } finally {
                        busy = false;
                    }
                });
            }

            if (linked && !busy && now >= nextPoll) {
                busy = true;
                nextPoll = now + POLL_MS;
                CompletableFuture.runAsync(() -> {
                    try {
                        JsonObject response = get("/stream/twitch");
                        if (response == null) return;

                        if (response.has("followers")) {
                            followers = response.get("followers").getAsInt();
                            lastFollower = optString(response, "lastFollower");
                            login = optString(response, "login");
                            status = "linked";
                        } else if (response.has("error")) {
                            status = optString(response, "error");
                            // A revoked token is the usual cause, and there is
                            // nothing to poll for once that has happened
                            if (status.contains("relink")) linked = false;
                        }
                    } finally {
                        busy = false;
                    }
                });
            }

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Twitch poll failed: {}", t.getMessage());
        }
    }

    /** Asks the worker once, so a freshly opened screen is not a minute stale. */
    public static void refreshSoon() { nextPoll = 0; }

    // ---------------- plumbing ----------------

    private static String optString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull()
                ? json.get(key).getAsString() : "";
    }

    private static JsonObject post(String path, String body) {
        return call(path, body == null ? "{}" : body, true);
    }

    private static JsonObject get(String path) {
        return call(path, null, false);
    }

    private static JsonObject call(String path, String body, boolean post) {
        String bearer = SpaceApi.tokenForStreaming();
        if (bearer.isEmpty()) {
            status = "not signed in";
            return null;
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(SpaceApi.BASE + path))
                    .header("Authorization", "Bearer " + bearer)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(12));

            HttpRequest request = post
                    ? builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
                    : builder.GET().build();

            HttpResponse<String> response = SpaceApi.client()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            JsonObject parsed = JsonParser.parseString(response.body()).getAsJsonObject();
            if (response.statusCode() != 200 && !parsed.has("error")) {
                status = "worker said " + response.statusCode();
                return null;
            }
            return parsed;

        } catch (Throwable t) {
            status = "offline";
            return null;
        }
    }

    private Twitch() {}
}
