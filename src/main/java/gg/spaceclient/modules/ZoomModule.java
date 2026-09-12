package gg.spaceclient.modules;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.input.RawKeyboard;
import gg.spaceclient.input.RawMouse;
import gg.spaceclient.module.Module;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.IntSetting;
import gg.spaceclient.setting.ModeSetting;

import net.minecraft.client.KeyMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Hold a key to zoom, and scroll to go further in or out.
 *
 * Two things make this feel right rather than merely work:
 *
 * The magnification is advanced by **real elapsed time**, not per tick. Ticking
 * runs twenty times a second, so a tick driven zoom moves in twenty visible
 * steps no matter the frame rate - which is exactly what a stuttering zoom
 * looks like. The value is recomputed each frame instead, from the wall clock.
 *
 * Scroll steps are **geometric**: each notch multiplies the magnification
 * rather than adding to it. Adding makes the first notch enormous and the last
 * one imperceptible; multiplying makes every notch feel the same size.
 */
public class ZoomModule extends Module {
    private final ModeSetting key = new ModeSetting(
            "key", "Zoom key", "Which key to hold",
            Arrays.asList("C", "X", "V", "B", "N", "Z", "G", "R", "F", "CTRL", "ALT", "BINDING"),
            "C");

    private final IntSetting factor = new IntSetting(
            "factor", "Zoom factor", "Magnification when the key is held", 4, 2, 12);

    private final ModeSetting transition = new ModeSetting(
            "transition", "Transition", "How the zoom eases in and out",
            Arrays.asList("INSTANT", "LINEAR", "EASE_OUT", "EASE_IN_OUT", "EXPONENTIAL"),
            "EASE_OUT");

    private final IntSetting durationIn = new IntSetting(
            "duration_in", "Zoom in time (tenths of a second)", "How long zooming in takes", 3, 0, 20);

    private final IntSetting durationOut = new IntSetting(
            "duration_out", "Zoom out time (tenths of a second)", "How long zooming out takes", 3, 0, 20);

    private final BooleanSetting scrollZoom = new BooleanSetting(
            "scroll_zoom", "Scroll to adjust", "Use the wheel while zoomed to go further", true);

    private final IntSetting stepAmount = new IntSetting(
            "step_amount", "Per scroll step (percent)", "How much one notch multiplies by", 150, 105, 300);

    private final IntSetting stepCount = new IntSetting(
            "step_count", "Scroll steps", "How many notches are allowed", 4, 1, 12);

    private final BooleanSetting slowSensitivity = new BooleanSetting(
            "slow_sensitivity", "Reduce sensitivity", "Aim slower the further you zoom", true);

    private final BooleanSetting keepSteps = new BooleanSetting(
            "keep_steps", "Remember scroll steps", "Keep the wheel position between zooms", false);

    // --- live state ---
    private double progress = 0;        // 0 while not zooming, 1 at full zoom
    private double scrollProgress = 0;  // smoothed position between scroll steps
    private int scrollSteps = 0;
    private long lastFrame = 0;

    private double normalSensitivity = -1;
    private boolean warned = false;

    private static String lastResult = "not attempted";
    public static String lastResult() { return lastResult; }

    private static volatile boolean MIXIN_ACTIVE = false;

    public static void markMixinActive() {
        if (!MIXIN_ACTIVE) {
            MIXIN_ACTIVE = true;
            lastResult = "renderer hook active";
        }
    }

    public static boolean isMixinActive() { return MIXIN_ACTIVE; }

    public ZoomModule() {
        super("zoom", "Zoom", "Hold to zoom, scroll to go further", false);
        addSettings(key, factor, transition, durationIn, durationOut,
                scrollZoom, stepAmount, stepCount, slowSensitivity, keepSteps);
    }

    private boolean keyDown() {
        if (!key.is("BINDING") && RawKeyboard.isAvailable()) {
            int code = RawKeyboard.codeFor(key.get());
            if (code != org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN) {
                return RawKeyboard.isDown(code);
            }
        }
        KeyMapping binding = SpaceClient.getZoomKey();
        return binding != null && binding.isDown();
    }

    @Override
    public void onTick() {
        boolean zooming = keyDown();

        // The wheel only belongs to the zoom while it is actually held
        RawMouse.setCapture(zooming && scrollZoom.get() && RawMouse.isInstalled());

        if (zooming && scrollZoom.get()) {
            int notches = RawMouse.consumeSteps();
            if (notches != 0) {
                scrollSteps = Math.max(0, Math.min(stepCount.get(), scrollSteps + notches));
            }
        }

        if (!zooming && !keepSteps.get()) {
            scrollSteps = 0;
        }

        handleSensitivity();
    }

