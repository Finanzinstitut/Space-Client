package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.ui.Odometer;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Session uptime with an optional break reminder after a few hours.
 * Playtime trackers are common; one that actually nudges you is not.
 */
public class SessionModule extends HudModule {
    private static final long REMINDER_AFTER_MS = 2L * 60 * 60 * 1000;

    private final BooleanSetting breakReminder = new BooleanSetting(
            "break_reminder", "Break reminder", "Show a reminder after two hours", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFFFFF);

    private final long startedAt = System.currentTimeMillis();

    public SessionModule() {
        super("session", "Session", "Uptime and an optional break reminder", 0.90f, 0.10f, false);
        addSettings(breakReminder, textColor);
    }

    @Override
    public int getWidth() { return 70; }

    @Override
    public int getHeight() { return mc.font.lineHeight * 2 + 2; }

    /**
     * The clock this was always meant to be.
     *
     * A session timer changes exactly once a second, in the last digit, which
     * is the cleanest case there is for a roll - the seconds flip like a
     * departure board and the minutes only move when they should.
     */
    private final Odometer clock = new Odometer();

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        long elapsedMs = System.currentTimeMillis() - startedAt;
        long elapsed = elapsedMs / 1000;
        String text = String.format("%d:%02d:%02d",
                elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60);

        clock.set(text);
        clock.draw(graphics, mc.font, x, y, textColor.get(), true);

        if (breakReminder.get() && elapsedMs > REMINDER_AFTER_MS) {
            long hours = elapsedMs / (60 * 60 * 1000);
            graphics.text(mc.font, hours + "h - time for a break?",
                    x, y + mc.font.lineHeight + 2, 0xFF38E0FF, true);
        }
    }
}
