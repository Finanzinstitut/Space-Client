package gg.spaceclient.modules;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.input.RawKeyboard;
import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws a mouse and lights up whichever button is pressed.
 *
 * The shape is drawn from rectangles rather than a texture, so every colour
 * stays configurable and there is no asset to ship.
 */
public class MouseTrackerModule extends HudModule {
    private static final int BODY_W = 44;
    private static final int BODY_H = 64;

    private final ColorSetting pressedColor = new ColorSetting(
            "pressed_color", "Pressed colour", "Colour a button lights up in", 0xFF38E0FF);

    private final ColorSetting bodyColor = new ColorSetting(
            "body_color", "Body colour", "Colour of the mouse body", 0xB0101028);

    private final ColorSetting outlineColor = new ColorSetting(
            "outline_color", "Outline colour", "Colour of the outlines", 0xFF7C5CFF);

    private final BooleanSetting showCps = new BooleanSetting(
            "show_cps", "Show CPS", "Print the click rate under the mouse", true);

    private final BooleanSetting trackMovement = new BooleanSetting(
            "track_movement", "Show movement dot", "A dot follows your aim movement", true);

    private double dotX = 0;
    private double dotY = 0;
    private double lastYaw = 0;
    private double lastPitch = 0;

    public MouseTrackerModule() {
        super("mousetracker", "Mouse Tracker", "Input overlay that visualises your mouse",
                0.02f, 0.32f, false);
        addSettings(pressedColor, bodyColor, outlineColor, showCps, trackMovement);
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;

        // Derived from how far the view turned, the closest we can get to raw
        // mouse deltas without hooking the device itself.
        double yaw = mc.player.getYRot();
        double pitch = mc.player.getXRot();
        double deltaYaw = yaw - lastYaw;
        double deltaPitch = pitch - lastPitch;
        lastYaw = yaw;
        lastPitch = pitch;

        // Wrapping past 360 would otherwise fling the dot across the pad
        if (deltaYaw > 180) deltaYaw -= 360;
        if (deltaYaw < -180) deltaYaw += 360;

        dotX += deltaYaw * 0.6;
        dotY += deltaPitch * 0.6;

        // Spring back so the dot settles when you stop moving
        dotX *= 0.75;
        dotY *= 0.75;

        double limit = BODY_W / 2.0 - 6;
        dotX = Math.max(-limit, Math.min(limit, dotX));
        dotY = Math.max(-limit, Math.min(limit, dotY));
    }

    @Override
    public int getWidth() { return BODY_W; }

    @Override
    public int getHeight() { return showCps.get() ? BODY_H + 12 : BODY_H; }

    private void outlineRect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1, x1 + 1, y2, color);
        g.fill(x2 - 1, y1, x2, y2, color);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        boolean left = mc.options != null && mc.options.keyAttack.isDown();
        boolean right = mc.options != null && mc.options.keyUse.isDown();
        // Read straight from the device when possible; the registered binding
        // is the fallback.
        boolean middle = RawKeyboard.isAvailable()
                ? RawKeyboard.isMouseDown(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE)
                : SpaceClient.isMiddleClickDown();

        int body = bodyColor.get();
        int outline = outlineColor.get();
        int lit = pressedColor.get();

        int splitY = y + 26;
        int midX1 = x + BODY_W / 2 - 5;
        int midX2 = x + BODY_W / 2 + 5;

        graphics.fill(x, y, x + BODY_W, y + BODY_H, body);
        graphics.fill(x + 1, y + 1, midX1, splitY, left ? lit : body);
        graphics.fill(midX2, y + 1, x + BODY_W - 1, splitY, right ? lit : body);
        graphics.fill(midX1 + 1, y + 6, midX2 - 1, y + 22, middle ? lit : outline);

        // Outlines last so they sit on top of the fills
        outlineRect(graphics, x, y, x + BODY_W, y + BODY_H, outline);
        outlineRect(graphics, x + 1, y + 1, midX1, splitY, outline);
        outlineRect(graphics, midX2, y + 1, x + BODY_W - 1, splitY, outline);
        graphics.fill(x + 1, splitY, x + BODY_W - 1, splitY + 1, outline);

        if (trackMovement.get()) {
            int centerX = x + BODY_W / 2;
            int centerY = splitY + (BODY_H - 26) / 2;
            int dx = centerX + (int) dotX;
            int dy = centerY + (int) dotY;
            graphics.fill(dx - 2, dy - 2, dx + 2, dy + 2, lit);
        }

        if (showCps.get()) {
            CpsModule cps = CpsModule.getInstance();
            String text = cps != null
                    ? cps.getLeftCps() + " | " + cps.getRightCps() + " CPS"
                    : "0 | 0 CPS";
            int textX = x + (BODY_W - mc.font.width(text)) / 2;
            graphics.text(mc.font, text, textX, y + BODY_H + 3, 0xFFFFFFFF, true);
        }
    }
}
