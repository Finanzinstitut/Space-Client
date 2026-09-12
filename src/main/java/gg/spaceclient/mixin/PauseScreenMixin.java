package gg.spaceclient.mixin;

import gg.spaceclient.ui.FlatButton;
import gg.spaceclient.ui.HostWorldScreen;
import gg.spaceclient.util.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a Host World button to the top right of the pause menu.
 *
 * Placed at an absolute position rather than added to the vanilla grid on
 * purpose. The pause menu is built through a GridLayout whose rows are laid out
 * before this runs, so inserting into it would mean rebuilding the layout and
 * moving every vanilla button. A corner button touches none of that.
 *
 * The mixin extends Screen so addRenderableWidget is reachable - it is
 * protected on Screen, and PauseScreen is a Screen.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void spaceclient$hostButton(CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getInstance();

            // Only a singleplayer world can be hosted. On a real server this
            // button would be meaningless, so it is simply not there.
            if (!mc.hasSingleplayerServer()) return;

            this.addRenderableWidget(new FlatButton(
                    this.width - 108, 8, 100, 20,
                    () -> gg.spaceclient.host.WorldHost.isHosting()
                            ? "Hosting" : "Host World",
                    () -> gg.spaceclient.host.WorldHost.isHosting(),
                    () -> Screens.open(new HostWorldScreen(this))));

        } catch (Throwable ignored) {
            // A missing button is better than an unopenable pause menu
        }
    }
}
