package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.setting.IntSetting;
import gg.spaceclient.setting.SettingGroup;
import gg.spaceclient.util.Reflect;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.lang.reflect.Field;

/**
 * The two hunger numbers the game keeps and never shows you.
 *
 * The haunches on the vanilla HUD are the last of three values, and the least
 * useful of them. Underneath sits saturation - a hidden buffer that is spent
 * before any haunch moves - and exhaustion, which fills up as you sprint, jump
 * and swing, and every time it reaches four it takes a point of saturation
 * away. A full hunger bar tells you nothing about whether you are one sprint
 * from starting to drop.
 *
 * That matters most in the two situations this client is built around. Before
 * a fight, full haunches with empty saturation means you should eat now rather
 * than mid-combo. And while building, an empty buffer is what turns a quiet
 * afternoon into a walk back to the chests.
 *
 * <h2>The forecast</h2>
 *
 * Exhaustion rises at a rate that depends entirely on what you are doing, so
 * there is no table to look it up in - sprinting and jumping is worlds away
 * from standing still. Instead the module watches how fast it has actually
 * been rising for you, over the last few seconds, and turns that into a
 * countdown to when haunches start disappearing. Stop moving and the estimate
 * stretches out; start sprinting and it collapses.
 *
 * <h2>Why every read goes through reflection</h2>
 *
 * Exhaustion has no getter - it is a private field on the hunger data - and
 * the accessors around it have not been proven against this version by a
 * compile. Following this mod's rule, all three go through Reflect, so a
 * renamed field costs a dash on screen instead of a build.
 */
public class NutritionModule extends HudModule {

    /** The exhaustion that has to build up before a point of food is spent. */
    private static final float PER_POINT = 4.0f;

    // --- what to show ---

    private final BooleanSetting showHunger = new BooleanSetting(
            "show_hunger", "Hunger", "The value the haunches already show", true);

    private final BooleanSetting showSaturation = new BooleanSetting(
            "show_saturation", "Saturation", "The hidden buffer spent before haunches move", true);

    private final BooleanSetting showExhaustion = new BooleanSetting(
            "show_exhaustion", "Exhaustion", "Fills to four, then takes a point away", true);

    private final BooleanSetting showForecast = new BooleanSetting(
            "show_forecast", "Countdown", "Estimated time until haunches start dropping", true);

    private final BooleanSetting compact = new BooleanSetting(
            "compact", "One line", "Everything on a single row instead of stacked", false);

    // --- appearance ---

    private final IntSetting warnAt = new IntSetting(
            "warn_at", "Warn below", "Saturation percentage that turns the readout amber",
            25, 0, 100);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour while the buffer is healthy", 0xFFFFFFFF);

    public NutritionModule() {
        super("nutrition", "Nutrition",
                "Saturation and exhaustion, the hunger values the game hides",
                0.02f, 0.70f, false);
        addGroups(
                SettingGroup.of("Readout", "Which of the three values to show",
                        showHunger, showSaturation, showExhaustion, showForecast, compact),
                SettingGroup.of("Appearance", "How it looks on screen",
                        warnAt, textColor)
        );
    }

    @Override
    protected long refreshMillis() { return 200; }

    // --- reading the hunger data ---

    private Object foodData() {
        if (mc.player == null) return null;
        return Reflect.call(mc.player, "getFoodData", "getHungerManager");
    }

    private float hunger(Object food) {
        Double value = Reflect.asDouble(Reflect.call(food, "getFoodLevel"));
        return value == null ? -1f : value.floatValue();
    }

    private float saturation(Object food) {
        Double value = Reflect.asDouble(Reflect.call(food, "getSaturationLevel"));
        return value == null ? -1f : value.floatValue();
    }

