package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.ui.Odometer;
import gg.spaceclient.ui.Pulse;
import gg.spaceclient.util.Reflect;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Health as a number rather than as hearts.
 *
 * Hearts are readable at full and unreadable in a fight. Half a heart of
 * difference decides whether one more hit is survivable, and counting hearts
 * while they shake and flash is exactly what nobody can do at the moment it
 * matters. A number does not shake.
 *
 * Absorption is separate rather than added in, because it behaves differently:
 * it does not regenerate and it disappears all at once, so a total that mixes
 * the two hides the part that is about to vanish.
 *
 * Every value is read reflectively. None of these methods is called anywhere
 * else in this mod, so nothing proves them, and a missing dash beats a build
 * that does not compile.
 */
public class HealthModule extends HudModule {

    private final BooleanSetting showAbsorption = new BooleanSetting(
            "absorption", "Show absorption", "List golden hearts separately", true);

    private final BooleanSetting showHunger = new BooleanSetting(
            "hunger", "Show hunger", "Include the food level", true);

    private final BooleanSetting decimals = new BooleanSetting(
            "decimals", "Show halves", "One decimal place instead of whole hearts", true);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour when health is not low", 0xFFFFFFFF);

    /**
     * Fires when health drops, not when it rises.
     *
     * Regenerating is not news. Being hit is, and it is the moment you are
     * least able to read a number - so the number reaches out instead.
     */
    private final Pulse hurt = new Pulse();

    /**
     * Rolls the whole readout, not just the health figure.
     *
     * Only characters that changed move, so a heart of regeneration rolls the
     * last digit and leaves the rest still. Taking real damage changes several
     * at once and the whole thing turns over, which is the difference worth
     * seeing.
     */
    private final Odometer shown = new Odometer();

    public HealthModule() {
        super("health", "Health",
                "Health, absorption and hunger as numbers",
                0.02f, 0.56f, false);
        addSettings(showAbsorption, showHunger, decimals, textColor);
    }

    @Override
    protected long refreshMillis() { return 100; }

    private float readFloat(Object target, String... names) {
        try {
            Object value = Reflect.call(target, names);
            if (value instanceof Float number) return number;
            if (value instanceof Integer number) return number;
            if (value instanceof Double number) return number.floatValue();
        } catch (Throwable ignored) {
            // Falls through to the sentinel
        }
        return -1f;
    }

    private float health() {
        return mc.player == null ? -1f : readFloat(mc.player, "getHealth");
    }

    private float absorption() {
        return mc.player == null ? -1f : readFloat(mc.player, "getAbsorptionAmount");
    }

    private int hunger() {
        try {
            if (mc.player == null) return -1;
            Object food = Reflect.call(mc.player, "getFoodData");
            if (food == null) return -1;
            return Math.round(readFloat(food, "getFoodLevel"));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private String format(float value) {
        if (value < 0) return "--";
        if (decimals.get()) return String.format("%.1f", value);
        return Integer.toString(Math.round(value));
    }

    private String text() {
        return cachedText(() -> {
            StringBuilder out = new StringBuilder(format(health()));

            float shield = absorption();
            if (showAbsorption.get() && shield > 0) {
                out.append(" +").append(format(shield));
            }
            if (showHunger.get()) {
                int food = hunger();
                out.append("   ").append(food < 0 ? "--" : food);
            }
            return out.toString();
        });
    }

    /**
     * Amber below half, red below a quarter.
     *
     * Thresholds rather than a gradient: a colour that shifts continuously is a
     * colour nobody notices changing, and the point is to be noticed.
     */
    private int colour() {
        float value = health();
        hurt.watchDrop(value);
        if (value < 0) return 0xFF808080;
        int base = value <= 5f ? 0xFFE86A6A
                : value <= 10f ? 0xFFE8C46A
                : textColor.get();
        return hurt.tint(base, 0xFFFF4F6D);
    }

    @Override
    public int getWidth() { return mc.font.width(text()); }

    @Override
    public int getHeight() { return mc.font.lineHeight; }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        // Colour first: it reads health and fires the damage highlight, and the
        // roll should start in the same frame that highlight does
        int colour = colour();
        shown.set(text());
        shown.draw(graphics, mc.font, x, y, colour, true);
    }
}
