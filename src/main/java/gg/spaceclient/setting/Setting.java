package gg.spaceclient.setting;

import com.google.gson.JsonObject;

/**
 * Base class for anything a module exposes in its settings panel.
 * Every setting knows how to write itself into the config and read itself back,
 * so adding a setting to a module needs no changes to the config code.
 */
public abstract class Setting {
    private final String id;
    private final String name;
    private final String description;

    protected Setting(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }

    public abstract void save(JsonObject json);
    public abstract void load(JsonObject json);
}
