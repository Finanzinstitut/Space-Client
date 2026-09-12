package gg.spaceclient.mixin;

import gg.spaceclient.access.ItemIdHolder;
import gg.spaceclient.access.ItemPhysicsHolder;

import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Gives the render state somewhere to keep what the drawing step needs.
 *
 * Two things now: the item's id, for scaling, and its resting pose, for the
 * physics. Both exist for the same reason - the state is rebuilt every frame
 * from a stack that is no longer in reach by the time anything is drawn.
 */
@Mixin(ItemEntityRenderState.class)
public class ItemEntityRenderStateMixin implements ItemIdHolder, ItemPhysicsHolder {

    @Unique
    private String spaceclient$itemId = "";

    @Override
    public String spaceclient$itemId() { return spaceclient$itemId; }

    @Override
    public void spaceclient$setItemId(String id) { this.spaceclient$itemId = id; }

    // --- resting pose, for the same reason and by the same route ---

    @Unique
    private float spaceclient$restYaw = 0f;

    @Unique
    private boolean spaceclient$settled = false;

    @Override
    public float spaceclient$restYaw() { return spaceclient$restYaw; }

    @Override
    public void spaceclient$setRestYaw(float yaw) { this.spaceclient$restYaw = yaw; }

    @Unique
    private boolean spaceclient$skipped = false;

    @Override
    public boolean spaceclient$isSkipped() { return spaceclient$skipped; }

    @Override
    public void spaceclient$setSkipped(boolean skipped) { this.spaceclient$skipped = skipped; }

    @Override
    public boolean spaceclient$isSettled() { return spaceclient$settled; }

    @Override
    public void spaceclient$setSettled(boolean settled) { this.spaceclient$settled = settled; }
}
