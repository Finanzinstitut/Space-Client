package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.font.FontPacks;
import gg.spaceclient.font.FontStyle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Picking the font the game draws with.
 *
 * Laid out as a grid of two columns rather than one list of eight rows, which
 * is not decoration: eight rows plus a header, a note and a Back button is
 * taller than the window at the GUI scales people actually play at, and a list
 * that needs scrolling to reach the last entry needs scroll handling, which in
 * this version means the input API this package deliberately keeps away from.
 * Four rows fit anywhere.
 *
 * There is no preview of each face. A real one would mean loading all eight
 * fonts at once to draw eight sample strings, and a fake one - the sample drawn
 * in whatever font is already active - would be worse than none, because it
 * would show every entry looking identical.
 */
public class FontScreen extends Screen {
    private static final int ROW_H = 26;
    private static final int GAP = 8;
    private static final int PANEL_W = 320;
    private static final int COL_W = (PANEL_W - GAP) / 2;

    private final Screen parent;

    public FontScreen(Screen parent) {
        super(Component.literal("Font"));
        this.parent = parent;
    }

    private int panelLeft() { return (this.width - PANEL_W) / 2; }

    /** The entry the mouse is over, for the description line at the bottom. */
    private FontStyle hovered = null;

    @Override
    protected void init() {
        int left = panelLeft();
        int top = 90;

        for (int i = 0; i < FontStyle.ALL.size(); i++) {
            FontStyle style = FontStyle.ALL.get(i);

            int x = left + (i % 2) * (COL_W + GAP);
            int y = top + (i / 2) * (ROW_H + GAP);

            this.addRenderableWidget(new FlatButton(
                    x, y, COL_W, ROW_H,
                    style::label,
                    () -> SpaceClient.getSettings().fontStyle().equals(style.id()),
                    () -> choose(style)
            ).asAction());
        }

        int listBottom = top + ((FontStyle.ALL.size() + 1) / 2) * (ROW_H + GAP);

        this.addRenderableWidget(new FlatButton(
                left, listBottom + GAP * 2, PANEL_W, ROW_H,
                () -> "Back",
                () -> false,
                this::onClose
        ).asAction());
    }

    /**
     * Nothing happens when the current font is picked again.
     *
     * Reapplying would mean a full resource reload for no change, and a resource
     * reload is a second or two of frozen game - not something to hand somebody
     * for double clicking a button.
     */
    private void choose(FontStyle style) {
        if (SpaceClient.getSettings().fontStyle().equals(style.id())) return;

        SpaceClient.getSettings().setFontStyle(style.id());
        SpaceClient.getConfigManager().save();
        FontPacks.apply(style.id());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Backdrop.draw(graphics, this.width, this.height);

        int left = panelLeft();

        graphics.fill(left - 18, 20, left + PANEL_W + 18, this.height - 20, Theme.PANEL);
        graphics.fill(left - 18, 20, left + PANEL_W + 18, 21, Theme.BORDER);

        JupiterIcon.draw(graphics, left, 34, 24);
        graphics.text(this.font, "FONT", left + 34, 38, Theme.CYAN, false);
        graphics.text(this.font, "Applies to the whole game, not just this menu",
                left + 34, 50, Theme.TEXT_DIM, false);
        graphics.fill(left, 74, left + PANEL_W, 75, Theme.BORDER);

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // Which button the cursor is over, worked out from the grid rather than
        // asked of the widgets - FlatButton refuses focus, so there is no
        // focused widget to ask.
        hovered = null;
        int top = 90;
        for (int i = 0; i < FontStyle.ALL.size(); i++) {
            int x = left + (i % 2) * (COL_W + GAP);
            int y = top + (i / 2) * (ROW_H + GAP);
            if (mouseX >= x && mouseX < x + COL_W && mouseY >= y && mouseY < y + ROW_H) {
                hovered = FontStyle.ALL.get(i);
                break;
            }
        }

        FontStyle shown = hovered != null
                ? hovered
                : FontStyle.byId(SpaceClient.getSettings().fontStyle());

        int noteY = this.height - 62;
        graphics.text(this.font, shown.label(), left, noteY, Theme.TEXT, false);
        graphics.text(this.font, shown.note(), left, noteY + 12, Theme.TEXT_DIM, false);
        graphics.text(this.font,
                "Locked to the top of the resource pack list",
                left, noteY + 26, Theme.OFF, false);
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
