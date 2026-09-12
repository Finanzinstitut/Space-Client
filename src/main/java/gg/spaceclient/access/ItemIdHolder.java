package gg.spaceclient.access;

/**
 * Carries an item's description id on a render state that has no room for one.
 *
 * `ItemEntityRenderState` holds an `ItemStackRenderState` - a baked model, not
 * a stack - so by the time the item is drawn there is nothing left to identify
 * it by. The id has to be picked up earlier, while the entity is still in hand,
 * and carried across on the state itself.
 *
 * This interface is what the two halves agree on: a mixin adds it to the render
 * state class, the renderer sets it during extraction and reads it during
 * drawing.
 *
 * It lives outside the mixin package on purpose, and that is not a matter of
 * taste. Everything under gg.spaceclient.mixin is claimed by the mixin config
 * and gets transformed on load, so a plain class there cannot be referenced by
 * ordinary code - the attempt fails the transformation, which fails the
 * resource load, which is a black screen on startup. Only real mixins listed in
 * spaceclient.mixins.json belong in that package.
 */
public interface ItemIdHolder {
    String spaceclient$itemId();
    void spaceclient$setItemId(String id);
}
