package gg.spaceclient.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Digits that roll into place instead of being replaced.
 *
 * The previous attempt eased the *value*, so 40 counted up through 41, 42, 43
 * to 47. That is a different effect and a worse one: it invents readings that
 * were never measured, and at a glance it looks like the number is unstable
 * rather than like it changed once.
 *
 * This animates the *characters*. When 47 becomes 52, the 4 slides up and out
 * while the 5 arrives from below in its place, and the 7 and 2 do the same a
 * moment later. The number is only ever 47 or 52; what moves is the type.
 *
 * Positions are staggered from the right, which is what an odometer does
 * mechanically - the last wheel turns first and drags the one beside it.
 */
public final class Odometer {

    /** How long one digit takes to travel. */
    private static final long ROLL_MS = 260;

    /** Delay between neighbouring digits, counted from the right. */
    private static final long STAGGER_MS = 45;

    /**
     * How far a digit travels, as a fraction of a line.
     *
     * Not a whole line. Without a clipping rectangle a digit that travels its
     * full height is still legible when it leaves, and two readable digits in
     * one slot is worse than a short hop. Sixty percent plus the fade is enough
     * to read as movement and little enough to stay inside the line.
     */
    private static final float TRAVEL = 0.6f;

    private String shown = "";
    private String previous = "";
    private long startedAt = 0;

    /** Sets the value, starting a roll if it differs from the last one. */
    public void set(String value) {
        if (value == null) value = "";
        if (value.equals(shown)) return;

        previous = shown;
        shown = value;
        startedAt = previous.isEmpty() ? 0 : System.currentTimeMillis();
    }

    public String value() { return shown; }

    /** How far along the whole roll is, counting the stagger. */
    private boolean rolling() {
        if (startedAt == 0) return false;
        long total = ROLL_MS + STAGGER_MS * Math.max(shown.length(), previous.length());
        return System.currentTimeMillis() - startedAt <= total;
    }

    /**
     * Progress of one character position, 0 to 1.
     *
     * `fromRight` is the distance from the right hand end, so the units digit
     * moves first and the leading digits follow.
     */
    private float progressFor(int fromRight) {
        long age = System.currentTimeMillis() - startedAt - fromRight * STAGGER_MS;
        if (age <= 0) return 0f;
        if (age >= ROLL_MS) return 1f;
        return Ease.outCubic(age / (float) ROLL_MS);
    }

    private static int withAlpha(int color, float factor) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * Ease.clamp01(factor));
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    /**
     * Draws the current value, rolling any characters that changed.
     *
     * Characters are placed against the *new* string throughout, so the layout
     * never shifts mid animation - only the glyph in each slot moves.
     */
    public void draw(GuiGraphicsExtractor graphics, Font font,
                     int x, int y, int color, boolean shadow) {
        if (!rolling()) {
            graphics.text(font, shown, x, y, color, shadow);
            return;
        }

        int line = font.lineHeight;
        int cursor = x;

        for (int index = 0; index < shown.length(); index++) {
            String character = String.valueOf(shown.charAt(index));
            int fromRight = shown.length() - 1 - index;

            // Compared from the right as well, so 99 -> 100 rolls the digits
            // that actually changed rather than every one of them
            int oldIndex = previous.length() - 1 - fromRight;
            String old = oldIndex >= 0 && oldIndex < previous.length()
                    ? String.valueOf(previous.charAt(oldIndex)) : "";

            if (old.equals(character)) {
                graphics.text(font, character, cursor, y, color, shadow);
            } else {
                float progress = progressFor(fromRight);
                int travel = Math.round(line * TRAVEL);

                if (progress < 1f && !old.isEmpty()) {
                    graphics.text(font, old, cursor,
                            y - Math.round(travel * progress),
                            withAlpha(color, 1f - progress), shadow);
                }

                graphics.text(font, character, cursor,
                        y + Math.round(travel * (1f - progress)),
                        withAlpha(color, progress), shadow);
            }

            cursor += font.width(character);
        }
    }

    /** Width of the settled value, for layout. */
    public int width(Font font) { return font.width(shown); }
}
