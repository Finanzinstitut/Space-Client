package gg.spaceclient.util;

/**
 * Measures where a slow moment actually goes.
 *
 * Written because the server screen has now been made faster four times and is
 * still reported as stuttering. Each fix addressed something real - a blocking
 * disk read, a burst of pings, icons hashed once per rebuild - and each was
 * chosen by reasoning about the code rather than by measuring it. Four guesses
 * is where guessing stops being reasonable.
 *
 * Deliberately crude. Nanotime around named sections, the worst value ever seen
 * for each, shown on the diagnostics screen. It will not find a slow line
 * inside a method, but it will say which of half a dozen candidates is worth
 * looking inside - and that is the question that has been unanswered.
 */
public final class Timings {

    /** Anything under this is not what anybody is feeling. */
    private static final long INTERESTING_MS = 4;

    private static final java.util.Map<String, Long> WORST =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.Map<String, Long> LAST =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Timings() {}

    /**
     * Times a section and records it.
     *
     * Returns whatever the body returns so it can wrap an expression without
     * restructuring the caller - a measurement that forces the code around it
     * to change shape is a measurement that gets removed again.
     */
    public static <T> T measure(String name, java.util.function.Supplier<T> body) {
        long start = System.nanoTime();
        try {
            return body.get();
        } finally {
            record(name, System.nanoTime() - start);
        }
    }

    public static void measure(String name, Runnable body) {
        long start = System.nanoTime();
        try {
            body.run();
        } finally {
            record(name, System.nanoTime() - start);
        }
    }

    private static void record(String name, long nanos) {
        long millis = nanos / 1_000_000L;
        LAST.put(name, millis);
        WORST.merge(name, millis, Math::max);
    }

    /** Clears the record, for measuring one action rather than a session. */
    public static void reset() {
        WORST.clear();
        LAST.clear();
    }

    /**
     * The slow sections, worst first.
     *
     * Only the ones above the threshold. A list where eleven entries say 0ms and
     * one says 300ms hides its own answer, and the whole point of this is to
     * point at something.
     */
    public static String report() {
        if (WORST.isEmpty()) return "nothing measured yet";

        java.util.List<java.util.Map.Entry<String, Long>> slow = new java.util.ArrayList<>();
        for (var entry : WORST.entrySet()) {
            if (entry.getValue() >= INTERESTING_MS) slow.add(entry);
        }

        if (slow.isEmpty()) {
            return "nothing over " + INTERESTING_MS + "ms";
        }

        slow.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (var entry : slow) {
            if (shown >= 4) break;
            if (shown > 0) out.append(", ");
            out.append(entry.getKey()).append(' ').append(entry.getValue()).append("ms");
            shown++;
        }
        return out.toString();
    }

    /** Whether anything measured is slow enough to be felt as a stutter. */
    public static boolean clean() {
        for (long value : WORST.values()) {
            if (value >= 30) return false;
        }
        return true;
    }
}
