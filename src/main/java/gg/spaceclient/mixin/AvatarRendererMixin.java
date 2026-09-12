package gg.spaceclient.mixin;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives capes motion of their own.
 *
 * All this once did far more - it swapped in shop capes and stamped wings onto
 * the render state. That went with the shop, and none of it is missed here:
 * this steers the three cape angles vanilla already keeps, so it works on any
 * cape at all, whether it came from Mojang or from a cosmetics mod, without
 * knowing or caring where the cloth came from.
 */
@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {

    /**
     * Hooked onto the cape's own extraction, not the general one.
     *
     * The general one is why this never worked. extractRenderState exists three
     * times on this class - once for the avatar, once for the living entity it
     * inherits from, and once for the plain entity - so naming it without a
     * descriptor gave the injector three candidates and no way to choose. With
     * require = 0 that resolved to doing nothing at all, silently, which is
     * exactly what it looked like in game.
     *
     * extractCapeState is a single private method that exists for this and
     * nothing else, so there is nothing to disambiguate. The descriptor is
     * spelled out anyway: the entity parameter is a type variable bounded by
     * Avatar, and erasure turns that into Avatar itself.
     */
    @Inject(
            method = "extractCapeState(Lnet/minecraft/world/entity/Avatar;"
                    + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"),
            require = 0)
    private void spaceclient$waveCape(Avatar avatar,
                                      AvatarRenderState state,
                                      float partialTick,
                                      CallbackInfo ci) {
        try {
            gg.spaceclient.render.CapeReport.ran();
            var manager = gg.spaceclient.SpaceClient.getModuleManager();
            if (manager == null) return;

            var module = manager.get("waveycape");
            if (!(module instanceof gg.spaceclient.modules.WaveyCapeModule wavey)) return;

            float[] shaped = wavey.shape(state.capeFlap, state.capeLean, state.capeLean2);
            if (shaped == null) return;

            state.capeFlap = shaped[0];
            state.capeLean = shaped[1];
            state.capeLean2 = shaped[2];

        } catch (Throwable ignored) {
            // Motion is a nicety; rendering the player is not
        }
    }
}
