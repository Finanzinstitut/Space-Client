package gg.spaceclient.access;

/**
 * Records whether each item scaling hook is actually running.
 *
 * The mixin config sets `defaultRequire: 0`, which is deliberate - a target
 * that has moved should skip its injection rather than crash the game on
 * launch. The cost is that a hook which never applies also never complains, and
 * the symptom is a setting that silently does nothing.
 *
 * Each hook reports the first time it fires. The diagnostics page then answers
 * the question directly instead of leaving it to guesswork over a log.
 *
 * Outside the mixin package, because the diagnostics screen reads it and
 * ordinary code cannot touch a class the mixin config owns.
 */
public final class ItemScaleReport {

    private static volatile boolean ground = false;
    private static volatile boolean hand = false;
    private static volatile boolean hotbar = false;

    public static void sawGround() { ground = true; }
    public static void sawHand() { hand = true; }
    public static void sawHotbar() { hotbar = true; }

    /**
     * Reads as a list of what has been seen so far.
     *
     * "not yet" rather than "broken", because a hook only reports once
     * something it applies to has been drawn - no dropped item on screen means
     * no ground report, and that is not a fault.
     */
    public static String status() {
        return "ground " + mark(ground)
                + ", hand " + mark(hand)
                + ", hotbar " + mark(hotbar);
    }

    private static String mark(boolean seen) { return seen ? "ok" : "not yet"; }

    private ItemScaleReport() {}
}
