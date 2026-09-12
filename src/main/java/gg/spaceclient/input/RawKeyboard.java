package gg.spaceclient.input;

import gg.spaceclient.SpaceClient;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

/**
 * Reads the physical keyboard, independent of what each key is bound to.
 *
 * Key bindings are the wrong source for a keyboard display: a player with
 * hotbar slot 5 on F should see F light up when they press F, not see nothing
 * because "F" is not a control the game calls F.
 *
 * GLFW answers that directly, but it needs the window handle, and the accessor
 * for it has moved between versions. The handle is a long on the window object,
 * and it is the only one, so it is found by type through reflection.
 */
public final class RawKeyboard {
    private static long handle = 0;
    private static boolean lookedUp = false;
    private static boolean warned = false;

    /**
     * GLFW must not be touched before the game has started it.
     *
     * Calling into it during mod initialisation queues a "library is not
     * initialised" error that Minecraft finds moments later and turns into a
     * crash before the window even opens. The flag is set from the first client
     * tick, by which point the window certainly exists.
     */
    private static volatile boolean ready = false;

    public static void markReady() {
        ready = true;
    }

    /** The GLFW window, for anything else that needs to talk to the device. */
    public static long windowHandle() {
        return handle();
    }

    private static long handle() {
        if (!ready) return 0;
        if (lookedUp && handle != 0) return handle;
        lookedUp = true;

        // GLFW knows the window it is currently drawing into, which is the one
        // we want. Digging through the Window object for a long field was the
        // roundabout way, and it found nothing on this version.
        try {
            long current = GLFW.glfwGetCurrentContext();
            if (current != 0) {
                handle = current;
                return handle;
            }
        } catch (Throwable ignored) {
            // Falls through to the reflective attempt
        }

        try {
            Object window = Minecraft.getInstance().getWindow();
            if (window == null) return 0;

            for (Field field : window.getClass().getDeclaredFields()) {
                if (field.getType() != long.class) continue;
                field.setAccessible(true);
                long value = field.getLong(window);
                // A window handle is a pointer, never zero
                if (value != 0) {
                    handle = value;
                    return handle;
                }
            }
        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Could not find the window handle", t);
        }
        return 0;
    }

    /** True while the given GLFW key is physically held. */
    public static boolean isDown(int glfwKey) {
        long window = handle();
        if (window == 0) {
            if (!warned) {
                warned = true;
                SpaceClient.LOGGER.warn(
                        "Raw keyboard unavailable - the keyboard view falls back to key bindings");
            }
            return false;
        }
        try {
            return GLFW.glfwGetKey(window, glfwKey) == GLFW.GLFW_PRESS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether raw reading works at all, so callers can fall back. */
    public static boolean isAvailable() {
        return handle() != 0;
    }

    /** True while the given GLFW mouse button is held. */
    public static boolean isMouseDown(int button) {
        long window = handle();
        if (window == 0) return false;
        try {
            return GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Maps a key label from the keyboard layout onto its GLFW code. */
    public static int codeFor(String label) {
        return switch (label) {
            case "A" -> GLFW.GLFW_KEY_A;
            case "B" -> GLFW.GLFW_KEY_B;
            case "C" -> GLFW.GLFW_KEY_C;
            case "D" -> GLFW.GLFW_KEY_D;
            case "E" -> GLFW.GLFW_KEY_E;
            case "F" -> GLFW.GLFW_KEY_F;
            case "G" -> GLFW.GLFW_KEY_G;
            case "H" -> GLFW.GLFW_KEY_H;
            case "I" -> GLFW.GLFW_KEY_I;
            case "J" -> GLFW.GLFW_KEY_J;
            case "K" -> GLFW.GLFW_KEY_K;
            case "L" -> GLFW.GLFW_KEY_L;
            case "M" -> GLFW.GLFW_KEY_M;
            case "N" -> GLFW.GLFW_KEY_N;
            case "O" -> GLFW.GLFW_KEY_O;
            case "P" -> GLFW.GLFW_KEY_P;
            case "Q" -> GLFW.GLFW_KEY_Q;
            case "R" -> GLFW.GLFW_KEY_R;
            case "S" -> GLFW.GLFW_KEY_S;
            case "T" -> GLFW.GLFW_KEY_T;
            case "U" -> GLFW.GLFW_KEY_U;
            case "V" -> GLFW.GLFW_KEY_V;
            case "W" -> GLFW.GLFW_KEY_W;
            case "X" -> GLFW.GLFW_KEY_X;
            // German layout: the key where a US keyboard has Y sits at Z
            case "Y" -> GLFW.GLFW_KEY_Y;
            case "Z" -> GLFW.GLFW_KEY_Z;
            case "1" -> GLFW.GLFW_KEY_1;
            case "2" -> GLFW.GLFW_KEY_2;
            case "3" -> GLFW.GLFW_KEY_3;
            case "4" -> GLFW.GLFW_KEY_4;
            case "5" -> GLFW.GLFW_KEY_5;
            case "6" -> GLFW.GLFW_KEY_6;
            case "7" -> GLFW.GLFW_KEY_7;
            case "8" -> GLFW.GLFW_KEY_8;
            case "9" -> GLFW.GLFW_KEY_9;
            case "0" -> GLFW.GLFW_KEY_0;
            case "TAB" -> GLFW.GLFW_KEY_TAB;
            case "CAPS" -> GLFW.GLFW_KEY_CAPS_LOCK;
            case "SHIFT" -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case "CTRL" -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case "ALT" -> GLFW.GLFW_KEY_LEFT_ALT;
            case "SPACE" -> GLFW.GLFW_KEY_SPACE;
            case "ESC" -> GLFW.GLFW_KEY_ESCAPE;
            case "ENTER" -> GLFW.GLFW_KEY_ENTER;
            case "F1" -> GLFW.GLFW_KEY_F1;
            case "F2" -> GLFW.GLFW_KEY_F2;
            case "F3" -> GLFW.GLFW_KEY_F3;
            case "F4" -> GLFW.GLFW_KEY_F4;
            case "F5" -> GLFW.GLFW_KEY_F5;
            default -> GLFW.GLFW_KEY_UNKNOWN;
        };
    }

    private RawKeyboard() {}
}
