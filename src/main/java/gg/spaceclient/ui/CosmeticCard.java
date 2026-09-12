package gg.spaceclient.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A cosmetic as a card: a picture with a label strip under it.
 *
 * ModCard was nearly right and is deliberately not reused. A module card is
 * mostly empty space because a module has nothing to show, so its whole surface
 * is the on/off colour. A cosmetic does have something to show, and the picture
 * has to be the largest thing on the card or the grid is just a list of names
 * with extra steps. Sharing a class would have meant one of the two carrying a
 * flag it ignores.
 */
public class CosmeticCard extends Button {

    private static final float HOVER_SPEED = 0.22f;
    private static final float STATE_SPEED = 0.16f;

    /** Height of the label strip at the bottom. */
    private static final int FOOT = 22;

    private final Supplier<String> label;
    private final Supplier<String> subtitle;
    private final Supplier<Identifier> texture;
    private final BooleanSupplier active;
    private final boolean dimmed;

    /**
     * How many frames are stacked in the texture.
     *
     * Cosmetica's outfit thumbnails are requested as animation sheets rather
     * than single images, so drawing the whole texture would squeeze every
     * frame into the card and show a column of tiny players. One is the right
     * value for anything that is a plain image.
     */
    private final int frames;

    private float hover = 0f;
    private float state = 0f;

    public CosmeticCard(int x, int y, int width, int height,
                        Supplier<String> label,
                        Supplier<String> subtitle,
                        Supplier<Identifier> texture,
                        BooleanSupplier active,
                        int frames,
                        boolean dimmed,
                        Runnable onPress) {
        super(x, y, width, height, Component.empty(), btn -> onPress.run(), DEFAULT_NARRATION);
        this.label = label;
        this.subtitle = subtitle;
        this.texture = texture;
        this.active = active;
        this.frames = Math.max(1, frames);
        this.dimmed = dimmed;
        this.state = active.getAsBoolean() ? 1f : 0f;
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
        hover = approach(hover, isHovered() ? 1f : 0f, HOVER_SPEED);
        state = approach(state, active.getAsBoolean() ? 1f : 0f, STATE_SPEED);

        int x1 = getX();
        int y1 = getY();
        int x2 = x1 + width;
        int y2 = y1 + height;

        // Lifts by a pixel on hover, as the module cards do
        int lift = Math.round(hover);
        y1 -= lift;
        y2 -= lift;

        graphics.fill(x1, y1, x2, y2, lerpColor(Theme.CARD, Theme.CARD_HOVER, hover));

        // Picture fills everything above the label strip
        int picBottom = y2 - FOOT;
        graphics.fill(x1 + 1, y1 + 1, x2 - 1, picBottom, Theme.CARD_FOOT);

        Identifier image = texture.get();
        boolean drawn = image != null && Textures.drawFrame(
                graphics, image, x1 + 1, y1 + 1, width - 2, picBottom - y1 - 1, frames, 0);

        if (!drawn) {
            // No texture, or no texture call on this version. A dashed frame
            // reads as "nothing here" rather than as a rendering failure.
            int cx = (x1 + x2) / 2;
            int cy = (y1 + picBottom) / 2;
            for (int i = -8; i <= 8; i += 4) {
                graphics.fill(cx + i, cy - 1, cx + i + 2, cy + 1, Theme.OFF);
            }
        }

        // Unusable outfits are still shown, because hiding them makes an outfit
        // look deleted when it is only unavailable on the current plan
        if (dimmed) {
            graphics.fill(x1 + 1, y1 + 1, x2 - 1, picBottom, 0x99000000);
        }

        int border = lerpColor(lerpColor(Theme.BORDER, Theme.BORDER_HOVER, hover),
                Theme.accent(), state);
        graphics.fill(x1, y1, x2, y1 + 1, border);
        graphics.fill(x1, y2 - 1, x2, y2, border);
        graphics.fill(x1, y1, x1 + 1, y2, border);
        graphics.fill(x2 - 1, y1, x2, y2, border);

        // The strip fills from the left as the item becomes the worn one
        graphics.fill(x1 + 1, picBottom, x2 - 1, y2 - 1, Theme.CARD_FOOT);
        if (state > 0.01f) {
            int fillTo = x1 + 1 + Math.round((x2 - x1 - 2) * state);
            graphics.fill(x1 + 1, picBottom, fillTo, y2 - 1, Theme.accent());
        }

        var font = Minecraft.getInstance().font;
        int textColor = state > 0.5f ? Theme.TEXT_ON_ACCENT : Theme.TEXT;
        String name = clip(label.get(), width - 10);
        graphics.text(font, name, x1 + (width - font.width(name)) / 2, picBottom + 7,
                textColor, false);

        // The slot the cosmetic occupies, in the top corner of the picture
        String tag = subtitle.get();
        if (tag != null && !tag.isEmpty()) {
            String shown = clip(tag, width - 10);
            graphics.fill(x1 + 1, y1 + 1, x1 + 1 + font.width(shown) + 8, y1 + 12, 0xB0000000);
            graphics.text(font, shown, x1 + 5, y1 + 3, Theme.TEXT_DIM, false);
        }
    }

    /**
     * Shortens a label to fit.
     *
     * The font is fetched here rather than passed in because the type's name on
     * this version is not proven anywhere in the codebase, and a parameter
     * would be the one place it had to be spelled out.
     */
    private static String clip(String text, int available) {
        if (text == null) return "";
        var font = Minecraft.getInstance().font;
        if (available <= 8 || font.width(text) <= available) return text;
        while (text.length() > 1 && font.width(text + "..") > available) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "..";
    }
}
