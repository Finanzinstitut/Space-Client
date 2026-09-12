package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.net.Presence;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * How many people are playing with Space Client right now.
 *
 * A global figure and nothing else. It used to also count the ones in your own
 * world, which was the more interesting number and the reason it had to go: it
 * reported players you cannot see, including through walls and behind you. A
 * count is less than a position, but it is still information about people the
 * game was not showing you, and that is the line this client does not cross.
 *
 * The roster is already fetched for the name tag badge, so this costs nothing
 * that was not already being paid.
 */
public class SpacePlayersModule extends HudModule {

    private final BooleanSetting compact = new BooleanSetting(
            "compact", "Compact", "Numbers only, without the words", false);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFF9AD8FF);

    public SpacePlayersModule() {
        super("spaceplayers", "Space Players",
                "How many people are on Space Client right now",
                0.85f, 0.32f, false);
        addSettings(compact, textColor);
    }

    /**
     * Twice a second.
     *
     * The roster itself only refreshes every ninety seconds, so this is really
     * about keeping the readout responsive after a refresh, not about the
     * figure itself changing.
     */
    @Override
    protected long refreshMillis() { return 500; }

    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        int online = Presence.onlineCount();
        if (online < 0) return compact.get() ? "--" : "Space: --";
        return compact.get() ? String.valueOf(online) : online + " on Space Client";
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
