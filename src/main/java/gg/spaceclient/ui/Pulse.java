package gg.spaceclient.ui;

/**
 * A brief highlight when something changes.
 *
 * The idea borrowed from phone interfaces: a number that changes should say so
 * for a moment and then stop. A permanent colour means every reading looks
 * urgent; no signal at all means a totem going from 2 to 1 slides past
 * unnoticed while you are busy being hit.
 *
 * Fades on its own clock so it behaves the same at any frame rate, and holds
 * at full for a moment before falling - a highlight that starts fading
 * immediately reads as a flicker rather than as a change.
 */
public final class Pulse {

    private static final long HOLD_MS = 120;
    private static final long FADE_MS = 500;

    private long firedAt = 0;
    private boolean primed = false;
    private double lastValue;

    /** Fires when the value differs from last time. Never on the first call. */
    public void watch(double value) {
        if (!primed) {
            primed = true;
            lastValue = value;
            return;
        }
        if (value != lastValue) {
            lastValue = value;
            firedAt = System.currentTimeMillis();
        }
    }

    /** Fires only when the value went down, for things that get used up. */
    public void watchDrop(double value) {
        if (!primed) {
            primed = true;
            lastValue = value;
            return;
        }
        if (value < lastValue) firedAt = System.currentTimeMillis();
        lastValue = value;
    }

    /** Fires only when the value went up, for things that accumulate. */
    public void watchRise(double value) {
        if (!primed) {
            primed = true;
            lastValue = value;
            return;
        }
        if (value > lastValue) firedAt = System.currentTimeMillis();
        lastValue = value;
    }

    public void fire() { firedAt = System.currentTimeMillis(); }

    /** How lit the highlight is right now, 1 to 0. */
    public float strength() {
        if (firedAt == 0) return 0f;

        long age = System.currentTimeMillis() - firedAt;
        if (age < HOLD_MS) return 1f;
        if (age > HOLD_MS + FADE_MS) return 0f;

        float remaining = 1f - (age - HOLD_MS) / (float) FADE_MS;
        // Eased out, so it leaves quietly instead of stopping dead
        return remaining * remaining;
    }

    /** Blends a colour toward the highlight by however lit it is. */
    public int tint(int normal, int highlight) {
        return Ease.color(normal, highlight, strength());
    }
}
