package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.util.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Everything adjustable, in two columns.
 *
 * A third layout on purpose: worlds are a row of cards, servers are a list of
 * rows, and settings are a grid. Each screen should be recognisable from its
 * shape alone before a single word is read - if all three looked the same, the
 * only way to know where you are would be the heading.
 *
 * The client's own settings sit in the same grid as the game's rather than
 * behind a separate door. Somebody looking for a brightness slider does not
 * care which half of the software owns it, and making them guess is how a
 * client ends up with two settings screens nobody can tell apart.
 *
 * <h2>Missing tiles are hidden, not greyed out</h2>
 *
 * The game's screens are reached by class name, and a name that no longer
 * exists means the tile is dropped from the grid entirely. A disabled tile
 * invites clicking; an absent one closes the question.
 */
public class SettingsHubScreen extends Screen {

    private static final int TILE_W = 230;
    private static final int TILE_H = 54;
    private static final int GAP = 10;

    private final Screen parent;
    private final java.util.List<SettingsTile> tiles = new java.util.ArrayList<>();

    private long openedAt = 0L;

    public SettingsHubScreen(Screen parent) {
        super(Component.literal("Settings"));
        this.parent = parent;
    }

    /** One tile before it knows where it will sit. */
    private record Plan(String title, String subtitle, SettingsTile.Mark mark, Runnable action) {}

    @Override
    protected void init() {
        if (openedAt == 0L) openedAt = System.currentTimeMillis();
        tiles.clear();

        java.util.List<Plan> plan = new java.util.ArrayList<>();

        // The client's own, first: this menu is reached from the client's
        // button, so what it owns should be what greets you
        plan.add(new Plan("Space Client", "Modules, HUD and everything this client adds",
                SettingsTile.Mark.CLIENT, () -> Screens.open(new SpaceMenuScreen())));

        plan.add(new Plan("Appearance", "Background, font and accent colour",
                SettingsTile.Mark.SKIN, () -> Screens.open(new AppearanceScreen(this))));

        plan.add(new Plan("HUD editor", "Move and scale what sits on screen",
                SettingsTile.Mark.HUD, this::openHudEditor));

        vanilla(plan, "Video", "Render distance, brightness and frame rate",
                SettingsTile.Mark.SCREEN,
                "net.minecraft.client.gui.screens.options.VideoSettingsScreen");

        vanilla(plan, "Sound", "Music and volume for each kind of sound",
                SettingsTile.Mark.SOUND,
                "net.minecraft.client.gui.screens.options.SoundOptionsScreen");

        vanilla(plan, "Controls", "Key bindings, mouse and sensitivity",
                SettingsTile.Mark.KEYS,
                "net.minecraft.client.gui.screens.options.controls.ControlsScreen");

        // Under options. rather than at the top level, which is where an
        // earlier guess put it - the tile simply never appeared.
        vanilla(plan, "Language", "The language the game is written in",
                SettingsTile.Mark.LANGUAGE,
                "net.minecraft.client.gui.screens.options.LanguageSelectScreen");

        vanilla(plan, "Chat", "Chat visibility, width and colours",
                SettingsTile.Mark.INFO,
                "net.minecraft.client.gui.screens.options.ChatOptionsScreen");

        vanilla(plan, "Accessibility", "Text background, narrator and motion",
                SettingsTile.Mark.INFO,
                "net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen");

        vanilla(plan, "Resource packs", "Textures, sounds and fonts",
                SettingsTile.Mark.PACKS,
                "net.minecraft.client.gui.screens.packs.PackSelectionScreen");

        vanilla(plan, "All options", "The game's own settings screen, unchanged",
                SettingsTile.Mark.INFO,
                "net.minecraft.client.gui.screens.options.OptionsScreen");

        // Two columns, centred as a block
        int columns = 2;
        int blockWidth = columns * TILE_W + (columns - 1) * GAP;
        int left = (this.width - blockWidth) / 2;
        int top = 88;

        for (int i = 0; i < plan.size(); i++) {
            Plan entry = plan.get(i);
            int column = i % columns;
            int row = i / columns;

            SettingsTile tile = new SettingsTile(
                    left + column * (TILE_W + GAP),
                    top + row * (TILE_H + GAP),
                    TILE_W, TILE_H,
                    entry.title(), entry.subtitle(), entry.mark(), entry.action());
            tile.setAppear(0f);
            tiles.add(tile);
            this.addRenderableWidget(tile);
        }

        this.addRenderableWidget(new FlatButton(
                left, this.height - 40, 110, 24,
                () -> "Back", () -> false, this::onClose).asAction());
    }

    /**
     * Adds a tile only if the screen behind it exists.
     *
     * The class is looked up now rather than when the tile is pressed, so a
     * missing screen costs a gap in the grid instead of a button that swallows
     * a click.
     */
    private void vanilla(java.util.List<Plan> plan, String title, String subtitle,
                         SettingsTile.Mark mark, String className) {
        try {
            Class.forName(className);
        } catch (Throwable ignored) {
            return;
        }
        plan.add(new Plan(title, subtitle, mark, () -> openVanilla(className)));
    }

    /**
     * Opens one of the game's own screens.
     *
     * The options object is read straight off the field. Asking for it through
     * a getter returned null, and because a missing argument used to be passed
     * as null rather than refused, every one of these screens was built with no
     * settings behind it - they opened onto nothing at all. Passing the real
     * object is the fix; refusing to build without it is the guard, so the same
     * mistake shows up as a tile that does not react instead of a blank screen.
     */
    private void openVanilla(String className) {
        Object options = Minecraft.getInstance().options;

        Object screen = Construct.strict(className, this, options, Minecraft.getInstance());
        if (screen == null) screen = Construct.of(className, this, options, Minecraft.getInstance());

        if (screen instanceof Screen target) {
            Screens.open(target);
        } else {
            SpaceClient.LOGGER.warn("Could not open {} on this version", className);
        }
    }

    private void openHudEditor() {
        Screens.open(new HudEditorScreen(this));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (!MenuWallpaper.draw(graphics, this.width, this.height, mouseX, mouseY, delta)) {
            Backdrop.draw(graphics, this.width, this.height);
        }
        graphics.fill(0, 0, this.width, this.height, 0x60000000);

        long now = System.currentTimeMillis() - openedAt;
        for (int i = 0; i < tiles.size(); i++) {
            // Down the columns rather than across, so the eye follows the
            // same path the grid is read in
            long start = 45L * i;
            tiles.get(i).setAppear(span(now, start, start + 240));
        }

        String title = "Settings";
        int titleWidth = this.font.width(title) * 2;
        boolean scaled = Scale.push(graphics, (this.width - titleWidth) / 2, 30, 2f);
        graphics.text(this.font, title,
                scaled ? 0 : (this.width - titleWidth) / 2, scaled ? 0 : 30,
                0xFFFFFFFF, false);
        if (scaled) Scale.pop(graphics);

        String hint = "Space Client " + SpaceClient.VERSION;
        graphics.text(this.font, hint,
                (this.width - this.font.width(hint)) / 2, 56, 0xFFB9B4DC, false);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private static float span(long now, long from, long to) {
        if (now <= from) return 0f;
        if (now >= to) return 1f;
        return (now - from) / (float) (to - from);
    }

    @Override
    public void onClose() {
        Screens.open(parent);
    }
}
