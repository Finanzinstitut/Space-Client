package gg.spaceclient.mixin;

import gg.spaceclient.access.ItemScaleReport;
import gg.spaceclient.config.ItemSizes;
import gg.spaceclient.ui.Scale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scales items drawn in the interface, which is mostly the hotbar.
 *
 * The target is the same private `item` method the armour element already
 * borrows through GuiItemInvoker, so its existence and signature are proven by
 * that element working rather than by a guess.
 *
 * Two differences from the world cases. The scaling goes through Scale, because
 * the interface uses a flat matrix stack whose method names changed and that
 * class already resolves them. And it translates to the icon's centre first,
 * so an enlarged item grows around its slot instead of sliding down and right
 * out of it.
 */
@Mixin(GuiGraphicsExtractor.class)
public class GuiItemScaleMixin {

    private static final String TARGET =
            "item(Lnet/minecraft/world/entity/LivingEntity;"
            + "Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/world/item/ItemStack;III)V";

    /** Whether the last call actually scaled, so the pop matches the push. */
    @Unique
    private boolean spaceclient$pushed = false;

    @Inject(method = TARGET, at = @At("HEAD"))
    private void spaceclient$grow(LivingEntity owner, Level level, ItemStack stack,
                                  int x, int y, int seed, CallbackInfo ci) {
        spaceclient$pushed = false;
        ItemScaleReport.sawHotbar();
        try {
            if (stack == null || stack.isEmpty()) return;

            float scale = ItemSizes.get(ItemSizes.keyFor(stack)).hotbar();
            if (scale == 1f) return;

            // Around the middle of the 16 pixel icon rather than its corner
            GuiGraphicsExtractor graphics = (GuiGraphicsExtractor) (Object) this;
            int centreX = x + 8;
            int centreY = y + 8;

            if (Scale.push(graphics, centreX, centreY, scale)) {
                spaceclient$pushed = true;
                // The draw still uses absolute coordinates, so shift back by
                // the centre we just translated to
                Scale.translate(graphics, -centreX, -centreY);
            }
        } catch (Throwable ignored) {
            spaceclient$pushed = false;
        }
    }

    @Inject(method = TARGET, at = @At("RETURN"))
    private void spaceclient$shrink(LivingEntity owner, Level level, ItemStack stack,
                                    int x, int y, int seed, CallbackInfo ci) {
        if (!spaceclient$pushed) return;
        spaceclient$pushed = false;
        Scale.pop((GuiGraphicsExtractor) (Object) this);
    }
}
