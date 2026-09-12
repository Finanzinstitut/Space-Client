package gg.spaceclient.ui;

import gg.spaceclient.music.Lyrics;
import gg.spaceclient.music.MediaSession;
import gg.spaceclient.music.MusicWatcher;
import gg.spaceclient.net.NowPlayingShare;
import gg.spaceclient.net.Presence;
import gg.spaceclient.net.SpaceApi;
import gg.spaceclient.util.Diagnostics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Shows what the mod could and could not find on this version. */
public class DiagnosticsScreen extends Screen {
    private static final int PANEL_W = 420;

    private final Screen parent;
    private List<Diagnostics.Check> checks = List.of();

    public DiagnosticsScreen(Screen parent) {
        super(Component.literal("Diagnostics"));
        this.parent = parent;
    }

    /** How far the list has been scrolled, in pixels. */
    private double scroll = 0;

    /** Where the content ended last frame, so scrolling knows when to stop. */
    private int contentBottom = 0;

    private int panelLeft() { return (this.width - PANEL_W) / 2; }

    /** The first row of content, and the last pixel before the Back button. */
    private int listTop() { return 80; }
    private int listBottom() { return this.height - 52; }

    /** Whether a row at this height is inside the visible strip. */
    private boolean visible(int y) {
        return y >= listTop() - this.font.lineHeight && y <= listBottom();
    }

    private int maxScroll() {
        return Math.max(0, contentBottom - listBottom() + 8);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - scrollY * 18));
        return true;
    }

    /** One label and value row, cut to the panel rather than wrapped. */
    private int line(GuiGraphicsExtractor graphics, int left, int y,
                     String label, String value) {
        if (visible(y)) {
            graphics.text(this.font, label, left, y, Theme.TEXT, false);

            String text = value == null ? "-" : value;
            int room = PANEL_W - 190;
            while (this.font.width(text) > room && text.length() > 1) {
                text = text.substring(0, text.length() - 1);
            }

            graphics.text(this.font, text, left + 190, y, Theme.TEXT_DIM, false);
        }
        return y + this.font.lineHeight + 3;
    }

    @Override
    protected void init() {
        checks = Diagnostics.run();

        this.addRenderableWidget(new FlatButton(
                panelLeft(), this.height - 46, PANEL_W, 24,
                () -> "Back",
                () -> false,
                this::onClose
        ).asAction());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Backdrop.draw(graphics, this.width, this.height);

        int left = panelLeft();
        graphics.fill(left - 18, 20, left + PANEL_W + 18, this.height - 20, Theme.PANEL);
        graphics.fill(left - 18, 20, left + PANEL_W + 18, 21, Theme.BORDER);

        JupiterIcon.draw(graphics, left, 34, 24);
        graphics.text(this.font, "DIAGNOSTICS", left + 34, 38, Theme.CYAN, false);
        graphics.text(this.font, "What this Minecraft version does and does not expose",
                left + 34, 50, Theme.TEXT_DIM, false);
        graphics.fill(left, 74, left + PANEL_W, 75, Theme.BORDER);

        // Everything below the header scrolls as one list, so the last rows
        // cannot end up underneath the Back button on a short window
        int y = 88 - (int) scroll;

        for (Diagnostics.Check check : checks) {
            String mark = check.ok() ? "OK" : "--";
            int color = check.ok() ? 0xFF4ADE80 : 0xFFFF6B81;

            if (visible(y)) {
                graphics.text(this.font, mark, left, y, color, false);
                graphics.text(this.font, check.name(), left + 24, y, Theme.TEXT, false);
            }

            // Details can get long; wrap rather than run off the panel
            String detail = check.detail();
            int detailX = left + 190;
            int room = PANEL_W - 190;

            while (!detail.isEmpty()) {
                String line = detail;
                while (this.font.width(line) > room && line.length() > 1) {
                    line = line.substring(0, line.length() - 1);
                }
                if (visible(y)) {
                    graphics.text(this.font, line, detailX, y, Theme.TEXT_DIM, false);
                }
                detail = detail.substring(line.length());
                y += this.font.lineHeight + 2;
            }
            y += 2;
        }

        // Live state, below the version checks. These are not pass or fail
        // questions about this Minecraft build - they say what the two things
        // that talk to the outside world are doing right now, which is
        // otherwise only visible in the log.
        y += 8;
        if (visible(y)) graphics.fill(left, y, left + PANEL_W, y + 1, Theme.BORDER);
        y += 10;

        if (visible(y)) graphics.text(this.font, "NOW PLAYING", left, y, Theme.CYAN, false);
        y += this.font.lineHeight + 4;

        y = line(graphics, left, y, "Music lookup", MusicWatcher.status());
        y = line(graphics, left, y, "Media session", MediaSession.status());
        y = line(graphics, left, y, "Playback position", MediaSession.timelineStatus());
        y = line(graphics, left, y, "Player scan", MusicWatcher.seenProcesses());
        y = line(graphics, left, y, "Sharing", SpaceApi.status()
                + (SpaceApi.hasToken() ? " (token held)" : " (no token)"));
        y = line(graphics, left, y, "Reported", NowPlayingShare.reportStatus());
        y = line(graphics, left, y, "Songs known", NowPlayingShare.cacheStatus());
        y = line(graphics, left, y, "Lyrics", Lyrics.status());
        y = line(graphics, left, y, "Lyric line", NowPlayingShare.lyricStatus());
        y = line(graphics, left, y, "Name tag hook", NowPlayingShare.hookStatus());
        y = line(graphics, left, y, "Badges", Presence.status());
        y = line(graphics, left, y, "Item scaling",
                gg.spaceclient.access.ItemScaleReport.status());

        contentBottom = y + (int) scroll;

        // A hint only while there is something below the fold
        if (maxScroll() > 0) {
            graphics.text(this.font, "scroll for more",
                    left + PANEL_W - this.font.width("scroll for more"),
                    this.height - 62, Theme.TEXT_DIM, false);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