    /**
     * The divisor the renderer applies. Called once per frame, which is what
     * makes the movement smooth: the value is advanced by however much real
     * time has passed since the previous frame.
     */
    public float currentFactor() {
        long now = System.nanoTime();
        double delta = lastFrame == 0 ? 0 : (now - lastFrame) / 1_000_000_000.0;
        lastFrame = now;

        // A pause or a lag spike would otherwise jump the zoom across
        delta = Math.min(delta, 0.1);

        boolean zooming = keyDown();
        double seconds = (zooming ? durationIn.get() : durationOut.get()) / 10.0;

        if (seconds <= 0.001) {
            progress = zooming ? 1 : 0;
        } else {
            double step = delta / seconds;
            progress += zooming ? step : -step;
            progress = Math.max(0, Math.min(1, progress));
        }

        // The scroll position eases as well, so a notch glides rather than jumps
        double scrollTarget = stepCount.get() > 0
                ? scrollSteps / (double) stepCount.get()
                : 0;
        double scrollStep = delta / 0.25;
        if (scrollProgress < scrollTarget) {
            scrollProgress = Math.min(scrollTarget, scrollProgress + scrollStep);
        } else {
            scrollProgress = Math.max(scrollTarget, scrollProgress - scrollStep);
        }

        double eased = ease(progress);
        double base = 1.0 + (factor.get() - 1.0) * eased;

        // Each notch multiplies, so every step feels the same size
        double multiplier = stepAmount.get() / 100.0;
        double steps = scrollProgress * stepCount.get();
        double scrolled = Math.pow(multiplier, steps);

        return (float) (base * (eased > 0.001 ? scrolled : 1.0));
    }

    /** Shapes the 0 to 1 progress into a curve. */
    private double ease(double t) {
        return switch (transition.get()) {
            case "INSTANT" -> t > 0 ? 1 : 0;
            case "LINEAR" -> t;
            case "EASE_IN_OUT" -> t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
            case "EXPONENTIAL" -> t == 0 ? 0 : Math.pow(2, 10 * t - 10);
            default -> 1 - Math.pow(1 - t, 3); // EASE_OUT
        };
    }

    private void handleSensitivity() {
        if (!slowSensitivity.get() || mc.options == null) return;

        double zoom = currentFactor();
        Object sensitivityOption = mc.options.sensitivity();

        if (zoom > 1.05) {
            if (normalSensitivity < 0) {
                Double value = readOption(sensitivityOption);
                if (value != null) normalSensitivity = value;
            }
            if (normalSensitivity >= 0) {
                writeOption(sensitivityOption, normalSensitivity / zoom);
            }
        } else if (normalSensitivity >= 0) {
            writeOption(sensitivityOption, normalSensitivity);
            normalSensitivity = -1;
        }
    }

    @Override
    protected void onDisable() {
        RawMouse.setCapture(false);
        if (normalSensitivity >= 0 && mc.options != null) {
            writeOption(mc.options.sensitivity(), normalSensitivity);
            normalSensitivity = -1;
        }
        progress = 0;
        scrollProgress = 0;
        scrollSteps = 0;
    }

    private void warnOnce() {
        if (warned) return;
        warned = true;
        SpaceClient.LOGGER.warn("Zoom could not read an option on this version");
    }

    private Double readOption(Object option) {
        if (option == null) return null;
        for (String name : new String[]{"get", "getValue", "value"}) {
            try {
                Method method = option.getClass().getMethod(name);
                Object value = method.invoke(option);
                if (value instanceof Number number) return number.doubleValue();
            } catch (Exception ignored) {
                // Try the next name
            }
        }
        warnOnce();
        return null;
    }

    /** Boxes the value to whatever type the option already holds. */
    private void writeOption(Object option, double value) {
        if (option == null) return;

        Object current = null;
        for (String getter : new String[]{"get", "getValue", "value"}) {
            try {
                current = option.getClass().getMethod(getter).invoke(option);
                if (current != null) break;
            } catch (Exception ignored) {
                // Try the next getter
            }
        }

        Object argument = current instanceof Integer
                ? Integer.valueOf((int) Math.round(value))
                : current instanceof Float
                        ? Float.valueOf((float) value)
                        : Double.valueOf(value);

        for (String name : new String[]{"set", "setValue"}) {
            for (Method method : option.getClass().getMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getParameterCount() != 1) continue;
                try {
                    method.invoke(option, argument);
                    return;
                } catch (Exception ignored) {
                    // Try the next overload
                }
            }
        }
    }
}
