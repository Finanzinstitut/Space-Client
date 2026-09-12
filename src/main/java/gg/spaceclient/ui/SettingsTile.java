package gg.spaceclient.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * One settings destination, as a tile with a drawn mark and a line of what it
 * is for.
 *
 * The subtitle is the point. "Video" and "Controls" are learned labels rather
 * than descriptive ones, and everyone has opened three of them looking for one
 * setting. A line saying what lives inside turns a guess into a read.
 */
public class SettingsTile extends Button {

    public enum Mark { SCREEN, SOUND, KEYS, LANGUAGE, SKIN, PACKS, CLIENT, HUD, INFO }

    private final String title;
    private final String subtitle;
    private final Mark mark;

    private float hover = 0f;
    private float appear = 0f;

    public SettingsTile(int x, int y, int width, int height,
                        String title, String subtitle, Mark mark, Runnable onPress) {
        super(x, y, width, height, Component.empty(),
                btn -> { if (((SettingsTile) btn).appear > 0.5f) onPress.run(); },
                DEFAULT_NARRATION);
        this.title = title;
        this.subtitle = subtitle;
        this.mark = mark;
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

        hover = Ease.approach(hover, isHovered() ? 1f : 0f, 0.25f, Math.max(delta, 0.1f));

        float in = Ease.outCubic(appear);
        int alpha = Math.round(255 * in);

        // Grows into place from slightly small, which reads as a tile landing
        // rather than a rectangle switching on
        int shrink = Math.round((1f - in) * 6f);
        int x1 = getX() + shrink;
        int y1 = getY() + shrink;
        int w = this.width - shrink * 2;
        int h = this.height - shrink * 2;

        Glass.panel(graphics, x1, y1, w, h,
                MenuIcon.scaleAlpha(Ease.color(0x50100D2A, 0x90221C58, hover), alpha), 8);

        int ink = MenuIcon.scaleAlpha(Ease.color(0xFF8B84C8, Theme.CYAN, hover), alpha);
        drawMark(graphics, x1 + 26, y1 + h / 2, ink);

        var font = Minecraft.getInstance().font;
        int textX = x1 + 50;

        graphics.text(font, title, textX, y1 + h / 2 - font.lineHeight - 1,
                MenuIcon.scaleAlpha(Ease.color(0xFFE9E6FF, 0xFFFFFFFF, hover), alpha), false);

        graphics.text(font, subtitle, textX, y1 + h / 2 + 2,
                MenuIcon.scaleAlpha(0xFF9A95C9, alpha), false);

        if (hover > 0.01f) {
            int edge = MenuIcon.scaleAlpha(Theme.CYAN, Math.round(alpha * hover));
            graphics.fill(x1 + 6, y1 + h - 1, x1 + w - 6, y1 + h, edge);
        }
    }

    private void drawMark(GuiGraphicsExtractor graphics, int cx, int cy, int colour) {
        switch (mark) {
            case SCREEN -> {
                outline(graphics, cx - 9, cy - 7, cx + 9, cy + 5, colour);
                graphics.fill(cx - 4, cy + 6, cx + 4, cy + 8, colour);
            }
            case SOUND -> {
                graphics.fill(cx - 8, cy - 3, cx - 4, cy + 3, colour);
                for (int i = 0; i < 5; i++) {
                    graphics.fill(cx - 4 + i, cy - 3 - i, cx - 3 + i, cy + 4 + i, colour);
                }
                MenuIcon.ringOutline(graphics, cx + 3, cy, 6, colour);
            }
            case KEYS -> {
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 3; col++) {
                        graphics.fill(cx - 9 + col * 7, cy - 6 + row * 7,
                                cx - 4 + col * 7, cy - 1 + row * 7, colour);
                    }
                }
            }
            case LANGUAGE -> {
                MenuIcon.ringOutline(graphics, cx, cy, 8, colour);
                graphics.fill(cx - 7, cy, cx + 7, cy + 1, colour);
                graphics.fill(cx, cy - 7, cx + 1, cy + 7, colour);
            }
            case SKIN -> {
                MenuIcon.circle(graphics, cx, cy - 4, 4, colour);
                for (int row = 0; row < 4; row++) {
                    graphics.fill(cx - 3 - row, cy + 2 + row, cx + 4 + row, cy + 3 + row, colour);
                }
            }
            case PACKS -> {
                for (int i = 0; i < 3; i++) {
                    outline(graphics, cx - 8 + i * 2, cy - 8 + i * 5,
                            cx + 8 - i * 2, cy - 2 + i * 5, colour);
                }
            }
            case CLIENT -> JupiterIcon.draw(graphics, cx - 9, cy - 9, 18);
            case HUD -> {
                outline(graphics, cx - 9, cy - 7, cx + 9, cy + 7, colour);
                graphics.fill(cx - 6, cy - 4, cx - 1, cy - 2, colour);
                graphics.fill(cx - 6, cy, cx + 3, cy + 2, colour);
            }
            case INFO -> {
                MenuIcon.ringOutline(graphics, cx, cy, 8, colour);
                graphics.fill(cx - 1, cy - 5, cx + 1, cy - 3, colour);
                graphics.fill(cx - 1, cy - 1, cx + 1, cy + 5, colour);
            }
        }
    }

    private static void outline(GuiGraphicsExtractor graphics,
                                int x1, int y1, int x2, int y2, int colour) {
        graphics.fill(x1, y1, x2, y1 + 1, colour);
        graphics.fill(x1, y2 - 1, x2, y2, colour);
        graphics.fill(x1, y1, x1 + 1, y2, colour);
        graphics.fill(x2 - 1, y1, x2, y2, colour);
    }
}
