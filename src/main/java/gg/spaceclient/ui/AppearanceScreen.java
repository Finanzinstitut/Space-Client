package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.config.ClientSettings;
import gg.spaceclient.util.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Background style and accent colour for the whole interface. */
public class AppearanceScreen extends Screen {
    private static final int ROW_H = 26;
    private static final int GAP = 8;
    private static final int PANEL_W = 320;

    private final Screen parent;

    public AppearanceScreen(Screen parent) {
        super(Component.literal("Appearance"));
        this.parent = parent;
    }

    private int panelLeft() { return (this.width - PANEL_W) / 2; }

    /**
     * Turns a stored style name into something readable.
     *
     * The names are stored as they are so an old config keeps working, but
     * BLACK_HOLE is not what anybody wants to read on a button.
     */
    private static String prettyStyle(String style) {
        return switch (style) {
            case "SPACE" -> "Starfield";
            case "DARK" -> "Dark";
            case "SOLID_BLACK" -> "Black";
            case "TRANSPARENT" -> "Transparent";
            case "AURORA" -> "Aurora";
            case "NEBULA" -> "Nebula";
            case "BLACK_HOLE" -> "Black hole";
            case "GALAXY" -> "Galaxy";
            default -> style;
        };
    }

    @Override
    protected void init() {
        ClientSettings settings = SpaceClient.getSettings();
        int left = panelLeft();
        int y = 90;

        this.addRenderableWidget(new FlatButton(
                left, y, PANEL_W, ROW_H,
                () -> "Background: " + prettyStyle(settings.backgroundStyle()),
                () -> false,
                () -> {
                    settings.cycleBackground();
                    SpaceClient.getConfigManager().save();
                }
        ));
        y += ROW_H + GAP;

        // Opens a screen rather than cycling in place like the background does.
        // Eight fonts is too many to page through one press at a time, and
        // unlike a background a font cannot be judged from its name - the
        // picker has room for a line saying what each one looks like.
        this.addRenderableWidget(new FlatButton(
                left, y, PANEL_W, ROW_H,
                () -> "Font: " + gg.spaceclient.font.FontStyle
                        .byId(settings.fontStyle()).label(),
                () -> false,
                () -> Screens.open(new FontScreen(this))
        ).asAction());
        y += ROW_H + GAP;

        // A toggle rather than a cycle: the custom menu is either the client's
        // or the game's, and there is no third option to page through.
        this.addRenderableWidget(new FlatButton(
                left, y, PANEL_W, ROW_H,
                () -> "Custom main menu",
                settings::customMenu,
                () -> {
                    settings.setCustomMenu(!settings.customMenu());
                    SpaceClient.getConfigManager().save();
                }
        ));
        y += ROW_H + GAP + 4;

        // The accent is picked on a wheel; a hex field would mean typing.
        this.addRenderableWidget(new ColorWheel(
                left, y, 96, settings.accentSetting(),
                () -> SpaceClient.getConfigManager().save()
        ));
        y += 96 + GAP + 4;


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
        graphics.text(this.font, "APPEARANCE", left + 34, 38, Theme.CYAN, false);
        graphics.text(this.font, "Font, accent colour and menu background",
                left + 34, 50, Theme.TEXT_DIM, false);
        graphics.fill(left, 74, left + PANEL_W, 75, Theme.BORDER);

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // Live preview swatch of the current accent
        int swatchY = this.height - 60;
        graphics.text(this.font, "Preview", left, swatchY - 12, Theme.TEXT_DIM, false);
        graphics.fill(left, swatchY, left + PANEL_W, swatchY + 18, Theme.accentDim());
        graphics.fill(left, swatchY, left + 3, swatchY + 18, Theme.accent());
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
