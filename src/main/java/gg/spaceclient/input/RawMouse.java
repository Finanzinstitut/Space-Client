package gg.spaceclient.input;

import gg.spaceclient.SpaceClient;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWScrollCallbackI;

/**
 * Watches the scroll wheel without taking it away from the game.
 *
 * Minecraft installs its own scroll callback, and replacing it would break the
 * hotbar. GLFW hands back the previous callback when a new one is installed, so
 * ours records the movement and then passes it straight on - unless the zoom is
 * active, in which case the scroll belongs to the zoom and the hotbar is left
 * alone deliberately.
 */
public final class RawMouse {
    private static GLFWScrollCallbackI previous;
    private static GLFWScrollCallback ours;
    private static boolean installed = false;

    /** Accumulated wheel movement since it was last read. */
    private static volatile double pending = 0;

    /** Set by whoever wants the wheel; the game keeps it otherwise. */
    private static volatile boolean capture = false;

    public static void setCapture(boolean value) { capture = value; }
    public static boolean isInstalled() { return installed; }

    /** Wheel movement since the last call, in notches. */
    public static int consumeSteps() {
        double value = pending;
        pending = 0;
        return (int) Math.round(value);
    }

    public static void install() {
        if (installed) return;

        long window = RawKeyboard.windowHandle();
        if (window == 0) {
            // Called every tick until the window exists; saying so once is
            // plenty, and before that it is expected rather than a problem.
            return;
        }

        try {
            ours = new GLFWScrollCallback() {
                @Override
                public void invoke(long handle, double xOffset, double yOffset) {
                    if (capture) {
                        pending += yOffset;
                        // Swallowed on purpose: scrolling while zoomed should
                        // not also cycle the hotbar.
                        return;
                    }
                    if (previous != null) {
                        previous.invoke(handle, xOffset, yOffset);
                    }
                }
            };

            previous = GLFW.glfwSetScrollCallback(window, ours);
            installed = true;
            SpaceClient.LOGGER.info("Scroll wheel hooked, chaining to the game's own handler");

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Could not hook the scroll wheel", t);
        }
    }

    private RawMouse() {}
}
