package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Distance walked this session.
 *
 * The addition: it also converts the distance into Nether equivalents, because
 * the number people actually want when travelling is where to build the portal,
 * not how many blocks they walked.
 */
public class TravelModule extends HudModule {
    private final BooleanSetting showNether = new BooleanSetting(
            "show_nether", "Show Nether equivalent", "Also print the distance divided by eight", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFFFFF);

    private double travelled = 0;
    private double lastX, lastZ;
    private boolean started = false;

    public TravelModule() {
        super("travel", "Travelled", "Distance walked this session", 0.02f, 0.35f, false);
        addSettings(showNether, textColor);
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;

        if (!started) {
            lastX = mc.player.getX();
            lastZ = mc.player.getZ();
            started = true;
            return;
        }

        double dx = mc.player.getX() - lastX;
        double dz = mc.player.getZ() - lastZ;
        double step = Math.sqrt(dx * dx + dz * dz);

        // A teleport or dimension change would otherwise add thousands at once
        if (step < 20) travelled += step;

        lastX = mc.player.getX();
        lastZ = mc.player.getZ();
    }

    /** Cached; see HudModule.cachedText for why. */
    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        String base = String.format("%.0f m", travelled);
        if (showNether.get()) {
            base += String.format("  (%.0f in Nether)", travelled / 8);
        }
        return base;
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
