package gg.spaceclient.session;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.spaceclient.SpaceClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Mints a fresh Minecraft session and puts it into the running game.
 *
 * Why this exists: a session token lasts about a day. Leave the game open
 * longer than that and joining a server fails with "invalid session", which
 * normally means quitting and relaunching. Re-running the token chain and
 * swapping the result in avoids the restart entirely.
 *
 * The swap uses reflection rather than a mixin, and finds the field by its type
 * rather than its name. Field names move between versions; the type does not.
 * A failure here is a message in the log, not a broken game.
 */
public class SessionManager {
    private static final String AZURE_CLIENT_ID = "74f6b1c6-c83b-425b-ae42-573992624ab2";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile";

    private static String status = "";
    private static boolean busy = false;

    /**
     * The account chosen in game, which is not necessarily the one the launcher
     * has active. Without this, a refresh after switching would silently put
     * the account the game started with back on.
     */
    private static String chosenUuid = "";

    /** When a session was last minted, used to refresh before it expires. */
    private static long lastRefresh = 0;

    /** Tail of the applied token, so diagnostics can show one landed. */
    private static String lastTokenTail = "";

    public static String tokenTail() { return lastTokenTail; }

    public static String status() { return status; }
    public static boolean isBusy() { return busy; }
    public static long lastRefresh() { return lastRefresh; }

    /** Which account the game is currently meant to be using. */
    public static String chosenUuid() {
        return chosenUuid.isEmpty() ? LauncherAccounts.activeUuid() : chosenUuid;
    }

