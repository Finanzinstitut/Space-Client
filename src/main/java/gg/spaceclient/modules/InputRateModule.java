package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.setting.IntSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A rolling graph of your click rate.
 *
 * A CPS number tells you the last second; this shows the shape over time, which
 * is what reveals whether your clicking is steady or comes in bursts - the
 * difference between a consistent player and a lucky one.
 */
public class InputRateModule extends HudModule {
    private static final int HISTORY = 60;

    private final IntSetting height = new IntSetting(
            "height", "Graph height", "How tall the graph is drawn", 24, 10, 60);

    private final BooleanSetting showPeak = new BooleanSetting(
            "show_peak", "Show peak", "Print the highest rate reached", true);

    private final ColorSetting lineColor = new ColorSetting(
            "line_color", "Graph colour", "Colour of the bars", 0xFF38E0FF);

    private final Deque<Integer> samples = new ArrayDeque<>();
    private int peak = 0;
    private long lastSample = 0;

    public InputRateModule() {
        super("inputrate", "Click Graph", "Rolling graph of your click rate", 0.02f, 0.62f, false);
        addSettings(height, showPeak, lineColor);
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        // Four samples a second keeps the graph readable without being jumpy
        if (now - lastSample < 250) return;
        lastSample = now;

        CpsModule cps = CpsModule.getInstance();
        int value = cps == null ? 0 : cps.getLeftCps();
        peak = Math.max(peak, value);

        samples.addLast(value);
        while (samples.size() > HISTORY) samples.pollFirst();
    }

    @Override
    public int getWidth() { return HISTORY * 2; }

    @Override
    public int getHeight() { return height.get() + (showPeak.get() ? mc.font.lineHeight + 2 : 0); }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        int graphHeight = height.get();
        int top = y + (showPeak.get() ? mc.font.lineHeight + 2 : 0);

        // A scale that grows with the peak, so the graph always fills the box
        int scale = Math.max(8, peak);

        int index = 0;
        for (int value : samples) {
            int barHeight = Math.max(1, value * graphHeight / scale);
            int barX = x + index * 2;
            graphics.fill(barX, top + graphHeight - barHeight, barX + 2, top + graphHeight,
                    lineColor.get());
            index++;
        }

        if (showPeak.get()) {
            graphics.text(mc.font, "peak " + peak, x, y, 0xFF9A95C9, true);
        }
    }
}
