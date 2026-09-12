package gg.spaceclient.setting;

import com.google.gson.JsonObject;
import java.util.List;

/** A pick-one-of-several setting, e.g. the Keystrokes layout mode. */
public class ModeSetting extends Setting {
    private final List<String> options;
    private int index;

    public ModeSetting(String id, String name, String description, List<String> options, String defaultOption) {
        super(id, name, description);
        this.options = options;
        int i = options.indexOf(defaultOption);
        this.index = i >= 0 ? i : 0;
    }

    public List<String> getOptions() { return options; }
    public String get() { return options.get(index); }
    public int getIndex() { return index; }

    public boolean is(String option) { return get().equals(option); }

    public void set(String option) {
        int i = options.indexOf(option);
        if (i >= 0) index = i;
    }

    public void cycle() {
        index = (index + 1) % options.size();
    }

    @Override
    public void save(JsonObject json) {
        json.addProperty(getId(), get());
    }

    @Override
    public void load(JsonObject json) {
        if (json.has(getId())) set(json.get(getId()).getAsString());
    }
}
