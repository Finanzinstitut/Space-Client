package gg.spaceclient.render;

import com.mojang.blaze3d.vertex.PoseStack;

import org.joml.Quaternionf;

import java.lang.reflect.Method;

/**
 * Rotating a pose stack without naming the method at build time.
 *
 * pushPose, popPose and scale are already compiled against elsewhere in this
 * mod, so they are known good. mulPose is not, and this version has already
 * moved two input methods and a texture class out from under a guess - each
 * costing a build. Looking it up once and keeping the handle costs one
 * indirection per item drawn and cannot fail the compile.
 *
 * Failure is silent and total: no rotation rather than a broken pose. An item
 * that did not turn looks like vanilla, which is a fine thing to fall back to.
 */
public final class PoseOps {

    private static Method rotate = null;
    private static boolean lookedUp = false;
    private static boolean available = false;

    private PoseOps() {}

    /** Whether the rotation could be resolved, for the diagnostics screen. */
    public static boolean canRotate() {
        find();
        return available;
    }

    private static void find() {
        if (lookedUp) return;
        lookedUp = true;

        for (Method method : PoseStack.class.getMethods()) {
            if (!method.getName().equals("mulPose")) continue;
            if (method.getParameterCount() != 1) continue;
            if (!method.getParameterTypes()[0].isAssignableFrom(Quaternionf.class)) continue;

            rotate = method;
            available = true;
            return;
        }
    }

    /**
     * Turns the pose by the given angles, in degrees.
     *
     * Yaw first, then pitch, which is the order that reads as "lay it down
     * facing that way" rather than "tip it over and then spin the floor".
     */
    public static void rotate(PoseStack poseStack, float yawDegrees, float pitchDegrees) {
        find();
        if (!available || poseStack == null) return;

        try {
            Quaternionf turn = new Quaternionf()
                    .rotateY((float) Math.toRadians(yawDegrees))
                    .rotateX((float) Math.toRadians(pitchDegrees));
            rotate.invoke(poseStack, turn);
        } catch (Throwable ignored) {
            // No rotation this frame; the item draws as vanilla would
        }
    }
}
