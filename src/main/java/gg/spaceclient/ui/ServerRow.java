package gg.spaceclient.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.BooleanSupplier;

/**
 * One saved server, as a wide row.
 *
 * Deliberately not the card used for worlds. A world is remembered by its
 * picture, so it gets one; a server is remembered by its name and judged by
 * its ping and how full it is, none of which is a picture. Rows put those side
 * by side and let twelve of them fit on screen, which is the number of servers
 * people actually keep.
 */
public class ServerRow extends Button {

    /** What the row shows, gathered once per refresh rather than per frame. */
    public record Data(String name, String address, String motd,
                       long ping, int online, int max, Identifier icon) {}

    private final java.util.function.Supplier<Data> data;
    private final BooleanSupplier selected;

    private float hover = 0f;
    private float pick = 0f;

    /** Entrance, driven by the screen so the list assembles top to bottom. */
    private float appear = 0f;

    public ServerRow(int x, int y, int width, int height,
                     java.util.function.Supplier<Data> data,
                     BooleanSupplier selected,
                     Runnable onPress) {
        super(x, y, width, height, Component.empty(),
                btn -> { if (((ServerRow) btn).appear > 0.5f) onPress.run(); },
                DEFAULT_NARRATION);
        this.data = data;
        this.selected = selected;
    }

    public void setAppear(float value) {
        this.appear = Ease.clamp01(value);
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
        if (appear <= 0.01f) return;

        float step = Math.max(delta, 0.1f);
        hover = Ease.approach(hover, isHovered() ? 1f : 0f, 0.25f, step);
        pick = Ease.approach(pick, selected.getAsBoolean() ? 1f : 0f, 0.3f, step);

        Data row = data.get();
        if (row == null) return;

        int alpha = Math.round(255 * Ease.outCubic(appear));

        // Slides in from the left rather than fading in place, so a long list
        // reads as arriving in order instead of resolving all at once
        int slide = Math.round((1f - Ease.outCubic(appear)) * 24f);
        int x1 = getX() - slide;
        int y1 = getY();
        int x2 = x1 + this.width;
        int y2 = y1 + this.height;

        int plate = Ease.color(0x40100D2A, 0x88221C58, Math.max(hover, pick * 0.8f));
        Glass.panel(graphics, x1, y1, this.width, this.height,
                MenuIcon.scaleAlpha(plate, alpha), 6);

        // A bar on the left for the selected row: one mark, unmissable, and it
        // does not compete with the ping colour on the right
        if (pick > 0.01f) {
            graphics.fill(x1, y1 + 4, x1 + 3, y2 - 4,
                    MenuIcon.scaleAlpha(Theme.CYAN, Math.round(alpha * pick)));
        }

        var font = Minecraft.getInstance().font;
        int iconSize = this.height - 12;
        int iconX = x1 + 8;
        int iconY = y1 + 6;

        boolean drew = row.icon() != null
                && Textures.draw(graphics, row.icon(), iconX, iconY, iconSize, iconSize);
        if (!drew) {
            graphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize,
                    MenuIcon.scaleAlpha(0xFF1A1640, alpha));
        }

        int textX = iconX + iconSize + 10;
        int rightEdge = x2 - 76;
        int room = Math.max(20, rightEdge - textX);

        graphics.text(font, clip(font, row.name(), room), textX, y1 + 7,
                MenuIcon.scaleAlpha(Ease.color(0xFFE9E6FF, 0xFFFFFFFF, hover), alpha), false);

        graphics.text(font, clip(font, row.address(), room), textX, y1 + 7 + font.lineHeight + 2,
                MenuIcon.scaleAlpha(0xFF9A95C9, alpha), false);

        if (!row.motd().isEmpty() && this.height > 46) {
            graphics.text(font, clip(font, row.motd(), room),
                    textX, y1 + 7 + (font.lineHeight + 2) * 2,
                    MenuIcon.scaleAlpha(0xFF7E79AC, alpha), false);
        }

        drawPing(graphics, x2 - 62, y1 + 10, row, alpha);

        if (row.max() > 0) {
            String count = row.online() + "/" + row.max();
            graphics.text(font, count, x2 - 12 - font.width(count), y1 + 10 + 14,
                    MenuIcon.scaleAlpha(0xFF9A95C9, alpha), false);
        }
    }

    /**
     * Five bars, the way everyone already reads a signal.
     *
     * The number in milliseconds is the honest value but not the useful one -
     * nobody knows whether 80 is good without something to compare it to, and
     * the bars carry that comparison in their shape.
     */
    private void drawPing(GuiGraphicsExtractor graphics, int x, int y, Data row, int alpha) {
        long ping = row.ping();
        int lit = ping < 0 ? 0
                : ping < 60 ? 5
                : ping < 120 ? 4
                : ping < 200 ? 3
                : ping < 350 ? 2
                : 1;

        int good = 0xFF6ADF8F;
        int fair = 0xFFE8C46A;
        int poor = 0xFFE86A6A;
        int colour = lit >= 4 ? good : lit >= 2 ? fair : poor;

        for (int i = 0; i < 5; i++) {
            int barHeight = 3 + i * 2;
            int barX = x + i * 6;
            int barY = y + 11 - barHeight;
            int shade = i < lit
                    ? MenuIcon.scaleAlpha(colour, alpha)
                    : MenuIcon.scaleAlpha(0xFF332E55, alpha);
            graphics.fill(barX, barY, barX + 4, y + 11, shade);
        }
    }

    private static String clip(Object fontObject, String text, int room) {
        var font = Minecraft.getInstance().font;
        if (font.width(text) <= room) return text;
        String out = text;
        while (out.length() > 1 && font.width(out + "..") > room) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "..";
    }
}
