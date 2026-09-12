package gg.spaceclient.render;

/**
 * Says whether the cape hook is actually attached.
 *
 * Worth its own class because the failure here was invisible for weeks. The
 * injection named a method that exists three times, the injector could not
 * choose, and the mod was configured not to complain - so the cape simply
 * never moved and nothing anywhere said why. A hook that reports having run is
 * the difference between "capes are broken" and knowing which half to look at.
 */
public final class CapeReport {

    private static boolean everRan = false;

    private CapeReport() {}

    public static void ran() { everRan = true; }

    public static boolean hooked() { return everRan; }

    public static String status() {
        return everRan
                ? "extractCapeState hooked"
                : "hook never ran - check the method name for this version";
    }
}
