package gg.spaceclient;

import gg.spaceclient.config.ClientSettings;
import gg.spaceclient.config.ConfigManager;
import gg.spaceclient.module.HudModule;
import gg.spaceclient.module.ModuleManager;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import com.mojang.blaze3d.platform.InputConstants;


import gg.spaceclient.music.MusicControls;
import gg.spaceclient.session.SessionWatcher;
import gg.spaceclient.ui.AccountsScreen;
import gg.spaceclient.ui.FlatButton;
import gg.spaceclient.ui.ScreenInjector;
import gg.spaceclient.ui.SpaceMenuScreen;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpaceClient implements ClientModInitializer {
    public static final String MOD_ID = "spaceclient";
    /**
     * Read from the jar's own metadata rather than typed in here.
     *
     * It was a hardcoded "0.1.0" through every release since, so the menu
     * footer reported the same number no matter which build was running - which
     * makes the one question worth asking when something is missing ("am I even
     * running the new jar?") impossible to answer from inside the game.
     */
    public static final String VERSION = resolveVersion();

    private static String resolveVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer(MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("dev");
        } catch (Throwable ignored) {
            // A wrong version string is never worth failing to start over
            return "dev";
        }
    }
    public static final Logger LOGGER = LoggerFactory.getLogger("Space Client");

    private static ModuleManager moduleManager;
    private static ConfigManager configManager;
    private static ClientSettings settings;
    private static KeyMapping menuKey;
    /**
     * Minecraft has no default binding for the middle mouse button, so the
     * Mouse Tracker cannot read it without one. Registering it as a MOUSE-type
     * mapping also lets the player rebind it in the vanilla controls screen.
     */
    private static KeyMapping middleClickKey;
    private static KeyMapping zoomKey;

    public static ModuleManager getModuleManager() { return moduleManager; }
    public static ConfigManager getConfigManager() { return configManager; }
    public static ClientSettings getSettings() { return settings; }

    public static KeyMapping getZoomKey() { return zoomKey; }

    /** True while the middle mouse button is held. */
    public static boolean isMiddleClickDown() {
        return middleClickKey != null && middleClickKey.isDown();
    }

    @Override
    public void onInitializeClient() {
        moduleManager = new ModuleManager();
        settings = new ClientSettings();
        configManager = new ConfigManager();
        configManager.load();

        // Registered before anything can join a world, which is the only
        // requirement: payload types must exist on both ends before a handler
        // is attached.
        gg.spaceclient.net.Handshake.register();

        // Key mappings now take a registered Category object rather than a
        // translation key string.
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spaceclient.menu",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_RSHIFT,
                category
        ));

        middleClickKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spaceclient.middleclick",
                InputConstants.Type.MOUSE,
                2, // GLFW middle mouse button
                category
        ));

        zoomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spaceclient.zoom",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_C,
                category
        ));

        // A button on the server list, where switching accounts is actually
        // needed - the in-game menu is out of reach from the main menu.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // Playback controls belong on the chat screen, where the cursor is
            // free and the HUD element is still visible behind it.
            if (screen instanceof ChatScreen) {
                MusicControls.attach(screen);
                return;
            }

            // The resource pack screen reloads the repository while it builds
            // itself, which un-pins the font pack a moment before the list of
            // packs is drawn from it - so waiting for the next tick would mean
            // the screen shows the font as an ordinary, removable pack. Pinning
            // here and rebuilding the list is what closes that gap.
            //
            // Matched on the class name rather than an import: this screen is
            // not touched anywhere else in the mod, and a name that no longer
            // exists should quietly match nothing rather than fail the build.
            if (screen.getClass().getName().endsWith("PackSelectionScreen")) {
                gg.spaceclient.font.FontPacks.harden();
                gg.spaceclient.util.Reflect.call(screen, "updateList", "populateLists");
                return;
            }

            // The title screen is replaced rather than decorated, so it is
            // matched by name: TitleScreen is not imported anywhere else here,
            // and a class that no longer exists should quietly match nothing
            // instead of failing the build.
            //
            // Swapping the screen from inside AFTER_INIT is safe because the
            // replacement is not a TitleScreen, so the event fires once for
            // the original and never again for ours.
            if (screen.getClass().getName().endsWith("TitleScreen")
                    && settings.customMenu()) {
                client.gui.setScreen(new gg.spaceclient.ui.MainMenuScreen());
                return;
            }

            // The disconnect screen is where a failed login actually leaves
            // you, so that is where the retry belongs. Large networks put a
            // proxy in front of several backends, and when one is out of step
            // the login fails on that one and succeeds on the next - which is
            // why pressing this two or three times works where the first
            // attempt did not.
            if (screen.getClass().getName().endsWith("DisconnectedScreen")
                    && settings.customMenu()
                    && gg.spaceclient.ui.ServersScreen.hasLastServer()) {
                ScreenInjector.addWidget(screen, new FlatButton(
                        screen.width / 2 - 100, screen.height / 2 + 40, 200, 20,
                        () -> "Reconnect to "
                                + gg.spaceclient.ui.ServersScreen.lastServerName(),
                        () -> false,
                        gg.spaceclient.ui.ServersScreen::reconnectLast
                ).asAction());
                return;
            }

            // The disconnect screen goes back to the game's server list, not
            // to wherever you left from, so returning from a server dropped
            // you into vanilla's. Catching that screen wherever it appears is
            // steadier than trying to redirect the disconnect itself - which
            // sits behind an API this mod has never compiled against.
            if (screen instanceof JoinMultiplayerScreen
                    && settings.customMenu()
                    && gg.spaceclient.ui.ServersScreen.shouldReplaceVanillaList()) {
                client.gui.setScreen(new gg.spaceclient.ui.ServersScreen(
                        new gg.spaceclient.ui.MainMenuScreen()));
                return;
            }

            if (!(screen instanceof JoinMultiplayerScreen)) return;
            ScreenInjector.addWidget(screen, new FlatButton(
                    10, 10, 116, 20,
                    () -> "Space Client",
                    () -> false,
                    () -> client.gui.setScreen(new AccountsScreen(screen))
            ).asAction());
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // From here on the window exists, so GLFW is safe to talk to
            gg.spaceclient.input.RawKeyboard.markReady();

            while (menuKey.consumeClick()) {
                client.gui.setScreen(new SpaceMenuScreen());
            }
            moduleManager.onTick();
            SessionWatcher.tick(client);

            // Driven here rather than from the module, because a module only
            // ticks while it is enabled - and the entry still has to be taken
            // down when the setting goes off.
            gg.spaceclient.net.NowPlayingShare.tick();

            // Separate from the above on purpose: the badge is not a module
            // and has no setting behind it, so it ticks whenever the game is
            // in a world. Its own timers keep it to a couple of calls an hour.
            gg.spaceclient.net.Presence.tick();
            gg.spaceclient.net.Twitch.tick();

            // The window only exists once the game is running, so the hook is
            // installed on the first tick rather than during initialisation.
            gg.spaceclient.input.RawMouse.install();

            // Same reasoning one step further: the pack repository is not
            // necessarily built during initialisation. This runs once and
            // usually does nothing, because Minecraft has already restored the
            // font pack from its own options.
            gg.spaceclient.font.FontPacks.sync();

            // Every reload of the pack repository - F3+T, another mod, the
            // resource pack screen opening - throws away the pack objects and
            // builds new ones, which loses the lock on the font pack. This puts
            // it back. In the settled case it is a pointer comparison.
            gg.spaceclient.font.FontPacks.harden();
        });

        // Our elements draw just before the chat, so the HUD API handles layering.
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "hud"),
                SpaceClient::renderHud
        );

        LOGGER.info("Space Client {} ready", VERSION);
    }

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();

        for (HudModule module : moduleManager.getHudModules()) {
            if (!module.isEnabled()) continue;
            module.draw(graphics, module.getX(width), module.getY(height));
        }
    }
}
