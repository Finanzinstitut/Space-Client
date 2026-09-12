package gg.spaceclient.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Rounded, layered panels that read as glass.
 *
 * What this is not: a blur. Frosting the view behind a panel means sampling
 * the framebuffer and running a shader over it, and there is no way to do that
 * here that would survive a version change. What carries the look instead is
 * everything else glass does - a rounded edge, a bright line along the top
 * where light catches, a darker one underneath, and a body that is lighter at
 * the top than the bottom.
 *
 * Rounding is done by insetting each row. At the small radii an interface uses
 * that is indistinguishable from a real curve and costs a handful of fills.
 *
 * One lesson is baked into the numbers below: every highlight here is derived
 * from the plate's own opacity. A fixed bright edge on a nearly transparent
 * plate is not glass, it is a stray line with nothing underneath it.
 */
public final class Glass {

    /** How far each row is pulled in, by distance from the corner. */
    private static final int[] INSET_4 = { 2, 1, 0, 0 };
    private static final int[] INSET_6 = { 3, 2, 1, 1, 0, 0 };

    private static int[] insetsFor(int radius) {
        return radius >= 6 ? INSET_6 : INSET_4;
    }

    /**
     * A glass plate.
     *
     * `tint` carries both the colour and the opacity: glass is defined by what
     * shows through it, so a caller that cannot set the alpha cannot make
     * glass.
     */
    public static void panel(GuiGraphicsExtractor graphics,
                             int x, int y, int width, int height,
                             int tint, int radius) {
        if (width <= 0 || height <= 0) return;

        int[] insets = insetsFor(radius);
        int alpha = (tint >>> 24) & 0xFF;
        if (alpha == 0) return;

        int red = (tint >> 16) & 0xFF;
        int green = (tint >> 8) & 0xFF;
        int blue = tint & 0xFF;

        // The body, drawn as rows so the corners can be pulled in.
        //
        // The gradient lightens the colour toward the top rather than thinning
        // the alpha. Thinning was the first attempt and it was wrong: these
        // plates are dark and sit on dark backgrounds, so a more transparent
        // top simply disappeared and the plate lost its shape.
        for (int row = 0; row < height; row++) {
            int inset = 0;
            if (row < insets.length) inset = insets[row];
            else if (row >= height - insets.length) inset = insets[height - 1 - row];

            float down = row / (float) Math.max(1, height - 1);
            float lift = (1f - down) * 0.16f;

            int rowColor = (alpha << 24)
                    | (lighten(red, lift) << 16)
                    | (lighten(green, lift) << 8)
                    | lighten(blue, lift);

            graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, rowColor);
        }

        int sheenInset = insets.length > 0 ? insets[0] : 0;

        // The sheen, tied to how opaque the plate is and capped.
        //
        // It was a flat 55% white before, which on a nearly transparent dark
        // plate was the only thing visible - a bright grey line floating with
        // nothing under it. A highlight cannot be brighter than the surface it
        // is supposed to be lying on.
        int sheenAlpha = Math.min(46, Math.round(alpha * 0.30f));
        if (sheenAlpha > 6) {
            graphics.fill(x + sheenInset + 1, y, x + width - sheenInset - 1, y + 1,
                    (sheenAlpha << 24) | 0xFFFFFF);
        }

        int shadowAlpha = Math.min(60, Math.round(alpha * 0.28f));
        if (shadowAlpha > 6) {
            graphics.fill(x + sheenInset + 1, y + height - 1,
                    x + width - sheenInset - 1, y + height, shadowAlpha << 24);
        }
    }

    /** Moves a channel toward white by a fraction. */
    private static int lighten(int channel, float amount) {
        return Math.min(255, Math.round(channel + (255 - channel) * amount));
    }

    /**
     * The same plate with a coloured edge, for something that is selected or
     * active.
     */
    public static void panel(GuiGraphicsExtractor graphics,
                             int x, int y, int width, int height,
                             int tint, int radius, int edge, float strength) {
        panel(graphics, x, y, width, height, tint, radius);
        if (strength <= 0.01f) return;

        int alpha = Math.round(255 * Math.min(1f, strength));
        int color = (alpha << 24) | (edge & 0xFFFFFF);
        int inset = insetsFor(radius)[0];

        graphics.fill(x + inset, y, x + width - inset, y + 1, color);
        graphics.fill(x + inset, y + height - 1, x + width - inset, y + height, color);
        graphics.fill(x, y + inset, x + 1, y + height - inset, color);
        graphics.fill(x + width - 1, y + inset, x + width, y + height - inset, color);
    }

    private Glass() {}
}
