package gg.spaceclient.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens up the two item drawing calls, which vanilla keeps to itself.
 *
 * Both are private, so the armour element could not simply call them. Copying
 * what they do was the alternative and a poor one: the durability bar has a
 * colour ramp and a width that players read without thinking, and a reimplemented
 * one would look almost right today and wrong after the next change. An invoker
 * borrows the real thing instead.
 */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiItemInvoker {

    @Invoker("item")
    void spaceclient$item(LivingEntity owner, Level level, ItemStack stack,
                          int x, int y, int seed);

    @Invoker("itemBar")
    void spaceclient$itemBar(ItemStack stack, int x, int y);
}
