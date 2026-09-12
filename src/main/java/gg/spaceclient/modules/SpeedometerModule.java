package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Movement speed in blocks per second.
 *
 * The addition: speed is also shown as a percentage of the theoretical maximum
 * for your current state, so you can see whether your movement is efficient
 * rather than just reading a raw number.
 */
public class SpeedometerModule extends HudModule {
    private final BooleanSetting showEfficiency = new BooleanSetting(
            "show_efficiency", "Show efficiency", "Compare against the theoretical maximum", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFFFFF);

    private double lastX, lastZ;
    private double speed = 0;

    public SpeedometerModule() {
        super("speedometer", "Speedometer", "Displays your speed in m/s", 0.02f, 0.25f, false);
        addSettings(showEfficiency, textColor);
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;

        double dx = mc.player.getX() - lastX;
        double dz = mc.player.getZ() - lastZ;
        double distance = Math.sqrt(dx * dx + dz * dz);

        // 20 ticks per second, smoothed so the readout does not flicker
        speed = speed * 0.6 + (distance * 20) * 0.4;

        lastX = mc.player.getX();
        lastZ = mc.player.getZ();
    }

    private double theoreticalMax() {
        if (mc.player == null) return 5.6;
        if (mc.player.isSprinting()) return 5.6;
        return 4.3;
    }

    @Override
    public int getWidth() { return mc.font.width(text()); }

    @Override
    public int getHeight() { return mc.font.lineHeight; }

    /** speed changes every step, and a lagging number reads as a bug */
    @Override
    protected long refreshMillis() { return 50; }

    /** Cached; see HudModule.cachedText for why. */
    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        String text = String.format("%.1f m/s", speed);
        if (showEfficiency.get()) {
            double pct = Math.min(999, (speed / theoreticalMax()) * 100);
            text += String.format("  %.0f%%", pct);
        }
        return text;
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.text(mc.font, text(), x, y, textColor.get(), true);
    }
}
