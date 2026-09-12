package gg.spaceclient.ui;

import gg.spaceclient.host.HostAdmin;
import gg.spaceclient.util.Screens;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Who is connected, and what the host can do about it. */
public class HostPlayersScreen extends Screen {

    private static final int PANEL_W = 420;
    private static final int ROW_H = 28;

    private final Screen parent;

    private List<ServerPlayer> players = List.of();

    /** Rebuilt every second, so someone joining or leaving shows up. */
    private long lastRefresh = 0;

    public HostPlayersScreen(Screen parent) {
        super(Component.literal("Players"));
        this.parent = parent;
    }

    private int panelLeft() { return (this.width - PANEL_W) / 2; }

    @Override
    protected void init() {
        players = HostAdmin.players();
        lastRefresh = System.currentTimeMillis();

        int left = panelLeft();
        int y = 92;

        for (ServerPlayer player : players) {
            if (y > this.height - 90) break;

            boolean host = HostAdmin.isHost(player);
            boolean op = HostAdmin.isOp(player);

            // The host has no buttons on their own row. Kicking yourself out of
            // your own world is not a power worth offering.
            if (!host) {
                int x = left + PANEL_W - 210;

                this.addRenderableWidget(new FlatButton(
                        x, y, 60, 20,
                        () -> op ? "Deop" : "Op",
                        () -> op,
                        () -> {
                            if (op) HostAdmin.deop(player);
                            else HostAdmin.op(player);
                            this.rebuildWidgets();
                        }).asAction());

                this.addRenderableWidget(new FlatButton(
                        x + 66, y, 60, 20,
                        () -> "Kick",
                        () -> false,
                        () -> {
                            HostAdmin.kick(player, "Kicked by the host");
                            this.rebuildWidgets();
                        }).asAction());

                this.addRenderableWidget(new FlatButton(
                        x + 132, y, 60, 20,
                        () -> "Ban",
                        () -> false,
                        () -> {
                            HostAdmin.ban(player, "Banned by the host");
                            this.rebuildWidgets();
                        }).asAction());
            }

            y += ROW_H;
        }

        this.addRenderableWidget(new FlatButton(
                left, this.height - 46, PANEL_W, 24,
                () -> "Back",
                () -> false,
                this::onClose).asAction());
    }

    @Override
    public void tick() {
        if (System.currentTimeMillis() - lastRefresh > 1000) {
            List<ServerPlayer> now = HostAdmin.players();
            if (now.size() != players.size()) this.rebuildWidgets();
            else lastRefresh = System.currentTimeMillis();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Backdrop.draw(graphics, this.width, this.height);

        int left = panelLeft();
        graphics.fill(left - 18, 20, left + PANEL_W + 18, this.height - 20, Theme.PANEL);
        graphics.fill(left - 18, 20, left + PANEL_W + 18, 21, Theme.BORDER);

        JupiterIcon.draw(graphics, left, 34, 24);
        graphics.text(this.font, "PLAYERS", left + 34, 38, Theme.CYAN, false);
        graphics.text(this.font, players.size() + " connected",
                left + 34, 50, Theme.TEXT_DIM, false);
        graphics.fill(left, 74, left + PANEL_W, 75, Theme.BORDER);

        int y = 92;
        for (ServerPlayer player : players) {
            if (y > this.height - 90) break;

            boolean host = HostAdmin.isHost(player);
            String name = player.getName().getString();

            graphics.text(this.font, name, left, y + 6,
                    host ? Theme.CYAN : Theme.TEXT, false);

            if (host) {
                graphics.text(this.font, "host", left + this.font.width(name) + 8, y + 6,
                        Theme.TEXT_DIM, false);
            } else if (HostAdmin.isOp(player)) {
                graphics.text(this.font, "op", left + this.font.width(name) + 8, y + 6,
                        Theme.TEXT_DIM, false);
            }

            y += ROW_H;
        }

        String status = HostAdmin.status();
        if (!status.isEmpty()) {
            graphics.text(this.font, status, left, this.height - 62, Theme.TEXT_DIM, false);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        Screens.open(parent);
    }
}
