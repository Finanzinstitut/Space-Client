package gg.spaceclient.modules;

import gg.spaceclient.module.Module;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.setting.IntSetting;
import gg.spaceclient.setting.SettingGroup;

/**
 * The block under the crosshair, drawn so you can actually see it.
 *
 * Vanilla marks it with a one pixel black line, which was a reasonable choice
 * in 2010 and is a poor one on a bright screen against dark stone. Two separate
 * problems hide behind that: the edge is hard to see, and on a face-on wall
 * there is nothing to tell you which block of nine identical ones is selected.
 * An outline fixes the first, a shaded face fixes the second, and they are
 * different enough that both belong here with their own switches.
 *
 * <h2>One module, two toggles</h2>
 *
 * Rather than two modules. They answer the same question, share the same
 * lookup and the same drawing path, and a menu that lists them separately is a
 * menu where somebody turns on one and wonders why the other did not change.
 */
public class BlockHighlightModule extends Module {

    // --- outline ---

    private final BooleanSetting outline = new BooleanSetting(
            "outline", "Outline", "Draw the edges of the selected block", true);

    private final ColorSetting outlineColor = new ColorSetting(
            "outline_color", "Outline colour", "Colour of the edges", 0xFF38E0FF);

    /**
     * Edge thickness, in the same units the hitbox module uses.
     *
     * Shared scale on purpose: somebody who has already tuned their hitbox
     * lines knows what a 3 looks like, and making the same number mean
     * something different here would be a small cruelty.
     */
    private final IntSetting thickness = new IntSetting(
            "thickness", "Thickness", "How thick the edges are drawn", 3, 1, 12);

    // --- overlay ---

    private final BooleanSetting overlay = new BooleanSetting(
            "overlay", "Face overlay", "Shade the whole block, not just its edges", false);

    private final ColorSetting overlayColor = new ColorSetting(
            "overlay_color", "Overlay colour", "Colour of the shading", 0x3038E0FF);

    /**
     * Whether the shading covers the block or only the face you are pointing at.
     *
     * The single face is the more useful of the two and the less obvious. When
     * placing against a wall, what you need to know is not which block is
     * selected but which side of it you are about to build on.
     */
    private final BooleanSetting faceOnly = new BooleanSetting(
            "face_only", "Only the aimed face",
            "Shade the side you are pointing at instead of the whole block", true);

    public BlockHighlightModule() {
        super("blockhighlight", "Block Highlight",
                "Marks the block under your crosshair more clearly than vanilla", false);
        addGroups(
                SettingGroup.of("Outline", "The edges of the block",
                        outline, outlineColor, thickness),
                SettingGroup.of("Overlay", "Shading on the block itself",
                        overlay, overlayColor, faceOnly)
        );
    }

    public boolean wantsOutline() { return isEnabled() && outline.get(); }

    public boolean wantsOverlay() { return isEnabled() && overlay.get(); }

    public boolean facesOnly() { return faceOnly.get(); }

    public int outlineColor() { return outlineColor.get(); }

    public int overlayColor() { return overlayColor.get(); }

    /** Edge thickness in world units, matching the hitbox module's scale. */
    public double edgeThickness() { return thickness.get() * 0.008; }
}
