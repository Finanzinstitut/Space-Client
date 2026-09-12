package gg.spaceclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Opening a screen and writing a line of chat.
 *
 * Both were renamed in this version, and both are now verified rather than
 * guessed. setScreen moved from Minecraft to Gui - every other screen in this
 * mod already called it that way. displayClientMessage was split in two:
 * the boolean that used to choose between chat and the hotbar overlay is now
 * the method name, so sendSystemMessage is the chat one.
 */
public final class Screens {

    /** Puts a screen on the display. */
    public static void open(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /** Writes one line into the player's own chat. Nothing is sent anywhere. */
    public static void chat(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.sendSystemMessage(Component.literal(text));
    }

    private Screens() {}
}
