package gg.spaceclient.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.util.Reflect;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Talks to the Space Client worker.
 *
 * This is the piece that went with ShopClient - nothing in the mod had spoken
 * to the worker since. What comes back is deliberately smaller than what was
 * there before: one handshake, one short lived token, and two calls that carry
 * nothing but a song.
 *
 * Why a token instead of handshaking per request, which is what the shop did:
 * a handshake is a joinServer call against Mojang, and the song is reported on
 * a timer. Handshaking every time would mean hitting Mojang every few seconds
 * for a heartbeat, which is both wasteful and a good way to get rate limited.
 * So the handshake happens once, the worker hands back a token, and the
 * heartbeats carry only that.
 *
 * The access token never leaves this machine. The game proves who it is to
 * Mojang directly; the worker only asks Mojang whether that happened.
 */
public final class SpaceApi {

    /**
     * The worker.
     *
     * ---> FILL THIS IN <--- The old value went with ShopClient and I have no
     * copy of it. It is whatever `wrangler deploy` prints for the
     * `spaceclient-badges` worker, without a trailing slash - typically
     * https://spaceclient-badges.<your-subdomain>.workers.dev
     *
     * Until it is right, every call here fails quietly and the feature simply
     * does nothing. Nothing else in the mod is affected.
     */
    public static final String BASE = "https://spaceclient-badges.spaceclient-finanzinstitut.workers.dev";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile String token = "";
    private static volatile long tokenExpiresAt = 0;

    /** Last failure, so the diagnostics screen can say why nothing shows. */
    private static volatile String status = "not started";

    /**
     * The same, for the badge calls.
     *
     * Kept apart from `status` on purpose: the now playing calls run every few
     * seconds and would overwrite a badge failure within a tick of it
     * happening, which is exactly the message worth reading.
     */
    private static volatile String badgeStatus = "not started";

    public static String status() { return status; }
    public static String badgeStatus() { return badgeStatus; }
    public static boolean hasToken() { return !token.isEmpty(); }

    private static HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    // ---------------- identity ----------------

    /**
     * Gets a token, handshaking first if the current one has run out.
     *
     * Runs on a background thread only - joinServer is a network call and the
     * render thread must never wait on it.
     */
    private static synchronized String ensureToken() {
        // A minute of slack, so a token does not expire mid request
        if (!token.isEmpty() && System.currentTimeMillis() < tokenExpiresAt - 60_000) {
            return token;
        }

        Minecraft mc = Minecraft.getInstance();
        String name;
        try {
            name = mc.getUser().getName();
        } catch (Throwable t) {
            status = "no account in the running game";
            return "";
        }
        if (name == null || name.isEmpty()) {
            status = "no account name";
            return "";
        }

        String serverId = randomServerId();
        if (!joinServer(serverId)) {
            // joinServer already set the status
            return "";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/np/session"))
                    .header("X-Space-Name", name)
                    .header("X-Space-Server", serverId)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response =
                    http().send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                status = "session refused (" + response.statusCode() + "): "
                        + shorten(response.body());
                return "";
            }

            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!body.has("token")) {
                status = "worker returned no token";
                return "";
            }

