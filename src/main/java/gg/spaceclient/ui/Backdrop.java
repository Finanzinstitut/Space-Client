package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Random;

/**
 * The menu background: either one of the photographs, or the launcher's own
 * drawn look - a deep violet gradient, a scattering of stars and a glowing
 * planet in the top right corner.
 *
 * Star positions come from a fixed seed so they stay put between frames instead
 * of flickering, and are only recomputed when the window size changes.
 */
public final class Backdrop {
    private static final int STAR_COUNT = 140;

    private static int[] starX = new int[0];
    private static int[] starY = new int[0];
    private static int[] starSize = new int[0];
    private static int[] starColor = new int[0];
    private static int cachedWidth = -1;
    private static int cachedHeight = -1;

    private static void rebuild(int width, int height) {
        Random random = new Random(42);
        starX = new int[STAR_COUNT];
        starY = new int[STAR_COUNT];
        starSize = new int[STAR_COUNT];
        starColor = new int[STAR_COUNT];

        for (int i = 0; i < STAR_COUNT; i++) {
            starX[i] = random.nextInt(Math.max(1, width));
            starY[i] = random.nextInt(Math.max(1, height));
            starSize[i] = random.nextInt(10) == 0 ? 2 : 1;
            int brightness = 150 + random.nextInt(106);
            starColor[i] = 0xB0000000 | (brightness << 16) | (brightness << 8) | 0xFF;
        }
        cachedWidth = width;
        cachedHeight = height;
    }

    /**
     * The photographic backgrounds, by style name.
     *
     * Held as identifiers rather than loaded eagerly: only the selected one is
     * ever drawn, and the game loads a texture the first time it is asked for.
     * Three full screen images resident at once would be a lot of video memory
     * for two of them to be invisible.
     */
    /**
     * Slow drifting colour, drawn rather than photographed.
     *
     * The one background that actually moves. Three broad bands of colour slide
     * across each other on different periods, so the pattern never repeats
     * where anyone would notice, and each is drawn as a handful of wide
     * translucent columns - overlapping transparency is what makes the edges
     * soft without a blur being available.
     *
     * Deliberately slow. A background that draws attention to itself is a
     * background competing with the menu in front of it.
     */
    private static void drawAurora(GuiGraphicsExtractor graphics, int width, int height) {
        graphics.fill(0, 0, width, height, 0xFF06041A);

        long now = System.currentTimeMillis();
        int columns = Math.max(24, width / 24);
        int columnWidth = (int) Math.ceil(width / (float) columns);

        // Three bands: the accent, a cool counterpoint, and a warm one
        int[] colours = { Theme.accent() & 0xFFFFFF, 0x2E6BFF, 0xFF4FA8 };
        float[] periods = { 17000f, 23000f, 31000f };
        float[] heights = { 0.55f, 0.70f, 0.40f };

        for (int band = 0; band < colours.length; band++) {
            float phase = (now % (long) periods[band]) / periods[band] * (float) (Math.PI * 2);

            for (int column = 0; column < columns; column++) {
                float across = column / (float) columns;

                // Two waves of different wavelength per band, so the crest
                // wanders instead of marching
                float wave = (float) (Math.sin(across * 3.1f + phase)
                        + Math.sin(across * 1.3f - phase * 0.6f)) * 0.5f;

                int centre = Math.round(height * (0.5f + wave * 0.22f));
                int thickness = Math.round(height * heights[band] * 0.5f);

                int top = Math.max(0, centre - thickness);
                int bottom = Math.min(height, centre + thickness);
                if (bottom <= top) continue;

                int alpha = Math.round(26 + 22 * (wave * 0.5f + 0.5f));
                int colour = (alpha << 24) | colours[band];

                graphics.fill(column * columnWidth, top,
                        column * columnWidth + columnWidth, bottom, colour);
            }
        }

        // A darker floor and ceiling, so the middle reads as the lit part
        graphics.fill(0, 0, width, height / 6, 0x66000000);
        graphics.fill(0, height - height / 5, width, height, 0x66000000);
    }

    private static Identifier photoFor(String style) {
        String file = switch (style) {
            case "NEBULA" -> "nebula";
            case "BLACK_HOLE" -> "black_hole";
            case "GALAXY" -> "galaxy";
            default -> null;
        };
        if (file == null) return null;
        return Identifier.fromNamespaceAndPath(SpaceClient.MOD_ID,
                "textures/gui/backdrop/" + file + ".png");
    }

    public static void draw(GuiGraphicsExtractor graphics, int width, int height) {
        String style = SpaceClient.getSettings().backgroundStyle();

        Identifier photo = photoFor(style);
        if (photo != null) {
            // Filled black first: if the texture cannot be drawn on this
            // version the screen stays readable instead of showing the world
            // through it
            graphics.fill(0, 0, width, height, 0xFF05040E);

            if (Textures.draw(graphics, photo, 0, 0, width, height)) {
                // A veil over the photograph. These are bright images and the
                // menu is white text; without it the text sits on whatever
                // happens to be behind it and half of it disappears.
                graphics.fill(0, 0, width, height, 0x99000000);
                return;
            }

            // Falls through to the drawn starfield, which always works
        }

        if ("AURORA".equals(style)) {
            drawAurora(graphics, width, height);
            return;
        }

        if (!Theme.spaceBackdrop()) {
            graphics.fill(0, 0, width, height, Theme.backdrop());
            return;
        }

        if (width != cachedWidth || height != cachedHeight) {
            rebuild(width, height);
        }

        // Vertical gradient from the violet highlight down to near black
        graphics.fillGradient(0, 0, width, height, Theme.BG_MID, Theme.BG_DEEP);

        for (int i = 0; i < STAR_COUNT; i++) {
            int size = starSize[i];
            graphics.fill(starX[i], starY[i], starX[i] + size, starY[i] + size, starColor[i]);
        }

        drawPlanetGlow(graphics, width);
    }

    /**
     * The launcher's planet sits partly off the top right corner. Concentric
     * rings of increasing transparency stand in for the CSS radial gradient.
     */
    private static void drawPlanetGlow(GuiGraphicsExtractor graphics, int width) {
        int cx = width - 60;
        int cy = 30;
        int maxRadius = 120;

        for (int radius = maxRadius; radius > 0; radius -= 6) {
            // Fades out towards the edge of the glow
            int alpha = (int) (70 * (1.0 - radius / (double) maxRadius));
            if (alpha <= 2) continue;
            int color = (alpha << 24) | 0x7C5CFF;

            for (int dy = -radius; dy <= radius; dy += 2) {
                int y = cy + dy;
                if (y < 0) continue;
                double halfWidth = Math.sqrt(Math.max(0, radius * radius - dy * dy));
                if (halfWidth < 1) continue;
                int x1 = (int) (cx - halfWidth);
                int x2 = (int) (cx + halfWidth);
                graphics.fill(x1, y, x2, y + 2, color);
            }
        }
    }

    private Backdrop() {}
}
