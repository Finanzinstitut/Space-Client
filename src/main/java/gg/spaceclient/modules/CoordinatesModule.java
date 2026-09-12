package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class CoordinatesModule extends HudModule {
    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFFFFF);

    public CoordinatesModule() {
        super("coordinates", "Coordinates", "Shows your current coordinates", 0.02f, 0.15f, true);
        addSettings(textColor);
    }

    @Override
    public int getWidth() { return mc.font.width(text()); }

    @Override
    public int getHeight() { return mc.font.lineHeight; }

    /** coordinates are watched while moving, so they must keep up */
    @Override
    protected long refreshMillis() { return 50; }

    /** Cached; see HudModule.cachedText for why. */
    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        if (mc.player == null) return "-- -- --";
        return String.format("%.0f, %.0f, %.0f",
                mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.text(mc.font, text(), x, y, textColor.get(), true);
    }
}
