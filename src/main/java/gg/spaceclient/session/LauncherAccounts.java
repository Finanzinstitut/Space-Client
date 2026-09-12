package gg.spaceclient.session;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.spaceclient.SpaceClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the account list the Space Client launcher writes.
 *
 * The mod only ever reads this file - accounts are added and removed in the
 * launcher. Sharing it is what lets the game switch profiles without a restart,
 * since the refresh tokens needed to mint a new session already live there.
 */
public class LauncherAccounts {

    /** Mirrors where the launcher's Rust side puts its config. */
    private static Path configDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", ".");

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isEmpty()) {
                return Paths.get(appData, "space-client");
            }
            return Paths.get(home, "AppData", "Roaming", "space-client");
        }
        if (os.contains("mac")) {
            return Paths.get(home, "Library", "Application Support", "space-client");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isEmpty()) {
            return Paths.get(xdg, "space-client");
        }
        return Paths.get(home, ".config", "space-client");
    }

    public static Path accountsFile() {
        return configDir().resolve("accounts.json");
    }

    public static boolean isAvailable() {
        return Files.exists(accountsFile());
    }

    public static List<LauncherAccount> load() {
        List<LauncherAccount> out = new ArrayList<>();
        Path file = accountsFile();
        if (!Files.exists(file)) return out;

        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var array = root.getAsJsonArray("accounts");
            if (array == null) return out;

            for (var element : array) {
                JsonObject account = element.getAsJsonObject();
                out.add(new LauncherAccount(
                        get(account, "username"),
                        get(account, "uuid"),
                        get(account, "access_token"),
                        get(account, "refresh_token"),
                        account.has("expires_at") ? account.get("expires_at").getAsLong() : 0L,
                        account.has("offline") && account.get("offline").getAsBoolean()
                ));
            }
        } catch (Exception e) {
            SpaceClient.LOGGER.warn("Could not read the launcher's accounts: {}", e.getMessage());
        }
        return out;
    }

    public static String activeUuid() {
        Path file = accountsFile();
        if (!Files.exists(file)) return "";
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return root.has("active_uuid") ? root.get("active_uuid").getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String get(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    private LauncherAccounts() {}
}