            token = body.get("token").getAsString();
            long seconds = body.has("expiresIn") ? body.get("expiresIn").getAsLong() : 3600;
            tokenExpiresAt = System.currentTimeMillis() + seconds * 1000L;
            status = "ok";
            return token;

        } catch (Throwable t) {
            status = "could not reach the worker: " + t.getMessage();
            return "";
        }
    }

    /** A one time id for the handshake, in the shape Mojang accepts. */
    private static String randomServerId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    /**
     * Tells Mojang this account is joining a server with the given id.
     *
     * Done through reflection rather than a direct call: the session service
     * lives in authlib, whose joinServer has taken a GameProfile on some
     * versions and a bare UUID on others. Matching by parameter type means the
     * mod does not have to be right about which one this version ships.
     */
    private static boolean joinServer(String serverId) {
        try {
            Minecraft mc = Minecraft.getInstance();

            Object service = findSessionService(mc);
            if (service == null) {
                status = "no session service found on Minecraft";
                return false;
            }

            Object user = Reflect.call(mc, "getUser");
            if (user == null) {
                status = "no account object";
                return false;
            }

            Object accessToken = Reflect.call(user,
                    "getAccessToken", "accessToken", "getSessionId");
            if (!(accessToken instanceof String secret) || secret.isEmpty()) {
                status = "no access token reachable on the account object";
                return false;
            }

            Object profile = Reflect.call(user, "getGameProfile", "gameProfile");
            if (profile == null) profile = Reflect.call(mc, "getGameProfile");
            UUID uuid = profileUuid(user, mc);

            for (Method method : service.getClass().getMethods()) {
                if (!method.getName().equals("joinServer")) continue;

                Class<?>[] parameters = method.getParameterTypes();
                Object[] arguments = new Object[parameters.length];

                // Strings are filled in the order authlib has always used them:
                // the token first, then the server id.
                List<String> strings = List.of(secret, serverId);
                int stringIndex = 0;
                boolean complete = true;

                for (int i = 0; i < parameters.length; i++) {
                    Class<?> parameter = parameters[i];

                    if (parameter == String.class) {
                        if (stringIndex >= strings.size()) { complete = false; break; }
                        arguments[i] = strings.get(stringIndex++);
                    } else if (parameter == UUID.class) {
                        if (uuid == null) { complete = false; break; }
                        arguments[i] = uuid;
                    } else if (profile != null && parameter.isInstance(profile)) {
                        arguments[i] = profile;
                    } else {
                        complete = false;
                        break;
                    }
                }
                if (!complete) continue;

                method.setAccessible(true);
                method.invoke(service, arguments);
                return true;
            }

            status = "no joinServer this version accepts";
            return false;

        } catch (Throwable t) {
            // An invalid session throws here, which is worth saying plainly
            String message = t.getCause() != null ? t.getCause().toString() : t.toString();
            status = "handshake failed: " + shorten(message);
            SpaceClient.LOGGER.warn("Mojang handshake failed", t);
            return false;
        }
    }

    /**
     * Finds the object that can talk to Mojang's session server.
     *
     * Looked up by what it can do, not by what it is called. Guessing accessor
     * names failed first; then searching Minecraft's own fields failed too, and
     * the dump said why: the service is not held directly. Minecraft has a
     * `services` record, and the session service is a component of it. So the
     * search goes two levels deep - every field Minecraft holds, and every
     * field those hold - asking each whether it has a joinServer method.
     *
     * Only fields are read, and only methods whose return type already looks
     * session shaped are called. Invoking arbitrary no-argument methods on
     * Minecraft to see what comes back would be a fine way to hit stop() or
     * clearLevel().
     */
    private static Object findSessionService(Minecraft mc) {
        // Two passes rather than one recursive walk, so the direct hit wins
        // even if some nested field would also match
        for (Field field : Minecraft.class.getDeclaredFields()) {
            Object value = readField(field, mc);
            if (canJoinServer(value)) return value;
        }

        for (Field field : Minecraft.class.getDeclaredFields()) {
            Object holder = readField(field, mc);
            if (holder == null) continue;

            Class<?> type = holder.getClass();
            if (type.getName().startsWith("java.")) continue;

            for (Field nested : type.getDeclaredFields()) {
                Object value = readField(nested, holder);
                if (canJoinServer(value)) return value;
            }
        }

        // Then getters, but only ones already declared to return something
        // session shaped - the return type is checked before anything is called
        for (Method method : Minecraft.class.getMethods()) {
            if (method.getParameterCount() != 0) continue;
            if (method.getReturnType() == void.class) continue;
            if (!method.getReturnType().getName().contains("Session")) continue;
            try {
                method.setAccessible(true);
                if (canJoinServer(method.invoke(mc))) return method.invoke(mc);
            } catch (Throwable ignored) {
                // Next method
            }
        }

        return null;
    }

    /** Reads one instance field, or null if it cannot be read. */
    private static Object readField(Field field, Object owner) {
        if (Modifier.isStatic(field.getModifiers())) return null;
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean canJoinServer(Object candidate) {
        if (candidate == null) return false;
        for (Method method : candidate.getClass().getMethods()) {
            if (method.getName().equals("joinServer")) return true;
        }
        return false;
    }

    private static UUID profileUuid(Object user, Minecraft mc) {
        Object value = Reflect.call(user, "getProfileId", "getUuid", "getProfileUuid");
        if (value instanceof UUID id) return id;
        if (value instanceof String text) {
            try {
                return UUID.fromString(text.length() == 32 ? dashed(text) : text);
            } catch (Throwable ignored) {
                // Fall through to the player
            }
        }
        // Minecraft.getGameProfile() is confirmed present on this version, so
        // the id is reachable even before a world is joined
        Object profile = Reflect.call(mc, "getGameProfile");
        Object id = Reflect.call(profile, "getId", "id");
        if (id instanceof UUID fromProfile) return fromProfile;

        // In world the player carries the same id
        return mc.player != null ? mc.player.getUUID() : null;
    }

    private static String dashed(String raw) {
        return raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-"
                + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-"
                + raw.substring(20);
    }

    // ---------------- presence ----------------

    /**
     * Announces that this account runs Space Client.
     *
     * Carries the same token the now playing calls use, and nothing else. The
     * worker reads the identity out of that token rather than believing a uuid
     * in the body, which means a registration cannot be forged for somebody
     * who never installed the mod.
     *
     * An earlier attempt sent uuid and name for the worker to check against
     * Mojang's profile API. That check runs on Cloudflare, and Mojang answers
     * Cloudflare with a block page - so it always failed and nobody was ever
     * registered. The token costs nothing extra here: the mod holds one
     * already for reporting songs.
     *
     * Returns whether the worker accepted it, so the caller knows whether to
     * wait the full interval or retry sooner.
     */
    public static boolean register() {
        String bearer = ensureToken();
        if (bearer.isEmpty()) return false;

        JsonObject body = new JsonObject();
        body.addProperty("version", SpaceClient.VERSION);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/register"))
                    .header("Authorization", "Bearer " + bearer)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response =
                    http().send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                // The token died early; the next attempt mints a fresh one
                token = "";
                tokenExpiresAt = 0;
                badgeStatus = "token expired";
                return false;
            }
            if (response.statusCode() != 200) {
                badgeStatus = "register refused (" + response.statusCode() + "): "
                        + shorten(response.body());
                return false;
            }

            badgeStatus = "registered";
            return true;

        } catch (Throwable t) {
            badgeStatus = "register failed: " + t.getMessage();
            return false;
        }
    }

    /**
     * The session token, for the streaming calls in Twitch.
     *
     * Exposed rather than duplicating the handshake there: one place mints
     * tokens, one place holds them, and a token refreshed for a now playing
     * report is the same token the follower poll needs.
     */
    public static String tokenForStreaming() {
        try {
            return ensureToken();
        } catch (Throwable t) {
            return "";
        }
    }

    /** The shared HTTP client, so callers do not each build their own. */
    public static java.net.http.HttpClient client() { return http(); }

    /**
     * Heartbeat: tells the worker this account is in a world right now.
     *
     * The badge means "is playing with Space Client", not "installed it once",
     * and those are different questions needing different answers. Registration
     * records the permanent fact and is written at most twice a day; this
     * records the temporary one and expires on its own after fifteen minutes,
     * because a client that crashes never gets to say it left.
     */
    public static boolean heartbeat() {
        String bearer = ensureToken();
        if (bearer.isEmpty()) return false;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/presence"))
                    .header("Authorization", "Bearer " + bearer)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response =
                    http().send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                token = "";
                tokenExpiresAt = 0;
                badgeStatus = "token expired";
                return false;
            }
            if (response.statusCode() != 200) {
                badgeStatus = "presence refused (" + response.statusCode() + ")";
                return false;
            }

            badgeStatus = "online";
            return true;

        } catch (Throwable t) {
            badgeStatus = "presence failed: " + t.getMessage();
            return false;
        }
    }

    /**
     * Says this account has stopped playing.
     *
     * Not strictly required - presence expires by itself - but without it the
     * badge hangs around for up to a quarter of an hour after someone quits,
     * which is exactly the wrong impression for a badge that claims to mean
     * "playing right now".
     */
    public static void leave() {
        String bearer = token;
        if (bearer == null || bearer.isEmpty()) return;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/presence"))
                    .header("Authorization", "Bearer " + bearer)
                    .timeout(Duration.ofSeconds(5))
                    .method("DELETE", HttpRequest.BodyPublishers.noBody())
                    .build();
            http().send(request, HttpResponse.BodyHandlers.ofString());
            badgeStatus = "offline";
        } catch (Throwable ignored) {
            // Leaving is best effort: the entry expires anyway
        }
    }

    /**
     * The full list of accounts carrying a badge.
     *
     * Everyone, not just the players in sight. The answer is identical for
     * every client and changes only when somebody installs the mod, so the
     * worker serves it from cache and one request replaces what would
     * otherwise be a lookup per player per poll.
     *
     * Returns null if the call did not get through, which the caller must not
     * confuse with an empty roster - the first means keep what you had, the
     * second means everyone lost their badge.
     */
    public static List<String> badgeUsers() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/users"))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                badgeStatus = "roster refused (" + response.statusCode() + ")";
                return null;
            }

            JsonObject parsed = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!parsed.has("users")) return null;

            JsonArray array = parsed.getAsJsonArray("users");
            List<String> users = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                users.add(array.get(i).getAsString());
            }
            return users;

        } catch (Throwable t) {
            badgeStatus = "roster failed: " + t.getMessage();
            return null;
        }
    }

    // ---------------- the two calls ----------------

    /**
     * Reports what is playing. An empty title clears the entry instead, which
     * is what switching the setting off sends.
     */
    public static void report(String source, String artist, String title,
                              boolean playing, double position) {
        String bearer = ensureToken();
        if (bearer.isEmpty()) return;

        JsonObject body = new JsonObject();
        body.addProperty("source", source);
        body.addProperty("artist", artist);
        body.addProperty("title", title);
        body.addProperty("playing", playing);
        body.addProperty("position", position);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/np/report"))
                    .header("Authorization", "Bearer " + bearer)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response =
                    http().send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                // The token died early; the next report mints a fresh one
                token = "";
                tokenExpiresAt = 0;
                status = "token expired";
            } else if (response.statusCode() != 200) {
                status = "report refused (" + response.statusCode() + ")";
            } else {
                status = "ok";
            }

        } catch (Throwable t) {
            status = "report failed: " + t.getMessage();
        }
    }

    /**
     * Asks what a list of players is listening to.
     *
     * Open, like the old /shop/worn was: the answer is only what those players
     * have already chosen to publish, so there is nothing here to protect that
     * the reporting side has not already released.
     */
    public static JsonObject songsFor(List<UUID> uuids) {
        if (uuids.isEmpty()) return null;

        JsonArray list = new JsonArray();
        for (UUID uuid : uuids) list.add(uuid.toString());

        JsonObject body = new JsonObject();
        body.add("uuids", list);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/np/get"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response =
                    http().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            JsonObject parsed = JsonParser.parseString(response.body()).getAsJsonObject();
            return parsed.has("playing") ? parsed.getAsJsonObject("playing") : null;

        } catch (Throwable t) {
            status = "lookup failed: " + t.getMessage();
            return null;
        }
    }

    private static String shorten(String text) {
        if (text == null) return "";
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }

    private SpaceApi() {}
}
