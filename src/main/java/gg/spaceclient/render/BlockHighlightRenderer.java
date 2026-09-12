package gg.spaceclient.render;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.modules.BlockHighlightModule;
import gg.spaceclient.util.Reflect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;

/**
 * Draws the selection marker for the block under the crosshair.
 *
 * Rides the same route the hitboxes take - submitted at the tail of the world
 * renderer's own features, through the collector, as quads. That path was
 * worked out once for this version and there is no reason to solve it twice.
 *
 * <h2>The block's real shape, not a cube</h2>
 *
 * Half the blocks worth selecting are not cubes. A slab, a fence post, a chest
 * and a carpet all have their own outline, and a marker drawn as a unit cube
 * around any of them is worse than vanilla's thin line because it is confidently
 * wrong. So the shape is asked for, and only when that cannot be reached does
 * this fall back to a cube - which at least matches for the blocks people spend
 * most of their time pointing at.
 */
public final class BlockHighlightRenderer {

    private static boolean failed = false;
    private static String failure = "";
    private static boolean everDrew = false;

    private BlockHighlightRenderer() {}

    public static boolean hasFailed() { return failed; }

    public static String status() {
        if (failed) return failure;
        return everDrew ? "drawing" : "nothing selected yet";
    }

    /** Called once per frame from the world renderer's tail. */
    public static void submit(SubmitNodeCollector collector) {
        if (failed) return;

        try {
            var manager = SpaceClient.getModuleManager();
            if (manager == null) return;

            var module = manager.get("blockhighlight");
            if (!(module instanceof BlockHighlightModule highlight)) return;
            if (!highlight.wantsOutline() && !highlight.wantsOverlay()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;

            Object hit = readField(mc, "hitResult");
            if (hit == null) return;

            // Only an actual block counts. A result of type MISS still answers
            // getBlockPos - with the empty space the ray stopped in - so asking
            // for a position is not enough of a test on its own, and the
            // marker ended up floating in mid air whenever nothing was hit.
            Object kind = Reflect.call(hit, "getType");
            if (kind == null || !"BLOCK".equalsIgnoreCase(String.valueOf(kind))) return;

            Object pos = Reflect.call(hit, "getBlockPos");
            if (pos == null) return;

            // Belt and braces: an air block means the ray landed on nothing
            // worth marking even if the result claims otherwise
            Object state = Reflect.callWith(mc.level, "getBlockState", pos);
            Object empty = Reflect.call(state, "isAir");
            if (empty instanceof Boolean air && air) return;

            Vec3 camera = HitboxRenderer.cameraPosition(mc);
            if (camera == null) return;

            RenderType type = HitboxRenderer.lineType();
            if (type == null) return;

            AABB shape = shapeOf(mc, pos);
            if (shape == null) return;

            AABB box = shape.move(-camera.x, -camera.y, -camera.z);
            everDrew = true;

            if (highlight.wantsOverlay()) {
                // Grown by a hair so the shading sits just outside the block's
                // own surface. Drawn exactly on it, the two fight for the same
                // depth and the face flickers as the camera moves.
                AABB shaded = box.inflate(0.002);

                if (highlight.facesOnly()) {
                    AABB face = faceOf(shaded, hit);
                    if (face != null) {
                        HitboxRenderer.submitFilled(collector, type, face,
                                highlight.overlayColor());
                    }
                } else {
                    HitboxRenderer.submitFilled(collector, type, shaded,
                            highlight.overlayColor());
                }
            }

            if (highlight.wantsOutline()) {
                double thickness = highlight.edgeThickness();
                HitboxRenderer.submitBox(collector, type,
                        box.inflate(thickness / 2 + 0.001),
                        highlight.outlineColor(), thickness);
            }

        } catch (Throwable t) {
            failed = true;
            failure = "stopped after " + t.getClass().getSimpleName();
            SpaceClient.LOGGER.warn("Block highlight disabled: {}", String.valueOf(t));
        }
    }

    /**
     * The block's outline in world coordinates.
     *
     * Tries the real shape first. A voxel shape can be several boxes - a fence
     * is a post and up to four arms - and the enclosing box of all of them is
     * a better marker than either one alone, so they are merged.
     */
    private static AABB shapeOf(Minecraft mc, Object pos) {
        double x = intOf(Reflect.call(pos, "getX"));
        double y = intOf(Reflect.call(pos, "getY"));
        double z = intOf(Reflect.call(pos, "getZ"));

        try {
            Object state = Reflect.callWith(mc.level, "getBlockState", pos);
            if (state != null) {
                Object shape = Reflect.callWith(state, "getShape", mc.level, pos);
                if (shape == null) shape = Reflect.call(state, "getOutlineShape");

                if (shape != null) {
                    Object boxes = Reflect.call(shape, "toAabbs");
                    if (boxes instanceof java.util.List<?> list && !list.isEmpty()) {
                        AABB merged = null;
                        for (Object entry : list) {
                            if (!(entry instanceof AABB aabb)) continue;
                            merged = merged == null ? aabb : merged.minmax(aabb);
                        }
                        if (merged != null) return merged.move(x, y, z);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the cube
        }

        return new AABB(x, y, z, x + 1, y + 1, z + 1);
    }

    /**
     * Flattens the box onto the side being pointed at.
     *
     * The direction comes off the hit result; without it there is no face to
     * pick and the caller falls back to shading nothing rather than guessing.
     */
    private static AABB faceOf(AABB box, Object hit) {
        Object direction = Reflect.call(hit, "getDirection");
        if (direction == null) return null;

        String name = String.valueOf(direction).toUpperCase(java.util.Locale.ROOT);

        return switch (name) {
            case "DOWN" -> new AABB(box.minX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ);
            case "UP" -> new AABB(box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ);
            case "NORTH" -> new AABB(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ);
            case "SOUTH" -> new AABB(box.minX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ);
            case "WEST" -> new AABB(box.minX, box.minY, box.minZ, box.minX, box.maxY, box.maxZ);
            case "EAST" -> new AABB(box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            default -> null;
        };
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Object readField(Object target, String name) {
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
}
