package gg.spaceclient.modules;

import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.setting.IntSetting;
import gg.spaceclient.setting.SettingGroup;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * How far away the last thing you hit was.
 *
 * The number people actually want is not the live distance to whatever the
 * crosshair happens to be over - that changes forty times a second and is
 * unreadable while fighting. It is the distance at the moment a hit landed,
 * held long enough to be read afterwards. That is the number that says whether
 * you are reaching further than you should be, or being hit from further than
 * someone else should be.
 *
 * <h2>Why the measurement is not the obvious one</h2>
 *
 * Distance between two entity positions is measured between their centres, and
 * a player's centre is inside their body. The reach a server checks is from
 * the eye to the nearest point of the target's box, which on a player standing
 * still is most of a block shorter. Reporting centre distance would make every
 * hit look longer than it was, which is worse than useless on a client that
 * people use to argue about reach - so both are available and the honest one
 * is the default.
 */
public class ReachModule extends HudModule {

    private final BooleanSetting toBox = new BooleanSetting(
            "to_box", "Measure to hitbox",
            "From your eye to the target's box, the way a server checks it", true);

    private final BooleanSetting holdLast = new BooleanSetting(
            "hold_last", "Hold last hit",
            "Keep a landed hit on screen instead of following the crosshair", true);

    private final IntSetting holdFor = new IntSetting(
            "hold_for", "Hold for",
            "How long the last hit stays on screen, in tenths of a second", 30, 5, 100);

    private final BooleanSetting showBest = new BooleanSetting(
            "show_best", "Show session best",
            "Also show the longest hit since the client started", false);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the reading", 0xFFFFFFFF);

    public ReachModule() {
        super("reach", "Reach", "How far away your last hit landed",
                0.02f, 0.55f, false);
        addGroups(
                SettingGroup.of("Measurement", "What the number means",
                        toBox, holdLast, holdFor),
                SettingGroup.of("Display", "What is shown",
                        showBest, textColor)
        );
    }

    @Override
    protected long refreshMillis() { return 50; }

    // --- state ---

    private double lastHit = -1;
    private long lastHitAt = 0L;
    private double best = -1;

    private boolean wasAttacking = false;

    @Override
    public void onTick() {
        if (mc.player == null) return;

        boolean attacking = mc.options != null && mc.options.keyAttack.isDown();

        // The rising edge only. Holding the button down would otherwise record
        // a hit every tick and the reading would follow the crosshair rather
        // than the swing.
        if (attacking && !wasAttacking) {
            Entity target = crosshairEntity();
            if (target != null) {
                double distance = measure(target);
                if (distance >= 0) {
                    lastHit = distance;
                    lastHitAt = System.currentTimeMillis();
                    if (distance > best) best = distance;
                }
            }
        }
        wasAttacking = attacking;
    }

