package gg.spaceclient.setting;

import com.google.gson.JsonObject;

public class IntSetting extends Setting {
    private int value;
    private final int min;
    private final int max;

    public IntSetting(String id, String name, String description, int defaultValue, int min, int max) {
        super(id, name, description);
        this.value = defaultValue;
        this.min = min;
        this.max = max;
    }

    public int get() { return value; }
    public int getMin() { return min; }
    public int getMax() { return max; }

    public void set(int value) {
        this.value = Math.max(min, Math.min(max, value));
    }

    @Override
    public void save(JsonObject json) {
        json.addProperty(getId(), value);
    }

    @Override
    public void load(JsonObject json) {
        if (json.has(getId())) set(json.get(getId()).getAsInt());
    }
}
