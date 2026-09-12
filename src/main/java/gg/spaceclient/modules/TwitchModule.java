package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.net.Twitch;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.ui.Pulse;
import gg.spaceclient.ui.Textures;
import gg.spaceclient.ui.Theme;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Twitch follower count and most recent follower, with the channel mark.
 *
 * Reads whatever Twitch last told the worker; it never talks to Twitch itself.
 * When the channel is not linked it says so rather than showing a zero, because
 * a zero is a number someone might believe.
 */
public class TwitchModule extends HudModule {

    private static final Identifier ICON =
            Identifier.fromNamespaceAndPath("spaceclient", "textures/gui/stream/twitch.png");

    private static final int ICON_SIZE = 8;
    private static final int ICON_GAP = 5;
    private static final int PAD = 4;

    private static final String FOLLOWERS = "followers";
    private static final String NEWEST = "newest ";

    /** Twitch purple, and a lighter tone for a name against the dark plate. */
    private static final int BRAND = 0xFF9146FF;
    private static final int BRAND_LIGHT = 0xFFC9A9FF;
    private static final int PLATE = 0xB0100C1C;

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the follower count", 0xFFFFFFFF);

    private final BooleanSetting showLast = new BooleanSetting(
            "show_last", "Show newest follower",
            "Append the most recent follower's name", true);

    private final BooleanSetting showIcon = new BooleanSetting(
            "show_icon", "Show icon", "Draw the Twitch mark in front", true);

    /**
     * Lights up when the follower count goes up.
     *
     * The one number here where the rise is the event. Somebody following you
     * mid-stream is exactly what you would want to glance down and catch.
     */
    private final Pulse gained = new Pulse();

    public TwitchModule() {
        super("twitch", "Twitch Followers",
                "Follower count and newest follower for your linked channel",
                0.02f, 0.32f, false);
        addSettings(showIcon, showLast, textColor);

        // This element draws its own plate, so the generic grey one behind it
        // would only show as a border of the wrong colour
        setBackgroundEnabled(false);
    }

    /**
     * Once a second is plenty.
     *
     * The underlying number only changes once a minute when the worker is
     * polled, so rebuilding this string any faster would be formatting the same
     * characters over and over.
     */
    @Override
    protected long refreshMillis() { return 1000; }

    private String text() { return cachedText(this::buildText); }

    /** Space reserved for the icon, or none when it's hidden. */
    private int iconWidth() {
        return showIcon.get() ? ICON_SIZE + ICON_GAP : 0;
    }

    /** The count line: the number and nothing else. */
    private String countLine() {
        if (!Twitch.isLinked()) return "not linked";
        int followers = Twitch.followers();
        if (followers < 0) return "...";
        return group(followers);
    }

    /**
     * Thousands separated with a thin gap.
     *
     * A streamer with four thousand followers should not have to count digits
     * to read their own overlay, and String.format's locale grouping would put
     * a comma there for some players and a full stop for others.
     */
    private static String group(int value) {
        String digits = Integer.toString(value);
        if (digits.length() <= 4) return digits;

        StringBuilder out = new StringBuilder();
        int lead = digits.length() % 3;
        if (lead > 0) out.append(digits, 0, lead);
        for (int at = lead; at < digits.length(); at += 3) {
            if (out.length() > 0) out.append(' ');
            out.append(digits, at, at + 3);
        }
        return out.toString();
    }

    private String buildText() {
        // Kept for the width calculation the HUD editor uses
        String last = Twitch.lastFollower();
        return countLine() + (showLast.get() && !last.isEmpty() ? "  " + last : "");
    }

    @Override
    public int getWidth() {
        int width = iconWidth() + mc.font.width(countLine()) + 4 + mc.font.width(FOLLOWERS);

        String last = Twitch.lastFollower();
        if (showLast.get() && Twitch.isLinked() && !last.isEmpty()) {
            width = Math.max(width, iconWidth() + mc.font.width(NEWEST + last));
        }
        return width + PAD * 2;
    }

    @Override
    public int getHeight() {
        return PAD * 2 + mc.font.lineHeight + (secondLine() ? mc.font.lineHeight + 1 : 0);
    }

    private boolean secondLine() {
        return showLast.get() && Twitch.isLinked() && !Twitch.lastFollower().isEmpty();
    }

    /**
     * Two lines, not one.
     *
     * The count and the newest follower were run together on a single line with
     * a plus sign between them, which made a name look like part of the number.
     * They are different facts and now read as two.
     */
    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        int width = getWidth();
        int height = getHeight();

        // Twitch purple down the left edge, the way the launcher marks a
        // section. Cheaper than a border and it says whose numbers these are
        // before any of the text is read.
        graphics.fill(x, y, x + width, y + height, PLATE);
        graphics.fill(x, y, x + 2, y + height, BRAND);

        int textX = x + PAD + iconWidth();
        int lineY = y + PAD;

        if (showIcon.get()) {
            int iconY = lineY + (mc.font.lineHeight - ICON_SIZE) / 2;
            // Through Textures rather than a direct blit: the texture call's
            // signature on 26.2 is unverified, and that class resolves it at
            // runtime, so a wrong guess costs a coloured square rather than a
            // failed build.
            if (!Textures.draw(graphics, ICON, x + PAD, iconY, ICON_SIZE, ICON_SIZE)) {
                graphics.fill(x + PAD, iconY, x + PAD + ICON_SIZE, iconY + ICON_SIZE, BRAND);
            }
        }

        String count = countLine();
        gained.watchRise(Twitch.followers());

        int color = Twitch.isLinked()
                ? gained.tint(textColor.get(), BRAND_LIGHT) : Theme.OFF;
        graphics.text(mc.font, count, textX, lineY, color, false);

        // The word sits dimmer and after the number, so the eye lands on the
        // figure first
        if (Twitch.isLinked()) {
            graphics.text(mc.font, FOLLOWERS,
                    textX + mc.font.width(count) + 4, lineY, Theme.OFF, false);
        }

        if (secondLine()) {
            int secondY = lineY + mc.font.lineHeight + 1;
            graphics.text(mc.font, NEWEST, x + PAD, secondY, Theme.OFF, false);
            graphics.text(mc.font, Twitch.lastFollower(),
                    x + PAD + mc.font.width(NEWEST), secondY, BRAND_LIGHT, false);
        }
    }
}
