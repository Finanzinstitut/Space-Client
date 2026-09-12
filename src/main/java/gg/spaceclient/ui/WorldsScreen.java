package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.util.Reflect;
import gg.spaceclient.util.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The world list as a row of cards rather than a column of rows.
 *
 * A saved world is remembered as a picture long before it is remembered as a
 * name - "the one on the cliff" comes to mind faster than whatever it got
 * called at three in the morning. The game already renders a thumbnail for
 * every world and then shows it at thirty pixels wide next to a wall of text.
 * These cards give it the space to actually be recognised.
 *
 * <h2>Everything here is reflective</h2>
 *
 * Listing worlds, reading a summary and opening one are three separate APIs
 * that no part of this mod has compiled against, and all three have moved
 * around in recent versions. So the whole screen is built on Reflect and
 * degrades in one step: if the list cannot be read it says so and offers the
 * game's own screen, rather than presenting an empty grid that looks like you
 * have lost your saves.
 */
public class WorldsScreen extends Screen {

    private static final int CARD_W = 150;
    private static final int CARD_H = 118;
    private static final int CARD_GAP = 18;

    private final Screen parent;

    /** Built once when the screen opens; reading saves is disk work. */
    private java.util.List<Entry> entries = java.util.List.of();
    private String failure = null;

    /**
     * The leftmost card, counted in whole cards.
     *
     * Whole cards for the same reason the server list steps by whole rows:
     * there is no clip here, so a card sliding half off the edge would draw
     * over the heading and the back button instead of disappearing.
     */
    private int scrollCard = 0;

    private final DragScroll drag = new DragScroll();
    private int dragCarry = 0;

    /** New thumbnails decoded on this frame. */
    private static int texturesThisFrame = 0;

    public WorldsScreen(Screen parent) {
        super(Component.literal("Singleplayer"));
        this.parent = parent;
    }

    /** One saved world, already reduced to what the card draws. */
    private record Entry(String id, String name, String detail, Path icon) {}

    @Override
    protected void init() {
        if (entries.isEmpty() && failure == null) load();

        int total = entries.size() + 1;          // the cards, plus the new one
        int visible = visibleCards();
        scrollCard = Math.max(0, Math.min(maxScrollCard(), scrollCard));

        int shown = Math.min(visible, total - scrollCard);
        int blockWidth = shown * CARD_W + (shown - 1) * CARD_GAP;
        int startX = (this.width - blockWidth) / 2;
        int y = (this.height - CARD_H) / 2;

        for (int slot = 0; slot < shown; slot++) {
            int index = scrollCard + slot;
            int x = startX + slot * (CARD_W + CARD_GAP);

            if (index < entries.size()) {
                Entry entry = entries.get(index);
                this.addRenderableWidget(new WorldCard(
                        x, y, CARD_W, CARD_H,
                        entry.name(), entry.detail(), texture(entry),
                        false, () -> { if (!drag.swallowsClick()) open(entry.id()); }));
            } else {
                this.addRenderableWidget(new WorldCard(
                        x, y, CARD_W, CARD_H,
                        "New world", "", null, true,
                        () -> { if (!drag.swallowsClick()) createWorld(); }));
            }
        }

        this.addRenderableWidget(new FlatButton(
                20, this.height - 36, 100, 22,
                () -> "Back", () -> false, this::onClose).asAction());
    }

    /** How many whole cards fit across, leaving a margin at each edge. */
    private int visibleCards() {
        int room = this.width - 60;
        return Math.max(1, (room + CARD_GAP) / (CARD_W + CARD_GAP));
    }

    private int maxScrollCard() {
        return Math.max(0, entries.size() + 1 - visibleCards());
    }

    private boolean scrollBy(double amount) {
        int max = maxScrollCard();
        if (max <= 0) return false;

        int before = scrollCard;
        scrollCard = Math.max(0, Math.min(max, scrollCard - (int) Math.signum(amount)));
        if (scrollCard != before) this.rebuildWidgets();
        return true;
    }

