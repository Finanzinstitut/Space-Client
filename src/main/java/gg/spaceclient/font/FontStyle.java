package gg.spaceclient.font;

import java.util.List;

/**
 * One entry in the font picker.
 *
 * The id is what goes in the config and never changes, the label is what the
 * button says, and the file is the name of the zip that ships inside the mod
 * jar. A null file means "no pack at all", which is how the vanilla font is
 * expressed - there is nothing to install to get it, only something to remove.
 */
public record FontStyle(String id, String label, String file, String note) {

    /** The config value written when nothing has been chosen yet. */
    public static final String DEFAULT = "DEFAULT";

    /**
     * Vanilla comes first so the least surprising option is the one your eye
     * lands on, and Open Sans comes last because it is the odd one out: every
     * other entry is a bitmap redraw of Minecraft's own glyphs, that one is a
     * TrueType face at a different size.
     *
     * Open Sans is here at all because it used to be forced. Versions up to
     * 1.8.2 shipped assets/minecraft/font/default.json in the jar, which
     * replaced the game's font everywhere with no way to say no. That file is
     * gone; the same override is now this entry, off by default.
     */
    public static final List<FontStyle> ALL = List.of(
            new FontStyle(DEFAULT, "Minecraft", null,
                    "The font the game ships with"),
            new FontStyle("SMOOTH", "Smooth", "smooth",
                    "Vanilla shapes with the stair steps taken off"),
            new FontStyle("ANTI_ALIAS", "Anti-Alias", "antialias",
                    "Softened edges, thinner strokes"),
            new FontStyle("SMALL_CAPS", "Small Caps", "smallcaps",
                    "Lower case drawn as reduced capitals"),
            new FontStyle("SQUARE", "Square", "square",
                    "Squared off letters on an even grid"),
            new FontStyle("DOODLE", "Doodle", "doodle",
                    "Hand drawn, as though written with a mouse"),
            new FontStyle("BLOCKY", "Blocky", "blocky",
                    "Heavy, chunky, low detail"),
            new FontStyle("OPEN_SANS", "Open Sans", "opensans",
                    "The launcher's typeface, as TrueType")
    );

    public static FontStyle byId(String id) {
        for (FontStyle style : ALL) {
            if (style.id.equals(id)) return style;
        }
        return ALL.get(0);
    }

    public static boolean isKnown(String id) {
        for (FontStyle style : ALL) {
            if (style.id.equals(id)) return true;
        }
        return false;
    }

    /** True for the entry that installs nothing. */
    public boolean isVanilla() {
        return file == null;
    }
}
