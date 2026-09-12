package gg.spaceclient.mixin;

import gg.spaceclient.access.ItemScaleReport;
import gg.spaceclient.config.ItemSizes;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scales the item held in first person.
 *
 * Easier than the ground case: the stack is right there in the signature, so
 * nothing has to be carried across from anywhere.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    private static final String TARGET =
            "renderItem(Lnet/minecraft/world/entity/LivingEntity;"
            + "Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/item/ItemDisplayContext;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V";

    @Inject(method = TARGET, at = @At("HEAD"))
    private void spaceclient$grow(LivingEntity owner, ItemStack stack,
                                  ItemDisplayContext context, PoseStack poseStack,
                                  SubmitNodeCollector collector, int light,
                                  CallbackInfo ci) {
        ItemScaleReport.sawHand();
        poseStack.pushPose();
        float scale = spaceclient$scaleFor(stack);
        if (scale != 1f) poseStack.scale(scale, scale, scale);
    }

    @Inject(method = TARGET, at = @At("RETURN"))
    private void spaceclient$shrink(LivingEntity owner, ItemStack stack,
                                    ItemDisplayContext context, PoseStack poseStack,
                                    SubmitNodeCollector collector, int light,
                                    CallbackInfo ci) {
        poseStack.popPose();
    }

    private static float spaceclient$scaleFor(ItemStack stack) {
        try {
            return ItemSizes.get(ItemSizes.keyFor(stack)).hand();
        } catch (Throwable ignored) {
            return 1f;
        }
    }
}
