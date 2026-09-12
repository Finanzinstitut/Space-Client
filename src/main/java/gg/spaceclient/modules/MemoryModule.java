package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.ui.Rolling;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Memory in use.
 *
 * The addition: a bar that turns amber then red as the heap fills, so you see a
 * memory problem building before the game starts stuttering rather than after.
 */
public class MemoryModule extends HudModule {
    private final BooleanSetting showBar = new BooleanSetting(
            "show_bar", "Show bar", "Draw a fill bar under the numbers", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFFFFF);

    public MemoryModule() {
        super("memory", "Memory", "Shows how much memory the game is using", 0.02f, 0.30f, false);
        addSettings(showBar, textColor);
    }

    private long usedMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L;
    }

    private long maxMb() {
        return Runtime.getRuntime().maxMemory() / 1_048_576L;
    }

    private float ratio() {
        long max = maxMb();
        return max <= 0 ? 0 : usedMb() / (float) max;
    }

    /**
     * Eased, not rolled - the one place where counting is the right effect.
     *
     * FPS and ping arrive as discrete readings about once a second, so their
     * digits roll once per reading. Memory does not: it drifts continuously and
     * then drops when the collector runs, and rolling every unit would be a
     * permanent blur. Easing follows the drift and reads as one moving figure.
     *
     * Only the used part moves. The maximum is fixed for the life of the
     * process, so animating it would be animating a constant.
     */
    private final Rolling shownUsed = new Rolling();

    private String text() {
        return shownUsed.update(usedMb()) + " / " + maxMb() + " MB";
    }

    @Override
    public int getWidth() { return Math.max(90, mc.font.width(text())); }

    @Override
    public int getHeight() { return mc.font.lineHeight + (showBar.get() ? 6 : 0); }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        float ratio = ratio();
        int color = ratio > 0.9f ? 0xFFFF6B81 : ratio > 0.75f ? 0xFFFFD9A0 : textColor.get();
        graphics.text(mc.font, text(), x, y, color, true);

        if (!showBar.get()) return;
        int barY = y + mc.font.lineHeight + 2;
        int width = getWidth();
        graphics.fill(x, barY, x + width, barY + 3, 0x60000000);
        graphics.fill(x, barY, x + (int) (width * ratio), barY + 3, color);
    }
}
