package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.config.Profiles;
import gg.spaceclient.module.Module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The main menu, as a window rather than a takeover.
 *
 * The previous version painted the whole screen, which made a quick toggle feel
 * like leaving the game. This one is a panel in the middle with the world still
 * visible around it, so opening the menu reads as pausing rather than exiting.
 *
 * The other change is how modules are found. Categories used to be the only way
 * through the list, which made them behave like pages: to reach a module you
 * first had to know which shelf it was on. Now the list is one continuous
 * scroll with a search field above it, and the categories are a filter you may
 * use rather than a route you must take.
 *
 * Everything interactive is a widget, and the scroll callbacks carry no
 * @Override, both for the reason the rest of this package does: this version
 * reworked the input API, and a signature that no longer matches should become
 * a dead method rather than a failed build.
 */
public class SpaceMenuScreen extends Screen {

    private static final int RAIL_W = 104;
    private static final int ROW_H = 26;
    private static final int ROW_GAP = 2;
    private static final int HEADER_H = 42;
    private static final int CHIP_H = 18;
    private static final int FOOTER_H = 30;
    private static final int PAD = 12;

    /** Which rail entry is showing. Only Mods has a list; the rest open screens. */
    private String section = "Mods";

    /** Null means every category. */
    private String category = null;

    /** Narrows the list to modules that are switched on. */
    private boolean onlyEnabled = false;

    private String query = "";
    private EditBox search;

    /**
     * Scroll position in whole rows, not pixels.
     *
     * Pixel scrolling means rows are half in and half out of the list, which
     * has to be hidden by drawing over the overflow. That was the previous
     * approach and it failed twice over: every module was given a widget, not
     * just the visible ones, so the list ran hundreds of pixels past the window
     * in both directions; and the panel colour has an alpha of 0xE6, so the
     * strips meant to cover the overflow were themselves see through and only
     * dimmed it.
     *
     * Counting in rows removes the problem rather than papering over it. Only
     * rows that fit are given a widget, and they always sit fully inside the
     * list, so there is no overflow to hide and nothing can be drawn outside
     * the window.
     */
    private int scrollRow = 0;
    private float scrollShown = 0f;

    /**
     * Set at construction rather than in init, so the fade is not restarted
     * every time the widgets are rebuilt.
     */
    private final long openedAt = System.currentTimeMillis();

    private final List<ModRow> rows = new ArrayList<>();

    public SpaceMenuScreen() {
        super(Component.literal("Space Client"));
    }

    // ---------------- geometry ----------------
    //
    // Clamped rather than proportional: below the minimum the list stops being
    // readable, and above the maximum a centred panel starts to feel like the
    // full screen layout this replaced.

    private int panelW() { return Math.max(400, Math.min(620, this.width - 80)); }
    private int panelH() { return Math.max(240, Math.min(400, this.height - 60)); }
    private int panelX() { return (this.width - panelW()) / 2; }
    private int panelY() { return (this.height - panelH()) / 2; }

    private int contentLeft()  { return panelX() + RAIL_W; }
    private int contentRight() { return panelX() + panelW(); }
    private int listTop()      { return panelY() + HEADER_H + CHIP_H + 10; }
    private int listBottom()   { return panelY() + panelH() - FOOTER_H; }

    // ---------------- data ----------------

    /** Settings that are not inside a group, so they are not listed twice. */
    private static List<gg.spaceclient.setting.Setting> ungrouped(Module module) {
        java.util.Set<gg.spaceclient.setting.Setting> inGroups = new java.util.HashSet<>();
        module.getGroups().forEach(g -> inGroups.addAll(g.settings()));
        return module.getSettings().stream().filter(s -> !inGroups.contains(s)).toList();
    }

