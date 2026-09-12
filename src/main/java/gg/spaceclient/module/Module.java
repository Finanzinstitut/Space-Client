package gg.spaceclient.module;

import com.google.gson.JsonObject;
import gg.spaceclient.setting.Setting;
import gg.spaceclient.setting.SettingGroup;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** A single toggleable feature. */
public abstract class Module {
    protected static final Minecraft mc = Minecraft.getInstance();

    private final String id;
    private final String name;
    private final String description;
    private final List<Setting> settings = new ArrayList<>();
    private final List<SettingGroup> groups = new ArrayList<>();
    private boolean enabled;

    protected Module(String id, String name, String description, boolean enabledByDefault) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.enabled = enabledByDefault;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<Setting> getSettings() { return settings; }

    /** Settings split into sub-screens, for modules with too many to list flat. */
    public List<SettingGroup> getGroups() { return groups; }

    /** True when there is anything at all to configure. */
    public boolean hasSettings() {
        return !settings.isEmpty() || !groups.isEmpty();
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (enabled) onEnable(); else onDisable();
    }

    public void toggle() {
        setEnabled(!this.enabled);
    }

    /** Called once when the module is switched on. */
    protected void onEnable() {}

    /** Called once when switched off - undo anything global here. */
    protected void onDisable() {}

    protected void addSettings(Setting... toAdd) {
        for (Setting s : toAdd) settings.add(s);
    }

    protected void addGroups(SettingGroup... toAdd) {
        for (SettingGroup g : toAdd) {
            groups.add(g);
            // Grouped settings still take part in saving and loading
            settings.addAll(g.settings());
        }
    }

    /** Called every client tick while enabled. */
    public void onTick() {}

    public void save(JsonObject json) {
        json.addProperty("enabled", enabled);
        JsonObject settingsJson = new JsonObject();
        for (Setting s : settings) s.save(settingsJson);
        json.add("settings", settingsJson);
    }

    public void load(JsonObject json) {
        if (json.has("enabled")) {
            boolean value = json.get("enabled").getAsBoolean();
            // Set the field first so a hook that reads isEnabled() sees the
            // final state, then fire the hook itself.
            if (value != enabled) {
                enabled = value;
                if (value) onEnable(); else onDisable();
            }
        }
        if (json.has("settings")) {
            JsonObject settingsJson = json.getAsJsonObject("settings");
            for (Setting s : settings) s.load(settingsJson);
        }
    }
}
