package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.util.Reflect;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Who else is online.
 *
 * The addition: it flags when the player count changes, so you notice someone
 * joining or leaving without watching the tab list - useful on a small server.
 */
public class ServerInfoModule extends HudModule {
    private final BooleanSetting flagChanges = new BooleanSetting(
            "flag_changes", "Flag changes", "Highlight briefly when the count changes", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFFFFF);

    private int lastCount = -1;
    private long changedAt = 0;

    public ServerInfoModule() {
        super("serverinfo", "Players Online", "How many players are on the server", 0.85f, 0.25f, false);
        addSettings(flagChanges, textColor);
    }

    /**
     * The accessor for the player list has not been confirmed for this version,
     * so several likely names are tried and the first collection wins.
     */
    private int count() {
        Object connection = mc.getConnection();
        if (connection == null) return 0;

        Object players = Reflect.call(connection,
                "getOnlinePlayers", "getPlayerInfoMap", "getListedOnlinePlayers");
        if (players instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        if (players instanceof java.util.Map<?, ?> map) {
            return map.size();
        }
        return 0;
    }

    @Override
    public void onTick() {
        int now = count();
        if (lastCount >= 0 && now != lastCount) {
            changedAt = System.currentTimeMillis();
        }
        lastCount = now;
    }

    /** Cached; see HudModule.cachedText for why. */
    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        return count() + " online";
    }

    @Override
    public int getWidth() { return Math.max(60, mc.font.width(text())); }

    @Override
    public int getHeight() { return mc.font.lineHeight; }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        int color = textColor.get();
        if (flagChanges.get() && System.currentTimeMillis() - changedAt < 3000) {
            color = 0xFF38E0FF;
        }
        graphics.text(mc.font, text(), x, y, color, true);
    }
}
