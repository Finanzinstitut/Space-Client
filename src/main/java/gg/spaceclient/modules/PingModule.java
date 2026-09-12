package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.ui.Odometer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;

public class PingModule extends HudModule {
    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFFFFF);

    public PingModule() {
        super("ping", "Ping", "Shows your ping to the server", 0.02f, 0.20f, false);
        addSettings(textColor);
    }

    @Override
    public int getWidth() { return mc.font.width(text()); }

    @Override
    public int getHeight() { return mc.font.lineHeight; }

    /**
     * Not cached any more, and not for style.
     *
     * cachedText rebuilds on a timer, which would make the roll start at
     * whatever moment the timer happened to fire rather than when the reading
     * changed. The server updates latency about once a second, so each new
     * reading gets exactly one roll.
     */
    private final Odometer shown = new Odometer();

    private int latency() {
        if (mc.player == null || mc.getConnection() == null) return -1;
        PlayerInfo entry = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return entry != null ? entry.getLatency() : -1;
    }

    private String text() {
        int ping = latency();
        return ping < 0 ? "-- ms" : ping + " ms";
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        shown.set(text());
        shown.draw(graphics, mc.font, x, y, textColor.get(), true);
    }
}
