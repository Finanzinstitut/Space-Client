package gg.spaceclient.ui;

import gg.spaceclient.session.LauncherAccount;
import gg.spaceclient.session.LauncherAccounts;
import gg.spaceclient.session.SessionManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Switches between the accounts the launcher knows about, and refreshes the
 * current session, without leaving the game.
 */
public class AccountsScreen extends Screen {
    private static final int ROW_H = 26;
    private static final int GAP = 6;
    private static final int PANEL_W = 340;

    private final Screen parent;
    private List<LauncherAccount> accounts = List.of();

    public AccountsScreen(Screen parent) {
        super(Component.literal("Accounts"));
        this.parent = parent;
    }

    private int panelLeft() { return (this.width - PANEL_W) / 2; }

    @Override
    protected void init() {
        accounts = LauncherAccounts.load();
        String active = LauncherAccounts.activeUuid();
        String playing = Minecraft.getInstance().getUser().getName();

        int left = panelLeft();
        int y = 96;

        for (LauncherAccount account : accounts) {
            boolean current = account.username().equalsIgnoreCase(playing);

            this.addRenderableWidget(new FlatButton(
                    left, y, PANEL_W, ROW_H,
                    () -> account.username() + (account.offline() ? "  (offline)" : ""),
                    () -> current,
                    () -> SessionManager.applyAccount(account)
                            .thenRun(() -> Minecraft.getInstance().execute(this::rebuildWidgets))
            ));
            y += ROW_H + GAP;
        }

        y += GAP;

        this.addRenderableWidget(new FlatButton(
                left, y, PANEL_W, ROW_H,
                () -> "Refresh current session",
                () -> false,
                () -> SessionManager.refreshCurrent()
                        .thenRun(() -> Minecraft.getInstance().execute(this::rebuildWidgets))
        ).asAction());
        y += ROW_H + GAP * 2;

        this.addRenderableWidget(new FlatButton(
                left, y, PANEL_W, ROW_H,
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
        graphics.text(this.font, "ACCOUNTS", left + 34, 38, Theme.CYAN, false);
        // Read live from the game, so a failed swap is visible rather than hidden
        graphics.text(this.font, "Game reports: " + Minecraft.getInstance().getUser().getName(),
                left + 34, 50, Theme.TEXT_DIM, false);
        graphics.fill(left, 74, left + PANEL_W, 75, Theme.BORDER);

        if (accounts.isEmpty()) {
            graphics.text(this.font,
                    LauncherAccounts.isAvailable()
                            ? "The launcher has no accounts signed in."
                            : "Launcher accounts not found - start the game from Space Client.",
                    left, 100, Theme.TEXT_DIM, false);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        String status = SessionManager.status();
        if (!status.isEmpty()) {
            graphics.text(this.font, status, left, this.height - 34,
                    SessionManager.isBusy() ? Theme.TEXT_DIM : Theme.CYAN, false);
        }
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
