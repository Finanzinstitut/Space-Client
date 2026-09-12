package gg.spaceclient.ui;

/**
 * Easing curves, so movement in the interface starts and stops rather than
 * simply happening.
 *
 * Everything here was previously done with a linear interpolation or a fixed
 * fraction per frame. A fixed fraction is already better than linear - it eases
 * out for free - but it has no sense of how long a frame took, so the same
 * animation runs at a different speed at 30 frames a second than at 240. The
 * approach below takes the frame time, so it does not.
 */
public final class Ease {

    /**
     * Moves a value toward a target at a rate independent of frame rate.
     *
     * `speed` is roughly how much of the remaining distance is covered per
     * sixtieth of a second, so the numbers mean the same thing they did when
     * they were per-frame fractions and nothing had to be retuned.
     */
    public static float approach(float current, float target, float speed, float deltaTicks) {
        // Guard against a hitch: a frame that took a second should finish the
        // animation, not overshoot it into a wobble
        float step = 1f - (float) Math.pow(1f - clamp01(speed), Math.max(0.001f, deltaTicks));
        return current + (target - current) * step;
    }

    /** Slows into the finish. The default for anything appearing. */
    public static float outCubic(float t) {
        float inverse = 1f - clamp01(t);
        return 1f - inverse * inverse * inverse;
    }

    /** Eases at both ends. For things that move rather than appear. */
    public static float inOutCubic(float t) {
        t = clamp01(t);
        return t < 0.5f
                ? 4f * t * t * t
                : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    /**
     * Overshoots slightly and settles.
     *
     * Used where something is picked up or put down, because a small overshoot
     * reads as weight and makes the moment noticeable without a sound.
     */
    public static float outBack(float t) {
        t = clamp01(t);
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float inverse = t - 1f;
        return 1f + c3 * inverse * inverse * inverse + c1 * inverse * inverse;
    }

    public static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /** Blends two ARGB colours. */
    public static int color(int from, int to, float t) {
        t = clamp01(t);
        int a = lerp((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, t);
        int r = lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = lerp(from & 0xFF, to & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int from, int to, float t) {
        return from + Math.round((to - from) * t);
    }

    private Ease() {}
}
