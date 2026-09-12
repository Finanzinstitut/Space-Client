package gg.spaceclient.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A round icon button for the main menu.
 *
 * The five of these are the whole menu, so they carry more weight than a
 * button normally would: there is no text to fall back on, and a symbol that
 * is not recognised in the first second is a menu nobody can use. Each glyph
 * is therefore drawn from rectangles at a fixed small size rather than scaled
 * from a texture - a hand drawn shape stays crisp at any window size, and the
 * mod ships no icon sheet.
 *
 * The label appears under the row on hover instead of under every icon, so the
 * resting state stays as quiet as the screenshot it is modelled on.
 */
public class MenuIcon extends Button {

    public enum Kind { WORLDS, SERVERS, ACCOUNT, SETTINGS, QUIT }

    private final Kind kind;
    private final String label;

    /** Hover, eased rather than switched, so the ring grows into place. */
    private float hover = 0f;

    /**
     * How far this icon has arrived, from the screen's staggered entrance.
     * Zero means not drawn at all rather than drawn transparent, because a
     * button that is invisible but clickable is worse than one that is absent.
     */
    private float appear = 0f;

    public MenuIcon(int x, int y, int size, Kind kind, String label, Runnable onPress) {
        // Guarded through the button instance rather than by clearing the
        // widget's own enabled flag: that flag's name on this version has not
        // been proven by a compile, and the press handler is handed the button
        // anyway, so the check costs nothing and risks nothing.
        super(x, y, size, size, Component.empty(),
                btn -> { if (((MenuIcon) btn).appear > 0.5f) onPress.run(); },
                DEFAULT_NARRATION);
        this.kind = kind;
        this.label = label;
    }

    public String label() { return label; }

    public void setAppear(float value) {
        this.appear = Ease.clamp01(value);
    }

    public float appear() { return appear; }

    /** Menu icons are pointed at, not tabbed to. */
    @Override
    public void setFocused(boolean focused) { super.setFocused(false); }

    @Override
    public boolean isFocused() { return false; }

    public net.minecraft.client.gui.ComponentPath nextFocusPath(
            net.minecraft.client.gui.navigation.FocusNavigationEvent event) {
        return null;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (appear <= 0.01f) return;

        hover = Ease.approach(hover, isHovered() ? 1f : 0f, 0.25f, Math.max(delta, 0.1f));

        int size = this.width;
        int centreX = getX() + size / 2;

        // Rises the last few pixels into place rather than simply fading, so
        // the row assembles instead of materialising
        int lift = Math.round((1f - Ease.outCubic(appear)) * 10f);
        int centreY = getY() + size / 2 - lift;

        int alpha = Math.round(255 * Ease.outCubic(appear));
        int radius = size / 2 + Math.round(hover * 2f);

        // The plate: barely there at rest, lifting towards the accent on hover
        int plate = blend(0x20FFFFFF, withAlpha(Theme.accent(), 0x55), hover);
        circle(graphics, centreX, centreY, radius, scaleAlpha(plate, alpha));

        int ring = blend(0x40FFFFFF, Theme.CYAN, hover);
        ringOutline(graphics, centreX, centreY, radius, scaleAlpha(ring, alpha));

        int ink = scaleAlpha(blend(0xFFD8D4F0, 0xFFFFFFFF, hover), alpha);
        glyph(graphics, centreX, centreY, ink);
    }

    // --- the glyphs ---

    private void glyph(GuiGraphicsExtractor graphics, int cx, int cy, int colour) {
        switch (kind) {
            case WORLDS -> block(graphics, cx, cy, colour);
            case SERVERS -> globe(graphics, cx, cy, colour);
            case ACCOUNT -> person(graphics, cx, cy, colour);
            case SETTINGS -> gear(graphics, cx, cy, colour);
            case QUIT -> cross(graphics, cx, cy, colour);
        }
    }

    /** A block seen head on, with its top face folded over. */
    private void block(GuiGraphicsExtractor graphics, int cx, int cy, int colour) {
        int half = 6;
        int top = cy - half + 2;
        int bottom = cy + half;

        // Front face
        rect(graphics, cx - half, top, cx + half, bottom, colour);
        // The top face, drawn as a narrowing band so it reads as depth
        for (int i = 0; i < 3; i++) {
            int inset = i;
            graphics.fill(cx - half + inset, top - 3 + i, cx + half - inset, top - 2 + i, colour);
        }
        // A seam down the middle of the front face
        graphics.fill(cx - 1, top + 3, cx + 1, bottom - 3, colour);
    }

