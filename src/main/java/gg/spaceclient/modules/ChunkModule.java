package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Which chunk you are standing in, and where inside it.
 *
 * The addition: the position within the chunk is shown as well, which is what
 * you need when aligning a build to chunk borders or placing a portal.
 */
public class ChunkModule extends HudModule {
    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFFFFF);

    public ChunkModule() {
        super("chunk", "Chunk", "Chunk coordinates and position inside it", 0.02f, 0.40f, false);
        addSettings(textColor);
    }

    /** Cached; see HudModule.cachedText for why. */
    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        if (mc.player == null) return "-- --";
        int x = (int) Math.floor(mc.player.getX());
        int z = (int) Math.floor(mc.player.getZ());
        return String.format("Chunk %d, %d   in %d, %d",
                Math.floorDiv(x, 16), Math.floorDiv(z, 16),
                Math.floorMod(x, 16), Math.floorMod(z, 16));
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
