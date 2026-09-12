package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.module.Module;

/**
 * Whether streamer mode is on, and what that does to the rest of the client.
 *
 * Not a Module, deliberately. Modules appear in the list and are toggled
 * alongside things like Zoom, and streamer mode is not a feature of that kind:
 * it switches other modules off. Leaving it out of the list also stops the
 * confusing case where the thing that hides your coordinates sits two rows
 * below the coordinates it hid.
 */
public final class StreamerMode {

    /** Modules switched off when streamer mode goes on. */
    private static final String[] HIDES = { "coordinates", "coordscopy" };

    private static boolean on = false;

    /** Remembers what was on beforehand, so turning it off restores them. */
    private static final java.util.Set<String> restore = new java.util.HashSet<>();

    public static boolean isOn() { return on; }

    public static void set(boolean value) {
        if (on == value) return;
        on = value;

        if (value) {
            restore.clear();
            for (String id : HIDES) {
                Module module = SpaceClient.getModuleManager().get(id);
                if (module != null && module.isEnabled()) {
                    restore.add(id);
                    module.setEnabled(false);
                }
            }
        } else {
            // Only what was on before comes back. Switching streamer mode off
            // should undo what it did, not turn on things the player had
            // already chosen to leave off.
            for (String id : restore) {
                Module module = SpaceClient.getModuleManager().get(id);
                if (module != null) module.setEnabled(true);
            }
            restore.clear();
        }

        SpaceClient.getConfigManager().save();
    }

    /**
     * Re-applies the hiding after the config is loaded.
     *
     * Load order is the reason this exists: streamer mode is read back before
     * the modules are, so whatever it switched off would be switched straight
     * back on by the saved module states a moment later.
     */
    public static void reapply() {
        if (!on) return;
        for (String id : HIDES) {
            Module module = SpaceClient.getModuleManager().get(id);
            if (module != null && module.isEnabled()) {
                restore.add(id);
                module.setEnabled(false);
            }
        }
    }

    public static void save(com.google.gson.JsonObject json) {
        json.addProperty("enabled", on);
        com.google.gson.JsonArray remembered = new com.google.gson.JsonArray();
        for (String id : restore) remembered.add(id);
        json.add("restore", remembered);
    }

    public static void load(com.google.gson.JsonObject json) {
        if (json.has("enabled")) on = json.get("enabled").getAsBoolean();
        restore.clear();
        if (json.has("restore")) {
            for (com.google.gson.JsonElement element : json.getAsJsonArray("restore")) {
                restore.add(element.getAsString());
            }
        }
    }

    private StreamerMode() {}
}
