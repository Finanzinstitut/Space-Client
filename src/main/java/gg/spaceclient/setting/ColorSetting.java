package gg.spaceclient.setting;

import com.google.gson.JsonObject;

/** ARGB colour, stored as an int so it survives a JSON round trip cleanly. */
public class ColorSetting extends Setting {
    private int argb;

    public ColorSetting(String id, String name, String description, int defaultArgb) {
        super(id, name, description);
        this.argb = defaultArgb;
    }

    public int get() { return argb; }
    public void set(int argb) { this.argb = argb; }

    public int getRed()   { return (argb >> 16) & 0xFF; }
    public int getGreen() { return (argb >> 8) & 0xFF; }
    public int getBlue()  { return argb & 0xFF; }
    public int getAlpha() { return (argb >>> 24) & 0xFF; }

    public void setComponents(int a, int r, int g, int b) {
        this.argb = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    @Override
    public void save(JsonObject json) {
        json.addProperty(getId(), argb);
    }

    @Override
    public void load(JsonObject json) {
        if (json.has(getId())) argb = json.get(getId()).getAsInt();
    }
}
