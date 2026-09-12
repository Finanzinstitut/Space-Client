package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shows how far your aim is off the nearest axis.
 *
 * Building straight lines means facing exactly north, south, east or west, and
 * the vanilla F3 screen buries that in a wall of text. This shows only the
 * deviation, and turns green the moment you are aligned - the one number that
 * matters, at a glance.
 */
public class YawLockModule extends HudModule {
    private final BooleanSetting includeDiagonals = new BooleanSetting(
            "diagonals", "Include diagonals", "Also treat 45 degree headings as aligned", false);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour when not aligned", 0xFFFFFFFF);

    private float deviation() {
        if (mc.player == null) return 0;
        float yaw = mc.player.getYRot() % 360;
        if (yaw < 0) yaw += 360;

        float step = includeDiagonals.get() ? 45f : 90f;
        float nearest = Math.round(yaw / step) * step;
        float difference = yaw - nearest;
        if (difference > 180) difference -= 360;
        if (difference < -180) difference += 360;
        return difference;
    }

    public YawLockModule() {
        super("yawlock", "Align", "How far your aim is off the nearest axis", 0.02f, 0.50f, false);
        addSettings(includeDiagonals, textColor);
    }

    /** Cached; see HudModule.cachedText for why. */
    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        float off = deviation();
        return Math.abs(off) < 0.05f ? "aligned" : String.format("%+.1f°", off);
    }

    @Override
    public int getWidth() { return Math.max(50, mc.font.width(text())); }

    @Override
    public int getHeight() { return mc.font.lineHeight; }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        float off = Math.abs(deviation());
        int color = off < 0.05f ? 0xFF4ADE80 : off < 2f ? 0xFFFFD9A0 : textColor.get();
        graphics.text(mc.font, text(), x, y, color, true);
    }
}
