package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Which way you are facing, as a letter and an axis.
 *
 * The Compass element already draws a scrolling strip, which is the right thing
 * when you are steering. This is the other half of the same question: not
 * "which way am I turning" but "which way am I pointed", answered in two
 * characters you can read without stopping. Building along an axis wants the
 * second, and the strip is the wrong shape for it.
 */
public class DirectionModule extends HudModule {

    private static final String[] NAMES = { "South", "West", "North", "East" };
    private static final String[] SHORT = { "S", "W", "N", "E" };

    /** Axis the facing runs along, and which way along it. */
    private static final String[] AXES = { "+Z", "-X", "-Z", "+X" };

    private final BooleanSetting showAxis = new BooleanSetting(
            "show_axis", "Show axis", "Append the axis, e.g. -Z", true);

    private final BooleanSetting shortNames = new BooleanSetting(
            "short_names", "Short names", "N instead of North", false);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFFFFF);

    public DirectionModule() {
        super("direction", "Direction", "Which way you are facing, and the axis",
                0.02f, 0.66f, false);
        addSettings(shortNames, showAxis, textColor);
    }

    /**
     * Four times a second.
     *
     * The text changes only when crossing between quadrants, so anything
     * faster is rebuilding a string that has not changed.
     */
    @Override
    protected long refreshMillis() { return 250; }

    private int quadrant() {
        if (mc.player == null) return 0;
        // The same rounding vanilla uses for its own facing: yaw is measured
        // from south, and each direction owns forty five degrees either side
        float yaw = mc.player.getYRot();
        return Math.floorMod(Math.round(yaw / 90f), 4);
    }

    private String text() {
        return cachedText(() -> {
            int at = quadrant();
            String name = shortNames.get() ? SHORT[at] : NAMES[at];
            return showAxis.get() ? name + "  " + AXES[at] : name;
        });
    }

    @Override
    public int getWidth() { return mc.font.width(text()); }

    @Override
    public int getHeight() { return mc.font.lineHeight; }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.text(mc.font, text(), x, y, textColor.get(), true);
    }
}