    private boolean matches(Module module) {
        if (category != null && !category.equals(ModuleCategories.of(module))) return false;
        if (onlyEnabled && !module.isEnabled()) return false;
        if (query.isEmpty()) return true;

        String needle = query.toLowerCase(Locale.ROOT);
        // Description is searched too, so "fps" finds the module that mentions
        // frames without having the word in its name
        return module.getName().toLowerCase(Locale.ROOT).contains(needle)
                || module.getId().toLowerCase(Locale.ROOT).contains(needle)
                || module.getDescription().toLowerCase(Locale.ROOT).contains(needle);
    }

    private List<Module> shown() {
        List<Module> out = new ArrayList<>();
        for (Module module : SpaceClient.getModuleManager().getAll()) {
            if (matches(module)) out.add(module);
        }
        return out;
    }

    private int countIn(String cat) {
        int count = 0;
        for (Module module : SpaceClient.getModuleManager().getAll()) {
            if (cat == null || cat.equals(ModuleCategories.of(module))) count++;
        }
        return count;
    }

    // ---------------- build ----------------

    @Override
    protected void init() {
        buildSearch();
        buildRail();
        buildChips();
        buildStreamerButton();
        buildProfileButton();
        buildList();
    }

    /**
     * The search field is a vanilla EditBox on purpose.
     *
     * Typing needs the keyboard API, and this version reworked input. A widget
     * handles its own events internally, so using the vanilla one keeps this
     * screen out of a signature it cannot verify - the same reasoning that
     * keeps the mouse handlers below unannotated, applied one step earlier.
     *
     * The jar bears this out: input in 26.2 runs on KeyEvent and
     * CharacterEvent, so a hand written charTyped(char, int) would have
     * compiled into a method nothing ever calls, and the field would have sat
     * there refusing to type with no error to explain why.
     */
    private void buildSearch() {
        int w = 150;
        int x = contentRight() - PAD - w;
        int y = panelY() + 14;

        search = new EditBox(this.font, x, y, w, 16, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(Theme.TEXT);
        search.setHint(Component.literal("Search modules"));
        // Value before responder, not the other way round. setValue fires the
        // responder, and the responder rebuilds the list - so setting it after
        // would build the rows once here and again when init reaches buildList,
        // leaving two widgets stacked on every line.
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            scrollRow = 0;
            rebuildList();
        });
        this.addRenderableWidget(search);
        this.setInitialFocus(search);
    }

    private void buildRail() {
        int y = panelY() + HEADER_H + 4;
        String[][] entries = {
                {"Mods", ""},
                {"Move HUD", "hud"},
                {"Item Shower", "itemshower"},
                {"Accounts", "accounts"},
                {"Cosmetica", "cosmetica"},
                {"Appearance", "appearance"},
                {"Diagnostics", "diagnostics"},
                {"Credits", "credits"},
        };

        for (String[] entry : entries) {
            String name = entry[0];
            String opens = entry[1];
            this.addRenderableWidget(new NavButton(
                    panelX() + 6, y, RAIL_W - 12, 22, NavButton.Style.SIDEBAR,
                    () -> name,
                    () -> section.equals(name),
                    () -> open(name, opens)
            ));
            y += 24;
        }
    }

    private void open(String name, String opens) {
        Minecraft mc = Minecraft.getInstance();
        switch (opens) {
            case "hud" -> mc.gui.setScreen(new HudEditorScreen(this));
            case "itemshower" -> mc.gui.setScreen(new ItemShowerScreen(this));
            case "accounts" -> mc.gui.setScreen(new AccountsScreen(this));
            case "cosmetica" -> mc.gui.setScreen(new CosmeticsScreen(this));
            case "appearance" -> mc.gui.setScreen(new AppearanceScreen(this));
            case "diagnostics" -> mc.gui.setScreen(new DiagnosticsScreen(this));
            case "credits" -> mc.gui.setScreen(new CreditsScreen(this));
            default -> {
                section = name;
                this.rebuildWidgets();
            }
        }
    }

    /**
     * Streamer mode, on the right of the panel.
     *
     * Deliberately not in the left rail with the other sections. That rail is a
     * list of places in the menu; this is a switch that changes what the game
     * shows other people, and putting it where a mis-click is unlikely felt
     * worth a little asymmetry.
     */
    private void buildStreamerButton() {
        int w = 96;
        int h = 20;
        int x = contentRight() - PAD - w;
        int y = panelY() + panelH() - FOOTER_H + 5;

        this.addRenderableWidget(new NavButton(
                x, y, w, h, NavButton.Style.CHIP,
                () -> StreamerMode.isOn() ? "Streamer: on" : "Streamer",
                StreamerMode::isOn,
                () -> Minecraft.getInstance().gui.setScreen(new StreamerScreen(this))
        ));
    }

    /**
     * The profile switcher, on the footer's left.
     *
     * One button that cycles rather than a list that opens: there are rarely
     * more than a handful of profiles, and switching is something done between
     * activities rather than browsed. A menu would be three clicks where one
     * does.
     */
    private void buildProfileButton() {
        int w = 104;
        int h = 20;
        int x = contentLeft() + PAD;
        int y = panelY() + panelH() - FOOTER_H + 5;

        this.addRenderableWidget(new NavButton(
                x, y, w, h, NavButton.Style.CHIP,
                () -> "Layout: " + Profiles.active(),
                () -> false,
                () -> {
                    java.util.List<String> all = Profiles.list();
                    int at = all.indexOf(Profiles.active());
                    Profiles.switchTo(all.get((at + 1) % all.size()));
                    this.rebuildWidgets();
                }
        ));
    }

    private void buildChips() {
        int x = contentLeft() + PAD;
        int y = panelY() + HEADER_H;

        x += chip(x, y, "All", null) + 4;
        for (String cat : ModuleCategories.ALL) {
            if (countIn(cat) == 0) continue;   // an empty shelf is just noise
            x += chip(x, y, cat, cat) + 4;
        }

        // Sits apart from the categories because it stacks with them rather
        // than replacing them: HUD plus enabled is a reasonable thing to ask
        String label = "On";
        int w = this.font.width(label) + 16;
        if (x + w <= contentRight() - PAD) {
            this.addRenderableWidget(new NavButton(
                    x, y, w, CHIP_H, NavButton.Style.CHIP,
                    () -> label,
                    () -> onlyEnabled,
                    () -> {
                        onlyEnabled = !onlyEnabled;
                        scrollRow = 0;
                        this.rebuildWidgets();
                    }
            ));
        }
    }

    /** Returns the width used, so chips can sit side by side without a table. */
    private int chip(int x, int y, String label, String value) {
        int w = this.font.width(label) + 16;
        this.addRenderableWidget(new NavButton(
                x, y, w, CHIP_H, NavButton.Style.CHIP,
                () -> label,
                () -> java.util.Objects.equals(category, value),
                () -> {
                    category = value;
                    scrollRow = 0;
                    this.rebuildWidgets();
                }
        ));
        return w;
    }

    /** How many rows fit in the list area, at least one. */
    private int visibleRows() {
        return Math.max(1, (listBottom() - listTop()) / (ROW_H + ROW_GAP));
    }

    private int maxScrollRow() {
        return Math.max(0, shown().size() - visibleRows());
    }

    private void buildList() {
        rows.clear();

        List<Module> modules = shown();
        int left = contentLeft() + PAD;
        int width = contentRight() - PAD - left;
        int step = ROW_H + ROW_GAP;

        scrollRow = Math.max(0, Math.min(scrollRow, maxScrollRow()));

        int first = scrollRow;
        int last = Math.min(modules.size(), first + visibleRows());

        for (int i = first; i < last; i++) {
            Module module = modules.get(i);
            int y = listTop() + (i - first) * step;

            ModRow[] holder = new ModRow[1];
            holder[0] = new ModRow(
                    left, y, width, ROW_H,
                    module::getName,
                    () -> ModuleCategories.of(module),
                    module::isEnabled,
                    module.hasSettings(),
                    () -> {
                        if (holder[0].overGear()) {
                            Minecraft.getInstance().gui.setScreen(new SettingsScreen(
                                    this, module.getName(), module.getDescription(),
                                    ungrouped(module), module.getGroups()));
                            return;
                        }
                        module.toggle();
                        SpaceClient.getConfigManager().save();
                    }
            );
            this.addRenderableWidget(holder[0]);
            rows.add(holder[0]);
        }
    }

    /**
     * Rebuilds only the list, leaving the search field alone.
     *
     * rebuildWidgets would recreate the EditBox mid keystroke, which drops
     * focus and the caret with it - the field would accept one character and
     * then stop.
     */
    private void rebuildList() {
        for (ModRow row : rows) this.removeWidget(row);
        buildList();
    }

    // ---------------- scrolling ----------------

    /**
     * Moves the list by whole rows.
     *
     * One notch, one row. Slightly less fluid than pixel scrolling, and worth
     * it: a row is either in the list or it does not exist, so the list can
     * never spill past the window.
     */
    private boolean scrollBy(double amount) {
        int max = maxScrollRow();
        if (max <= 0) return false;

        int before = scrollRow;
        scrollRow = Math.max(0, Math.min(max, scrollRow - (int) Math.signum(amount)));
        if (scrollRow != before) rebuildList();
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return scrollBy(scrollY);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return scrollBy(amount);
    }

    // ---------------- paint ----------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        float age = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
        // Frame rate independent, so the thumb takes the same time to settle
        // at 30 frames a second as at 240
        scrollShown = Ease.approach(scrollShown, scrollRow, 0.35f, delta);

        int x0 = panelX();
        int y0 = panelY();
        int x1 = x0 + panelW();
        int y1 = y0 + panelH();

        // The chosen background, the same one every other screen draws.
        //
        // This screen used to draw a plain veil instead, on the reasoning that
        // a full screen starfield behind a window looked like the game blanking
        // out. That was solving the wrong problem: the result was a background
        // setting that visibly did nothing anywhere except the Appearance
        // screen where you changed it. Consistency is worth more than that
        // judgement was, and the photographs and Aurora look right behind a
        // window in a way the starfield did not.
        Backdrop.draw(graphics, this.width, this.height);

        // A little extra shade so the panel still separates from a bright
        // photograph behind it
        graphics.fill(0, 0, this.width, this.height, 0x44000000);

        // A soft edge around the panel, drawn as two rings rather than a blur,
        // which this renderer has no cheap way to do
        graphics.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0x30000000);
        graphics.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0x50000000);

        // Opaque base under the panel colour. Theme.CONTENT is 0xE6 alpha, so on
        // its own the world shows faintly through the menu - and anything drawn
        // over it to hide something is equally see through.
        graphics.fill(x0, y0, x1, y1, 0xFF070518);
        graphics.fill(x0, y0, x1, y1, Theme.CONTENT);

        // A light line just inside the top edge and a shadow under the bottom,
        // the same two strokes the HUD plates use. It is a small thing and it
        // is most of what separates a panel from a rectangle.
        graphics.fill(x0 + 2, y0 + 1, x1 - 2, y0 + 2, 0x30FFFFFF);
        graphics.fill(x0 + 2, y1 - 2, x1 - 2, y1 - 1, 0x40000000);
        graphics.fill(x0, y0, x0 + RAIL_W, y1, Theme.SIDEBAR);
        graphics.fill(x0 + RAIL_W, y0, x0 + RAIL_W + 1, y1, Theme.BORDER);

        // Panel outline
        graphics.fill(x0, y0, x1, y0 + 1, Theme.BORDER);
        graphics.fill(x0, y1 - 1, x1, y1, Theme.BORDER);
        graphics.fill(x0, y0, x0 + 1, y1, Theme.BORDER);
        graphics.fill(x1 - 1, y0, x1, y1, Theme.BORDER);

        // Header
        JupiterIcon.draw(graphics, x0 + 12, y0 + 12, 18);
        graphics.text(this.font, "SPACE", x0 + 36, y0 + 12, Theme.accent(), false);
        graphics.text(this.font, "CLIENT", x0 + 36, y0 + 24, Theme.TEXT, false);
        graphics.fill(x0, y0 + HEADER_H - 4, x1, y0 + HEADER_H - 3, Theme.BORDER);

        // Search field: its own frame, since the vanilla border was turned off
        int searchX = contentRight() - PAD - 150;
        int searchY = y0 + 12;
        graphics.fill(searchX - 6, searchY, contentRight() - PAD, searchY + 20, Theme.CARD);
        graphics.fill(searchX - 6, searchY, contentRight() - PAD, searchY + 1, Theme.BORDER);
        graphics.fill(searchX - 6, searchY + 19, contentRight() - PAD, searchY + 20, Theme.BORDER);

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        if (shown().isEmpty()) {
            String empty = query.isEmpty() ? "Nothing here" : "No module matches";
            graphics.text(this.font, empty,
                    contentLeft() + PAD, listTop() + 10, Theme.TEXT_DIM, false);
        }

        int maxRow = maxScrollRow();
        if (maxRow > 0) {
            int trackTop = listTop();
            int trackHeight = listBottom() - trackTop;
            int total = shown().size();
            int thumb = Math.max(20, trackHeight * visibleRows() / Math.max(1, total));
            int travel = trackHeight - thumb;
            int offset = Math.round(travel * (scrollShown / maxRow));
            int x = x1 - 5;
            graphics.fill(x, trackTop, x + 2, trackTop + trackHeight, Theme.CARD);
            graphics.fill(x, trackTop + offset, x + 2, trackTop + offset + thumb, Theme.accent());
        }

        // Footer. The module count used to sit on the left and the hovered
        // description was drawn in the same place, so the two overlapped into
        // an unreadable smear. The count is gone: the list is right there to be
        // counted, and the description is the line worth having.
        graphics.fill(x0 + RAIL_W + 1, y1 - FOOTER_H, x1 - 1, y1 - FOOTER_H + 1, Theme.BORDER);

        String name = Minecraft.getInstance().getUser() != null
                ? Minecraft.getInstance().getUser().getName() : "Player";
        // Left of the streamer button, which now owns the right hand end
        String right = name + "  v" + SpaceClient.VERSION;
        int rightX = contentRight() - PAD - 96 - 10 - this.font.width(right);
        graphics.text(this.font, right, rightX, y1 - 19, Theme.OFF, false);

        // Description of whichever row the pointer is over. Above the footer
        // rather than inside it: the profile button took that line, and a
        // description that appeared over a button would be unreadable and make
        // the button look broken.
        List<Module> modules = shown();
        int step = ROW_H + ROW_GAP;
        int room = contentRight() - PAD - (contentLeft() + PAD);
        for (int i = 0; i < rows.size(); i++) {
            int rowY = listTop() + i * step;
            if (mouseY >= rowY && mouseY < rowY + ROW_H
                    && mouseX >= contentLeft() && mouseX <= contentRight()) {
                int index = scrollRow + i;
                if (index < modules.size()) {
                    String description = modules.get(index).getDescription();
                    graphics.text(this.font,
                            this.font.plainSubstrByWidth(description, room),
                            contentLeft() + PAD, y1 - FOOTER_H - 11, Theme.TEXT_DIM, false);
                }
                break;
            }
        }

        if (age < 1f) {
            // Eased rather than linear: a fade that comes off at a constant
            // rate reads as a cut, because the eye notices the last few percent
            // most and that is exactly where linear spends the least time
            int veil = (int) ((1f - Ease.outCubic(age)) * 255) << 24;
            graphics.fill(0, 0, this.width, this.height, veil);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
