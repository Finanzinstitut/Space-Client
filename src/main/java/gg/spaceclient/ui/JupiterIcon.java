package gg.spaceclient.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws the Jupiter mark from rectangles rather than a texture.
 *
 * Doing it this way keeps the icon recolourable with the rest of the interface
 * and means there is no asset that has to load correctly before the menu works.
 */
public final class JupiterIcon {

    /** Bands of the gas giant, as a fraction of the radius. */
    private static final int[] BAND_COLORS = {
            0xFFD9B58A, 0xFFC49A6C, 0xFFE8CDA8, 0xFFBC8A5E,
            0xFFE2C49E, 0xFFC6986A, 0xFFE8D0B0, 0xFFD0A578,
    };

    /**
     * @param size diameter in pixels; 16 and up look best
     */
    public static void draw(GuiGraphicsExtractor graphics, int x, int y, int size) {
        int radius = size / 2;
        int cx = x + radius;
        int cy = y + radius;

        for (int row = 0; row < size; row++) {
            int dy = row - radius;
            // Half-width of the circle at this row
            double halfWidth = Math.sqrt(Math.max(0, radius * radius - dy * dy));
            if (halfWidth < 0.5) continue;

            int x1 = (int) Math.round(cx - halfWidth);
            int x2 = (int) Math.round(cx + halfWidth);

            int band = (row * BAND_COLORS.length) / size;
            graphics.fill(x1, y + row, x2, y + row + 1, BAND_COLORS[band]);
        }

        // The great red spot, lower right of centre
        int spotX = x + (int) (size * 0.58);
        int spotY = y + (int) (size * 0.52);
        int spotW = Math.max(2, size / 5);
        int spotH = Math.max(1, size / 8);
        graphics.fill(spotX, spotY, spotX + spotW, spotY + spotH, 0xFFB25442);
    }

    private JupiterIcon() {}
}
