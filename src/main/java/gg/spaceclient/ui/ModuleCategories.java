package gg.spaceclient.ui;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.module.Module;

import java.util.Map;

/**
 * Which shelf each module belongs on.
 *
 * Kept here as a lookup rather than as a field on Module, because a field
 * would mean touching every module's constructor - a wide, breakable change
 * for something only the menu cares about.
 *
 * Anything unlisted falls back to whether it draws on the HUD, so a new module
 * lands somewhere sensible without needing an entry.
 */
public final class ModuleCategories {

    public static final String HUD = "HUD";
    public static final String COMBAT = "Combat";
    public static final String VISUAL = "Visual";
    public static final String UTILITY = "Utility";

    public static final String[] ALL = { HUD, COMBAT, VISUAL, UTILITY };

    private static final Map<String, String> BY_ID = Map.ofEntries(
            Map.entry("hitbox", COMBAT),
            Map.entry("armor", COMBAT),
            Map.entry("reach", COMBAT),
            Map.entry("cps", COMBAT),
            Map.entry("keystrokes", COMBAT),
            Map.entry("crosshairinfo", COMBAT),

            Map.entry("zoom", VISUAL),
            Map.entry("waveycape", VISUAL),
            Map.entry("chunk", VISUAL),
            Map.entry("backdrop", VISUAL),
            Map.entry("camera", VISUAL),
            Map.entry("fullbright", VISUAL),
            Map.entry("saturation", VISUAL),
            Map.entry("itemphysics", VISUAL),
            Map.entry("fpsboost", UTILITY),
            Map.entry("blockhighlight", VISUAL),

            Map.entry("coordscopy", UTILITY),
            Map.entry("yawlock", UTILITY),
            Map.entry("autotext", UTILITY)
    );

    private ModuleCategories() {}

    public static String of(Module module) {
        String mapped = BY_ID.get(module.getId());
        if (mapped != null) return mapped;
        return module instanceof HudModule ? HUD : UTILITY;
    }
}
