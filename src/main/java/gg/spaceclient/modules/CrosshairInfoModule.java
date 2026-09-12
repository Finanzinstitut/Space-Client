package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Where you are aiming, in degrees.
 *
 * The addition: yaw is shown snapped to the nearest 45 degrees alongside the
 * exact value, because lining up on a diagonal is the case where the raw number
 * alone is hard to use.
 */
public class CrosshairInfoModule extends HudModule {
    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFFFFF);

    public CrosshairInfoModule() {
        super("aim", "Aim", "Your exact yaw and pitch", 0.02f, 0.45f, false);
        addSettings(textColor);
    }

    /** Cached; see HudModule.cachedText for why. */
    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        if (mc.player == null) return "--";
        float yaw = mc.player.getYRot() % 360;
        if (yaw < 0) yaw += 360;
        float pitch = mc.player.getXRot();

        float snapped = Math.round(yaw / 45f) * 45f % 360;
        return String.format("%.1f / %.1f   (snap %.0f)", yaw, pitch, snapped);
    }

    @Override
    public int getWidth() { return mc.font.width(text()); }

    @Override
    public int getHeight() { return mc.font.lineHeight; }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.text(mc.font, text(), x, y, textColor.get(), true);
    }
}
