package gg.spaceclient.modules;

import gg.spaceclient.module.Module;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.IntSetting;
import gg.spaceclient.setting.SettingGroup;

/**
 * Dropped items lie on the ground instead of hovering and spinning.
 *
 * Vanilla floats every dropped item at knee height and turns it slowly, which
 * is readable but has never looked like anything. An item that has been thrown
 * on the floor should be on the floor. The difference is most obvious where it
 * matters: a death pile stops being a rotating cloud and becomes a heap you
 * can look at.
 *
 * <h2>Written from scratch, and why that matters</h2>
 *
 * CreativeMD's ItemPhysic got here first and this client has no better idea
 * than theirs. None of their code is in here though, and the reason is the
 * licence rather than pride: ItemPhysic is LGPL-3.0, Space Client ships as all
 * rights reserved, and LGPL source cannot be copied into that. So this is a
 * separate implementation of the same idea, and the credits screen says so.
 *
 * <h2>The resting pose has to be decided once</h2>
 *
 * Every frame has to agree on how a given item is lying, or it shivers. The
 * yaw therefore comes from the entity's own id rather than from a random
 * number or the clock - the same item always lands the same way, and two items
 * next to each other still land differently. That is the whole trick, and it
 * is why the value is worked out during extraction and carried on the render
 * state rather than computed while drawing.
 */
public class ItemPhysicsModule extends Module {

    // --- how they lie ---

    private final BooleanSetting layFlat = new BooleanSetting(
            "lay_flat", "Lie flat", "Rest items on their face instead of standing them up", true);

    private final BooleanSetting stopSpin = new BooleanSetting(
            "stop_spin", "Stop spinning", "Settled items keep still", true);

    private final BooleanSetting stopBob = new BooleanSetting(
            "stop_bob", "Stop bobbing", "Settled items stop floating up and down", true);

    private final BooleanSetting randomYaw = new BooleanSetting(
            "random_yaw", "Vary the angle", "Give each item its own resting angle", true);

    /**
     * How far into the floor an item settles.
     *
     * A little, not none. An item resting exactly on the surface reads as
     * hovering a hair above it, because its own model has thickness and the
     * eye expects the contact to be hidden.
     */
    private final IntSetting sink = new IntSetting(
            "sink", "Sink", "How far items settle into the ground, in hundredths of a block",
            4, 0, 20);

    private final IntSetting lift = new IntSetting(
            "lift", "Tilt", "How far items lean while still in the air, in degrees",
            0, 0, 90);

    public ItemPhysicsModule() {
        super("itemphysics", "Item Physics",
                "Dropped items lie on the ground instead of spinning in the air", false);
        addGroups(
                SettingGroup.of("Resting", "How settled items sit",
                        layFlat, randomYaw, sink),
                SettingGroup.of("Motion", "What the game does that this stops",
                        stopSpin, stopBob, lift)
        );
    }

    // --- what the renderer asks ---

    public boolean laysFlat() { return isEnabled() && layFlat.get(); }

    public boolean stopsSpin() { return isEnabled() && stopSpin.get(); }

    public boolean stopsBob() { return isEnabled() && stopBob.get(); }

    /** How far to sink a settled item, in blocks. */
    public float sinkDepth() { return sink.get() / 100f; }

    /** The lean given to items that are still falling, in degrees. */
    public float airTilt() { return lift.get(); }

    /**
     * The angle a given item rests at.
     *
     * Derived from the entity id rather than drawn at random, so it survives
     * the render state being rebuilt every frame. Multiplied by a large odd
     * number first: consecutive ids are handed out to items dropped together,
     * and without that spreading step a death pile would land as a fan of
     * neatly increasing angles rather than as a heap.
     */
    public float restYawFor(int entityId) {
        if (!randomYaw.get()) return 0f;

        long scattered = (entityId * 2654435761L) & 0x7FFFFFFFL;
        return scattered % 360;
    }
}
