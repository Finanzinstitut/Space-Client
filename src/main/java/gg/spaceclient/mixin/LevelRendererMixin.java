package gg.spaceclient.mixin;

import gg.spaceclient.render.HitboxRenderer;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands our hitboxes to the world's geometry collector, after it has gathered
 * everything of its own.
 *
 * This replaces an attempt built on Fabric's world render event plus
 * `LevelRenderer.renderLineBox`. That method does not exist in this version -
 * the render rework moved drawing to a submit based pipeline, so there was
 * nothing to call and the boxes could never have appeared.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "submitFeatures", at = @At("TAIL"), require = 0)
    private void spaceclient$submitHitboxes(LevelRenderState levelRenderState,
                                            SubmitNodeCollector collector,
                                            boolean flag,
                                            CallbackInfo ci) {
        try {
            HitboxRenderer.submit(collector);
            gg.spaceclient.render.BlockHighlightRenderer.submit(collector);
        } catch (Throwable ignored) {
            // A hitbox problem must never take the world renderer down with it
        }
    }
}
