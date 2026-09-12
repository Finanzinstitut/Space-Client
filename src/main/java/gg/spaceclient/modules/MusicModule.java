package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.music.MusicWatcher;
import gg.spaceclient.music.NowPlaying;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.setting.ModeSetting;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Arrays;

/**
 * Shows what the local Spotify or Amazon Music app is playing.
 *
 * Only those two are read, on purpose: the track comes from the desktop app's
 * window title, so music playing in a browser has no process to read and is
 * ignored.
 *
 * Playback controls appear when the chat is open - see MusicControls, which
 * puts real buttons over this element while a screen has the mouse.
 */
public class MusicModule extends HudModule {
    private static final int WIDTH = 170;
    private static final int ART = 26;

    private final ModeSetting source = new ModeSetting(
            "source", "Source", "Which player to read",
            Arrays.asList("BOTH", "SPOTIFY", "AMAZON"), "BOTH");

    private final BooleanSetting showSource = new BooleanSetting(
            "show_source", "Show player name", "Print which app the track came from", true);

    private final BooleanSetting hideWhenIdle = new BooleanSetting(
            "hide_when_idle", "Hide when nothing plays", "Draw nothing while paused", true);

    private final ColorSetting titleColor = new ColorSetting(
            "title_color", "Title colour", "Colour of the track name", 0xFFFFFFFF);

    private final ColorSetting artistColor = new ColorSetting(
            "artist_color", "Artist colour", "Colour of the artist line", 0xFF9A95C9);

    /**
     * Off by default, and that is the whole point of it being a setting: this
     * publishes what you are listening to, continuously, to anyone running the
     * client who can see you. That is a thing to opt into, not out of.
     */
    private final BooleanSetting overName = new BooleanSetting(
            "over_name", "Over name",
            "Show your track above your name tag to other Space Client players", false);

    public MusicModule() {
        super("music", "Now Playing", "Shows the track from Spotify or Amazon Music",
                0.02f, 0.80f, false);
        addSettings(source, showSource, hideWhenIdle, overName, showOnSelf,
                lyrics, titleColor, artistColor);
    }

    /**
     * Normally your own song is not drawn over your own head - it is already in
     * the HUD element, so it would just be there twice. This puts it there
     * anyway, which is the only way to check the whole chain without a second
     * account: the line you see has been to the worker and back.
     */
    private final BooleanSetting showOnSelf = new BooleanSetting(
            "over_name_self", "Over my own name",
            "Also draw your own track over your head, for testing", false);

    /** Whether your own track is drawn over your own head too. */
    public boolean showsOnSelf() {
        return showOnSelf.get();
    }

    /**
     * Off by default, and the description carries the warning rather than
     * burying it: the lyrics come from a community database, not from a
     * licensed source, and switching this on sends a line of someone else's
     * copyrighted words through the Space Client backend.
     */
    private final BooleanSetting lyrics = new BooleanSetting(
            "lyrics", "Lyrics",
            "Show the current line under the track. Lyrics come from LRCLIB, "
                    + "a community database - they are not licensed, and enabling "
                    + "this shares one line with other players. Spotify only.", false);

    /** Whether the current lyric line should be shown and shared. */
    public boolean showsLyrics() {
        return lyrics.get();
    }

    /** Whether the track is being published over the name tag. */
    public boolean sharesOverName() {
        return overName.get();
    }

    /** The track, after the source filter is applied. */
    public NowPlaying track() {
        NowPlaying playing = MusicWatcher.current();
        if (playing.isEmpty()) return playing;

        if (source.is("SPOTIFY") && !playing.source().equals("Spotify")) return NowPlaying.NOTHING;
        if (source.is("AMAZON") && !playing.source().equals("Amazon Music")) return NowPlaying.NOTHING;
        return playing;
    }

    public boolean shouldDraw() {
        return !(track().isEmpty() && hideWhenIdle.get());
    }

    @Override
    public void onTick() {
        MusicWatcher.tick();
    }

    @Override
    public int getWidth() { return WIDTH; }

    @Override
    public int getHeight() { return ART + 8; }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        if (!shouldDraw()) return;

        NowPlaying playing = track();
        boolean idle = playing.isEmpty();

        // Cover art needs a web API; a drawn record stands in for it and keeps
        // the layout the same whether or not something is playing.
        drawRecord(graphics, x + 4, y + 4, ART);

        int textX = x + ART + 12;
        String title = idle ? "Nothing playing" : playing.title();
        String subtitle = idle
                ? MusicWatcher.status()
                : playing.artist().isEmpty() ? playing.source() : playing.artist();

        title = trim(title, WIDTH - ART - 20);
        subtitle = trim(subtitle, WIDTH - ART - 20);

        graphics.text(mc.font, title, textX, y + 6, titleColor.get(), true);
        graphics.text(mc.font, subtitle, textX, y + 6 + mc.font.lineHeight + 1,
                artistColor.get(), true);

        if (showSource.get() && !idle) {
            String label = playing.source().equals("Spotify") ? "SPOTIFY" : "AMAZON";
            int labelWidth = mc.font.width(label);
            graphics.text(mc.font, label, x + WIDTH - labelWidth - 6, y + 4, 0xFF4ADE80, false);
        }
    }

    /** A small record, drawn from rings so no texture has to load. */
    private void drawRecord(GuiGraphicsExtractor graphics, int x, int y, int size) {
        int radius = size / 2;
        int cx = x + radius;
        int cy = y + radius;

        for (int row = 0; row < size; row++) {
            int dy = row - radius;
            double half = Math.sqrt(Math.max(0, radius * radius - dy * dy));
            if (half < 0.5) continue;

            int x1 = (int) Math.round(cx - half);
            int x2 = (int) Math.round(cx + half);
            graphics.fill(x1, y + row, x2, y + row + 1, 0xFF1A1A22);
        }

        // Label in the middle and a highlight ring, so it reads as a record
        graphics.fill(cx - 3, cy - 3, cx + 3, cy + 3, 0xFF7C5CFF);
        graphics.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFF1A1A22);
    }

    private String trim(String text, int room) {
        if (mc.font.width(text) <= room) return text;
        while (text.length() > 1 && mc.font.width(text + "..") > room) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "..";
    }
}