    /** A globe: a ring with a waist and a meridian. */
    private void globe(GuiGraphicsExtractor graphics, int cx, int cy, int colour) {
        int radius = 7;
        ringOutline(graphics, cx, cy, radius, colour);
        graphics.fill(cx - radius + 1, cy, cx + radius - 1, cy + 1, colour);
        graphics.fill(cx - radius + 3, cy - 4, cx + radius - 3, cy - 3, colour);
        graphics.fill(cx - radius + 3, cy + 4, cx + radius - 3, cy + 5, colour);
        graphics.fill(cx, cy - radius + 1, cx + 1, cy + radius - 1, colour);
    }

    /** Head and shoulders. */
    private void person(GuiGraphicsExtractor graphics, int cx, int cy, int colour) {
        circle(graphics, cx, cy - 4, 4, colour);
        // Shoulders as a wide arc, flat along the bottom
        for (int row = 0; row < 5; row++) {
            int width = 3 + row * 2;
            graphics.fill(cx - width, cy + 2 + row, cx + width, cy + 3 + row, colour);
        }
    }

    /** A ring with eight teeth. */
    private void gear(GuiGraphicsExtractor graphics, int cx, int cy, int colour) {
        ringOutline(graphics, cx, cy, 5, colour);
        ringOutline(graphics, cx, cy, 4, colour);

        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2 * i / 8.0;
            int x = cx + (int) Math.round(Math.cos(angle) * 7);
            int y = cy + (int) Math.round(Math.sin(angle) * 7);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, colour);
        }
    }

    /** Two strokes through the centre. */
    private void cross(GuiGraphicsExtractor graphics, int cx, int cy, int colour) {
        for (int i = -5; i <= 5; i++) {
            graphics.fill(cx + i, cy + i, cx + i + 2, cy + i + 2, colour);
            graphics.fill(cx + i, cy - i, cx + i + 2, cy - i + 2, colour);
        }
    }

    // --- drawing helpers ---

    private static void rect(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int colour) {
        graphics.fill(x1, y1, x2, y1 + 1, colour);
        graphics.fill(x1, y2 - 1, x2, y2, colour);
        graphics.fill(x1, y1, x1 + 1, y2, colour);
        graphics.fill(x2 - 1, y1, x2, y2, colour);
    }

    /**
     * A filled disc, one row of pixels at a time.
     *
     * Row by row rather than a rounded rectangle because the icons sit on a
     * photograph: a rounded square with a large radius still reads as a square
     * against a busy background, and the row widths here are exact.
     */
    static void circle(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int colour) {
        if (radius <= 0 || ((colour >>> 24) & 0xFF) == 0) return;
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            if (half <= 0) continue;
            graphics.fill(cx - half, cy + dy, cx + half, cy + dy + 1, colour);
        }
    }

    /** The same shape hollowed out. */
    static void ringOutline(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int colour) {
        if (radius <= 1 || ((colour >>> 24) & 0xFF) == 0) return;
        for (int dy = -radius; dy <= radius; dy++) {
            int outer = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            int innerRadius = radius - 1;
            int inner = Math.abs(dy) > innerRadius ? 0
                    : (int) Math.round(Math.sqrt((double) innerRadius * innerRadius - (double) dy * dy));
            if (outer <= 0) continue;
            if (inner <= 0) {
                graphics.fill(cx - outer, cy + dy, cx + outer, cy + dy + 1, colour);
            } else {
                graphics.fill(cx - outer, cy + dy, cx - inner, cy + dy + 1, colour);
                graphics.fill(cx + inner, cy + dy, cx + outer, cy + dy + 1, colour);
            }
        }
    }

    static int withAlpha(int colour, int alpha) {
        return (alpha << 24) | (colour & 0xFFFFFF);
    }

    static int scaleAlpha(int colour, int alpha) {
        int existing = (colour >>> 24) & 0xFF;
        return ((existing * alpha / 255) << 24) | (colour & 0xFFFFFF);
    }

    static int blend(int from, int to, float t) {
        return Ease.color(from, to, t);
    }
}
