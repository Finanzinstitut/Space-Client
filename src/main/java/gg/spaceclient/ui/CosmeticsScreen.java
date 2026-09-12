package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Cosmetics, in Space Client's own interface.
 *
 * Cosmetica stays the account and the catalogue - nothing here stores a cape or
 * talks to a server. What changed is who draws it: the sidebar entry used to
 * hand the player over to Cosmetica's menu, which is a different shape, a
 * different palette and a different set of habits in the middle of a client
 * that is otherwise consistent. Reading its data and drawing it here keeps the
 * player in one interface.
 *
 * The layout deliberately matches SpaceMenuScreen down to the pixel offsets:
 * sidebar, chips, grid, footer. Two screens that are nearly the same are worse
 * than two that are either identical or plainly different.
 */
public class CosmeticsScreen extends Screen {

    private static final int SIDEBAR_W = 148;
    private static final int PAD = 18;
    private static final int CARD_W = 96;
    private static final int CARD_H = 118;
    private static final int GAP = 10;
    private static final int CHIP_H = 22;

    /**
     * Cosmetica requests its outfit thumbnails as an eight frame sheet, so a
     * card that drew the whole texture would show eight tiny players stacked.
     */
    private static final int OUTFIT_FRAMES = 8;

    private enum Tab { OUTFITS, WORN, ACCOUNT }

    private final Screen parent;

    private Tab tab = Tab.OUTFITS;
    private int scroll = 0;
    private int maxScroll = 0;
    private float scrollShown = 0f;

    /** Set once at construction so the fade is not restarted by a rebuild. */
    private final long openedAt = System.currentTimeMillis();

    private final List<CosmeticCard> cards = new ArrayList<>();
    private final List<Integer> cardBaseY = new ArrayList<>();

    /** What the hovered card would do, shown along the bottom. */
    private final List<String> hints = new ArrayList<>();

    public CosmeticsScreen(Screen parent) {
        super(Component.literal("Cosmetica"));
        this.parent = parent;
    }

    private int contentLeft() { return SIDEBAR_W + PAD; }
    private int contentRight() { return this.width - PAD; }
    private int gridTop() { return 108; }
    private int gridBottom() { return this.height - PAD; }

    private int columns() {
        int usable = contentRight() - contentLeft();
        return Math.max(1, (usable + GAP) / (CARD_W + GAP));
    }

    @Override
    protected void init() {
        buildSidebar();
        buildChips();
        buildGrid();
    }

    // ------------------------------------------------------------------
    // sidebar, mirroring the main menu so navigation does not change shape
    // ------------------------------------------------------------------

    private void buildSidebar() {
        int y = 96;
        String[] entries = {"Mods", "Move HUD", "Accounts", "Cosmetica", "Appearance", "Diagnostics"};

        for (String entry : entries) {
            String name = entry;
            this.addRenderableWidget(new NavButton(
                    0, y, SIDEBAR_W, 26, NavButton.Style.SIDEBAR,
                    () -> name,
                    () -> name.equals("Cosmetica"),
                    () -> jump(name)
            ));
            y += 28;
        }
    }

    private void jump(String name) {
        Minecraft mc = Minecraft.getInstance();
        switch (name) {
            case "Cosmetica" -> { /* already here */ }
            case "Move HUD" -> mc.gui.setScreen(new HudEditorScreen(this));
            case "Accounts" -> mc.gui.setScreen(new AccountsScreen(this));
            case "Appearance" -> mc.gui.setScreen(new AppearanceScreen(this));
            case "Diagnostics" -> mc.gui.setScreen(new DiagnosticsScreen(this));
            default -> mc.gui.setScreen(new SpaceMenuScreen());
        }
    }

    // ------------------------------------------------------------------
    // chips
    // ------------------------------------------------------------------

    private void buildChips() {
        int x = contentLeft();
        int y = 74;
        x += chip(x, y, "Outfits", Tab.OUTFITS) + 6;
        x += chip(x, y, "Worn", Tab.WORN) + 6;
        chip(x, y, "Account", Tab.ACCOUNT);
    }

    private int chip(int x, int y, String label, Tab value) {
        int w = this.font.width(label) + 22;
        this.addRenderableWidget(new NavButton(
                x, y, w, CHIP_H, NavButton.Style.CHIP,
                () -> label,
                () -> tab == value,
                () -> {
                    tab = value;
                    scroll = 0;
                    this.rebuildWidgets();
                }
        ));
        return w;
    }

    // ------------------------------------------------------------------
    // grid
    // ------------------------------------------------------------------

    /** One card's worth of description, gathered while the grid is built. */
    private record Entry(String label, String slot, Identifier texture, int frames,
                         boolean selected, boolean dim, String hint, Runnable action) {}

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>();

        if (!CosmeticaBridge.installed()) return out;

