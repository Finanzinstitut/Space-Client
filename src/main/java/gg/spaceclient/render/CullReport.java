package gg.spaceclient.render;

/**
 * Counts what the boost skipped, so the diagnostics screen can say whether it
 * is doing anything at all.
 *
 * Worth having because the failure mode here is silence. The culling hook is
 * declared without a descriptor and without requiring a match, which is the
 * right call on a version whose method shapes keep moving - but it means a
 * hook that never applied looks exactly like a hook with nothing to skip.
 * A count tells the two apart.
 */
public final class CullReport {

    private static int culled = 0;
    private static int kept = 0;
    private static boolean everRan = false;

    private CullReport() {}

    public static void culledItem() {
        everRan = true;
        culled++;
    }

    public static void keptItem() {
        everRan = true;
        kept++;
    }

    public static boolean hooked() { return everRan; }

    /**
     * What the boost has actually done, in words that are true.
     *
     * The previous version of this line said "shouldRender did not match" for
     * any state where nothing had been counted - including the ordinary case of
     * standing somewhere with no dropped items in sight. It named a method the
     * hook no longer even uses, and it reported a failure where there was
     * nothing to do. Three states, three answers.
     */
    public static String status() {
        if (!everRan) {
            return "no dropped items seen yet - drop something to check";
        }
        if (culled == 0) {
            return "hooked, " + kept + " drawn, none worth skipping yet";
        }
        return culled + " skipped, " + kept + " drawn since start";
    }
}
