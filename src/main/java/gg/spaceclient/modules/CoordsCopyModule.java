package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A marker you drop with the zoom-free press of a key, and the bearing back to it.
 *
 * Waypoint mods store lists in files; this is the small case that covers most
 * of the need - remember where you are standing, then see the direction and
 * distance back while you wander off.
 */
public class CoordsCopyModule extends HudModule {
    private final BooleanSetting showDistance = new BooleanSetting(
            "show_distance", "Show distance", "Print how far the marker is", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the readout", 0xFFFFD9A0);

    private double markX, markY, markZ;
    private boolean marked = false;
    private boolean wasSneaking = false;

    public CoordsCopyModule() {
        super("marker", "Marker", "Drop a spot and see the way back", 0.02f, 0.70f, false);
        addSettings(showDistance, textColor);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.options == null) return;

        // Sneak plus drop sets the marker: no extra key to bind, and the
        // combination is one nobody presses by accident while walking.
        boolean sneaking = mc.options.keyShift.isDown();
        boolean dropping = mc.options.keyDrop.isDown();

        if (sneaking && dropping && !wasSneaking) {
            markX = mc.player.getX();
            markY = mc.player.getY();
            markZ = mc.player.getZ();
            marked = true;
        }
        wasSneaking = sneaking && dropping;
    }

    /** Cached; see HudModule.cachedText for why. */
    private String text() { return cachedText(this::buildText); }

    private String buildText() {
        if (!marked) return "sneak + drop to mark";
        if (mc.player == null) return "--";

        double dx = markX - mc.player.getX();
        double dz = markZ - mc.player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        // Bearing relative to where the player is looking
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        double yaw = mc.player.getYRot();
        double relative = ((bearing - yaw + 540) % 360) - 180;

        String arrow = relative > 20 ? ">>" : relative < -20 ? "<<" : "^^";
        return showDistance.get()
                ? String.format("%s %.0fm  (%.0f, %.0f, %.0f)", arrow, distance, markX, markY, markZ)
                : String.format("%s (%.0f, %.0f, %.0f)", arrow, markX, markY, markZ);
    }

    @Override
    public int getWidth() { return mc.font.width(text()); }

    @Override
    public int getHeight() { return mc.font.lineHeight; }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.text(mc.font, text(), x, y, textColor.get(), true);
    }
}