        switch (tab) {
            case OUTFITS -> {
                String worn = CosmeticaBridge.selectedOutfitId();
                for (CosmeticaBridge.OutfitRef outfit : CosmeticaBridge.outfits()) {
                    boolean selected = !outfit.id().isEmpty() && outfit.id().equals(worn);
                    out.add(new Entry(
                            outfit.name(),
                            selected ? "Worn" : null,
                            outfit.thumbnail(),
                            OUTFIT_FRAMES,
                            selected,
                            !outfit.usable(),
                            outfit.usable()
                                    ? (selected ? "Already worn" : "Click to wear this outfit")
                                    : "Not available on your plan",
                            () -> {
                                if (!outfit.usable() || selected) return;
                                CosmeticaBridge.equip(outfit);
                            }));
                }
                out.add(new Entry("New outfit", "Create", null, 1, false, false,
                        "Opens Cosmetica's outfit builder",
                        CosmeticaBridge::openNewOutfit));
                if (!worn.isEmpty()) {
                    out.add(new Entry("Take off", "Clear", null, 1, false, false,
                            "Wear no outfit at all",
                            CosmeticaBridge::clearOutfit));
                }
            }
            case WORN -> {
                for (CosmeticaBridge.Worn item : CosmeticaBridge.worn()) {
                    out.add(new Entry(item.name(), item.slot(), item.thumbnail(), 1,
                            true, false,
                            "Worn in the " + item.slot().toLowerCase() + " slot",
                            CosmeticaBridge::openHome));
                }
                out.add(new Entry("Browse", "Catalogue", null, 1, false, false,
                        "Opens Cosmetica's catalogue to add cosmetics",
                        CosmeticaBridge::openBrowse));
                out.add(new Entry("Nametag", "Style", null, 1, false, false,
                        "Opens Cosmetica's nametag styling",
                        CosmeticaBridge::openNametag));
            }
            case ACCOUNT -> {
                out.add(new Entry("Cosmetica menu", "Open", null, 1, false, false,
                        "Cosmetica's own interface, with everything Space Client does not mirror",
                        CosmeticaBridge::openHome));
                out.add(new Entry("Outfit list", "Manage", null, 1, false, false,
                        "Cosmetica's outfit screen, where outfits can be deleted",
                        CosmeticaBridge::openOutfits));
                out.add(new Entry("Web panel", "Browser", null, 1, false, false,
                        "Opens cosmetica.cc in your browser",
                        () -> CosmeticaBridge.openWebPanel("")));
                out.add(new Entry("Refresh", "Reload", null, 1, false, false,
                        "Fetches your outfits from Cosmetica again",
                        () -> {
                            CosmeticaBridge.refresh();
                            this.rebuildWidgets();
                        }));
            }
        }
        return out;
    }

    private void buildGrid() {
        cards.clear();
        cardBaseY.clear();
        hints.clear();

        List<Entry> entries = entries();
        int cols = columns();
        int left = contentLeft();
        int top = gridTop() - scroll;

        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int x = left + (i % cols) * (CARD_W + GAP);
            int y = top + (i / cols) * (CARD_H + GAP);

            CosmeticCard card = new CosmeticCard(
                    x, y, CARD_W, CARD_H,
                    entry::label,
                    entry::slot,
                    entry::texture,
                    entry::selected,
                    entry.frames(),
                    entry.dim(),
                    () -> {
                        entry.action().run();
                        // The list can change shape after an action, and a
                        // rebuild is cheap here because there is no scroll
                        // animation to interrupt on a click.
                        this.rebuildWidgets();
                    });

            this.addRenderableWidget(card);
            cards.add(card);
            cardBaseY.add(gridTop() + (i / cols) * (CARD_H + GAP));
            hints.add(entry.hint());
        }

        int rows = (entries.size() + cols - 1) / cols;
        int needed = rows * (CARD_H + GAP);
        int room = gridBottom() - gridTop();
        maxScroll = Math.max(0, needed - room);
        scroll = Math.min(scroll, maxScroll);
    }

    // ------------------------------------------------------------------
    // scrolling, handled the same defensive way as the main menu
    // ------------------------------------------------------------------

    private boolean scrollBy(double amount) {
        if (maxScroll <= 0) return false;
        int before = scroll;
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (amount * 24)));
        if (scroll != before) reposition();
        return true;
    }

    private void reposition() {
        for (int i = 0; i < cards.size() && i < cardBaseY.size(); i++) {
            cards.get(i).setY(cardBaseY.get(i) - scroll);
        }
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return scrollBy(scrollY);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return scrollBy(amount);
    }

    // ------------------------------------------------------------------
    // drawing
    // ------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Backdrop.draw(graphics, this.width, this.height);

        float age = Math.min(1f, (System.currentTimeMillis() - openedAt) / 180f);
        scrollShown += (scroll - scrollShown) * 0.35f;

        graphics.fill(0, 0, SIDEBAR_W, this.height, Theme.SIDEBAR);
        graphics.fill(SIDEBAR_W, 0, this.width, this.height, Theme.CONTENT);
        graphics.fill(SIDEBAR_W, 0, SIDEBAR_W + 1, this.height, Theme.BORDER);

        JupiterIcon.draw(graphics, 16, 24, 22);
        graphics.text(this.font, "SPACE", 46, 24, Theme.accent(), false);
        graphics.text(this.font, "CLIENT", 46, 36, Theme.TEXT, false);
        graphics.fill(16, 60, SIDEBAR_W - 16, 61, Theme.BORDER);
        graphics.text(this.font, "MENU", 16, 76, Theme.TEXT_DIM, false);

        String name = Minecraft.getInstance().getUser() != null
                ? Minecraft.getInstance().getUser().getName() : "Player";
        graphics.fill(16, this.height - 44, SIDEBAR_W - 16, this.height - 43, Theme.BORDER);
        graphics.text(this.font, name, 16, this.height - 34, Theme.TEXT, false);
        graphics.text(this.font, "v" + SpaceClient.VERSION, 16, this.height - 22,
                Theme.TEXT_DIM, false);

        graphics.text(this.font, "Cosmetica", contentLeft(), 30, Theme.TEXT, false);
        graphics.text(this.font, status(), contentLeft(), 44, statusColor(), false);

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // Strips above and below hide the rows that scroll past the grid
        graphics.fill(SIDEBAR_W + 1, 0, this.width, gridTop() - 4, Theme.CONTENT);
        graphics.fill(SIDEBAR_W + 1, gridBottom(), this.width, this.height, Theme.CONTENT);

        if (!CosmeticaBridge.installed()) {
            drawMissing(graphics);
        } else if (cards.isEmpty()) {
            graphics.text(this.font, emptyLine(), contentLeft(), gridTop() + 10,
                    Theme.TEXT_DIM, false);
        }

        if (maxScroll > 0) {
            int trackTop = gridTop();
            int trackBottom = gridBottom();
            int trackHeight = trackBottom - trackTop;
            int thumb = Math.max(24, trackHeight * trackHeight / (trackHeight + maxScroll));
            int travel = trackHeight - thumb;
            int offset = maxScroll == 0 ? 0 : Math.round(travel * (scrollShown / maxScroll));
            int x = this.width - 6;
            graphics.fill(x, trackTop, x + 3, trackBottom, Theme.CARD);
            graphics.fill(x, trackTop + offset, x + 3, trackTop + offset + thumb, Theme.accent());
        }

        drawHint(graphics, mouseX, mouseY);

        if (age < 1f) {
            int veil = (int) ((1f - age) * 255) << 24;
            graphics.fill(0, 0, this.width, this.height, veil);
        }
    }

    private String status() {
        if (!CosmeticaBridge.installed()) {
            return CosmeticaBridge.modLoaded()
                    ? "Cosmetica is installed but does not answer"
                    : "Cosmetica is not installed";
        }
        String note = CosmeticaBridge.note();
        if (note != null) return note;
        return CosmeticaBridge.authenticated()
                ? "Signed in - cosmetics follow you onto any server"
                : "Signed out - showing the last cosmetics that were cached";
    }

    private int statusColor() {
        if (!CosmeticaBridge.installed() || CosmeticaBridge.note() != null) return 0xFFFF8A7A;
        return CosmeticaBridge.authenticated() ? Theme.CYAN : Theme.TEXT_DIM;
    }

    private String emptyLine() {
        return switch (tab) {
            case OUTFITS -> "No outfits yet. Make one and it will appear here.";
            case WORN -> "Nothing worn right now.";
            case ACCOUNT -> "";
        };
    }

    /** Says which of the two failures happened, rather than showing an empty grid. */
    private void drawMissing(GuiGraphicsExtractor graphics) {
        boolean loaded = CosmeticaBridge.modLoaded();
        String[] lines = loaded
                ? new String[]{
                        "Cosmetica is installed, but this build could not reach it.",
                        "",
                        "Space Client reads Cosmetica's own data rather than keeping",
                        "its own, and the classes it looks for belong to Cosmetica 2",
                        "for 26.2. A different version will not answer.",
                        "",
                        "Send the Cosmetica version along and this can be pointed at",
                        "the right names.",
                }
                : new String[]{
                        "Cosmetica is not installed.",
                        "",
                        "Space Client uses Cosmetica for capes, wings, bandanas and",
                        "horns rather than shipping its own. Cosmetics live on your",
                        "Cosmetica account, so they follow you onto any server.",
                        "",
                        "Install it from the launcher's Mods tab, or tick the box",
                        "when creating an instance.",
                };

        int y = gridTop();
        for (String line : lines) {
            if (!line.isEmpty()) {
                graphics.text(this.font, line, contentLeft(), y,
                        line.startsWith("Cosmetica is") ? Theme.TEXT : Theme.TEXT_DIM, false);
            }
            y += 14;
        }
    }

    /** The hovered card's explanation, along the bottom edge. */
    private void drawHint(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int cols = columns();
        for (int i = 0; i < hints.size(); i++) {
            int x = contentLeft() + (i % cols) * (CARD_W + GAP);
            int y = gridTop() - scroll + (i / cols) * (CARD_H + GAP);
            if (mouseX >= x && mouseX <= x + CARD_W && mouseY >= y && mouseY <= y + CARD_H
                    && mouseY >= gridTop() - 4 && mouseY <= gridBottom()) {
                graphics.text(this.font, hints.get(i), contentLeft(), this.height - 12,
                        Theme.TEXT_DIM, false);
                return;
            }
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