    private static HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    /**
     * Refreshes the given account and applies it to the running game.
     * Runs off the render thread; the result lands in {@link #status()}.
     */
    public static CompletableFuture<Boolean> applyAccount(LauncherAccount account) {
        if (busy) return CompletableFuture.completedFuture(false);
        busy = true;
        chosenUuid = account.uuid();
        status = "Signing in as " + account.username() + "...";

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (account.offline()) {
                    boolean ok = swapUser(account.username(), account.uuid(), "0", "legacy");
                    if (ok) lastRefresh = System.currentTimeMillis();
                    status = ok
                            ? "Switched to " + account.username() + " (offline)"
                            : "Could not apply the offline profile.";
                    return ok;
                }

                String token = mintSession(account);
                if (token == null) {
                    status = "Could not refresh the session. Sign in again in the launcher.";
                    return false;
                }

                // The profile call confirms the token works and gives the exact
                // name and UUID the session belongs to.
                JsonObject profile = fetchProfile(token);
                if (profile == null) {
                    status = "The new session was rejected by Minecraft services.";
                    return false;
                }

                String name = profile.get("name").getAsString();
                String uuid = dashed(profile.get("id").getAsString());

                boolean ok = swapUser(name, uuid, token, "msa");
                if (ok) lastRefresh = System.currentTimeMillis();
                status = ok
                        ? "Session refreshed - you are now " + name
                        : "Got a session but could not apply it to the running game.";
                return ok;

            } catch (Exception e) {
                SpaceClient.LOGGER.warn("Session refresh failed", e);
                status = "Session refresh failed: " + e.getMessage();
                return false;
            } finally {
                busy = false;
            }
        });
    }

    /**
     * Refreshes the account the game is currently using.
     *
     * That is the one picked in game if there was one, otherwise whatever the
     * launcher had active - looking only at the launcher is what made a refresh
     * after switching jump back to the starting account.
     */
    public static CompletableFuture<Boolean> refreshCurrent() {
        String wanted = chosenUuid();
        List<LauncherAccount> all = LauncherAccounts.load();

        Optional<LauncherAccount> account = all.stream()
                .filter(a -> a.uuid().equalsIgnoreCase(wanted))
                .findFirst();

        if (account.isEmpty()) {
            // Last resort: match on the name we are actually playing as
            String current = Minecraft.getInstance().getUser().getName();
            account = all.stream()
                    .filter(a -> a.username().equalsIgnoreCase(current))
                    .findFirst();
        }

        if (account.isEmpty()) {
            status = "No matching account found in the launcher.";
            return CompletableFuture.completedFuture(false);
        }
        return applyAccount(account.get());
    }

    // ---------------- token chain ----------------

    private static String mintSession(LauncherAccount account) throws Exception {
        // A token that is still valid needs no round trip
        if (!account.isExpired() && !account.accessToken().isEmpty()) {
            return account.accessToken();
        }

        // Prefer a rotated token this mod was handed over the launcher's copy,
        // which Microsoft may already have retired.
        String refresh = TokenStore.refreshToken(account.uuid(), account.refreshToken());
        if (refresh.isEmpty()) return null;

        String body = "client_id=" + AZURE_CLIENT_ID
                + "&grant_type=refresh_token"
                + "&refresh_token=" + java.net.URLEncoder.encode(
                        refresh, java.nio.charset.StandardCharsets.UTF_8)
                + "&scope=" + java.net.URLEncoder.encode(
                        "XboxLive.signin offline_access", java.nio.charset.StandardCharsets.UTF_8);

        JsonObject microsoft = postForm(TOKEN_URL, body);
        if (microsoft == null || !microsoft.has("access_token")) {
            // A rejected token is worth forgetting, so the launcher's copy gets
            // a turn on the next attempt.
            TokenStore.forget(account.uuid());
            return null;
        }
        String msToken = microsoft.get("access_token").getAsString();

        // Microsoft hands back a new refresh token and retires the one just
        // used; keeping it is what makes a second refresh work.
        if (microsoft.has("refresh_token")) {
            TokenStore.put(account.uuid(), microsoft.get("refresh_token").getAsString());
        }

        JsonObject xbl = postJson(XBL_URL, String.format("""
                {"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com",
                "RpsTicket":"d=%s"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}""",
                msToken), null);
        if (xbl == null || !xbl.has("Token")) return null;
        String xblToken = xbl.get("Token").getAsString();

        JsonObject xsts = postJson(XSTS_URL, String.format("""
                {"Properties":{"SandboxId":"RETAIL","UserTokens":["%s"]},
                "RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}""",
                xblToken), null);
        if (xsts == null || !xsts.has("Token")) return null;

        String xstsToken = xsts.get("Token").getAsString();
        String userHash = xsts.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString();

        JsonObject minecraft = postJson(MC_LOGIN_URL, String.format(
                "{\"identityToken\":\"XBL3.0 x=%s;%s\"}", userHash, xstsToken), null);
        if (minecraft == null || !minecraft.has("access_token")) return null;

        return minecraft.get("access_token").getAsString();
    }

    private static JsonObject fetchProfile(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MC_PROFILE_URL))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static JsonObject postForm(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            SpaceClient.LOGGER.warn("{} returned {}", url, response.statusCode());
            return null;
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static JsonObject postJson(String url, String body, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);

        HttpResponse<String> response = http().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            SpaceClient.LOGGER.warn("{} returned {}", url, response.statusCode());
            return null;
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    // ---------------- applying it ----------------

    /**
     * Replaces Minecraft's User instance. The field is located by type, so a
     * rename in a future version does not break it.
     */
    private static boolean swapUser(String name, String uuid, String token, String type) {
        try {
            Minecraft mc = Minecraft.getInstance();
            User replacement = buildUser(name, uuid, token, type);
            if (replacement == null) {
                SpaceClient.LOGGER.warn("Could not build a User - no constructor matched");
                status = "This version's account object could not be built.";
                return false;
            }

            boolean written = false;
            for (Field field : Minecraft.class.getDeclaredFields()) {
                if (!User.class.equals(field.getType())) continue;
                field.setAccessible(true);
                field.set(mc, replacement);
                written = true;
                SpaceClient.LOGGER.info("Wrote the new session into Minecraft.{}", field.getName());
            }

            if (!written) {
                SpaceClient.LOGGER.warn("No User field on Minecraft - cannot apply the session");
                status = "This version keeps the account somewhere this mod cannot reach.";
                return false;
            }

            // Verify rather than assume, and check the token as well as the
            // name. Checking only the name is what let a User with the uuid in
            // the token slot pass as success.
            String now = mc.getUser().getName();
            if (!now.equalsIgnoreCase(name)) {
                SpaceClient.LOGGER.warn(
                        "Session written but the game still reports {} instead of {}", now, name);
                status = "The session was written but the game still reports " + now + ".";
                return false;
            }

            String liveToken = readToken(mc.getUser());
            if (liveToken == null || !liveToken.equals(token)) {
                SpaceClient.LOGGER.warn("Session written but the access token did not land");
                status = "The name changed but the login token did not - servers would reject this.";
                return false;
            }

            lastTokenTail = token.length() > 8 ? token.substring(token.length() - 6) : "short";

            // The chat signing key still belongs to the previous account, and a
            // server with secure profiles enforced rejects the mismatch with
            // "invalid_public_key_signature". Rebuilding it is what makes the
            // switched account usable on those servers.
            refreshProfileKeys();

            SpaceClient.LOGGER.info("Session applied - now playing as {}", now);
            return true;

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Could not swap the session", t);
            status = "Could not apply the session: " + t.getMessage();
            return false;
        }
    }

    /**
     * Builds a User without depending on its constructor signature, which has
     * gained and lost parameters repeatedly across versions. Whichever
     * constructor is present is filled in positionally by parameter type.
     */
    /**
     * Builds a User for whichever constructor this version declares.
     *
     * The subtlety that broke this before: the values are assigned by position,
     * but only among parameters of the same type. If the UUID has its own
     * UUID-typed parameter, then the *second* String is the access token, not
     * the uuid - filling it with the uuid string produced a User with a valid
     * name and a nonsense token, which is exactly what "invalid session" looks
     * like from the server's side. The name check then passed, so the failure
     * was invisible.
     */
    private static User buildUser(String name, String uuid, String token, String type) {
        for (var constructor : User.class.getDeclaredConstructors()) {
            try {
                Class<?>[] parameters = constructor.getParameterTypes();

                // Does the uuid get a parameter of its own?
                boolean uuidIsSeparate = false;
                for (Class<?> parameter : parameters) {
                    if (parameter == UUID.class) uuidIsSeparate = true;
                }

                // Which strings are expected, in order
                List<String> strings = new ArrayList<>();
                strings.add(name);
                if (!uuidIsSeparate) strings.add(uuid);
                strings.add(token);

                Object[] arguments = new Object[parameters.length];
                int stringIndex = 0;

                for (int i = 0; i < parameters.length; i++) {
                    Class<?> parameter = parameters[i];

                    if (parameter == String.class) {
                        arguments[i] = stringIndex < strings.size()
                                ? strings.get(stringIndex)
                                : "";
                        stringIndex++;
                    } else if (parameter == UUID.class) {
                        arguments[i] = UUID.fromString(uuid);
                    } else if (parameter == Optional.class) {
                        arguments[i] = Optional.empty();
                    } else if (parameter.isEnum()) {
                        Object[] constants = parameter.getEnumConstants();
                        Object match = constants[0];
                        for (Object constant : constants) {
                            if (constant.toString().equalsIgnoreCase(type)) match = constant;
                        }
                        arguments[i] = match;
                    } else {
                        arguments[i] = null;
                    }
                }

                constructor.setAccessible(true);
                User built = (User) constructor.newInstance(arguments);

                // Verify the token landed where it belongs, rather than trusting
                // the ordering. A wrong slot is silent otherwise.
                if (!token.equals(readToken(built))) {
                    // The accessor did not give the token back. That is either
                    // a wrong constructor slot or an accessor whose name has
                    // moved - and those need different answers, so the check
                    // below only says whether the token is anywhere on the
                    // object at all. It does not move it.
                    if (!tokenPresentInAnyField(built, token)) {
                        SpaceClient.LOGGER.warn(
                                "Built a User but the access token is nowhere on it");
                        continue;
                    }
                    SpaceClient.LOGGER.warn(
                            "Access token is on the User but not where readToken looks"
                                    + " - accepting it, the accessor is probably renamed");
                }
                return built;

            } catch (Throwable t) {
                SpaceClient.LOGGER.warn("User constructor did not take: {}", t.getMessage());
            }
        }
        return null;
    }

    /**
     * Replaces the chat signing key manager so it belongs to the new account.
     *
     * Minecraft fetches a keypair tied to the signed-in profile and signs chat
     * with it. After a session swap the old keypair is still loaded, and servers
     * running with enforce-secure-profile refuse the join because the signature
     * does not match the profile presenting it.
     *
     * The manager is rebuilt through whichever static factory its class exposes,
     * with arguments matched by type from the running game. Failure here only
     * costs chat signing, so it is logged rather than treated as fatal.
     */
    private static void refreshProfileKeys() {
        try {
            Minecraft mc = Minecraft.getInstance();

            Field managerField = null;
            for (Field field : Minecraft.class.getDeclaredFields()) {
                if (field.getType().getSimpleName().contains("ProfileKeyPairManager")) {
                    managerField = field;
                    break;
                }
            }
            if (managerField == null) {
                SpaceClient.LOGGER.info("No chat signing manager on this version - nothing to refresh");
                return;
            }

            Class<?> managerType = managerField.getType();

            // Collect what the game can hand a factory: the api service, the
            // new account, and the game directory.
            List<Object> available = new ArrayList<>();
            for (Field field : Minecraft.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(mc);
                    if (value != null) available.add(value);
                } catch (Throwable ignored) {
                    // Skip anything unreadable
                }
            }

            for (Method factory : managerType.getMethods()) {
                if (!Modifier.isStatic(factory.getModifiers())) continue;
                if (!managerType.isAssignableFrom(factory.getReturnType())) continue;

                Class<?>[] parameters = factory.getParameterTypes();
                Object[] arguments = new Object[parameters.length];
                boolean complete = true;

                for (int i = 0; i < parameters.length; i++) {
                    Object match = null;
                    for (Object candidate : available) {
                        if (parameters[i].isInstance(candidate)) {
                            match = candidate;
                            break;
                        }
                    }
                    if (match == null) { complete = false; break; }
                    arguments[i] = match;
                }
                if (!complete) continue;

                Object rebuilt = factory.invoke(null, arguments);
                if (rebuilt == null) continue;

                managerField.setAccessible(true);
                managerField.set(mc, rebuilt);
                SpaceClient.LOGGER.info("Rebuilt the chat signing key for the new account");
                return;
            }

            SpaceClient.LOGGER.warn(
                    "Could not rebuild the chat signing key - servers enforcing secure profiles may refuse the join");

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Chat signing key refresh failed: {}", t.getMessage());
        }
    }

    /** Reads the access token back out, whatever the accessor is called. */
    private static String readToken(User user) {
        for (String name : new String[]{"getAccessToken", "accessToken", "getSessionId"}) {
            try {
                Object value = User.class.getMethod(name).invoke(user);
                if (value instanceof String text) return text;
            } catch (Exception ignored) {
                // Try the next accessor
            }
        }
        return null;
    }

    /**
     * Last resort: write the token straight into the field.
     *
     * Named for what it does. It was called writeTokenByField and it never
     * wrote anything - it walked the String fields and returned true when one
     * of them already held the token. As a repair step that was a lie: it
     * reported success without changing a thing, so a User with the token in
     * the wrong slot was accepted as fixed.
     *
     * Actually writing would mean picking a field to overwrite, and on a record
     * with final fields there is no honest way to choose. So this stays a
     * check, the caller treats it as one, and a token that is genuinely absent
     * now rejects the constructor instead of passing.
     */
    private static boolean tokenPresentInAnyField(User user, String token) {
        for (Field field : User.class.getDeclaredFields()) {
            if (field.getType() != String.class) continue;
            try {
                field.setAccessible(true);
                Object current = field.get(user);
                // The token field is the one that does not hold the name
                if (current instanceof String text && text.equals(token)) return true;
            } catch (Throwable ignored) {
                // Keep looking
            }
        }
        return false;
    }

    private static String dashed(String raw) {
        if (raw.length() != 32) return raw;
        return raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-"
                + raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-"
                + raw.substring(20);
    }

    private SessionManager() {}
}