    /**
     * Exhaustion, which has no getter at all and is read off the field.
     *
     * The two names cover the obfuscated-to-readable spread this version might
     * carry; whichever exists first wins.
     */
    private float exhaustion(Object food) {
        Object value = readField(food, "exhaustionLevel");
        if (value == null) value = readField(food, "exhaustion");
        return value instanceof Number number ? number.floatValue() : -1f;
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    // --- measuring how fast exhaustion is rising ---

    private float lastExhaustion = -1f;
    private long lastSampleAt = 0L;

    /**
     * Exhaustion per second, smoothed.
     *
     * A rolling average rather than the latest sample, because the raw rate
     * swings between zero and its peak from one step to the next, and a
     * countdown that jumps between "8s" and "four minutes" is worse than no
     * countdown. Weighted towards history so it settles quickly when you stop
     * but does not pretend a single sprint step is the new normal.
     */
    private float ratePerSecond = 0f;

    private void sample(float exhaustionNow) {
        long now = System.currentTimeMillis();

        if (lastExhaustion < 0f || lastSampleAt == 0L) {
            lastExhaustion = exhaustionNow;
            lastSampleAt = now;
            return;
        }

        float elapsed = (now - lastSampleAt) / 1000f;
        if (elapsed < 0.15f) return;

        float delta = exhaustionNow - lastExhaustion;

        // A drop means the counter hit four and reset while we were not
        // looking, so the real rise is what is left plus what came after
        if (delta < 0f) delta += PER_POINT;

        float sampled = delta / elapsed;

        // Ignore obvious nonsense from a world change or a teleport
        if (sampled >= 0f && sampled < 20f) {
            ratePerSecond = ratePerSecond * 0.7f + sampled * 0.3f;
        }

        lastExhaustion = exhaustionNow;
        lastSampleAt = now;
    }

    /**
     * Seconds until haunches start moving, or -1 when it cannot be said.
     *
     * Saturation is spent one point at a time, each costing four exhaustion,
     * and only once it is gone does food itself start falling. So the wait is
     * whatever is left of the current point plus four for every point of
     * buffer still standing.
     */
    private float secondsUntilDrop(float saturation, float exhaustion) {
        if (ratePerSecond <= 0.01f) return -1f;
        float needed = (PER_POINT - exhaustion)
                + PER_POINT * (float) Math.ceil(Math.max(0f, saturation));
        return needed / ratePerSecond;
    }

    private static String seconds(float value) {
        if (value < 0f) return "--";
        if (value >= 600f) return "10m+";
        if (value >= 60f) return Math.round(value / 60f) + "m";
        return Math.round(value) + "s";
    }

    private static String twoPlaces(float value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    // --- the text ---

    private java.util.List<String> lines() {
        java.util.List<String> out = new java.util.ArrayList<>();

        Object food = foodData();
        if (food == null) {
            out.add("Nutrition --");
            return out;
        }

        float hunger = hunger(food);
        float saturation = saturation(food);
        float exhaustion = exhaustion(food);

        if (exhaustion >= 0f) sample(exhaustion);

        java.util.List<String> parts = new java.util.ArrayList<>();
        if (showHunger.get()) {
            parts.add("Food " + (hunger < 0f ? "--" : Integer.toString(Math.round(hunger))));
        }
        if (showSaturation.get()) {
            parts.add("Sat " + (saturation < 0f ? "--" : twoPlaces(saturation)));
        }
        if (showExhaustion.get()) {
            parts.add("Exh " + (exhaustion < 0f ? "--" : twoPlaces(exhaustion)));
        }
        if (showForecast.get()) {
            parts.add("Drop " + (saturation < 0f || exhaustion < 0f
                    ? "--"
                    : seconds(secondsUntilDrop(saturation, exhaustion))));
        }

        if (parts.isEmpty()) parts.add("Nutrition");

        if (compact.get()) {
            out.add(String.join("  ", parts));
        } else {
            out.addAll(parts);
        }
        return out;
    }

    /**
     * Amber once the buffer is low, red once it is gone.
     *
     * The colour follows saturation rather than hunger, because that is the
     * value worth reacting to: haunches falling means you already missed it.
     */
    private int colour() {
        Object food = foodData();
        if (food == null) return textColor.get();

        float saturation = saturation(food);
        if (saturation < 0f) return textColor.get();

        if (saturation <= 0.05f) return 0xFFE86A6A;
        if (saturation * 100f / 20f <= warnAt.get()) return 0xFFE8C46A;
        return textColor.get();
    }

    @Override
    public int getWidth() {
        int width = 0;
        for (String line : rows()) width = Math.max(width, mc.font.width(line));
        return Math.max(width, 40);
    }

    @Override
    public int getHeight() {
        return rows().length * (mc.font.lineHeight + 1);
    }

    /** One rebuild and one split per refresh window, shared by all three. */
    private String[] rows() {
        return cachedLines(() -> String.join("\n", lines()));
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        int colour = colour();
        String[] rows = rows();
        for (int i = 0; i < rows.length; i++) {
            graphics.text(mc.font, rows[i], x, y + i * (mc.font.lineHeight + 1), colour, true);
        }
    }
}
