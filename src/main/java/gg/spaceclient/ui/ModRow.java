package gg.spaceclient.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A module as a single row: name, category, toggle.
 *
 * The grid of cards it replaces was built when the menu filled the screen. In
 * a window a third that size a 74 pixel card shows four modules at a time out
 * of twenty six, which turns browsing into scrolling. A 26 pixel row shows
 * eleven, and a list you can see the shape of is easier to search by eye than
 * a grid you have to page through.
 *
 * The row height also solves a clipping problem. Rows overhang the list edge by
 * at most their own height while scrolling, and at 26 pixels that overhang
 * still lands inside the window's footer, where a strip covers it. A card would
 * have hung past the window entirely and painted over the world.
 */
public class ModRow extends Button {

    private static final float HOVER_SPEED = 0.22f;
    private static final float STATE_SPEED = 0.18f;

    /** Width of the toggle pill on the right. */
    private static final int PILL_W = 26;
    private static final int PILL_H = 12;

    private final Supplier<String> label;
    private final Supplier<String> category;
    private final BooleanSupplier active;
    private final boolean hasSettings;

    private float hover = 0f;
    private float state = 0f;

    private int lastMouseX = 0;
    private int lastMouseY = 0;

    public ModRow(int x, int y, int width, int height,
                  Supplier<String> label,
                  Supplier<String> category,
                  BooleanSupplier active,
                  boolean hasSettings,
                  Runnable onPress) {
        super(x, y, width, height, Component.empty(), btn -> onPress.run(), DEFAULT_NARRATION);
        this.label = label;
        this.category = category;
        this.active = active;
        this.hasSettings = hasSettings;
        this.state = active.getAsBoolean() ? 1f : 0f;
    }

    /**
     * True when the pointer is over the gear dots rather than the row body.
     *
     * Same trick the cards used: one press handler, and the position of the
     * pointer at the last frame decides what the click meant. This version
     * changed the mouse event signatures, so overriding them is avoided
     * throughout the codebase.
     */
    public boolean overGear() {
        return hasSettings
                && lastMouseX >= getX() + width - PILL_W - 30
                && lastMouseX <= getX() + width - PILL_W - 8;
    }

    @Override
    public void setFocused(boolean focused) { super.setFocused(false); }

    @Override
    public boolean isFocused() { return false; }

    public net.minecraft.client.gui.ComponentPath nextFocusPath(
            net.minecraft.client.gui.navigation.FocusNavigationEvent event) {
        return null;
    }

    /** Delegates to Ease so every animation in the client shares one curve. */
    private static float approach(float current, float target, float speed, float delta) {
        return Ease.approach(current, target, speed, delta);
    }

    private static int lerpColor(int from, int to, float t) {
        return Ease.color(from, to, t);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        hover = approach(hover, isHovered() ? 1f : 0f, HOVER_SPEED, delta);
        state = approach(state, active.getAsBoolean() ? 1f : 0f, STATE_SPEED, delta);

        int x1 = getX();
        int y1 = getY();
        int x2 = x1 + width;
        int y2 = y1 + height;

        var font = net.minecraft.client.Minecraft.getInstance().font;

        // The row itself only appears under the pointer. A list where every
        // line is a filled box is heavier to read than one where the lines are
        // just text until you reach for them.
        if (hover > 0.01f) {
            // Glass under the pointer rather than a flat block: the row lifts
            // toward you instead of merely changing colour
            Glass.panel(graphics, x1, y1, x2 - x1, y2 - y1,
                    lerpColor(0x00000000, Theme.CARD_HOVER, hover), 4);
        }

        // A bar on the left edge marks what is on, readable in one sweep down
        // the list without reading any of the words
        if (state > 0.01f) {
            int barHeight = Math.round((height - 8) * state);
            int top = y1 + (height - barHeight) / 2;
            graphics.fill(x1, top, x1 + 2, top + barHeight, Theme.accent());
        }

        int textY = y1 + (height - 8) / 2;
        String name = label.get();
        graphics.text(font, name, x1 + 12, textY,
                lerpColor(Theme.TEXT_DIM, Theme.TEXT, Math.max(hover, state)), false);

        // Category sits after the name rather than in a column, so a long name
        // is never truncated to keep a column straight
        String tag = category.get();
        int tagX = x1 + 12 + font.width(name) + 8;
        int pillLeft = x2 - 10 - PILL_W;
        if (tagX + font.width(tag) < pillLeft - 34) {
            graphics.text(font, tag, tagX, textY, Theme.OFF, false);
        }

        if (hasSettings) {
            int dotColor = overGear() && hover > 0.3f ? Theme.accent() : Theme.OFF;
            int dotY = y1 + height / 2 - 1;
            for (int i = 0; i < 3; i++) {
                graphics.fill(pillLeft - 28 + i * 5, dotY,
                        pillLeft - 26 + i * 5, dotY + 2, dotColor);
            }
        }

        // The toggle: a track that fills and a knob that slides, so the state
        // is legible at a glance and the change has a direction
        int pillTop = y1 + (height - PILL_H) / 2;
        graphics.fill(pillLeft, pillTop, pillLeft + PILL_W, pillTop + PILL_H,
                lerpColor(Theme.OFF, Theme.accent(), state));

        // Eased on the way across, so the knob leaves and arrives rather than
        // travelling at one speed
        int knob = pillLeft + 1 + Math.round((PILL_W - PILL_H) * Ease.inOutCubic(state));
        graphics.fill(knob, pillTop + 1, knob + PILL_H - 2, pillTop + PILL_H - 1,
                state > 0.5f ? Theme.TEXT_ON_ACCENT : Theme.TEXT_DIM);
    }
}
