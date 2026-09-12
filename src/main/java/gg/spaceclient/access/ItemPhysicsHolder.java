package gg.spaceclient.access;

/**
 * Carries what a dropped item needs to lie still, on a render state that keeps
 * nothing across frames.
 *
 * The whole difficulty of resting an item on the ground is that the resting
 * pose has to be the same every frame. A yaw picked at draw time would jitter,
 * and one derived from the clock would slowly rotate. So it is worked out once
 * during extraction, from the entity's own id, and carried across on the state
 * - which is the same trick the item scaling already uses, for the same reason.
 *
 * Outside the mixin package on purpose: everything under gg.spaceclient.mixin
 * is claimed by the mixin config and transformed on load, so a plain class
 * there cannot be referenced from ordinary code.
 */
public interface ItemPhysicsHolder {

    /** The resting yaw in degrees, stable for the life of the entity. */
    float spaceclient$restYaw();

    void spaceclient$setRestYaw(float yaw);

    /**
     * Whether the boost decided this item is not worth drawing.
     *
     * Kept on the state rather than decided while drawing because the decision
     * needs the entity - its distance and its place in the frame's budget -
     * and by the time anything is submitted the entity is gone.
     */
    boolean spaceclient$isSkipped();

    void spaceclient$setSkipped(boolean skipped);

    /** Whether the item is settled rather than still falling or floating. */
    boolean spaceclient$isSettled();

    void spaceclient$setSettled(boolean settled);
}
