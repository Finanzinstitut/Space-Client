package gg.spaceclient.modules;

import gg.spaceclient.module.Module;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.IntSetting;

/**
 * Gives the cape motion of its own instead of a flat, dead slab.
 *
 * Vanilla decides the cape angle from one thing: how fast you moved since last
 * frame. Stand still and it hangs perfectly rigid, which is what makes it read
 * as cardboard. This adds a slow drift on top, so it breathes when you are
 * idle, and lets the movement response be pushed past what vanilla allows.
 *
 * This is deliberately not a per-segment rope simulation like tr7zw's mod does.
 * That needs the cape drawn as a chain of pieces, which means replacing the
 * whole cape render path; this steers the three angles vanilla already has, so
 * it inherits every other behaviour - elytra priority, sneaking, first person -
 * for free and cannot break rendering for anyone.
 */
public class WaveyCapeModule extends Module {

    private final IntSetting strength = new IntSetting(
            "strength", "Sway strength",
            "How far the cape swings when you move", 140, 20, 300);

    private final IntSetting idleSway = new IntSetting(
            "idle_sway", "Idle drift",
            "How much it stirs while standing still", 40, 0, 100);

    private final IntSetting speed = new IntSetting(
            "speed", "Drift speed",
            "How quickly the idle motion cycles", 50, 10, 150);

    private final BooleanSetting sideways = new BooleanSetting(
            "sideways", "Sideways lean",
            "Let the cape swing left and right as well", true);

    public WaveyCapeModule() {
        super("waveycape", "Wavey Cape",
                "Makes capes move instead of hanging stiff", false);
        addSettings(strength, idleSway, speed, sideways);
    }

    /**
     * The extra angles for this frame, in the order vanilla stores them.
     *
     * Returns null when off, so the mixin can leave the state untouched rather
     * than write back values that happen to match - an untouched state is one
     * fewer thing to explain when something else looks wrong.
     */
    public float[] shape(float capeFlap, float capeLean, float capeLean2) {
        if (!isEnabled()) return null;

        // A time base that keeps running while the game is paused would snap
        // the cape on unpause; tying it to the world clock avoids that.
        long now = System.currentTimeMillis();
        double t = (now % 100_000L) / 1000.0 * (speed.get() / 50.0);

        float boost = strength.get() / 100f;
        float drift = idleSway.get() / 100f;

        // The idle motion is two waves at different rates, because a single
        // sine reads as a machine rather than as cloth.
        float breathe = (float) (Math.sin(t * 1.7) * 2.5 + Math.sin(t * 0.9) * 1.5) * drift;
        float sway = (float) (Math.sin(t * 1.1) * 3.0) * drift;

        float flap = capeFlap * boost + breathe;
        float lean = capeLean * boost;
        float lean2 = sideways.get() ? capeLean2 + sway : capeLean2;

        return new float[]{flap, lean, lean2};
    }
}
