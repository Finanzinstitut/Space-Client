package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.util.Reflect;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The in-game day and clock, and how long until the light changes.
 *
 * The Clock element shows the real time, which is the question "how long have I
 * been playing". This is the other one: how long until it is dark, which
 * decides whether to start the trip home or keep mining. A vanilla clock item
 * shows the sun's position and leaves the arithmetic to you.
 *
 * The world time is read reflectively. `getDayTime` has been on Level for many
 * versions and almost certainly still is, but nothing else in this mod calls
 * it, so nothing proves it - and the cost of being wrong through reflection is
 * a dash on screen rather than a build that does not compile.
 */
public class DayTimeModule extends HudModule {

    /** Minecraft's day is this many ticks, and the sun sets partway through. */
    private static final long DAY_LENGTH = 24000L;
    private static final long DUSK = 12000L;
    private static final long DAWN = 23000L;

    private final BooleanSetting showDay = new BooleanSetting(
            "show_day", "Show day", "Include the day number", true);

    private final BooleanSetting showCountdown = new BooleanSetting(
            "show_countdown", "Show countdown",
            "Time until sunset or sunrise, in real minutes", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFE9A8);

    public DayTimeModule() {
        super("daytime", "Day & Time",
                "In-game day, clock, and time until dark",
                0.02f, 0.70f, false);
        addSettings(showDay, showCountdown, textColor);
    }

    /** The displayed minute changes about every four seconds. */
    @Override
    protected long refreshMillis() { return 1000; }

    private long worldTime() {
        try {
            if (mc.level == null) return -1;
            Object value = Reflect.call(mc.level, "getDayTime");
            return value instanceof Long time ? time : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        long total = worldTime();
        if (total < 0) return "--";

        long inDay = Math.floorMod(total, DAY_LENGTH);
        long day = total / DAY_LENGTH;

        // Minecraft's day starts at 6am, not midnight
        long minutesOfDay = (inDay * 24 * 60 / DAY_LENGTH + 6 * 60) % (24 * 60);
        String clock = String.format("%02d:%02d", minutesOfDay / 60, minutesOfDay % 60);

        StringBuilder out = new StringBuilder();
        if (showDay.get()) out.append("Day ").append(day).append("  ");
        out.append(clock);

        if (showCountdown.get()) {
            boolean night = inDay >= DUSK && inDay < DAWN;
            long ticksLeft = night ? DAWN - inDay : (inDay < DUSK ? DUSK - inDay
                    : DAY_LENGTH - inDay + DUSK);

            // Twenty ticks a second, so a real-world minute is 1200
            long seconds = ticksLeft / 20;
            out.append(night ? "  sunrise in " : "  dark in ");
            if (seconds >= 60) out.append(seconds / 60).append('m');
            else out.append(seconds).append('s');
        }
        return out.toString();
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
