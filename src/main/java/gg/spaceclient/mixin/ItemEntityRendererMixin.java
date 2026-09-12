package gg.spaceclient.mixin;

import gg.spaceclient.access.ItemIdHolder;
import gg.spaceclient.access.ItemPhysicsHolder;
import gg.spaceclient.access.ItemScaleReport;
import gg.spaceclient.config.ItemSizes;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.item.ItemEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales dropped items.
 *
 * This is the one that matters. When somebody dies in a fight their inventory
 * lands as thirty entities in one heap, and a totem in that heap looks exactly
 * like the cobblestone next to it. Drawing one item type larger turns reading
 * the pile into seeing a shape.
 *
 * The id is captured during extraction, because that is the last point at which
 * the actual stack is in reach - the render state carries a baked model and
 * nothing that says what the item was.
 */
@Mixin(ItemEntityRenderer.class)
public class ItemEntityRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;"
            + "Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
            at = @At("TAIL"))
    private void spaceclient$rememberId(ItemEntity entity, ItemEntityRenderState state,
                                        float partialTicks, CallbackInfo ci) {
        try {
            // Über ItemSizes.keyFor, nicht stack.getDescriptionId(): das gibt
            // es auf 26.2 nicht, die Id sitzt am Item statt am Stack.
            ((ItemIdHolder) (Object) state)
                    .spaceclient$setItemId(ItemSizes.keyFor(entity.getItem()));
        } catch (Throwable ignored) {
            // Without an id the item simply draws at its normal size
        }

        spaceclient$settle(entity, state);
        spaceclient$judge(entity, state);
    }

    /**
     * Decides here whether this item is worth drawing at all.
     *
     * Moved from shouldRender, which never matched: the diagnostics said so
     * plainly - "hook never ran". Rather than guess that method's shape a
     * second time, the decision rides on the one injection in this class whose
     * descriptor is spelled out and proven to apply.
     *
     * The verdict cannot cancel anything from here, so it is carried on the
     * render state and acted on when the item is submitted. That costs the
     * extraction of an item that will not be drawn, which is a fraction of
     * what drawing it costs.
     */
    private void spaceclient$judge(ItemEntity entity, ItemEntityRenderState state) {
        try {
            var manager = gg.spaceclient.SpaceClient.getModuleManager();
            if (manager == null) return;

            var module = manager.get("fpsboost");
            if (!(module instanceof gg.spaceclient.modules.FpsBoostModule boost)) return;

            boolean draw = boost.allowItem(entity);
            ((ItemPhysicsHolder) (Object) state).spaceclient$setSkipped(!draw);

            if (draw) gg.spaceclient.render.CullReport.keptItem();
            else gg.spaceclient.render.CullReport.culledItem();

        } catch (Throwable ignored) {
            // Draw it, as the game would have
        }
    }

    /**
     * Works out how this item is lying, once, while the entity is still here.
     *
     * Both halves have to happen at this point rather than at drawing time.
     * The yaw has to be stable across frames or the item shivers, and the spin
     * can only be cancelled by emptying what it is computed from - which the
     * drawing step has already read by the time it runs.
     */
    private void spaceclient$settle(ItemEntity entity, ItemEntityRenderState state) {
        try {
            var manager = gg.spaceclient.SpaceClient.getModuleManager();
            if (manager == null) return;

            var module = manager.get("itemphysics");
            if (!(module instanceof gg.spaceclient.modules.ItemPhysicsModule physics)) return;
            if (!physics.isEnabled()) return;

            var holder = (ItemPhysicsHolder) (Object) state;

            // Settled means resting on something. An item still falling keeps
            // vanilla's behaviour, because a thing that lies flat in mid air
            // looks more wrong than one that spins.
            Object grounded = gg.spaceclient.util.Reflect.call(entity, "onGround", "isOnGround");
            boolean settled = !(grounded instanceof Boolean flag) || flag;

            holder.spaceclient$setSettled(settled);
            holder.spaceclient$setRestYaw(physics.restYawFor(entity.getId()));

            if (settled && (physics.stopsSpin() || physics.stopsBob())) {
                gg.spaceclient.render.ItemStateFields.still(state);
            }

        } catch (Throwable ignored) {
            // Physics is a nicety; drawing the item is not
        }
    }

    /**
     * Pushed at the top and popped at every exit.
     *
     * The method returns early when the stack is empty, so the pop has to sit
     * on RETURN rather than after the last statement - otherwise that one path
     * would leave the matrix pushed and everything drawn afterwards in the
     * frame would inherit the scale.
     */
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void spaceclient$grow(ItemEntityRenderState state, PoseStack poseStack,
                                  SubmitNodeCollector collector, CameraRenderState camera,
                                  CallbackInfo ci) {
        // Before anything is pushed. Cancelling after a pushPose would leave
        // the matrix stack unbalanced and every later draw inside a transform
        // that was never meant for it - a whole frame ruined to save one item.
        try {
            if (((ItemPhysicsHolder) (Object) state).spaceclient$isSkipped()) {
                ci.cancel();
                return;
            }
        } catch (Throwable ignored) {
            // Draw it
        }

        ItemScaleReport.sawGround();
        poseStack.pushPose();
        float scale = spaceclient$scaleFor(state);
        if (scale != 1f) poseStack.scale(scale, scale, scale);

        // After the scale on purpose: the sink is a distance in world blocks,
        // and applying it inside a scaled pose would bury a large item deeper
        // than a small one.
        spaceclient$lay(state, poseStack);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("RETURN"))
    private void spaceclient$shrink(ItemEntityRenderState state, PoseStack poseStack,
                                    SubmitNodeCollector collector, CameraRenderState camera,
                                    CallbackInfo ci) {
        poseStack.popPose();
    }

    /** Turns a settled item onto its face and lowers it onto the ground. */
    private static void spaceclient$lay(ItemEntityRenderState state, PoseStack poseStack) {
        try {
            var manager = gg.spaceclient.SpaceClient.getModuleManager();
            if (manager == null) return;

            var module = manager.get("itemphysics");
            if (!(module instanceof gg.spaceclient.modules.ItemPhysicsModule physics)) return;
            if (!physics.laysFlat()) return;

            var holder = (ItemPhysicsHolder) (Object) state;
            float yaw = holder.spaceclient$restYaw();

            if (!holder.spaceclient$isSettled()) {
                // Still in the air: a lean rather than a lie, so it reads as
                // tumbling towards the ground instead of sitting on nothing
                float tilt = physics.airTilt();
                if (tilt > 0f) gg.spaceclient.render.PoseOps.rotate(poseStack, yaw, tilt);
                return;
            }

            // Down first, then over. Rotating about the item's own centre and
            // then dropping it keeps the contact point on the floor whichever
            // way it happens to be facing.
            poseStack.translate(0f, -0.18f - physics.sinkDepth(), 0f);
            gg.spaceclient.render.PoseOps.rotate(poseStack, yaw, 90f);

        } catch (Throwable ignored) {
            // The item draws upright, as it always did
        }
    }

    private static float spaceclient$scaleFor(ItemEntityRenderState state) {
        try {
            String id = ((ItemIdHolder) (Object) state).spaceclient$itemId();
            return ItemSizes.get(id).ground();
        } catch (Throwable ignored) {
            return 1f;
        }
    }
}
