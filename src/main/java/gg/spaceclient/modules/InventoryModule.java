package gg.spaceclient.modules;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * Your inventory, without opening it.
 *
 * The thing this is actually for: in a fight, opening the inventory means
 * standing still with the screen covered, and the two questions people open it
 * to answer - how many totems are left, is there another gapple - take a glance
 * to read and a lifetime to ask for. Showing the grid permanently removes the
 * pause entirely.
 *
 * Only the three storage rows. No avatar, no crafting grid, no armour slots:
 * the avatar and crafting are decoration in this context, and the armour is
 * already its own element. What is left is the part that answers the question.
 *
 * Slots 9 to 35 are those three rows. Slots 0 to 8 are the hotbar, which is
 * already on screen, so it is off by default and available for anyone who
 * would rather have everything in one block.
 */
public class InventoryModule extends HudModule {

    private static final int CELL = 18;
    private static final int ICON = 16;
    private static final int COLUMNS = 9;

    /** The three storage rows, after the hotbar. */
    private static final int STORAGE_FIRST = 9;
    private static final int STORAGE_LAST = 35;

    /** Where the offhand lives in the inventory's own numbering. */
    private static final int OFFHAND_SLOT = 40;

    private final BooleanSetting showHotbar = new BooleanSetting(
            "show_hotbar", "Include hotbar", "Add the hotbar row underneath", false);

    private final BooleanSetting showOffhand = new BooleanSetting(
            "show_offhand", "Include offhand", "Add the offhand slot", true);

    private final BooleanSetting showCounts = new BooleanSetting(
            "show_counts", "Show counts", "Stack sizes in the corner of each slot", true);

    private final BooleanSetting showBars = new BooleanSetting(
            "show_bars", "Show durability", "Damage bars on worn items", true);

    private final BooleanSetting hideEmpty = new BooleanSetting(
            "hide_empty", "Hide empty slots",
            "Draw nothing where a slot is empty, instead of a tile", false);

    private final ColorSetting slotColor = new ColorSetting(
            "slot_color", "Slot colour", "Colour of the empty slot tiles", 0x40FFFFFF);

    private boolean iconsBroken = false;

    public InventoryModule() {
        super("inventory", "Inventory",
                "Your inventory on screen, without opening it",
                0.80f, 0.55f, false);
        addSettings(showHotbar, showOffhand, showCounts, showBars, hideEmpty, slotColor);

        // The tiles are the background; a plate behind them would only add a
        // second edge around the same shape
        setBackgroundEnabled(false);
    }

    /**
     * Four times a second.
     *
     * Nothing here is cached text, but the render itself is twenty seven item
     * draws, and the inventory does not change faster than this is read.
     */
    @Override
    protected long refreshMillis() { return 250; }

    private int rows() {
        int rows = 3;
        if (showHotbar.get()) rows++;
        if (showOffhand.get()) rows++;
        return rows;
    }

    @Override
    public int getWidth() { return COLUMNS * CELL; }

    @Override
    public int getHeight() { return rows() * CELL; }

    private ItemStack slot(int index) {
        try {
            if (mc.player == null) return ItemStack.EMPTY;
            ItemStack stack = mc.player.getInventory().getItem(index);
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        int row = 0;

        for (int index = STORAGE_FIRST; index <= STORAGE_LAST; index++) {
            int offset = index - STORAGE_FIRST;
            int column = offset % COLUMNS;
            row = offset / COLUMNS;
            drawSlot(graphics, slot(index),
                    x + column * CELL, y + row * CELL);
        }
        row++;

        if (showHotbar.get()) {
            for (int index = 0; index < COLUMNS; index++) {
                drawSlot(graphics, slot(index), x + index * CELL, y + row * CELL);
            }
            row++;
        }

        if (showOffhand.get()) {
            // Alone on its row and at the left, so it does not read as part of
            // a nine wide line it is not part of
            drawSlot(graphics, slot(OFFHAND_SLOT), x, y + row * CELL);
        }
    }

    private void drawSlot(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        boolean empty = stack == null || stack.isEmpty();

        if (empty) {
            // The tile stays unless asked otherwise: a grid with holes in it is
            // harder to count across than one with gaps you can see
            if (!hideEmpty.get()) {
                graphics.fill(x, y, x + ICON, y + ICON, slotColor.get());
            }
            return;
        }

        if (!iconsBroken) {
            try {
                // Both calls are private on GuiGraphicsExtractor; the invoker
                // mixin is what makes them reachable, the same way the armour
                // element reaches them
                var gui = (gg.spaceclient.mixin.GuiItemInvoker) (Object) graphics;
                gui.spaceclient$item(mc.player, mc.level, stack, x, y, 0);
                if (showBars.get()) gui.spaceclient$itemBar(stack, x, y);
            } catch (Throwable t) {
                iconsBroken = true;
                SpaceClient.LOGGER.warn("Inventory icons unavailable on this version", t);
            }
        }

        if (iconsBroken) {
            graphics.fill(x, y, x + ICON, y + ICON, 0x55FFFFFF);
        }

        if (!showCounts.get()) return;

        int count = stack.getCount();
        if (count <= 1) return;

        // Drawn here rather than by the game: the invoker exposes the item and
        // the damage bar, and the count belongs to a decorations call that is
        // not among them
        String label = Integer.toString(count);
        int labelX = x + ICON - mc.font.width(label);
        graphics.text(mc.font, label, labelX, y + ICON - 7, 0xFFFFFFFF, true);
    }
}
