package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.module.Module;
import gg.spaceclient.net.Twitch;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Streamer mode: what is hidden, and which channel is attached.
 *
 * Only Twitch is here, and that is a limit of the platforms rather than of the
 * client. Twitch publishes both a follower count and who the newest follower
 * is. YouTube publishes a subscriber count but will not say who subscribed
 * unless they made it public, which almost nobody does, so a "newest
 * subscriber" line would be blank or wrong most of the time. TikTok publishes
 * neither without an app review, and no follower list at any tier.
 */
public class StreamerScreen extends Screen {

    private static final int ROW_H = 26;
    private static final int GAP = 8;
    private static final int PANEL_W = 320;

    private final Screen parent;

    public StreamerScreen(Screen parent) {
        super(Component.literal("Streamer Mode"));
        this.parent = parent;
    }

    private int panelLeft() { return (this.width - PANEL_W) / 2; }

    @Override
    protected void init() {
        // The screen was probably opened to check these numbers, so ask for a
        // fresh set rather than showing whatever the last poll left behind
        Twitch.refreshSoon();

        int left = panelLeft();
        int y = 92;

        this.addRenderableWidget(new FlatButton(
                left, y, PANEL_W, ROW_H,
                () -> "Streamer mode: " + (StreamerMode.isOn() ? "on" : "off"),
                StreamerMode::isOn,
                () -> {
                    StreamerMode.set(!StreamerMode.isOn());
                    this.rebuildWidgets();
                }
        ));
        y += ROW_H + GAP;

        Module followerHud = SpaceClient.getModuleManager().get("twitch");
        if (followerHud != null) {
            this.addRenderableWidget(new FlatButton(
                    left, y, PANEL_W, ROW_H,
                    () -> "Follower display: "
                            + (followerHud.isEnabled() ? "on" : "off"),
                    followerHud::isEnabled,
                    () -> {
                        followerHud.toggle();
                        SpaceClient.getConfigManager().save();
                    }
            ));
        }
        y += ROW_H + GAP * 2;

        if (Twitch.isLinking()) {
            this.addRenderableWidget(new FlatButton(
                    left, y, PANEL_W, ROW_H,
                    () -> "Cancel linking",
                    () -> false,
                    () -> {
                        Twitch.cancelLink();
                        this.rebuildWidgets();
                    }
            ).asAction());

        } else if (Twitch.isLinked()) {
            this.addRenderableWidget(new FlatButton(
                    left, y, PANEL_W, ROW_H,
                    () -> "Unlink " + (Twitch.login().isEmpty()
                            ? "Twitch" : Twitch.login()),
                    () -> false,
                    () -> {
                        Twitch.unlink();
                        this.rebuildWidgets();
                    }
            ).asAction());

        } else {
            this.addRenderableWidget(new FlatButton(
                    left, y, PANEL_W, ROW_H,
                    () -> "Link Twitch account",
                    () -> false,
                    () -> {
                        Twitch.startLink();
                        this.rebuildWidgets();
                    }
            ));
        }

        this.addRenderableWidget(new FlatButton(
                left, this.height - 46, PANEL_W, ROW_H,
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
        graphics.text(this.font, "STREAMER MODE", left + 34, 38, Theme.CYAN, false);
        graphics.text(this.font, "Hides your position and shows your channel",
                left + 34, 50, Theme.TEXT_DIM, false);
        graphics.fill(left, 74, left + PANEL_W, 75, Theme.BORDER);

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int y = this.height - 130;

        if (Twitch.isLinking()) {
            // The code is the whole point of this screen while linking, so it
            // gets the space and the accent colour
            graphics.text(this.font, "Open " + Twitch.verifyUrl(),
                    left, y, Theme.TEXT_DIM, false);
            graphics.text(this.font, "and enter this code:", left, y + 12, Theme.TEXT_DIM, false);
            graphics.text(this.font, Twitch.userCode(), left, y + 28, Theme.accent(), false);
            graphics.text(this.font, "This screen notices by itself once you have.",
                    left, y + 44, Theme.OFF, false);

        } else if (Twitch.isLinked()) {
            int followers = Twitch.followers();
            graphics.text(this.font,
                    followers < 0 ? "Followers: loading..." : "Followers: " + followers,
                    left, y, Theme.TEXT, false);

            String last = Twitch.lastFollower();
            graphics.text(this.font,
                    last.isEmpty() ? "Newest follower: none yet" : "Newest follower: " + last,
                    left, y + 12, Theme.TEXT_DIM, false);

        } else {
            graphics.text(this.font, "Only Twitch is supported.", left, y, Theme.TEXT_DIM, false);
            graphics.text(this.font, "YouTube and TikTok do not publish", left, y + 12, Theme.OFF, false);
            graphics.text(this.font, "who followed you last.", left, y + 22, Theme.OFF, false);
        }

        graphics.text(this.font, Twitch.status(), left, this.height - 66, Theme.OFF, false);
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
