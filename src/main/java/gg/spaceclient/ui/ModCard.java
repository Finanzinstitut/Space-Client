package gg.spaceclient.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A module as a card rather than a row.
 *
 * Rows made every module look like a settings line; a grid of cards reads as a
 * collection you browse, which is what this list actually is. The bottom strip
 * carries the name and the on state, so the eye can sweep the grid and see
 * what is active without reading a single word.
 *
 * Animation here is not decoration. A card that lights up as the pointer
 * arrives tells you what a click will hit before you make it, and a colour that
 * slides in over a moment rather than snapping makes it obvious that the click
 * registered - both of which a flat repaint leaves you guessing about.
 */
public class ModCard extends Button {

    /** Long enough to read as motion, short enough not to feel sluggish. */
    private static final float HOVER_SPEED = 0.22f;
    private static final float STATE_SPEED = 0.16f;

    private final Supplier<String> label;
    private final BooleanSupplier active;
    private final boolean hasSettings;

    /** Eased 0..1 rather than booleans, so drawing can interpolate. */
    private float hover = 0f;
    private float state = 0f;

    private int lastMouseX = 0;
    private int lastMouseY = 0;

    public ModCard(int x, int y, int width, int height,
                   Supplier<String> label,
                   BooleanSupplier active,
                   boolean hasSettings,
                   Runnable onPress) {
        super(x, y, width, height, Component.empty(), btn -> onPress.run(), DEFAULT_NARRATION);
        this.label = label;
        this.active = active;
        this.hasSettings = hasSettings;
        this.state = active.getAsBoolean() ? 1f : 0f;
    }

    /** True when the pointer is over the gear corner rather than the card body. */
    public boolean overGear() {
        return hasSettings
                && lastMouseX >= getX() + width - 20
                && lastMouseY <= getY() + 20;
    }

    /** Mouse driven only, for the same reason FlatButton is. */
    @Override
    public void setFocused(boolean focused) { super.setFocused(false); }

    @Override
    public boolean isFocused() { return false; }

    public net.minecraft.client.gui.ComponentPath nextFocusPath(
            net.minecraft.client.gui.navigation.FocusNavigationEvent event) {
        return null;
    }

    private static float approach(float current, float target, float speed) {
        return current + (target - current) * speed;
    }

    private static int lerpColor(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        hover = approach(hover, isHovered() ? 1f : 0f, HOVER_SPEED);
        state = approach(state, active.getAsBoolean() ? 1f : 0f, STATE_SPEED);

        int x1 = getX();
        int y1 = getY();
        int x2 = x1 + width;
        int y2 = y1 + height;

        // The card lifts by a pixel on hover, which sells depth more cheaply
        // than any shadow this renderer could draw.
        int lift = Math.round(hover);
        y1 -= lift;
        y2 -= lift;

        int base = lerpColor(Theme.CARD, Theme.CARD_HOVER, hover);
        int filled = lerpColor(base, Theme.accentDim(), state * 0.85f);
        graphics.fill(x1, y1, x2, y2, filled);

        // Border brightens toward the accent as the module comes on
        int border = lerpColor(lerpColor(Theme.BORDER, Theme.BORDER_HOVER, hover),
                Theme.accent(), state);
        graphics.fill(x1, y1, x2, y1 + 1, border);
        graphics.fill(x1, y2 - 1, x2, y2, border);
        graphics.fill(x1, y1, x1 + 1, y2, border);
        graphics.fill(x2 - 1, y1, x2, y2, border);

        // The strip fills from the left as the module switches on, so the
        // change has a direction instead of simply appearing
        int stripTop = y2 - 22;
        graphics.fill(x1 + 1, stripTop, x2 - 1, y2 - 1, Theme.CARD_FOOT);
        if (state > 0.01f) {
            int fillTo = x1 + 1 + Math.round((x2 - x1 - 2) * state);
            graphics.fill(x1 + 1, stripTop, fillTo, y2 - 1, Theme.accent());
        }

        var font = net.minecraft.client.Minecraft.getInstance().font;
        String name = label.get();
        int textColor = state > 0.5f ? Theme.TEXT_ON_ACCENT : Theme.TEXT;
        graphics.text(font, name,
                x1 + (width - font.width(name)) / 2, stripTop + 7, textColor, false);

        // Settings live in the corner: three dots, lit only when reachable
        if (hasSettings) {
            int dotColor = overGear() ? Theme.accent() : Theme.TEXT_DIM;
            for (int i = 0; i < 3; i++) {
                graphics.fill(x2 - 15 + i * 4, y1 + 8, x2 - 13 + i * 4, y1 + 10, dotColor);
            }
        }
    }
}
