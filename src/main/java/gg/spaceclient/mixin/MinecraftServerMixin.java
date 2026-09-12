package gg.spaceclient.mixin;

import gg.spaceclient.host.WorldHost;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets accounts without a Mojang session join a hosted world.
 *
 * Two separate checks have to give way, and they are not the same thing:
 *
 *   usesAuthentication  - whether Mojang is asked to confirm who is connecting
 *   enforceSecureProfile - whether a signed chat key is required
 *
 * The second one alone is what produces "Invalid signature for profile public
 * key". An offline account has no signed key at all, so both have to go for it
 * to get in.
 *
 * Both injections are require = 0. If either name is wrong on this version the
 * build still succeeds and the switch simply does nothing, rather than taking
 * the whole mod down with it.
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    @Inject(method = "usesAuthentication", at = @At("HEAD"),
            cancellable = true, require = 0)
    private void spaceclient$allowOfflineAuth(CallbackInfoReturnable<Boolean> info) {
        if (WorldHost.allowsOffline()) info.setReturnValue(false);
    }

    @Inject(method = "enforceSecureProfile", at = @At("HEAD"),
            cancellable = true, require = 0)
    private void spaceclient$allowUnsignedChat(CallbackInfoReturnable<Boolean> info) {
        if (WorldHost.allowsOffline()) info.setReturnValue(false);
    }
}