    /**
     * The distance to report.
     *
     * From the eye rather than the feet, because that is where the game
     * measures from, and to the nearest point of the box rather than to the
     * centre, because that is what a server checks against.
     */
    private double measure(Entity target) {
        try {
            if (!toBox.get()) return target.distanceTo(mc.player);

            double eyeX = mc.player.getX();
            double eyeY = eyeHeight();
            double eyeZ = mc.player.getZ();

            Object box = gg.spaceclient.util.Reflect.call(target, "getBoundingBox");
            if (box == null) return target.distanceTo(mc.player);

            double minX = box(box, "minX");
            double minY = box(box, "minY");
            double minZ = box(box, "minZ");
            double maxX = box(box, "maxX");
            double maxY = box(box, "maxY");
            double maxZ = box(box, "maxZ");

            if (Double.isNaN(minX)) return target.distanceTo(mc.player);

            // Nearest point of the box to the eye, per axis
            double dx = Math.max(0, Math.max(minX - eyeX, eyeX - maxX));
            double dy = Math.max(0, Math.max(minY - eyeY, eyeY - maxY));
            double dz = Math.max(0, Math.max(minZ - eyeZ, eyeZ - maxZ));

            return Math.sqrt(dx * dx + dy * dy + dz * dz);

        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * The eye's height in world space.
     *
     * getX and getY are compiled against elsewhere in this mod and so are
     * known good; the eye accessor is not, and this version has already moved
     * enough method names to make that worth respecting. Falling back to a
     * standing player's eye height is close enough that a hit measured while
     * crouching reads a few centimetres long rather than not at all.
     */
    private double eyeHeight() {
        Double direct = gg.spaceclient.util.Reflect.asDouble(
                gg.spaceclient.util.Reflect.call(mc.player, "getEyeY"));
        if (direct != null) return direct;

        Double offset = gg.spaceclient.util.Reflect.asDouble(
                gg.spaceclient.util.Reflect.call(mc.player, "getEyeHeight"));
        return mc.player.getY() + (offset == null ? 1.62 : offset);
    }

    private static double box(Object box, String field) {
        Class<?> current = box.getClass();
        while (current != null) {
            try {
                Field found = current.getDeclaredField(field);
                found.setAccessible(true);
                return found.getDouble(box);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /**
     * Whatever the crosshair is on, by whichever route answers.
     *
     * Two of them, because the first was a guess that produced nothing on this
     * version: crosshairPickEntity is a field name that has come and gone, and
     * a module that reports "--" forever is indistinguishable from one that
     * works and has nothing to say.
     *
     * The second route goes through the hit result, which the block highlight
     * already reads successfully - so it is known to be reachable here.
     */
    private Entity crosshairEntity() {
        Object picked = readField(mc, "crosshairPickEntity");
        if (picked instanceof Entity entity) {
            route = "crosshairPickEntity";
            return entity;
        }

        Object hit = readField(mc, "hitResult");
        if (hit != null) {
            Object kind = gg.spaceclient.util.Reflect.call(hit, "getType");
            if (kind != null && "ENTITY".equalsIgnoreCase(String.valueOf(kind))) {
                Object target = gg.spaceclient.util.Reflect.call(hit, "getEntity");
                if (target instanceof Entity entity) {
                    route = "hitResult";
                    return entity;
                }
            }
        }

        route = "nothing in the crosshair";
        return null;
    }

    private static String route = "not looked up yet";

    /** Which route found the target, for the diagnostics screen. */
    public static String lastRoute() { return route; }

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

    // --- the text ---

    private String[] rows() {
        return cachedLines(() -> {
            StringBuilder out = new StringBuilder();

            boolean fresh = holdLast.get()
                    && lastHit >= 0
                    && System.currentTimeMillis() - lastHitAt < holdFor.get() * 100L;

            // A hit, if there is a recent one; otherwise whatever the crosshair
            // is on. The first version showed nothing at all between hits,
            // which is technically what "hold last hit" means and not at all
            // what somebody pointing at a player expects to see. Holding a hit
            // should outrank the live reading, not replace it.
            if (fresh) {
                out.append(String.format(Locale.ROOT, "%.2f m", lastHit));
            } else {
                Entity target = crosshairEntity();
                double live = target == null ? -1 : measure(target);
                out.append(live < 0 ? "-- m" : String.format(Locale.ROOT, "%.2f m", live));
            }

            if (showBest.get() && best >= 0) {
                out.append('\n').append(String.format(Locale.ROOT, "best %.2f m", best));
            }
            return out.toString();
        });
    }

    @Override
    public int getWidth() {
        int width = 0;
        for (String row : rows()) width = Math.max(width, mc.font.width(row));
        return Math.max(width, 40);
    }

    @Override
    public int getHeight() {
        return rows().length * (mc.font.lineHeight + 1);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        String[] rows = rows();
        for (int i = 0; i < rows.length; i++) {
            graphics.text(mc.font, rows[i], x, y + i * (mc.font.lineHeight + 1),
                    i == 0 ? textColor.get() : 0xFF9A95C9, true);
        }
    }
}
