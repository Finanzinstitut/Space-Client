package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * How fast the server is actually running, in ticks per second.
 *
 * The client has no direct reading of this - no packet carries it. What it does
 * have is the world clock, which the server corrects roughly once a second. The
 * client advances that clock locally at a flat twenty in between, so a short
 * sample says twenty no matter what; over several seconds the corrections
 * dominate and the average settles on the server's real rate.
 *
 * That makes this an estimate rather than a measurement, and it is worth being
 * clear about the difference. It is accurate for the thing people use it for -
 * spotting a struggling server - but it will not resolve 19.8 from 20.0, and it
 * leans on the server sending time updates, which a few rewrite or suppress.
 */
public class TpsModule extends HudModule {

    /** Long enough for the clock corrections to outweigh local ticking. */
    private static final long WINDOW_MS = 4_000L;

    private final BooleanSetting colorCoded = new BooleanSetting(
            "color_coded", "Colour by health",
            "Green when healthy, amber when slipping, red when struggling", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Used when colour by health is off", 0xFFFFFFFF);

    private long windowStartMs = 0L;
    private long windowStartTicks = 0L;
    private double tps = -1;

    public TpsModule() {
        super("tps", "TPS", "Estimates how fast the server is ticking", 0.02f, 0.26f, false);
        addSettings(colorCoded, textColor);
    }

    @Override
    public int getWidth() { return mc.font.width(text()); }

    @Override
    public int getHeight() { return mc.font.lineHeight; }

    /**
     * Called every client tick to keep the sampling window rolling.
     *
     * Sampling in render() instead would tie the reading to frame rate, and a
     * player whose frames are struggling is exactly the one who wants to know
     * whether the server is too.
     */
    @Override
    public void onTick() {
        if (mc.level == null) {
            // Between worlds the old window means nothing
            windowStartMs = 0L;
            tps = -1;
            return;
        }

        long now = System.currentTimeMillis();
        long ticks = mc.level.getGameTime();

        if (windowStartMs == 0L) {
            windowStartMs = now;
            windowStartTicks = ticks;
            return;
        }

        long elapsed = now - windowStartMs;
        if (elapsed < WINDOW_MS) return;

        long ticked = ticks - windowStartTicks;

        // A backwards clock means the server moved time itself, not that it ran
        // in reverse; the sample is spoiled rather than meaningful.
        if (ticked < 0) {
            windowStartMs = now;
            windowStartTicks = ticks;
            return;
        }

        double sampled = ticked * 1000.0 / elapsed;

        // Smoothed, because a readout that flickers between 19 and 21 every
        // few seconds is harder to read than one that drifts
        tps = tps < 0 ? sampled : tps * 0.6 + sampled * 0.4;

        windowStartMs = now;
        windowStartTicks = ticks;
    }

    /** Cached; see HudModule.cachedText for why. */
    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        if (mc.level == null) return "-- tps";
        if (tps < 0) return "... tps";
        return String.format("%.1f tps", Math.min(tps, 20.0));
    }

    private int color() {
        if (!colorCoded.get() || tps < 0) return textColor.get();
        if (tps >= 19.0) return 0xFF55FF55;
        if (tps >= 15.0) return 0xFFFFAA00;
        return 0xFFFF5555;
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.text(mc.font, text(), x, y, color(), true);
    }
}
