package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.util.Reflect;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/**
 * Where you are standing in the other dimension.
 *
 * Linking portals is arithmetic done badly by hand: divide by eight in the
 * overworld, multiply by eight in the nether, and get one of them backwards at
 * some point. Almost every client shows coordinates and none of them show this,
 * which is odd, because the conversion is the only time the number is hard.
 *
 * The dimension is read reflectively and matched on its name. `dimension()`
 * returns a registry key whose text ends in `the_nether`, and matching the text
 * survives the method being renamed in a way a cast would not - and the cost of
 * being wrong is a label that says overworld while you stand in the nether,
 * not a crash.
 */
public class NetherPortalModule extends HudModule {

    private final BooleanSetting showLabel = new BooleanSetting(
            "show_label", "Show label", "Name the dimension being converted to", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFB27F);

    public NetherPortalModule() {
        super("portal", "Portal Coords",
                "Your position converted to the linked dimension",
                0.02f, 0.74f, false);
        addSettings(showLabel, textColor);
    }

    /** Position changes constantly, but nobody reads it faster than this. */
    @Override
    protected long refreshMillis() { return 150; }

    /**
     * Whether the player is in the nether.
     *
     * The end has no portal linking of this kind, so it is treated as the
     * overworld: the numbers shown there are what an overworld position would
     * convert to, which is meaningless but harmless, and the alternative was a
     * third case that says nothing.
     */
    private boolean inNether() {
        try {
            if (mc.level == null) return false;
            Object key = Reflect.call(mc.level, "dimension");
            return key != null
                    && key.toString().toLowerCase(Locale.ROOT).contains("the_nether");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        if (mc.player == null) return "--";

        boolean nether = inNether();
        double x = mc.player.getX();
        double z = mc.player.getZ();

        // Eight overworld blocks to one nether block. Y is not converted
        // because portals do not link on it - the game searches vertically for
        // somewhere to put you.
        long outX = Math.round(nether ? x * 8 : x / 8);
        long outZ = Math.round(nether ? z * 8 : z / 8);

        String coords = outX + ", " + outZ;
        if (!showLabel.get()) return coords;
        return (nether ? "Overworld " : "Nether ") + coords;
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
