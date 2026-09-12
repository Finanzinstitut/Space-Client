package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * The menu's photograph, drawn slightly larger than the window and drifting
 * with the cursor.
 *
 * The image is scaled past the edges on purpose. That overscan is what the
 * parallax spends: without it there is nothing to slide and the picture would
 * have to shrink away from the borders as it moved. Around a tenth is enough
 * to feel like depth and little enough that the crop does not lose anything.
 *
 * <h2>Why it lags behind the cursor</h2>
 *
 * The offset is eased rather than applied straight from the mouse position. A
 * background pinned exactly to the cursor reads as a sheet of paper stuck to
 * it - the small delay is the whole illusion, because distant things are
 * supposed to answer slowly. It is also frame rate independent, so the drift
 * feels the same at 60 and at 240.
 *
 * The picture follows the cursor rather than opposing it: moving the mouse to
 * the top left pulls the image that way too. Both conventions exist and the
 * inverted one reads as looking around a room, but this menu is a flat surface
 * being nudged, not a window being leaned through.
 */
public final class MenuWallpaper {

    public static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(SpaceClient.MOD_ID, "textures/gui/menu_background.png");

    /** The image's own proportions, used so it covers rather than stretches. */
    private static final float SOURCE_W = 1920f;
    private static final float SOURCE_H = 1080f;

    /** How much larger than the window the picture is drawn. */
    private static final float OVERSCAN = 0.12f;

    /**
     * How much of the spare room the drift is allowed to use.
     *
     * Short of all of it, because the offset is eased and therefore still
     * travelling when the cursor reaches the edge - spending the last of the
     * margin would let a border show for a frame on the way to a corner.
     */
    private static final float TRAVEL = 0.85f;

    private static float offsetX = 0f;
    private static float offsetY = 0f;

    /**
     * How much of the drift is currently allowed, from nothing to all of it.
     *
     * The introduction sets this to zero and raises it once the icons are in
     * place. A picture answering the cursor while a greeting is still being
     * read pulls the eye away from the words - the drift is an invitation to
     * touch things, and there is nothing to touch yet.
     */
    private static float influence = 1f;

    public static void setInfluence(float value) {
        influence = Ease.clamp01(value);
    }

    /**
     * Draws the wallpaper, or reports that it could not.
     *
     * @return false when there is no usable texture call, so the caller can
     *         fall back to the drawn backdrop instead of leaving black.
     */
    public static boolean draw(GuiGraphicsExtractor graphics,
                               int width, int height,
                               int mouseX, int mouseY, float delta) {
        if (width <= 0 || height <= 0) return false;

        // Cover, not fit: the larger of the two ratios means neither axis is
        // left short, whatever shape the window has been dragged into
        float cover = Math.max(width / SOURCE_W, height / SOURCE_H);
        float scale = cover * (1f + OVERSCAN);

        int drawWidth = Math.round(SOURCE_W * scale);
        int drawHeight = Math.round(SOURCE_H * scale);

        float roomX = Math.max(0, (drawWidth - width) / 2f) * TRAVEL;
        float roomY = Math.max(0, (drawHeight - height) / 2f) * TRAVEL;

        // From the centre of the window, as a fraction either side
        float fromCentreX = clamp((mouseX - width / 2f) / (width / 2f));
        float fromCentreY = clamp((mouseY - height / 2f) / (height / 2f));

        // Negated because the image is drawn from its own top left corner:
        // to make the visible scene travel towards the cursor, the crop has to
        // travel away from it. Reasoning about it the other way round is what
        // put the first version of this the wrong way round in both axes.
        float step = Math.max(delta, 0.1f);
        offsetX = Ease.approach(offsetX, -fromCentreX * roomX * influence, 0.09f, step);
        offsetY = Ease.approach(offsetY, -fromCentreY * roomY * influence, 0.09f, step);

        int x = Math.round((width - drawWidth) / 2f + offsetX);
        int y = Math.round((height - drawHeight) / 2f + offsetY);

        return Textures.draw(graphics, TEXTURE, x, y, drawWidth, drawHeight);
    }

    /** Centres the drift again, so a screen does not open mid-slide. */
    public static void reset() {
        offsetX = 0f;
        offsetY = 0f;
    }

    private static float clamp(float value) {
        return value < -1f ? -1f : (value > 1f ? 1f : value);
    }

    private MenuWallpaper() {}
}
