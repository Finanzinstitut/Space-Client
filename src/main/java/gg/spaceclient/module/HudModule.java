package gg.spaceclient.module;

import com.google.gson.JsonObject;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A module that draws on the HUD. Position is a fraction of the screen so
 * elements keep their place when the window is resized.
 */
public abstract class HudModule extends Module {
    private float xPercent;
    private float yPercent;

    /**
     * How large this element draws, 1 being its natural size.
     *
     * Per element rather than one setting for the whole HUD, because the
     * elements are read at different distances of attention: a coordinate
     * readout is glanced at and wants to be small, a follower count during a
     * stream is being shown to other people and wants to be large.
     */
    private float scale = 1.0f;

    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 3.0f;

    /**
     * HUD elements get their own look, separate from the menu: a plain grey
     * plate behind the content so the readout stays legible over any terrain.
     */
    private final BooleanSetting background = new BooleanSetting(
            "background", "Background", "Draw a grey plate behind this element", true);

    private final ColorSetting backgroundColor = new ColorSetting(
            "background_color", "Background colour", "Colour of the plate", 0x80404040);

    protected HudModule(String id, String name, String description,
                        float defaultX, float defaultY, boolean enabledByDefault) {
        super(id, name, description, enabledByDefault);
        this.xPercent = defaultX;
        this.yPercent = defaultY;
        addSettings(background, backgroundColor);
    }

    /** Lets a module opt out of the plate when it draws its own. */
    protected void setBackgroundEnabled(boolean enabled) {
        background.set(enabled);
    }

    /** Width and height of the drawn content, used to size the plate. */
    public abstract int getWidth();
    public abstract int getHeight();

    /**
     * How often this element's text is allowed to be rebuilt, in milliseconds.
     *
     * Overridden by anything that genuinely has to keep up with the frame rate.
     * Everything else is read by a human eye, and a human eye cannot tell four
     * hundred updates a second from ten.
     */
    protected long refreshMillis() { return 100; }

    private String cachedText = null;
    private long cachedAt = 0L;

    /**
     * The element's text, rebuilt at most once per refresh window.
     *
     * The HUD loop asks every module for its width and then draws it, so any
     * text built inside those methods is built twice a frame. Twenty modules
     * formatting strings four hundred times a second is real work and real
     * garbage, and it is the reason a HUD can cost frames rather than just
     * occupy pixels. The supplier runs on the tick that needs it and the answer
     * is handed out until it goes stale.
     */
    protected String cachedText(java.util.function.Supplier<String> builder) {
        long now = System.currentTimeMillis();
        if (cachedText == null || now - cachedAt >= refreshMillis()) {
            cachedText = builder.get();
            cachedLines = null;
            cachedAt = now;
        }
        return cachedText;
    }

    private String[] cachedLines = null;

    /**
     * The same text, already split into lines.
     *
     * Worth its own cache because caching the string alone did not finish the
     * job. A multi line element calls split in getWidth, again in getHeight and
     * a third time while drawing - three arrays per module per frame, for a
     * string that had not changed since the last refresh. With forty modules
     * on screen that is a steady stream of garbage produced by the very client
     * that also ships an FPS module, which is not a good look.
     *
     * Split once when the text is rebuilt, handed out until then.
     */
    protected String[] cachedLines(java.util.function.Supplier<String> builder) {
        String text = cachedText(builder);
        if (cachedLines == null) {
            cachedLines = text.isEmpty() ? EMPTY_LINES : text.split("\n");
        }
        return cachedLines;
    }

    private static final String[] EMPTY_LINES = new String[0];

    /** Drops the cache, for when a setting changes what the text should say. */
    protected void invalidateText() {
        cachedText = null;
        cachedLines = null;
    }

    /**
     * Draws the plate, then the element itself. Subclasses implement render();
     * this is what the HUD loop calls.
     */
    public void draw(GuiGraphicsExtractor graphics, int x, int y) {
        // When scaling works the matrix has already moved to the element's
        // corner, so everything below draws from the origin instead
        boolean scaled = scale != 1.0f && gg.spaceclient.ui.Scale.push(graphics, x, y, scale);
        int drawX = scaled ? 0 : x;
        int drawY = scaled ? 0 : y;

        try {
            if (background.get()) {
                // A glass plate rather than a flat rectangle. Same colour and
                // the same alpha the setting has always carried; what changed
                // is the rounded edge, the light line along the top and the
                // body being brighter at the top than the bottom.
                int padding = 4;
                gg.spaceclient.ui.Glass.panel(graphics,
                        drawX - padding, drawY - padding,
                        getWidth() + padding * 2, getHeight() + padding * 2,
                        backgroundColor.get(), 4);
            }
            render(graphics, drawX, drawY);
        } finally {
            // In a finally block because a module that throws mid-draw would
            // otherwise leave the matrix pushed and skew everything drawn after
            // it for the rest of the frame
            if (scaled) gg.spaceclient.ui.Scale.pop(graphics);
        }
    }

    /**
     * Whether the editor refuses to move or resize this element.
     *
     * Exists because of what the HUD is for: once something sits exactly where
     * you want it, the next visit to the editor is about a different element,
     * and nudging the settled one by two pixels on the way past is the most
     * annoying thing the editor can do.
     */
    private boolean locked = false;

    public boolean isLocked() { return locked; }
    public void setLocked(boolean value) { this.locked = value; }

    public float getScale() { return scale; }

    public void setScale(float value) {
        // Not finite means a corrupt config, and clamping does not catch it:
        // Math.min and Math.max hand NaN straight back, so a NaN would sail
        // through the bounds and end up as the scale. From there the element
        // draws nowhere and cannot be dragged back, because the editor needs a
        // position to grab and there isn't one.
        if (!Float.isFinite(value)) return;
        this.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    /**
     * The size this element occupies on screen.
     *
     * getWidth and getHeight are the content's own measurements and stay
     * unscaled, because the module draws in its own coordinates. Anything
     * asking where the element *is* - the editor's outline, its grab area -
     * wants these instead.
     */
    public int getScaledWidth() { return Math.round(getWidth() * scale); }
    public int getScaledHeight() { return Math.round(getHeight() * scale); }

    public void setPosition(float x, float y) {
        // Same reasoning as setScale: a non finite value survives the clamp
        // and puts the element somewhere it can never be recovered from
        if (Float.isFinite(x)) this.xPercent = Math.max(0f, Math.min(1f, x));
        if (Float.isFinite(y)) this.yPercent = Math.max(0f, Math.min(1f, y));
    }

    /** The stored fractions, for anything saving a layout of its own. */
    public float getXPercent() { return xPercent; }
    public float getYPercent() { return yPercent; }

    public int getX(int screenWidth) { return (int) (xPercent * screenWidth); }
    public int getY(int screenHeight) { return (int) (yPercent * screenHeight); }

    public abstract void render(GuiGraphicsExtractor graphics, int x, int y);

    @Override
    public void save(JsonObject json) {
        super.save(json);
        json.addProperty("x", xPercent);
        json.addProperty("y", yPercent);
        json.addProperty("scale", scale);
        json.addProperty("locked", locked);
    }

    @Override
    public void load(JsonObject json) {
        super.load(json);
        if (json.has("x")) xPercent = json.get("x").getAsFloat();
        if (json.has("y")) yPercent = json.get("y").getAsFloat();
        if (json.has("scale")) setScale(json.get("scale").getAsFloat());
        if (json.has("locked")) locked = json.get("locked").getAsBoolean();
    }
}
