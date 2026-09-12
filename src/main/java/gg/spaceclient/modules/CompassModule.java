package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A strip compass showing which way you are facing.
 *
 * The addition over a plain "facing north" readout: the strip scrolls smoothly,
 * so you can line up on a heading between two cardinal points, which is what you
 * actually want when building or following a coordinate line.
 */
public class CompassModule extends HudModule {
    private static final int WIDTH = 140;
    private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private final BooleanSetting showDegrees = new BooleanSetting(
            "show_degrees", "Show degrees", "Print the exact heading", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the markings", 0xFFFFFFFF);

    public CompassModule() {
        super("compass", "Compass", "Shows your heading as a scrolling strip", 0.40f, 0.03f, false);
        addSettings(showDegrees, textColor);
    }

    /** 0 is north, increasing clockwise. */
    private float heading() {
        if (mc.player == null) return 0;
        float yaw = mc.player.getYRot() % 360;
        if (yaw < 0) yaw += 360;
        return (yaw + 180) % 360;
    }

    @Override
    public int getWidth() { return WIDTH; }

    @Override
    public int getHeight() { return mc.font.lineHeight * (showDegrees.get() ? 2 : 1) + 4; }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        float heading = heading();
        int centre = x + WIDTH / 2;

        // Each compass point slides past according to how far off it is
        for (int i = 0; i < POINTS.length; i++) {
            float pointAngle = i * 45f;
            float difference = ((pointAngle - heading + 540) % 360) - 180;
            if (Math.abs(difference) > 70) continue;

            int px = centre + (int) (difference * (WIDTH / 160f));
            String label = POINTS[i];
            int labelX = px - mc.font.width(label) / 2;
            boolean cardinal = i % 2 == 0;
            graphics.text(mc.font, label, labelX, y + 4,
                    cardinal ? textColor.get() : 0xFF9A95C9, true);
        }

        // Centre marker
        graphics.fill(centre, y, centre + 1, y + 3, textColor.get());

        if (showDegrees.get()) {
            String degrees = String.format("%.0f°", heading);
            graphics.text(mc.font, degrees,
                    centre - mc.font.width(degrees) / 2, y + mc.font.lineHeight + 4,
                    textColor.get(), true);
        }
    }
}
