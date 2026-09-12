package gg.spaceclient.setting;

import com.google.gson.JsonObject;

public class BooleanSetting extends Setting {
    private boolean value;

    public BooleanSetting(String id, String name, String description, boolean defaultValue) {
        super(id, name, description);
        this.value = defaultValue;
    }

    public boolean get() { return value; }
    public void set(boolean value) { this.value = value; }
    public void toggle() { this.value = !this.value; }

    @Override
    public void save(JsonObject json) {
        json.addProperty(getId(), value);
    }

    @Override
    public void load(JsonObject json) {
        if (json.has(getId())) value = json.get(getId()).getAsBoolean();
    }
}
