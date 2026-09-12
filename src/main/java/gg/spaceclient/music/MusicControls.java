package gg.spaceclient.music;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.modules.MusicModule;
import gg.spaceclient.ui.FlatButton;
import gg.spaceclient.ui.ScreenInjector;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Puts playback buttons over the Now Playing element while the chat is open.
 *
 * The HUD itself cannot take clicks - it draws underneath everything and the
 * mouse is captured by the game. Once a screen has the cursor, though, ordinary
 * widgets work, so three small buttons are added to the chat screen exactly
 * where the element sits. Nothing is drawn there when the module is off or
 * nothing is playing.
 */
public final class MusicControls {
    private static final int BUTTON = 18;
    private static final int GAP = 2;

    public static void attach(Screen screen) {
        MusicModule module = (MusicModule) SpaceClient.getModuleManager().get("music");
        if (module == null || !module.isEnabled()) return;
        if (module.track().isEmpty()) return;
        if (!MusicWatcher.isSupported()) return;

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        int x = module.getX(width);
        int y = module.getY(height);

        // A row just under the element, so it never covers the track name
        int row = y + module.getHeight() + 2;

        ScreenInjector.addWidget(screen, new FlatButton(
                x, row, BUTTON, BUTTON,
                () -> "<",
                () -> false,
                MusicWatcher::previous
        ).asAction());

        ScreenInjector.addWidget(screen, new FlatButton(
                x + BUTTON + GAP, row, BUTTON, BUTTON,
                () -> "||",
                () -> false,
                MusicWatcher::playPause
        ).asAction());

        ScreenInjector.addWidget(screen, new FlatButton(
                x + (BUTTON + GAP) * 2, row, BUTTON, BUTTON,
                () -> ">",
                () -> false,
                MusicWatcher::next
        ).asAction());
    }

    private MusicControls() {}
}
