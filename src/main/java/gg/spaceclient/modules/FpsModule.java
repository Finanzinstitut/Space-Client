package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.ui.Odometer;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class FpsModule extends HudModule {

    /**
     * The digits roll rather than the value counting.
     *
     * Easing the number was the first attempt and it was the wrong effect: it
     * invented readings that were never measured and made a steady frame rate
     * look unstable. Minecraft updates this figure about once a second, so the
     * roll happens once per reading - which is exactly what an odometer does.
     */
    private final Odometer shown = new Odometer();

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the counter", 0xFFFFFFFF);

    public FpsModule() {
        super("fps", "FPS", "Shows your current frames per second", 0.02f, 0.05f, true);
        addSettings(textColor);
    }

    @Override
    public int getWidth() { return mc.font.width(mc.getFps() + " FPS"); }

    @Override
    public int getHeight() { return mc.font.lineHeight; }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        shown.set(mc.getFps() + " FPS");
        shown.draw(graphics, mc.font, x, y, textColor.get(), true);
    }
}
