package gg.spaceclient.mixin;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.modules.ZoomModule;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Divides the computed field of view by the zoom factor.
 *
 * The target confirmed against Zoomify's own source: the field of view is
 * computed in **`Camera.calculateFov`**, not in `GameRenderer.getFov` as an
 * earlier attempt guessed. `GameRenderer.getFov` does not compute anything
 * itself in this version - it reads the option, which is why writing to that
 * option only ever hit the slider's clamp. Zoomify hooks `calculateFov` on
 * `Camera` for the same reason this now does.
 *
 * The class is named as a string, the method by name only, and the injection
 * requires nothing (`require = 0`), so a signature that has moved is skipped
 * with a log line rather than stopping the game from starting.
 */
@Mixin(targets = "net.minecraft.client.Camera")
public class CameraMixin {

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true, require = 0)
    private void spaceclient$zoomFov(CallbackInfoReturnable<Float> cir) {
        apply(cir);
    }

    /**
     * Some versions split hand and world FOV into a second method; hooking it
     * too keeps the held item in proportion with the zoomed view. A missing
     * target here is harmless - the world FOV hook above is what matters.
     */
    @Inject(method = "calculateHudFov", at = @At("RETURN"), cancellable = true, require = 0)
    private void spaceclient$zoomHudFov(CallbackInfoReturnable<Float> cir) {
        apply(cir);
    }

    private void apply(CallbackInfoReturnable<Float> cir) {
        try {
            ZoomModule zoom = (ZoomModule) SpaceClient.getModuleManager().get("zoom");
            if (zoom == null || !zoom.isEnabled()) return;

            ZoomModule.markMixinActive();

            float factor = zoom.currentFactor();
            if (factor <= 1.001f) return;

            cir.setReturnValue(cir.getReturnValueF() / factor);
        } catch (Throwable ignored) {
            // A misbehaving zoom must never take the renderer down with it
        }
    }
}
