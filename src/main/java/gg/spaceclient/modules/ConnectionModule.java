package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * How steady the connection is, rather than how fast.
 *
 * Every client shows ping. Ping is the number that explains the least: a steady
 * 120 plays fine and a 40 that swings to 200 and back does not, and only the
 * second one is what people mean when they say the server feels bad. The figure
 * that separates them is jitter - how much the ping moves between samples - and
 * essentially no client shows it.
 *
 * A short bar chart of recent samples sits underneath, because the shape of the
 * variation says more in one glance than either number: a sawtooth is a
 * congested route, an occasional spike is something else entirely.
 */
public class ConnectionModule extends HudModule {

    private static final int SAMPLES = 40;
    private static final long SAMPLE_EVERY_MS = 500;

    private static final int BAR_HEIGHT = 12;

    private final int[] history = new int[SAMPLES];
    private int filled = 0;
    private int at = 0;
    private long lastSample = 0;

    private final BooleanSetting showGraph = new BooleanSetting(
            "show_graph", "Show graph", "Draw the recent history as bars", true);

    private final BooleanSetting showJitter = new BooleanSetting(
            "show_jitter", "Show jitter", "Include the variation figure", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFFFFF);

    public ConnectionModule() {
        super("connection", "Connection",
                "Ping with jitter and a short history",
                0.02f, 0.78f, false);
        addSettings(showGraph, showJitter, textColor);
    }

    @Override
    protected long refreshMillis() { return 250; }

    private int currentPing() {
        if (mc.player == null || mc.getConnection() == null) return -1;
        PlayerInfo entry = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return entry != null ? entry.getLatency() : -1;
    }

    /**
     * Takes a sample on its own clock rather than per frame.
     *
     * The server only updates latency every second or so; sampling faster would
     * fill the history with copies of the same value and flatten the jitter to
     * zero, which is precisely the number this element exists to show.
     */
    private void sample() {
        long now = System.currentTimeMillis();
        if (now - lastSample < SAMPLE_EVERY_MS) return;
        lastSample = now;

        int ping = currentPing();
        if (ping < 0) return;

        history[at] = ping;
        at = (at + 1) % SAMPLES;
        if (filled < SAMPLES) filled++;
    }

    /**
     * Mean absolute change between consecutive samples.
     *
     * Not the standard deviation, which a slow drift from 40 to 90 would report
     * as large variation even though every individual step was smooth. The
     * step-to-step difference answers the question actually being asked: does
     * this connection lurch?
     */
    private int jitter() {
        if (filled < 3) return -1;

        int total = 0;
        int steps = 0;
        for (int i = 1; i < filled; i++) {
            int previous = history[(at - i - 1 + SAMPLES * 2) % SAMPLES];
            int current = history[(at - i + SAMPLES * 2) % SAMPLES];
            total += Math.abs(current - previous);
            steps++;
        }
        return steps == 0 ? -1 : Math.round(total / (float) steps);
    }

    private int peak() {
        int max = 1;
        for (int i = 0; i < filled; i++) max = Math.max(max, history[i]);
        return max;
    }

    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        sample();

        int ping = currentPing();
        if (ping < 0) return "offline";

        StringBuilder out = new StringBuilder();
        out.append(ping).append(" ms");

        if (showJitter.get()) {
            int jitter = jitter();
            out.append(jitter < 0 ? "  ~--" : "  ~" + jitter);
        }
        return out.toString();
    }

    /** Green when steady, amber when it moves, red when it lurches. */
    private int qualityColor() {
        int jitter = jitter();
        if (jitter < 0) return 0xFF808080;
        if (jitter <= 8) return 0xFF6FCF6F;
        if (jitter <= 25) return 0xFFE8C46A;
        return 0xFFE86A6A;
    }

    @Override
    public int getWidth() {
        return Math.max(mc.font.width(text()), showGraph.get() ? SAMPLES * 2 : 0);
    }

    @Override
    public int getHeight() {
        return mc.font.lineHeight + (showGraph.get() ? BAR_HEIGHT + 2 : 0);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        sample();

        graphics.text(mc.font, text(), x, y, textColor.get(), true);
        if (!showGraph.get()) return;

        int top = y + mc.font.lineHeight + 2;
        int max = peak();
        int color = qualityColor();

        for (int i = 0; i < filled; i++) {
            // Oldest on the left, so the graph reads the way time does
            int value = history[(at - filled + i + SAMPLES * 2) % SAMPLES];
            int height = Math.max(1, Math.round(value * (float) BAR_HEIGHT / max));
            int barX = x + i * 2;
            graphics.fill(barX, top + BAR_HEIGHT - height, barX + 1, top + BAR_HEIGHT, color);
        }
    }
}
