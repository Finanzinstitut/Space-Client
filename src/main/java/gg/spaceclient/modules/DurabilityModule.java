package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.setting.IntSetting;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * How much is left in what you are holding.
 *
 * The armour element already watches what you are wearing. This watches the
 * thing that is doing the damage, which is the one that breaks mid-swing and
 * turns a fight that was going well into punching. The vanilla bar appears at
 * a fifth remaining and is four pixels tall, which is a warning delivered too
 * late and too quietly.
 *
 * Numbers rather than a bar, because "sixty hits left" and "eight hits left"
 * are different decisions and both look like a short orange line.
 */
public class DurabilityModule extends HudModule {

    private final BooleanSetting showOffhand = new BooleanSetting(
            "offhand", "Include offhand", "Watch the offhand item too", true);

    private final BooleanSetting asPercent = new BooleanSetting(
            "percent", "As percent", "Percentage instead of remaining uses", false);

    private final BooleanSetting hideUnbreakable = new BooleanSetting(
            "hide_unbreakable", "Hide undamageable",
            "Say nothing for items that cannot break", true);

    private final IntSetting warnAt = new IntSetting(
            "warn_at", "Warn below", "Percentage at which the number turns red",
            25, 5, 90);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour when durability is fine", 0xFFFFFFFF);

    public DurabilityModule() {
        super("durability", "Durability",
                "Remaining uses of the items in your hands",
                0.02f, 0.62f, false);
        addSettings(showOffhand, asPercent, hideUnbreakable, warnAt, textColor);
    }

    @Override
    protected long refreshMillis() { return 250; }

    private ItemStack held(EquipmentSlot which) {
        try {
            return mc.player == null ? ItemStack.EMPTY : mc.player.getItemBySlot(which);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    private record Reading(String label, int percent) {}

    private java.util.List<Reading> readings() {
        java.util.List<Reading> out = new java.util.ArrayList<>();
        add(out, held(EquipmentSlot.MAINHAND));
        if (showOffhand.get()) add(out, held(EquipmentSlot.OFFHAND));
        return out;
    }

    private void add(java.util.List<Reading> out, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;

        try {
            int max = stack.getMaxDamage();
            if (max <= 0) {
                // A pearl has no durability, and saying so every frame is noise
                if (!hideUnbreakable.get()) out.add(new Reading("--", 100));
                return;
            }

            int left = max - stack.getDamageValue();
            int percent = Math.round(left * 100f / max);
            out.add(new Reading(asPercent.get() ? percent + "%" : Integer.toString(left),
                    percent));

        } catch (Throwable ignored) {
            // Nothing to report for this hand
        }
    }

    private int colourFor(Reading reading) {
        if (reading.percent() <= warnAt.get()) return 0xFFE86A6A;
        if (reading.percent() <= warnAt.get() * 2) return 0xFFE8C46A;
        return textColor.get();
    }

    @Override
    public int getWidth() {
        int width = 0;
        for (Reading reading : readings()) width = Math.max(width, mc.font.width(reading.label()));
        return Math.max(width, 24);
    }

    @Override
    public int getHeight() {
        return Math.max(1, readings().size()) * (mc.font.lineHeight + 1);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        int line = 0;
        for (Reading reading : readings()) {
            graphics.text(mc.font, reading.label(),
                    x, y + line * (mc.font.lineHeight + 1), colourFor(reading), true);
            line++;
        }
    }
}
