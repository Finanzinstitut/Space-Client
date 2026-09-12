package gg.spaceclient.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * One world, as a card: the game's own thumbnail with the name underneath.
 *
 * The picture is the point, so it gets the whole top of the card and the text
 * sits below it rather than over it - a name printed across a screenshot is
 * hard to read against sky and impossible against snow.
 */
public class WorldCard extends Button {

    private final String name;
    private final String detail;
    private final Identifier thumbnail;

    /** The dashed, empty card at the end of the row. */
    private final boolean placeholder;

    private float hover = 0f;

    public WorldCard(int x, int y, int width, int height,
                     String name, String detail, Identifier thumbnail,
                     boolean placeholder, Runnable onPress) {
        super(x, y, width, height, Component.empty(), btn -> onPress.run(), DEFAULT_NARRATION);
        this.name = name;
        this.detail = detail;
        this.thumbnail = thumbnail;
        this.placeholder = placeholder;
    }

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
        hover = Ease.approach(hover, isHovered() ? 1f : 0f, 0.25f, Math.max(delta, 0.1f));

        int x1 = getX();
        int y1 = getY();
        int x2 = x1 + this.width;
        int y2 = y1 + this.height;

        // Lifts towards the cursor rather than only brightening, which is what
        // makes a row of cards feel like objects instead of pictures
        int lift = Math.round(hover * 4f);
        y1 -= lift;
        y2 -= lift;

        int imageHeight = this.height - 30;

        Glass.panel(graphics, x1, y1, this.width, this.height,
                Ease.color(0xC0100D2A, 0xE01A1550, hover), 8);

        if (placeholder) {
            drawPlaceholder(graphics, x1, y1, x2, y2, imageHeight);
        } else {
            drawThumbnail(graphics, x1, y1, imageHeight);
        }

        var font = Minecraft.getInstance().font;
        int textY = y1 + imageHeight + 6;

        String label = name;
        int room = this.width - 12;
        if (font.width(label) > room) {
            while (label.length() > 1 && font.width(label + "..") > room) {
                label = label.substring(0, label.length() - 1);
            }
            label = label + "..";
        }

        graphics.text(font, label, x1 + 8, textY,
                Ease.color(0xFFE9E6FF, 0xFFFFFFFF, hover), false);

        if (!detail.isEmpty()) {
            graphics.text(font, detail, x1 + 8, textY + font.lineHeight + 1,
                    0xFF9A95C9, false);
        }

        // The accent edge only on hover, so a row at rest stays calm
        if (hover > 0.01f) {
            int edge = MenuIcon.scaleAlpha(Theme.CYAN, Math.round(255 * hover));
            graphics.fill(x1 + 6, y1, x2 - 6, y1 + 1, edge);
            graphics.fill(x1 + 6, y2 - 1, x2 - 6, y2, edge);
        }
    }

    private void drawThumbnail(GuiGraphicsExtractor graphics, int x1, int y1, int imageHeight) {
        int inset = 4;
        int width = this.width - inset * 2;

        boolean drawn = thumbnail != null && Textures.draw(
                graphics, thumbnail, x1 + inset, y1 + inset, width, imageHeight - inset);

        if (!drawn) {
            // No icon, or no usable texture call: a plain plate rather than a
            // hole, so the card still reads as a card
            graphics.fill(x1 + inset, y1 + inset,
                    x1 + inset + width, y1 + imageHeight, 0xFF1A1640);

            var font = Minecraft.getInstance().font;
            String mark = "?";
            graphics.text(font, mark,
                    x1 + this.width / 2 - font.width(mark) / 2,
                    y1 + imageHeight / 2 - font.lineHeight / 2,
                    0xFF4A4480, false);
        }
    }

    private void drawPlaceholder(GuiGraphicsExtractor graphics,
                                 int x1, int y1, int x2, int y2, int imageHeight) {
        // A dashed outline, drawn as a run of short segments
        int colour = Ease.color(0xFF3A3560, Theme.CYAN, hover);
        for (int x = x1 + 8; x < x2 - 8; x += 8) {
            graphics.fill(x, y1 + 6, Math.min(x + 4, x2 - 8), y1 + 7, colour);
            graphics.fill(x, y2 - 7, Math.min(x + 4, x2 - 8), y2 - 6, colour);
        }
        for (int y = y1 + 8; y < y2 - 8; y += 8) {
            graphics.fill(x1 + 6, y, x1 + 7, Math.min(y + 4, y2 - 8), colour);
            graphics.fill(x2 - 7, y, x2 - 6, Math.min(y + 4, y2 - 8), colour);
        }

        // A plus in the middle of where the picture would be
        int cx = x1 + this.width / 2;
        int cy = y1 + imageHeight / 2;
        int arm = 9;
        graphics.fill(cx - arm, cy - 1, cx + arm, cy + 2, colour);
        graphics.fill(cx - 1, cy - arm, cx + 2, cy + arm, colour);
    }
}
