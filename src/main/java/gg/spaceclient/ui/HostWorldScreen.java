package gg.spaceclient.ui;

import gg.spaceclient.host.WorldHost;
import gg.spaceclient.util.Screens;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

/** Choose how the world opens, then open it. */
public class HostWorldScreen extends Screen {

    private static final int PANEL_W = 320;

    private final Screen parent;

    /** Kept between openings, so the choice does not reset every time. */
    private static GameType mode = GameType.SURVIVAL;
    private static boolean cheats = false;

    public HostWorldScreen(Screen parent) {
        super(Component.literal("Host World"));
        this.parent = parent;
    }

    private int panelLeft() { return (this.width - PANEL_W) / 2; }

    @Override
    protected void init() {
        int left = panelLeft();
        int y = 96;

        this.addRenderableWidget(new FlatButton(
                left, y, PANEL_W, 24,
                () -> "Game mode: " + name(mode),
                () -> true,
                () -> { mode = next(mode); this.rebuildWidgets(); }));

        y += 30;
        this.addRenderableWidget(new FlatButton(
                left, y, PANEL_W, 24,
                () -> "Cheats: " + (cheats ? "on" : "off"),
                () -> cheats,
                () -> { cheats = !cheats; this.rebuildWidgets(); }));

        y += 30;
        this.addRenderableWidget(new FlatButton(
                left, y, PANEL_W, 24,
                () -> "Offline accounts: " + (WorldHost.allowsOffline() ? "allowed" : "no"),
                WorldHost::allowsOffline,
                () -> {
                    WorldHost.setAllowOffline(!WorldHost.allowsOffline());
                    this.rebuildWidgets();
                }));

        y += 46;
        boolean hosting = WorldHost.isHosting();

        this.addRenderableWidget(new FlatButton(
                left, y, PANEL_W, 26,
                () -> hosting ? "Close world" : "Host World",
                () -> !hosting,
                () -> {
                    if (WorldHost.isHosting()) WorldHost.stop();
                    else WorldHost.host(mode, cheats);
                    this.onClose();
                }).asAction());

        // Only worth showing once there is somebody to manage
        if (hosting) {
            y += 32;
            this.addRenderableWidget(new FlatButton(
                    left, y, PANEL_W, 24,
                    () -> "Manage players",
                    () -> false,
                    () -> Screens.open(new HostPlayersScreen(this)))
                    .asAction());
        }

        this.addRenderableWidget(new FlatButton(
                left, this.height - 46, PANEL_W, 24,
                () -> "Back",
                () -> false,
                this::onClose).asAction());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Backdrop.draw(graphics, this.width, this.height);

        int left = panelLeft();
        graphics.fill(left - 18, 20, left + PANEL_W + 18, this.height - 20, Theme.PANEL);
        graphics.fill(left - 18, 20, left + PANEL_W + 18, 21, Theme.BORDER);

        JupiterIcon.draw(graphics, left, 34, 24);
        graphics.text(this.font, "HOST WORLD", left + 34, 38, Theme.CYAN, false);
        graphics.text(this.font, "Open this world to friends",
                left + 34, 50, Theme.TEXT_DIM, false);
        graphics.fill(left, 74, left + PANEL_W, 75, Theme.BORDER);

        // Below the buttons: what the last attempt actually did. Hosting can
        // half succeed - open on the local network but not reachable from
        // outside - and that difference is the whole story for the person
        // waiting on an address to send.
        if (WorldHost.allowsOffline()) {
            graphics.text(this.font,
                    "Anyone who can reach the world may pick any name.",
                    left, 190, 0xFFFFC65C, false);
        }

        int y = this.height - 96;
        graphics.text(this.font, "Status", left, y, Theme.TEXT, false);
        y += this.font.lineHeight + 3;

        String text = WorldHost.status();
        int room = PANEL_W;
        while (!text.isEmpty() && y < this.height - 54) {
            String row = text;
            while (this.font.width(row) > room && row.length() > 1) {
                row = row.substring(0, row.length() - 1);
            }
            graphics.text(this.font, row, left, y, Theme.TEXT_DIM, false);
            text = text.substring(row.length());
            y += this.font.lineHeight + 2;
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private static String name(GameType type) {
        return switch (type) {
            case CREATIVE -> "Creative";
            case ADVENTURE -> "Adventure";
            case SPECTATOR -> "Spectator";
            default -> "Survival";
        };
    }

    private static GameType next(GameType type) {
        return switch (type) {
            case SURVIVAL -> GameType.CREATIVE;
            case CREATIVE -> GameType.ADVENTURE;
            case ADVENTURE -> GameType.SPECTATOR;
            default -> GameType.SURVIVAL;
        };
    }

    @Override
    public void onClose() {
        Screens.open(parent);
    }
}
