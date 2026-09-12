package gg.spaceclient.config;

import com.google.gson.JsonObject;
import gg.spaceclient.setting.ColorSetting;

import java.util.Arrays;
import java.util.List;

/**
 * Interface-wide settings, kept separate from the per-module ones because they
 * apply to the menu itself rather than to anything in the world.
 */
public class ClientSettings {
    /**
     * The photographs come after the drawn styles on purpose.
     *
     * Cycling starts at the cheap ones, so somebody who just wants a plain
     * background never has to page through three images to reach it.
     */
    public static final List<String> BACKGROUND_STYLES =
            Arrays.asList("SPACE", "DARK", "SOLID_BLACK", "TRANSPARENT",
                    "AURORA", "NEBULA", "BLACK_HOLE", "GALAXY");

    private String backgroundStyle = "SPACE";

    /**
     * Which font the game draws with.
     *
     * DEFAULT is Minecraft's own, and it is the default on purpose: up to 1.8.2
     * this client replaced the font for everybody with no setting to turn it
     * off, which is not a decision a HUD mod should be making on its own. The
     * old Open Sans override is still available, but now as a choice.
     *
     * This is only what the menu displays. The game reads its font from the
     * selected resource packs, which Minecraft stores itself - see FontPacks.
     */
    private String fontStyle = gg.spaceclient.font.FontStyle.DEFAULT;

    /**
     * The accent lives in a ColorSetting so the same colour wheel widget the
     * modules use can drive it too.
     */
    /**
     * Whether the client replaces the game's title screen.
     *
     * On by default because it is the most visible thing the client does, but
     * a setting rather than a fact: the custom menu plays a short animation on
     * every launch, and somebody restarting repeatedly to test a mod wants
     * that gone. Turning it off restores vanilla's screen completely.
     */
    private boolean customMenu = true;

    public boolean customMenu() { return customMenu; }

    public void setCustomMenu(boolean value) { this.customMenu = value; }

    private final ColorSetting accent = new ColorSetting(
            "accent", "Accent colour", "Colour used across the interface", 0xFF7C5CFF);
    // The launcher's violet, so the in-game menu matches it out of the box.
    public String backgroundStyle() { return backgroundStyle; }
    public String fontStyle() { return fontStyle; }

    public void setFontStyle(String id) {
        if (gg.spaceclient.font.FontStyle.isKnown(id)) fontStyle = id;
    }

    public int accentColor() { return accent.get(); }
    public ColorSetting accentSetting() { return accent; }

    public void cycleBackground() {
        int index = BACKGROUND_STYLES.indexOf(backgroundStyle);
        backgroundStyle = BACKGROUND_STYLES.get((index + 1) % BACKGROUND_STYLES.size());
    }

    public int red()   { return accent.getRed(); }
    public int green() { return accent.getGreen(); }
    public int blue()  { return accent.getBlue(); }

    public void setChannels(int r, int g, int b) {
        accent.setComponents(255, r, g, b);
    }

    public void save(JsonObject json) {
        json.addProperty("background_style", backgroundStyle);
        json.addProperty("accent_color", accent.get());
        json.addProperty("font_style", fontStyle);
        json.addProperty("custom_menu", customMenu);
    }

    public void load(JsonObject json) {
        if (json.has("background_style")) {
            String value = json.get("background_style").getAsString();
            if (BACKGROUND_STYLES.contains(value)) backgroundStyle = value;
        }
        if (json.has("custom_menu")) customMenu = json.get("custom_menu").getAsBoolean();
        if (json.has("accent_color")) {
            accent.set(json.get("accent_color").getAsInt());
        }
        // setFontStyle rather than a plain assignment, so an id from a build
        // that had a style this one does not falls back to Minecraft's font
        // instead of naming a pack that will never be found.
        if (json.has("font_style")) {
            setFontStyle(json.get("font_style").getAsString());
        }
    }
}
