package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.config.ItemSizes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-item render sizes.
 *
 * Items are picked from your own inventory rather than from a list of every
 * item in the game. Two reasons: the registry list is two thousand entries deep
 * and nobody scrolls it, and the item you want to configure is almost always
 * one you are holding - you set the totem larger because you just lost a fight
 * over one.
 *
 * Anything already configured stays listed even when it is not in the
 * inventory, so a setting can be found again after it has been made.
 */
public class ItemShowerScreen extends Screen {

    private static final int ROW_H = 22;
    private static final int GAP = 6;
    private static final int LIST_W = 150;
    private static final int PANEL_W = 420;
    private static final int ICON = 16;

    private final Screen parent;

    /** Description id of the item being edited. */
    private String selected = null;

    /** Description id to a stack, for drawing the icon and the name. */
    private final Map<String, ItemStack> known = new LinkedHashMap<>();

    private int scroll = 0;

    private boolean iconsBroken = false;

    public ItemShowerScreen(Screen parent) {
        super(Component.literal("Item Shower"));
        this.parent = parent;
    }

    private int panelLeft() { return (this.width - PANEL_W) / 2; }
    private int listTop() { return 96; }
    private int listBottom() { return this.height - 60; }
    private int visibleRows() { return Math.max(1, (listBottom() - listTop()) / (ROW_H + 2)); }

    /**
     * Everything worth listing: what you are carrying, plus what you have
     * already configured.
     */
    private void collect() {
        known.clear();
        try {
            if (mc.player != null) {
                for (int slot = 0; slot < 41; slot++) {
                    ItemStack stack = mc.player.getInventory().getItem(slot);
                    if (stack == null || stack.isEmpty()) continue;
                    known.putIfAbsent(ItemSizes.keyFor(stack), stack);
                }
            }
        } catch (Throwable ignored) {
            // A missing inventory is not worth a broken screen; the configured
            // list below still gives something to work with
        }

        for (String id : ItemSizes.all().keySet()) {
            known.putIfAbsent(id, ItemStack.EMPTY);
        }
    }

    private List<String> ids() { return new ArrayList<>(known.keySet()); }

    private String labelFor(String id, ItemStack stack) {
        try {
            if (stack != null && !stack.isEmpty()) return stack.getHoverName().getString();
        } catch (Throwable ignored) {
            // Fall through to the id
        }
        // "item.minecraft.totem_of_undying" -> "totem of undying"
        String tail = id.substring(id.lastIndexOf('.') + 1);
        return tail.replace('_', ' ');
    }

    @Override
    protected void init() {
        collect();
        if (selected == null && !known.isEmpty()) selected = ids().get(0);

        int left = panelLeft();
        int y = listTop();

        List<String> ids = ids();
        for (int i = scroll; i < Math.min(ids.size(), scroll + visibleRows()); i++) {
            String id = ids.get(i);
            this.addRenderableWidget(new NavButton(
                    left, y, LIST_W, ROW_H, NavButton.Style.SIDEBAR,
                    () -> labelFor(id, known.get(id)),
                    () -> id.equals(selected),
                    () -> {
                        selected = id;
                        this.rebuildWidgets();
                    }
            ));
            y += ROW_H + 2;
        }

        if (selected != null) buildSliders(left + LIST_W + 20);

        this.addRenderableWidget(new FlatButton(
                left, this.height - 46, 120, ROW_H,
                () -> "Back", () -> false, this::onClose).asAction());
    }

