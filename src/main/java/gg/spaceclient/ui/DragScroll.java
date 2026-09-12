package gg.spaceclient.ui;

import gg.spaceclient.input.RawKeyboard;

import org.lwjgl.glfw.GLFW;

/**
 * Scrolling a list by holding the left button and pulling.
 *
 * Deliberately built on GLFW rather than on the screen's own drag callback.
 * That callback changed shape in this version along with the rest of the input
 * API, and the last attempt to name one of those signatures cost a build. The
 * window handle and the button state come from LWJGL, which has not moved, and
 * the cursor position is already handed to every draw call - so the whole
 * gesture can be assembled from things that are known to work.
 *
 * <h2>Telling a drag from a click</h2>
 *
 * Both start the same way, so nothing is a drag until the pointer has moved
 * far enough to rule out a shaky click. Once it has, the press that follows is
 * swallowed for a moment after release: letting go at the end of a pull
 * otherwise lands as a click on whatever row happened to be under the cursor,
 * which is how you end up joining a server you were only scrolling past.
 */
public final class DragScroll {

    /** How far the pointer must travel before a press counts as a pull. */
    private static final int SLOP = 5;

    /** How long after a pull a press is still ignored. */
    private static final long SETTLE_MS = 180;

    private boolean held = false;
    private boolean dragging = false;

    private int startX = 0;
    private int startY = 0;
    private int lastX = 0;
    private int lastY = 0;

    private long releasedAt = 0L;

    /** How far the pointer moved since the last frame, once dragging. */
    private int deltaX = 0;
    private int deltaY = 0;

    /**
     * Reads the button and the cursor for this frame.
     *
     * Call once per draw, before asking for the deltas.
     */
    public void update(int mouseX, int mouseY) {
        deltaX = 0;
        deltaY = 0;

        boolean down = isLeftDown();

        if (down && !held) {
            held = true;
            dragging = false;
            startX = mouseX;
            startY = mouseY;
            lastX = mouseX;
            lastY = mouseY;
            return;
        }

        if (!down) {
            if (dragging) releasedAt = System.currentTimeMillis();
            held = false;
            dragging = false;
            return;
        }

        if (!dragging) {
            if (Math.abs(mouseX - startX) < SLOP && Math.abs(mouseY - startY) < SLOP) return;
            dragging = true;
            // Counted from where the slop was crossed, not from the press, so
            // the list does not jump five pixels the moment it starts
            lastX = mouseX;
            lastY = mouseY;
        }

        deltaX = mouseX - lastX;
        deltaY = mouseY - lastY;
        lastX = mouseX;
        lastY = mouseY;
    }

    public int deltaX() { return deltaX; }

    public int deltaY() { return deltaY; }

    public boolean isDragging() { return dragging; }

    /** True while a press should be ignored because it ended a pull. */
    public boolean swallowsClick() {
        return dragging || System.currentTimeMillis() - releasedAt < SETTLE_MS;
    }

    private static boolean isLeftDown() {
        try {
            long window = RawKeyboard.windowHandle();
            if (window == 0) return false;
            return GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                    == GLFW.GLFW_PRESS;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
