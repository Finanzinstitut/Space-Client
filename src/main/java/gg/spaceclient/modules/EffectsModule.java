package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.setting.IntSetting;
import gg.spaceclient.setting.SettingGroup;
import gg.spaceclient.util.Reflect;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

/**
 * Your active effects, as a list you can read at a glance.
 *
 * The game shows these as icons in the top right with no numbers at all, and
 * then hides them entirely the moment an inventory is open - which is exactly
 * when somebody is deciding whether to drink another potion. What matters is
 * not that you have Strength, it is that it runs out in nine seconds.
 *
 * <h2>Sorted by what is about to leave</h2>
 *
 * Shortest remaining first, rather than alphabetically or by whatever order
 * the game happens to hold them in. The list is read under pressure and the
 * thing worth knowing is always at the top - an effect with four minutes left
 * is not news.
 */
public class EffectsModule extends HudModule {

    private final BooleanSetting showTime = new BooleanSetting(
            "show_time", "Time left", "How long each effect has to run", true);

    private final BooleanSetting showLevel = new BooleanSetting(
            "show_level", "Level", "The effect's strength as a numeral", true);

    /**
     * Whether effects with no end are shown at all.
     *
     * Off by default. A beacon effect that lasts as long as you stand there is
     * true but not information, and on a survival server it would sit at the
     * bottom of the list forever taking up a line.
     */
    private final BooleanSetting showInfinite = new BooleanSetting(
            "show_infinite", "Endless effects", "Include effects with no timer", false);

    private final IntSetting warnAt = new IntSetting(
            "warn_at", "Warn under", "Seconds remaining before an effect turns amber",
            10, 0, 60);

    private final IntSetting limit = new IntSetting(
            "limit", "Show at most", "How many effects to list", 6, 1, 12);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour while there is time left", 0xFFFFFFFF);

    public EffectsModule() {
        super("effects", "Effects", "Active potion effects with their timers",
                0.85f, 0.08f, false);
        addGroups(
                SettingGroup.of("Readout", "What each line says",
                        showTime, showLevel, showInfinite, limit),
                SettingGroup.of("Appearance", "How it looks",
                        warnAt, textColor)
        );
    }

    @Override
    protected long refreshMillis() { return 200; }

    /** One effect, reduced to what a line needs. */
    private record Active(String name, int level, int ticks) {}

    private java.util.List<Active> read() {
        java.util.List<Active> out = new java.util.ArrayList<>();
        if (mc.player == null) return out;

        Object effects = Reflect.call(mc.player, "getActiveEffects", "getStatusEffects");
        if (!(effects instanceof java.util.Collection<?> collection)) return out;

        for (Object instance : collection) {
            try {
                Object durationValue = Reflect.call(instance, "getDuration");
                int ticks = durationValue instanceof Number number ? number.intValue() : -1;

                boolean endless = ticks < 0 || ticks > 20 * 60 * 60;
                if (endless && !showInfinite.get()) continue;

                Object amplifierValue = Reflect.call(instance, "getAmplifier");
                int level = (amplifierValue instanceof Number number ? number.intValue() : 0) + 1;

                out.add(new Active(nameOf(instance), level, endless ? Integer.MAX_VALUE : ticks));

            } catch (Throwable ignored) {
                // Skip this one rather than lose the list
            }
        }

        out.sort(java.util.Comparator.comparingInt(Active::ticks));
        return out;
    }

    /**
     * A readable name for an effect.
     *
     * Reached through the effect's own description key and tidied up, because
     * the alternative is a translated component and the route to one has not
     * been established on this version. "effect.minecraft.fire_resistance"
     * becomes "Fire Resistance", which is what it says on the tin.
     */
    private String nameOf(Object instance) {
        Object effect = Reflect.call(instance, "getEffect");

        Object key = Reflect.call(effect, "getDescriptionId");
        if (key == null && effect != null) {
            Object value = Reflect.call(effect, "value");
            key = Reflect.call(value, "getDescriptionId");
        }

        String text = key instanceof String string ? string : String.valueOf(effect);

        int dot = text.lastIndexOf('.');
        if (dot >= 0 && dot < text.length() - 1) text = text.substring(dot + 1);

        text = text.replace('_', ' ').trim();
        if (text.isEmpty()) return "Effect";

        StringBuilder pretty = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            if (pretty.length() > 0) pretty.append(' ');
            pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return pretty.toString();
    }

    private static final String[] NUMERALS = {"", "II", "III", "IV", "V", "VI", "VII", "VIII"};

    private static String numeral(int level) {
        if (level <= 1) return "";
        return level - 1 < NUMERALS.length ? NUMERALS[level - 1] : Integer.toString(level);
    }

    private static String clock(int ticks) {
        if (ticks == Integer.MAX_VALUE) return "**";

        int seconds = Math.max(0, ticks / 20);
        int minutes = seconds / 60;
        seconds %= 60;
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private String[] rows() {
        return cachedLines(() -> {
            java.util.List<Active> active = read();
            if (active.isEmpty()) return "";

            StringBuilder out = new StringBuilder();
            int shown = 0;

            for (Active effect : active) {
                if (shown >= limit.get()) break;
                if (shown > 0) out.append('\n');

                out.append(effect.name());

                if (showLevel.get()) {
                    String numeral = numeral(effect.level());
                    if (!numeral.isEmpty()) out.append(' ').append(numeral);
                }
                if (showTime.get()) {
                    out.append("  ").append(clock(effect.ticks()));
                }
                shown++;
            }
            return out.toString();
        });
    }

    /** Amber once the shortest effect is nearly gone. */
    private int colour() {
        java.util.List<Active> active = read();
        if (active.isEmpty()) return textColor.get();

        int soonest = active.get(0).ticks();
        if (soonest != Integer.MAX_VALUE && soonest / 20 <= warnAt.get()) return 0xFFE8C46A;
        return textColor.get();
    }

    @Override
    public int getWidth() {
        int width = 0;
        for (String row : rows()) width = Math.max(width, mc.font.width(row));
        return Math.max(width, 40);
    }

    @Override
    public int getHeight() {
        return Math.max(1, rows().length) * (mc.font.lineHeight + 1);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        String[] rows = rows();
        if (rows.length == 0) return;

        int colour = colour();
        for (int i = 0; i < rows.length; i++) {
            // Only the first line carries the warning colour: it is the one
            // about to run out, and colouring the rest would say the same
            // thing about effects that are fine
            graphics.text(mc.font, rows[i], x, y + i * (mc.font.lineHeight + 1),
                    i == 0 ? colour : textColor.get(), true);
        }
    }
}
