package gg.spaceclient.session;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.spaceclient.SpaceClient;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers refresh tokens the mod has been handed.
 *
 * Microsoft rotates refresh tokens: every use hands back a new one and retires
 * the old. The launcher stores its own copy, but the mod only reads that file -
 * so the second refresh in a session was replaying a token Microsoft had already
 * invalidated, and failed. That is why "invalid session" kept coming back even
 * though a refresh appeared to run.
 *
 * Keeping the rotated tokens here fixes that without writing into the
 * launcher's file, which stays the launcher's business.
 */
public class TokenStore {
    private static final Map<String, String> tokens = new HashMap<>();
    private static boolean loaded = false;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("spaceclient-tokens.json");
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;

        try {
            Path path = file();
            if (!Files.exists(path)) return;
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            for (String key : root.keySet()) {
                tokens.put(key, root.get(key).getAsString());
            }
        } catch (Exception e) {
            SpaceClient.LOGGER.warn("Could not read stored tokens: {}", e.getMessage());
        }
    }

    private static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            tokens.forEach(root::addProperty);

            java.nio.file.Path path = file();
            Files.writeString(path, root.toString());
            restrictToOwner(path);

        } catch (Exception e) {
            SpaceClient.LOGGER.warn("Could not store the refreshed token: {}", e.getMessage());
        }
    }

    /**
     * Takes the file away from everyone but its owner.
     *
     * These are refresh tokens: whoever holds one can mint new sessions for
     * the account until it is revoked. Written with default permissions they
     * are readable by every account on the machine, which on a shared or
     * family computer is a real difference and costs one call to avoid.
     *
     * Not a substitute for keeping them out of plain text altogether - that is
     * a larger change and belongs in the launcher - but there is no reason to
     * leave the weaker version in place while waiting for it.
     */
    private static void restrictToOwner(java.nio.file.Path path) {
        try {
            var view = Files.getFileAttributeView(path,
                    java.nio.file.attribute.PosixFileAttributeView.class);
            if (view != null) {
                Files.setPosixFilePermissions(path,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                return;
            }

            // Windows has no POSIX bits; the ACL equivalent is a larger job, so
            // this at least keeps the file out of casual reach
            java.io.File asFile = path.toFile();
            asFile.setReadable(false, false);
            asFile.setReadable(true, true);
            asFile.setWritable(false, false);
            asFile.setWritable(true, true);

        } catch (Throwable ignored) {
            // Permissions are a hardening step, not a reason to lose the token
        }
    }

    /** The freshest refresh token known for an account. */
    public static synchronized String refreshToken(String uuid, String fallback) {
        load();
        String stored = tokens.get(uuid.toLowerCase());
        return stored != null && !stored.isEmpty() ? stored : fallback;
    }

    public static synchronized void put(String uuid, String refreshToken) {
        if (uuid == null || refreshToken == null || refreshToken.isEmpty()) return;
        load();
        tokens.put(uuid.toLowerCase(), refreshToken);
        save();
    }

    /** Dropped when a refresh is rejected, so the launcher's copy is tried next. */
    public static synchronized void forget(String uuid) {
        load();
        if (tokens.remove(uuid.toLowerCase()) != null) save();
    }

    private TokenStore() {}
}
