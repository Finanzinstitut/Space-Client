package gg.spaceclient.setting;

import java.util.Arrays;
import java.util.List;

/**
 * A named bundle of settings that gets its own sub-screen.
 *
 * Hitboxes need this: four categories each with the same three options would be
 * twelve rows in one flat list, which is unreadable. As a group per category it
 * is four buttons that open three rows each.
 */
public record SettingGroup(String name, String description, List<Setting> settings) {

    public static SettingGroup of(String name, String description, Setting... settings) {
        return new SettingGroup(name, description, Arrays.asList(settings));
    }
}
