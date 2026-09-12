package gg.spaceclient.modules;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ModeSetting;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The armour you are wearing, drawn as the items themselves.
 *
 * The durability bar is vanilla's own call, the same one the inventory makes,
 * so its colours and width match what players already read at a glance. A hand
 * drawn bar would have to imitate that and would drift the moment it changed.
 */
public class ArmorModule extends HudModule {

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET,
    };

    /** One inventory slot, plus a little air between them. */
    private static final int ICON = 16;
    private static final int GAP = 4;

    private final ModeSetting display = new ModeSetting(
            "display", "Show", "Durability bar, a percentage, or both",
            List.of("Bar", "Percent", "Both"), "Bar");

    private final ModeSetting direction = new ModeSetting(
            "direction", "Layout", "Side by side or stacked",
            List.of("Horizontal", "Vertical"), "Horizontal");

    private final BooleanSetting showHeld = new BooleanSetting(
            "show_held", "Include held item",
            "Adds whatever is in your main hand", true);

    private final BooleanSetting hideEmpty = new BooleanSetting(
            "hide_empty", "Hide empty slots",
            "Leave out slots with nothing in them", true);

    /** Set once if the item draw call turns out not to exist on this version. */
    private static boolean iconsBroken = false;

    public ArmorModule() {
        super("armor", "Armour", "Your armour and how much of it is left",
                0.88f, 0.40f, false);
        addSettings(display, direction, showHeld, hideEmpty);
    }

    /** Not read every frame, but a swap should not visibly lag either. */
    @Override
    protected long refreshMillis() { return 250; }

    private ItemStack slot(EquipmentSlot which) {
        try {
            return mc.player == null ? null : mc.player.getItemBySlot(which);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The pieces to draw, in the order they are worn. */
    private List<ItemStack> pieces() {
        List<ItemStack> out = new ArrayList<>();
        if (mc.player == null) return out;

        for (EquipmentSlot which : SLOTS) {
            ItemStack stack = slot(which);
            boolean empty = stack == null || stack.isEmpty();
            if (empty && hideEmpty.get()) continue;
            out.add(stack == null ? ItemStack.EMPTY : stack);
        }

        if (showHeld.get()) {
            ItemStack held = slot(EquipmentSlot.MAINHAND);
            boolean empty = held == null || held.isEmpty();
            if (!empty || !hideEmpty.get()) {
                out.add(held == null ? ItemStack.EMPTY : held);
            }
        }
        return out;
    }

    private boolean vertical() { return "Vertical".equals(direction.get()); }
    private boolean wantsBar() { return !"Percent".equals(display.get()); }
    private boolean wantsPercent() { return !"Bar".equals(display.get()); }

    @Override
    public int getWidth() {
        int count = Math.max(1, pieces().size());
        if (vertical()) {
            return wantsPercent() ? ICON + 4 + mc.font.width("100%") : ICON;
        }
        return count * ICON + (count - 1) * GAP;
    }

    @Override
    public int getHeight() {
        int count = Math.max(1, pieces().size());
        if (vertical()) return count * (ICON + GAP) - GAP;
        return ICON + (wantsPercent() ? mc.font.lineHeight : 0);
    }

    private static int percentOf(ItemStack stack) {
        try {
            int max = stack.getMaxDamage();
            if (max <= 0) return -1;
            return Math.round((max - stack.getDamageValue()) * 100f / max);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * White until it matters, then amber, then red.
     *
     * Late on purpose: a warning that starts at half is one people learn to
     * ignore, and the bar already carries the gradual story.
     */
    private static int colorFor(int pct) {
        if (pct < 0) return 0xFFFFFFFF;
        if (pct <= 10) return 0xFFFF5555;
        if (pct <= 30) return 0xFFFFAA00;
        return 0xFFDDDDDD;
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        List<ItemStack> pieces = pieces();
        if (pieces.isEmpty()) return;

        int cx = x;
        int cy = y;

        for (ItemStack stack : pieces) {
            drawPiece(graphics, stack, cx, cy);
            if (vertical()) cy += ICON + GAP;
            else cx += ICON + GAP;
        }
    }

    private void drawPiece(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
        boolean empty = stack == null || stack.isEmpty();

        if (empty) {
            // An empty slot keeps its place, so the row does not reflow every
            // time a piece breaks
            graphics.fill(x, y, x + ICON, y + ICON, 0x33FFFFFF);
        } else if (!iconsBroken) {
            try {
                // Both calls are private on GuiGraphicsExtractor; the invoker
                // mixin is what makes them reachable from here.
                var gui = (gg.spaceclient.mixin.GuiItemInvoker) (Object) graphics;
                gui.spaceclient$item(mc.player, mc.level, stack, x, y, 0);
                if (wantsBar()) gui.spaceclient$itemBar(stack, x, y);
            } catch (Throwable t) {
                iconsBroken = true;
                SpaceClient.LOGGER.warn("Armour icons unavailable on this version", t);
            }
        }

        if (!empty && iconsBroken) {
            // A plate where the icon would have gone, so the element still says
            // something instead of turning into blank space
            graphics.fill(x, y, x + ICON, y + ICON, 0x55FFFFFF);
        }

        if (!wantsPercent()) return;

        int pct = empty ? -1 : percentOf(stack);
        String label = pct < 0 ? "-" : pct + "%";

        if (vertical()) {
            graphics.text(mc.font, label, x + ICON + 4,
                    y + (ICON - mc.font.lineHeight) / 2 + 1, colorFor(pct), true);
        } else {
            graphics.text(mc.font, label,
                    x + (ICON - mc.font.width(label)) / 2, y + ICON, colorFor(pct), true);
        }
    }
}
