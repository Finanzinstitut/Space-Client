package gg.spaceclient.ui;

/**
 * A number that travels to its new value instead of jumping.
 *
 * Counters like frames per second change every tick, and a readout that
 * reprints a different figure sixty times a second is a flicker rather than a
 * number - the eye reads the movement and not the value. Easing toward the
 * target settles the last digits and, oddly, makes the figure easier to read
 * while it is changing rather than harder.
 *
 * Two things it deliberately does not do. It never animates the first value:
 * a counter that counts up from zero every time you open a menu is a loading
 * bar pretending to be data. And it snaps when the gap is large, because a
 * ping going from 40 to 900 is an event, and gliding there over half a second
 * would hide exactly the moment worth noticing.
 */
public final class Rolling {

    /** Fraction of the remaining distance covered per sixtieth of a second. */
    private static final float SPEED = 0.25f;

    /** Past this the value jumps: it is a change of situation, not a drift. */
    private static final float SNAP_RATIO = 4f;

    private float current;
    private boolean primed = false;

    /**
     * Its own clock.
     *
     * HudModule.render is handed no frame time, and threading one through
     * every element to animate a couple of counters would be a wide change for
     * a narrow reason. Measuring here keeps the animation frame rate
     * independent without touching the interface every element implements.
     */
    private long lastNanos = 0;

    /** The value to display, rounded. */
    public int value() { return Math.round(current); }

    public float raw() { return current; }

    /**
     * Moves toward the target and returns what to display.
     *
     * The frame time is measured rather than passed in, and capped: a frame
     * that took a second - loading a world, alt tabbing back - should finish
     * the animation, not overshoot it into a wobble.
     */
    public int update(float target) {
        long now = System.nanoTime();
        // Twentieths of a tick, the same unit Ease.approach expects
        float delta = lastNanos == 0 ? 1f
                : Math.min(5f, (now - lastNanos) / 16_666_667f);
        lastNanos = now;

        if (!primed) {
            primed = true;
            current = target;
            return value();
        }

        float gap = Math.abs(target - current);
        if (gap > Math.max(8f, Math.abs(current) * SNAP_RATIO)) {
            current = target;
            return value();
        }

        current = Ease.approach(current, target, SPEED, delta);

        // Without this the last fraction of a unit never arrives and a settled
        // counter sits one below its real value forever
        if (Math.abs(target - current) < 0.02f) current = target;

        return value();
    }

    /** Drops the animation, so the next update shows the value immediately. */
    public void reset() { primed = false; lastNanos = 0; }
}