    // The wheel scrolls a horizontal row sideways here. Turning it is the
    // gesture for "further along the list", whichever way the list runs.
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return scrollBy(scrollY);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return scrollBy(amount);
    }

    private void applyDrag() {
        if (!drag.isDragging()) {
            dragCarry = 0;
            return;
        }
        dragCarry += drag.deltaX();

        int step = CARD_W + CARD_GAP;
        while (Math.abs(dragCarry) >= step) {
            int direction = dragCarry > 0 ? 1 : -1;
            dragCarry -= direction * step;

            int before = scrollCard;
            scrollCard = Math.max(0, Math.min(maxScrollCard(), scrollCard - direction));
            if (scrollCard == before) {
                dragCarry = 0;
                break;
            }
            this.rebuildWidgets();
        }
    }

    // --- reading the saves ---

    private void load() {
        try {
            Object source = Reflect.call(Minecraft.getInstance(), "getLevelSource");
            if (source == null) throw new IllegalStateException("no level source");

            Object candidates = Reflect.call(source, "findLevelCandidates");
            Object future = Reflect.callWith(source, "loadLevelSummaries", candidates);

            Object list = future == null ? null : Reflect.call(future, "join", "get");
            if (!(list instanceof java.util.List<?> summaries)) {
                throw new IllegalStateException("no summaries");
            }

            java.util.List<Entry> out = new java.util.ArrayList<>();
            for (Object summary : summaries) {
                Entry entry = toEntry(summary);
                if (entry != null) out.add(entry);
            }
            entries = out;

        } catch (Throwable t) {
            failure = "Could not read the world list on this version";
            SpaceClient.LOGGER.warn("World list unavailable: {}", String.valueOf(t));
        }
    }

    private Entry toEntry(Object summary) {
        try {
            Object id = Reflect.call(summary, "getLevelId", "getLevelName");
            Object name = Reflect.call(summary, "getLevelName", "getLevelId");
            if (!(id instanceof String levelId)) return null;

            String label = name instanceof String text && !text.isEmpty() ? text : levelId;

            Object played = Reflect.call(summary, "getLastPlayed");
            String detail = "";
            if (played instanceof Number millis && millis.longValue() > 0) {
                detail = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(millis.longValue()));
            }

            Object icon = Reflect.call(summary, "getIcon");
            Path iconPath = icon instanceof Path path && Files.isRegularFile(path) ? path : null;

            return new Entry(levelId, label, detail, iconPath);

        } catch (Throwable ignored) {
            return null;
        }
    }

    // --- thumbnails ---

    private static final java.util.Map<String, Identifier> TEXTURES = new java.util.HashMap<>();

    /**
     * The world's own thumbnail, registered once and remembered.
     *
     * Keyed by world id rather than by image, because unlike a server icon
     * this file changes every time the world is saved - keying by content
     * would register a new texture on every visit.
     */
    private Identifier texture(Entry entry) {
        if (entry.icon() == null) return null;

        Identifier known = TEXTURES.get(entry.id());
        if (known != null) return known;

        // One per frame, for the same reason the server list holds its icons
        // back: reading a PNG off disk and uploading it is not free, and doing
        // it for every world the first time the row draws is a visible stall.
        if (texturesThisFrame >= 1) return null;
        texturesThisFrame++;

        try {
            byte[] bytes = Files.readAllBytes(entry.icon());

            Identifier id = Identifier.fromNamespaceAndPath(
                    SpaceClient.MOD_ID,
                    "world_icon/" + entry.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));

            Identifier registered = TextureLoader.register(bytes, id);
            if (registered != null) TEXTURES.put(entry.id(), registered);
            return registered;

        } catch (Throwable ignored) {
            return null;
        }
    }

    // --- actions ---

    private void open(String levelId) {
        try {
            Object flows = Reflect.call(Minecraft.getInstance(), "createWorldOpenFlows");
            if (flows == null) return;

            // Asked whether it ran rather than what it returned: openWorld is
            // void, so a null answer meant "try the other shape" and the world
            // was opened twice in a row.
            boolean opened = Construct.invokedOn(flows, "openWorld",
                    levelId, (Runnable) () -> Screens.open(this));

            if (!opened) {
                SpaceClient.LOGGER.warn("Could not open the world on this version");
            }

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Could not open the world: {}", String.valueOf(t));
        }
    }

    /**
     * Opens the game's world creator.
     *
     * The first version of this insisted on a two argument openFresh and gave
     * up when it did not find one, which on this version meant the button did
     * nothing at all. Now any shape that can be filled from what is on offer
     * will do, and the names the class actually has are logged when none can -
     * so a miss produces the answer rather than another guess.
     */
    private void createWorld() {
        String className = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen";

        String used = Construct.invokedBest(className,
                new String[]{"openFresh", "createFresh", "openCreateWorldScreen"},
                Minecraft.getInstance(), this);

        if (used != null) {
            SpaceClient.LOGGER.info("World creator opened through {}", used);
            return;
        }

        // Deliberately no constructor fallback. Building this screen directly
        // succeeds and produces something that opens and then ignores every
        // button on it, because the creation context it needs is not something
        // that can be handed over from here - which is worse than saying so.
        SpaceClient.LOGGER.warn("Could not open the world creator: {}",
                Construct.describeStatics(className, null));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        texturesThisFrame = 0;
        drag.update(mouseX, mouseY);
        applyDrag();

        if (!MenuWallpaper.draw(graphics, this.width, this.height, mouseX, mouseY, delta)) {
            Backdrop.draw(graphics, this.width, this.height);
        }
        graphics.fill(0, 0, this.width, this.height, 0x60000000);

        String title = "Singleplayer";
        boolean scaled = Scale.push(graphics, (this.width - this.font.width(title) * 2) / 2, 30, 2f);
        graphics.text(this.font, title,
                scaled ? 0 : (this.width - this.font.width(title) * 2) / 2,
                scaled ? 0 : 30, 0xFFFFFFFF, false);
        if (scaled) Scale.pop(graphics);

        String hint = failure != null
                ? failure
                : maxScrollCard() > 0
                    ? "Pick a world  ·  scroll or drag for more"
                    : "Pick a world, or make a new one";
        graphics.text(this.font, hint,
                (this.width - this.font.width(hint)) / 2, 56,
                failure != null ? 0xFFE8C46A : 0xFFB9B4DC, false);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        Screens.open(parent);
    }
}
