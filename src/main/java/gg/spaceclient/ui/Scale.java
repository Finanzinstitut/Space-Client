package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.lang.reflect.Method;

/**
 * Scales what a HUD element draws.
 *
 * Through reflection, following the rule the rest of this mod uses: anything
 * proven by a successful compile is called directly, anything guessed goes
 * through reflection. This is guessed, for a specific reason - Mojang replaced
 * the GUI's PoseStack with a two dimensional matrix stack a few versions ago,
 * so the calls are either pushPose/popPose with three-argument transforms or
 * pushMatrix/popMatrix with two.
 *
 * The methods are looked up once and remembered. Searching per frame would be
 * wasteful, but the real reason is correctness: reflective calls to void
 * methods return null whether they worked or not, so "did that succeed" cannot
 * be answered after the fact and has to be settled before the first call.
 *
 * A version this does not recognise costs the scaling and nothing else - every
 * element draws at its natural size, exactly as it did before scaling existed.
 */
public final class Scale {

    private static boolean resolved = false;
    private static boolean usable = false;

    private static Method push;
    private static Method pop;
    private static Method translate;
    private static Method scale;

    /** True when the transforms take two arguments rather than three. */
    private static boolean flat = true;

    private static synchronized void resolve(Object pose) {
        if (resolved) return;
        resolved = true;

        Class<?> type = pose.getClass();

        push = find(type, 0, "pushMatrix", "pushPose");
        pop = find(type, 0, "popMatrix", "popPose");

        // Two argument transforms mean the newer flat matrix stack
        translate = find(type, 2, "translate");
        scale = find(type, 2, "scale");
        flat = translate != null && scale != null;

        if (!flat) {
            translate = find(type, 3, "translate");
            scale = find(type, 3, "scale");
        }

        usable = push != null && pop != null && translate != null && scale != null;

        if (!usable) {
            SpaceClient.LOGGER.warn(
                    "HUD scaling unavailable on this version ({}); elements draw at 1x",
                    type.getName());
        }
    }

    private static Method find(Class<?> type, int argCount, String... names) {
        for (String name : names) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == argCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    /**
     * Scales subsequent drawing around a point.
     *
     * Returns whether it worked. When it did, draw from the origin rather than
     * from the point - the translation has already moved there.
     */
    public static boolean push(GuiGraphicsExtractor graphics, int x, int y, float value) {
        if (graphics == null) return false;

        Object pose = gg.spaceclient.util.Reflect.call(graphics, "pose");
        if (pose == null) {
            if (!resolved) {
                resolved = true;
                SpaceClient.LOGGER.warn("HUD scaling unavailable: no pose on the graphics object");
            }
            return false;
        }

        resolve(pose);
        if (!usable) return false;

        try {
            push.invoke(pose);
            // Translate before scaling, so an element grows from its own corner
            // rather than sliding toward the screen's
            if (flat) {
                translate.invoke(pose, (float) x, (float) y);
                scale.invoke(pose, value, value);
            } else {
                translate.invoke(pose, (double) x, (double) y, 0.0d);
                scale.invoke(pose, value, value, 1.0f);
            }
            return true;

        } catch (Throwable t) {
            // Undo the push if the transform failed halfway, or every element
            // drawn afterwards inherits a stack that never came back
            try {
                pop.invoke(pose);
            } catch (Throwable ignored) {
                // Nothing further can be done here
            }
            usable = false;
            return false;
        }
    }

    /**
     * Translates without scaling.
     *
     * Needed by the hotbar case: drawing there uses absolute screen
     * coordinates, so after scaling around an icon's centre the origin has to
     * be moved back or the item lands somewhere else entirely.
     */
    public static void translate(GuiGraphicsExtractor graphics, int x, int y) {
        if (graphics == null || !usable) return;
        Object pose = gg.spaceclient.util.Reflect.call(graphics, "pose");
        if (pose == null) return;
        try {
            if (flat) translate.invoke(pose, (float) x, (float) y);
            else translate.invoke(pose, (double) x, (double) y, 0.0d);
        } catch (Throwable ignored) {
            // The item draws unscaled, which is the same as before
        }
    }

    public static void pop(GuiGraphicsExtractor graphics) {
        if (graphics == null || pop == null) return;
        Object pose = gg.spaceclient.util.Reflect.call(graphics, "pose");
        if (pose == null) return;
        try {
            pop.invoke(pose);
        } catch (Throwable ignored) {
            // A failed pop cannot be recovered from here; the frame is already
            // whatever it is
        }
    }

    private Scale() {}
}
