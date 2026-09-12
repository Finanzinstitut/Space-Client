package gg.spaceclient.config;

import com.google.gson.*;
import gg.spaceclient.SpaceClient;
import gg.spaceclient.module.Module;
import gg.spaceclient.modules.KeystrokesModule;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads and writes the single JSON config in the instance's config folder. */
public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public ConfigManager() {
        this.file = FabricLoader.getInstance().getConfigDir().resolve("spaceclient.json");
    }

    public void save() {
        JsonObject root = new JsonObject();

        JsonObject interfaceJson = new JsonObject();
        SpaceClient.getSettings().save(interfaceJson);
        root.add("interface", interfaceJson);

        JsonObject modules = new JsonObject();

        for (Module module : SpaceClient.getModuleManager().getAll()) {
            JsonObject moduleJson = new JsonObject();
            module.save(moduleJson);
            if (module instanceof KeystrokesModule ks) {
                moduleJson.addProperty("custom_keys", ks.getCustomKeys());
            }
            modules.add(module.getId(), moduleJson);
        }
        root.add("modules", modules);

        JsonObject itemSizes = new JsonObject();
        ItemSizes.save(itemSizes);
        root.add("itemsizes", itemSizes);

        JsonObject profiles = new JsonObject();
        Profiles.saveState(profiles);
        root.add("profiles", profiles);

        JsonObject streamer = new JsonObject();
        gg.spaceclient.ui.StreamerMode.save(streamer);
        root.add("streamer", streamer);

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            SpaceClient.LOGGER.error("Could not save config", e);
        }
    }

    public void load() {
        if (!Files.exists(file)) {
            save();
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();

            if (root.has("interface")) {
                SpaceClient.getSettings().load(root.getAsJsonObject("interface"));
            }
            if (root.has("itemsizes")) {
                ItemSizes.load(root.getAsJsonObject("itemsizes"));
            }
            if (root.has("profiles")) {
                Profiles.loadState(root.getAsJsonObject("profiles"));
            }
            if (root.has("streamer")) {
                gg.spaceclient.ui.StreamerMode.load(root.getAsJsonObject("streamer"));
            }
            if (!root.has("modules")) return;
            JsonObject modules = root.getAsJsonObject("modules");

            for (Module module : SpaceClient.getModuleManager().getAll()) {
                if (!modules.has(module.getId())) continue;
                JsonObject moduleJson = modules.getAsJsonObject(module.getId());
                module.load(moduleJson);
                if (module instanceof KeystrokesModule ks && moduleJson.has("custom_keys")) {
                    ks.setCustomKeys(moduleJson.get("custom_keys").getAsString());
                }
            }
            // After the modules, not before: streamer mode is read first, so
            // anything it switched off would be switched straight back on by
            // the saved module states a moment later.
            gg.spaceclient.ui.StreamerMode.reapply();

        } catch (Exception e) {
            // A corrupt config must not stop the game from starting
            SpaceClient.LOGGER.error("Could not read config, using defaults", e);
        }
    }
}