    /**
     * Three sliders, in percent.
     *
     * Percent rather than a multiplier because "150" is a size people can
     * picture and "1.5" is a number they have to convert first.
     */
    private void buildSliders(int left) {
        String id = selected;
        ItemSizes.Sizes sizes = ItemSizes.get(id);
        int y = listTop() + 20;
        int width = PANEL_W - LIST_W - 20;

        this.addRenderableWidget(new SliderRow(left, y, width, ROW_H,
                "In the hotbar", Math.round(sizes.hotbar() * 100), 400, value -> {
            ItemSizes.set(id, ItemSizes.get(id).withHotbar(value / 100f));
            SpaceClient.getConfigManager().save();
        }));
        y += ROW_H + GAP;

        this.addRenderableWidget(new SliderRow(left, y, width, ROW_H,
                "In your hand", Math.round(sizes.hand() * 100), 400, value -> {
            ItemSizes.set(id, ItemSizes.get(id).withHand(value / 100f));
            SpaceClient.getConfigManager().save();
        }));
        y += ROW_H + GAP;

        this.addRenderableWidget(new SliderRow(left, y, width, ROW_H,
                "On the ground", Math.round(sizes.ground() * 100), 400, value -> {
            ItemSizes.set(id, ItemSizes.get(id).withGround(value / 100f));
            SpaceClient.getConfigManager().save();
        }));
        y += ROW_H + GAP * 2;

        this.addRenderableWidget(new FlatButton(left, y, 120, ROW_H,
                () -> "Reset this item", () -> false,
                () -> {
                    ItemSizes.set(id, ItemSizes.Sizes.DEFAULT);
                    SpaceClient.getConfigManager().save();
                    this.rebuildWidgets();
                }).asAction());
    }

    private boolean scrollList(double amount) {
        List<String> ids = ids();
        int max = Math.max(0, ids.size() - visibleRows());
        int before = scroll;
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(amount)));
        if (scroll != before) this.rebuildWidgets();
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return scrollList(scrollY);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return scrollList(amount);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Backdrop.draw(graphics, this.width, this.height);

        int left = panelLeft();
        graphics.fill(left - 18, 24, left + PANEL_W + 18, this.height - 24, Theme.PANEL);

        JupiterIcon.draw(graphics, left, 38, 22);
        graphics.text(this.font, "ITEM SHOWER", left + 32, 40, Theme.CYAN, false);
        graphics.text(this.font, "Make an item bigger so you can find it in a pile",
                left + 32, 52, Theme.TEXT_DIM, false);
        graphics.fill(left, 84, left + PANEL_W, 85, Theme.BORDER);

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        // Icons beside the list rows, drawn after the buttons so they sit on top
        List<String> ids = ids();
        int y = listTop();
        for (int i = scroll; i < Math.min(ids.size(), scroll + visibleRows()); i++) {
            ItemStack stack = known.get(ids.get(i));
            drawIcon(graphics, stack, left + LIST_W - ICON - 4, y + (ROW_H - ICON) / 2);
            y += ROW_H + 2;
        }

        if (selected != null) {
            int right = left + LIST_W + 20;
            graphics.text(this.font, labelFor(selected, known.get(selected)),
                    right, listTop(), Theme.TEXT, false);

            ItemSizes.Sizes sizes = ItemSizes.get(selected);
            String summary = Math.round(sizes.hotbar() * 100) + "%  "
                    + Math.round(sizes.hand() * 100) + "%  "
                    + Math.round(sizes.ground() * 100) + "%";
            graphics.text(this.font, summary, right, this.height - 92, Theme.OFF, false);
        }

        graphics.text(this.font,
                ItemSizes.all().size() + " item(s) configured",
                left, this.height - 92, Theme.TEXT_DIM, false);
    }

    private void drawIcon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) {
            graphics.fill(x, y, x + ICON, y + ICON, 0x22FFFFFF);
            return;
        }
        if (iconsBroken) {
            graphics.fill(x, y, x + ICON, y + ICON, 0x55FFFFFF);
            return;
        }
        try {
            // The same private call the armour element borrows, through the
            // invoker mixin that already exists for it
            var gui = (gg.spaceclient.mixin.GuiItemInvoker) (Object) graphics;
            gui.spaceclient$item(mc.player, mc.level, stack, x, y, 0);
        } catch (Throwable t) {
            iconsBroken = true;
            SpaceClient.LOGGER.warn("Item icons unavailable on this version", t);
        }
    }

    private static final Minecraft mc = Minecraft.getInstance();

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
