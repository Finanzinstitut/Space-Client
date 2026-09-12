package gg.spaceclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.module.HudModule;
import gg.spaceclient.module.Module;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whole HUD layouts, saved and switched in one click.
 *
 * The gap this fills: the way somebody wants their screen arranged is not one
 * arrangement. Building wants coordinates and the portal converter and no
 * combat readouts; PvP wants the opposite; streaming wants the follower count
 * large and the coordinates gone entirely. Every client makes you rebuild that
 * by hand each time, so in practice nobody does - they settle on one cluttered
 * layout that is wrong for everything.
 *
 * A profile stores which modules are on, where each sits and how large it is.
 * It deliberately does not store anything else: colours, key bindings and
 * account details are preferences about you rather than about the task, and
 * having them jump around when switching context would be its own annoyance.
 */
public final class Profiles {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static String active = "default";

    private static Path folder() {
        return FabricLoader.getInstance().getConfigDir().resolve("spaceclient-profiles");
    }

    private static Path fileFor(String name) {
        return folder().resolve(safe(name) + ".json");
    }

    /** Keeps a profile name from escaping the folder or breaking a filesystem. */
    private static String safe(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return cleaned.isEmpty() ? "profile" : cleaned;
    }

    public static String active() { return active; }

    /** Profiles on disk, plus the built-in names, in a stable order. */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        names.add("default");
        names.add("building");
        names.add("pvp");
        names.add("streaming");

        try {
            if (Files.isDirectory(folder())) {
                Files.list(folder()).forEach(path -> {
                    String name = path.getFileName().toString().replace(".json", "");
                    if (!names.contains(name)) names.add(name);
                });
            }
        } catch (Exception e) {
            SpaceClient.LOGGER.warn("Could not list profiles: {}", e.getMessage());
        }
        return names;
    }

    /**
     * Writes the current arrangement into a profile.
     *
     * Called before switching away, so leaving a profile keeps whatever was
     * adjusted while in it. Nobody thinks to press save before changing context,
     * and losing five minutes of dragging to a click is the kind of thing that
     * makes a feature go unused.
     */
    public static void save(String name) {
        try {
            JsonObject root = new JsonObject();
            JsonObject modules = new JsonObject();

            for (Module module : SpaceClient.getModuleManager().getAll()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("enabled", module.isEnabled());

                if (module instanceof HudModule hud) {
                    entry.addProperty("x", hud.getXPercent());
                    entry.addProperty("y", hud.getYPercent());
                    entry.addProperty("scale", hud.getScale());
                }
                modules.add(module.getId(), entry);
            }

            root.add("modules", modules);
            Files.createDirectories(folder());
            Files.writeString(fileFor(name), GSON.toJson(root));

        } catch (Exception e) {
            SpaceClient.LOGGER.error("Could not save profile {}", name, e);
        }
    }

    /**
     * Loads a profile, saving the current one first.
     *
     * A missing file is not an error: the four built-in names exist before
     * anyone has arranged them, and the sensible response to loading one for
     * the first time is to lay it out rather than to complain.
     */
    public static void switchTo(String name) {
        if (name.equals(active)) return;

        save(active);
        Path file = fileFor(name);

        if (!Files.exists(file)) {
            applyPreset(name);
            active = name;
            save(name);
            SpaceClient.getConfigManager().save();
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject modules = root.getAsJsonObject("modules");

            for (Module module : SpaceClient.getModuleManager().getAll()) {
                if (!modules.has(module.getId())) continue;
                JsonObject entry = modules.getAsJsonObject(module.getId());

                if (entry.has("enabled")) {
                    module.setEnabled(entry.get("enabled").getAsBoolean());
                }
                if (module instanceof HudModule hud) {
                    if (entry.has("x") && entry.has("y")) {
                        hud.setPosition(entry.get("x").getAsFloat(), entry.get("y").getAsFloat());
                    }
                    if (entry.has("scale")) hud.setScale(entry.get("scale").getAsFloat());
                }
            }

            active = name;
            SpaceClient.getConfigManager().save();

        } catch (Exception e) {
            SpaceClient.LOGGER.error("Could not load profile {}", name, e);
        }
    }

    /**
     * A starting arrangement for a built-in name.
     *
     * Only touches what the name is about. A preset that switched off
     * everything it did not mention would throw away the rest of somebody's
     * setup to make a point about focus.
     */
    private static void applyPreset(String name) {
        switch (name) {
            case "building" -> {
                on("coordinates");
                on("direction");
                on("portal");
                on("chunk");
                off("cps");
                off("keystrokes");
            }
            case "pvp" -> {
                on("cps");
                on("keystrokes");
                on("armor");
                on("connection");
                off("portal");
                off("music");
            }
            case "streaming" -> {
                on("twitch");
                on("spaceplayers");
                off("coordinates");
                off("coordscopy");
            }
            default -> {
                // "default" is whatever the client ships with, so nothing is
                // forced - loading it for the first time simply keeps the
                // arrangement it was created from
            }
        }
    }

    private static void on(String id) { set(id, true); }
    private static void off(String id) { set(id, false); }

    private static void set(String id, boolean enabled) {
        Module module = SpaceClient.getModuleManager().get(id);
        if (module != null) module.setEnabled(enabled);
    }

    public static void saveState(JsonObject json) {
        json.addProperty("active", active);
    }

    public static void loadState(JsonObject json) {
        if (json.has("active")) active = json.get("active").getAsString();
    }

    private Profiles() {}
}
