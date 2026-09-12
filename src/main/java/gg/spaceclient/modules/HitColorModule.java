package gg.spaceclient.modules;

import gg.spaceclient.module.Module;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.setting.IntSetting;
import gg.spaceclient.setting.SettingGroup;

import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lights up entities you hit, and the one currently within your reach.
 *
 * Two separate cues, both configurable, because they answer different
 * questions: the reach tint tells you whether a swing would connect at all,
 * while the hit tint confirms one landed. Having both in one module means one
 * place to set the colours rather than two that must be kept in step.
 *
 * The colour is drawn as a translucent shell over the entity rather than by
 * recolouring its model: tinting the model needs a hook into the entity
 * renderer whose shape has not been confirmed for this version, and a shell
 * through the pipeline that already works is worth more than a tint that may
 * never draw.
 */
public class HitColorModule extends Module {

    // --- when you land a hit ---
    private final BooleanSetting hitOn = new BooleanSetting(
            "hit_on", "Show", "Light up an entity you hit", true);
    private final ColorSetting hitColor = new ColorSetting(
            "hit_color", "Colour", "Colour of the flash", 0x60FF4444);
    private final IntSetting hitDuration = new IntSetting(
            "hit_duration", "Duration (tenths of a second)", "How long the flash lasts", 4, 1, 20);
    private final BooleanSetting fadeOut = new BooleanSetting(
            "fade", "Fade out", "Let the flash fade instead of cutting off", true);

    // --- while a target is in range ---
    private final BooleanSetting reachOn = new BooleanSetting(
            "reach_on", "Show", "Light up whatever is within reach", false);
    private final ColorSetting reachColor = new ColorSetting(
            "reach_color", "Colour", "Colour of the reach tint", 0x3038E0FF);
    private final IntSetting reachDistance = new IntSetting(
            "reach_distance", "Reach (tenths of a block)",
            "How far a hit is assumed to land", 30, 20, 60);

    /** Entities hit recently, and when. */
    private final Map<Integer, Long> hits = new LinkedHashMap<>();

    private boolean wasAttacking = false;
    private Entity inReach = null;

    public HitColorModule() {
        super("hitcolor", "Hit Colour", "Tints entities you hit or can reach", false);
        addGroups(
                SettingGroup.of("On hit", "The flash when a hit lands",
                        hitOn, hitColor, hitDuration, fadeOut),
                SettingGroup.of("In reach", "The tint while a target is close enough",
                        reachOn, reachColor, reachDistance)
        );
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;

        Entity target = crosshairEntity();

        // Within reach is decided by distance, since the game's own reach is
        // not exposed to a mod in a form worth relying on
        double reach = reachDistance.get() / 10.0;
        inReach = target != null && target.distanceTo(mc.player) <= reach ? target : null;

        boolean attacking = mc.options != null && mc.options.keyAttack.isDown();
        if (attacking && !wasAttacking && inReach != null) {
            hits.put(inReach.getId(), System.currentTimeMillis());
        }
        wasAttacking = attacking;

        // Forget old flashes so the map cannot grow without bound
        long cutoff = System.currentTimeMillis() - hitDuration.get() * 100L;
        hits.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    /** What the crosshair is on, read from the game's own pick result. */
    private Entity crosshairEntity() {
        Object picked = readField(mc, "crosshairPickEntity");
        return picked instanceof Entity entity ? entity : null;
    }

    private static Object readField(Object target, String name) {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                var field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * The colour to shade an entity with, or 0 for none.
     * Called from the renderer once per entity per frame.
     */
    public int tintFor(Entity entity) {
        if (hitOn.get()) {
            Long when = hits.get(entity.getId());
            if (when != null) {
                long age = System.currentTimeMillis() - when;
                long life = hitDuration.get() * 100L;
                if (age <= life) {
                    int argb = hitColor.get();
                    if (!fadeOut.get()) return argb;

                    // Fade the alpha only, so the colour itself stays true
                    int alpha = (int) (((argb >>> 24) & 0xFF) * (1.0 - age / (double) life));
                    return (alpha << 24) | (argb & 0x00FFFFFF);
                }
            }
        }

        if (reachOn.get() && entity == inReach) {
            return reachColor.get();
        }
        return 0;
    }

    /** True when there is anything at all to draw. */
    public boolean anythingToTint() {
        return (hitOn.get() && !hits.isEmpty()) || (reachOn.get() && inReach != null);
    }
}
