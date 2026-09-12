package gg.spaceclient.render;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.modules.HitColorModule;
import gg.spaceclient.modules.HitboxModule;
import gg.spaceclient.util.Reflect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * Draws the hitboxes through 26.2's submit based render pipeline.
 *
 * Everything before this was built on `LevelRenderer.renderLineBox`, which does
 * not exist in this version at all - the render rework replaced it. Geometry is
 * now handed to a SubmitNodeCollector, which is what PolyHitbox does and what
 * this follows: the collector takes a pose, a render type and a callback that
 * writes the vertices.
 */
public final class HitboxRenderer {
    /** One identity pose, reused: the coordinates are already world relative. */
    private static final PoseStack IDENTITY = new PoseStack();

    private static Method renderTypeGetter;
    private static boolean lookedUp = false;
    private static boolean warned = false;
    private static boolean available = false;

    /** Set once drawing has thrown, so it is not retried every frame. */
    private static boolean failed = false;
    private static String failure = "";

    public static boolean hasFailed() { return failed; }
    public static String failure() { return failure; }

    public static void setAvailable(boolean value) { available = value; }
    public static boolean isAvailable() { return available; }

    /** Called from the mixin once per frame, after the world's own features. */
    public static void submit(SubmitNodeCollector collector) {
        if (failed) return;

        HitboxModule module = (HitboxModule) SpaceClient.getModuleManager().get("hitbox");
        HitColorModule tint = (HitColorModule) SpaceClient.getModuleManager().get("hitcolor");

        boolean wantBoxes = module != null && module.isEnabled() && module.anyCategoryOn();
        boolean wantTint = tint != null && tint.isEnabled() && tint.anythingToTint();
        if (!wantBoxes && !wantTint) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        RenderType lines = lineType();
        if (lines == null) {
            warnOnce("no line render type found");
            return;
        }

        // The camera sits at the origin of the render space, so every box has
        // to be moved by its position.
        Vec3 camera = cameraPosition(mc);
        if (camera == null) {
            warnOnce("camera position unavailable");
            return;
        }

        available = true;
        float partialTick = partialTick(mc);

        for (Entity entity : mc.level.entitiesForRendering()) {
            // Anything the game has hidden stays hidden, unless asked otherwise.
            // A spectator is invisible to everyone and should never show up.
            boolean invisible = entity.isInvisible()
                    || (entity instanceof net.minecraft.world.entity.player.Player player
                        && player.isSpectator());
            if (invisible && !(wantBoxes && module != null && module.showInvisible())) continue;

            // The tinted shell is drawn first, so an outline sits on top of it
            if (wantTint) {
                int shade = tint.tintFor(entity);
                if (shade != 0) {
                    AABB shell = interpolated(entity, partialTick)
                            .move(-camera.x, -camera.y, -camera.z)
                            .inflate(0.02);
                    submitFilled(collector, lines, shell, shade);
                }
            }

            if (!wantBoxes || module == null) continue;

            HitboxModule.Category category = module.categoryOf(entity);
            if (!module.isEnabledFor(category)) continue;
            if (entity.distanceTo(mc.player) > module.getRange()) continue;

            // Optional: skip anything the player cannot actually see
            if (module.hideBehindWalls() && !mc.player.hasLineOfSight(entity)) continue;

            // The box has to follow the entity's *drawn* position, not the one
            // from the last tick. Entities are rendered between ticks, so an
            // uninterpolated box lags behind anything that moves and jitters
            // twenty times a second - which is what the boxes were doing.
            AABB box = interpolated(entity, partialTick)
                    .move(-camera.x, -camera.y, -camera.z);
            int argb = module.colorFor(category);
            int width = module.widthFor(category);
            boolean arrow = module.arrowFor(category);

            // Each unit of width is about a sixth of a block wide slab. The box
            // is also nudged outwards by half of that: an edge sitting exactly
            // on the model's surface flickers against it as the camera moves.
            double thickness = width * 0.008;
            box = box.inflate(thickness / 2);
            submitBox(collector, lines, box, argb, thickness);

            if (arrow) {
                Vec3 eyes = entity.getEyePosition(partialTick).subtract(camera);
                Vec3 tip = eyes.add(entity.getViewVector(partialTick).scale(2.0));
                submitLine(collector, lines, eyes, tip, 0xFF0000FF, thickness);
            }
        }
    }

    /** Twelve edges of a box, written as line pairs. */
    static void submitBox(SubmitNodeCollector collector, RenderType type,
                                  AABB box, int argb, double thickness) {
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;

        double[][] edges = {
                {x1, y1, z1, x2, y1, z1}, {x2, y1, z1, x2, y1, z2},
                {x2, y1, z2, x1, y1, z2}, {x1, y1, z2, x1, y1, z1},
                {x1, y2, z1, x2, y2, z1}, {x2, y2, z1, x2, y2, z2},
                {x2, y2, z2, x1, y2, z2}, {x1, y2, z2, x1, y2, z1},
                {x1, y1, z1, x1, y2, z1}, {x2, y1, z1, x2, y2, z1},
                {x2, y1, z2, x2, y2, z2}, {x1, y1, z2, x1, y2, z2},
        };

        // The callback runs later, while the frame is being built, so a failure
        // inside it lands in the render thread rather than here. Guarding the
        // submit alone was not enough: that is how a bad vertex took the whole
        // game down instead of just switching the module off.
        collector.submitCustomGeometry(IDENTITY, type, (pose, buffer) -> {
            try {
                for (double[] edge : edges) {
                    writeEdge(buffer, edge[0], edge[1], edge[2], edge[3], edge[4], edge[5],
                            thickness, argb);
                }
            } catch (Throwable t) {
                disableAfterFailure(t);
            }
        });
    }

    /**
     * A solid, translucent shell around an entity.
     *
     * Six faces rather than an outline: the point is to shade the whole body,
     * and a box the size of the hitbox is close enough to it that the tint
     * reads as the entity glowing.
     */
    static void submitFilled(SubmitNodeCollector collector, RenderType type,
                                     AABB box, int argb) {
        double x1 = box.minX, y1 = box.minY, z1 = box.minZ;
        double x2 = box.maxX, y2 = box.maxY, z2 = box.maxZ;

        collector.submitCustomGeometry(IDENTITY, type, (pose, buffer) -> {
            try {
                // bottom and top
                face(buffer, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, argb);
                face(buffer, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, argb);
                // the four sides
                face(buffer, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, argb);
                face(buffer, x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, argb);
                face(buffer, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, argb);
                face(buffer, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, argb);
            } catch (Throwable t) {
                disableAfterFailure(t);
            }
        });
    }

    /** One quad from four corners. */
    private static void face(VertexConsumer buffer,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double x3, double y3, double z3,
                             double x4, double y4, double z4, int argb) {
        buffer.addVertex((float) x1, (float) y1, (float) z1).setColor(argb);
        buffer.addVertex((float) x2, (float) y2, (float) z2).setColor(argb);
        buffer.addVertex((float) x3, (float) y3, (float) z3).setColor(argb);
        buffer.addVertex((float) x4, (float) y4, (float) z4).setColor(argb);
    }

    private static void submitLine(SubmitNodeCollector collector, RenderType type,
                                   Vec3 from, Vec3 to, int argb, double thickness) {
        collector.submitCustomGeometry(IDENTITY, type, (pose, buffer) -> {
            try {
                writeEdge(buffer, from.x, from.y, from.z, to.x, to.y, to.z, thickness, argb);
            } catch (Throwable t) {
                disableAfterFailure(t);
            }
        });
    }

    /**
     * The bounding box where the entity is actually being drawn.
     *
     * Rendering happens between ticks, so an entity's drawn position sits
     * somewhere between its previous and current one. The box is moved by that
     * same difference, which keeps it locked to the model instead of trailing
     * it.
     */
    private static AABB interpolated(Entity entity, float partialTick) {
        AABB box = entity.getBoundingBox();
        if (partialTick >= 0.999f) return box;

        Double oldX = Reflect.asDouble(field(entity, "xOld"));
        Double oldY = Reflect.asDouble(field(entity, "yOld"));
        Double oldZ = Reflect.asDouble(field(entity, "zOld"));
        if (oldX == null || oldY == null || oldZ == null) return box;

        // How far back from the current position the drawn one sits
        double dx = (oldX - entity.getX()) * (1 - partialTick);
        double dy = (oldY - entity.getY()) * (1 - partialTick);
        double dz = (oldZ - entity.getZ()) * (1 - partialTick);

        // A teleport would otherwise stretch the box across the world
        if (Math.abs(dx) > 8 || Math.abs(dy) > 8 || Math.abs(dz) > 8) return box;

        return box.move(dx, dy, dz);
    }

    /** Reads a field by name, since these are fields rather than accessors. */
    private static Object field(Object target, String name) {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                var f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** How far between ticks this frame sits, 0 to 1. */
    private static float partialTick(Minecraft mc) {
        Object tracker = Reflect.call(mc, "getDeltaTracker", "getTimer");
        if (tracker == null) return 1.0f;

        // The accessor takes a flag on some versions and nothing on others
        Object value = Reflect.callWith(tracker, "getGameTimeDeltaPartialTick", Boolean.FALSE);
        if (value == null) value = Reflect.call(tracker, "getGameTimeDeltaPartialTick");
        if (value == null) value = Reflect.call(tracker, "getRealtimeDeltaTicks", "partialTick");

        Double partial = Reflect.asDouble(value);
        return partial == null ? 1.0f : (float) Math.max(0, Math.min(1, partial));
    }

    /**
     * Draws one edge as a pair of crossed quads.
     *
     * The debug quad type carries no line width, so an edge is a thin slab
     * instead. Two of them at right angles keep the edge visible from every
     * direction, where a single flat quad would disappear when viewed edge on.
     */
    private static void writeEdge(VertexConsumer buffer,
                                  double x1, double y1, double z1,
                                  double x2, double y2, double z2,
                                  double thickness, int argb) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-5) return;

        dx /= length; dy /= length; dz /= length;

        // Any two directions at right angles to the edge will do
        double ax, ay, az;
        if (Math.abs(dy) < 0.9) {
            ax = -dz; ay = 0; az = dx;      // perpendicular in the horizontal plane
        } else {
            ax = 1; ay = 0; az = 0;
        }
        double aLength = Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= aLength; ay /= aLength; az /= aLength;

        // The second perpendicular is the cross product of the first two
        double bx = dy * az - dz * ay;
        double by = dz * ax - dx * az;
        double bz = dx * ay - dy * ax;

        double half = thickness / 2.0;

        quad(buffer, x1, y1, z1, x2, y2, z2, ax * half, ay * half, az * half, argb);
        quad(buffer, x1, y1, z1, x2, y2, z2, bx * half, by * half, bz * half, argb);
    }

    /** A flat slab from one point to another, offset either side. */
    private static void quad(VertexConsumer buffer,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double ox, double oy, double oz, int argb) {
        buffer.addVertex((float) (x1 - ox), (float) (y1 - oy), (float) (z1 - oz)).setColor(argb);
        buffer.addVertex((float) (x2 - ox), (float) (y2 - oy), (float) (z2 - oz)).setColor(argb);
        buffer.addVertex((float) (x2 + ox), (float) (y2 + oy), (float) (z2 + oz)).setColor(argb);
        buffer.addVertex((float) (x1 + ox), (float) (y1 + oy), (float) (z1 + oz)).setColor(argb);
    }

    /** The render type used for debug style lines, whatever it is called here. */
    static RenderType lineType() {
        if (lookedUp && renderTypeGetter == null) return null;

        if (!lookedUp) {
            lookedUp = true;
            try {
                Class<?> types = Class.forName(
                        "net.minecraft.client.renderer.rendertype.RenderTypes");
                // Quads, not lines: the debug quad type is what this pipeline
                // offers, and drawing edges as thin quads is also what makes a
                // configurable thickness possible at all.
                for (String name : new String[]{"debugQuads", "debugFilledBox", "lines"}) {
                    for (Method method : types.getMethods()) {
                        if (!method.getName().equals(name)) continue;
                        if (method.getParameterCount() != 0) continue;
                        if (!RenderType.class.isAssignableFrom(method.getReturnType())) continue;
                        renderTypeGetter = method;
                        break;
                    }
                    if (renderTypeGetter != null) break;
                }
            } catch (Throwable t) {
                SpaceClient.LOGGER.warn("Could not reach the render types: {}", t.getMessage());
            }
        }

        try {
            return renderTypeGetter == null ? null : (RenderType) renderTypeGetter.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static Vec3 cameraPosition(Minecraft mc) {
        Object camera = Reflect.call(mc.gameRenderer, "getMainCamera", "mainCamera");
        Object position = Reflect.call(camera, "getPosition", "position");
        return position instanceof Vec3 vec ? vec : null;
    }

    /**
     * Switches the module off after a drawing failure.
     *
     * A vertex the pipeline rejects throws on the render thread, which ends the
     * frame and takes the game with it. Rather than repeating that every frame,
     * the module stops itself and says why - a hitbox is not worth a crash.
     */
    private static void disableAfterFailure(Throwable cause) {
        if (!failed) {
            failed = true;
            failure = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            SpaceClient.LOGGER.error("Hitbox drawing failed - switching the module off", cause);

            HitboxModule module = (HitboxModule) SpaceClient.getModuleManager().get("hitbox");
            if (module != null) module.setEnabled(false);
        }
    }

    private static void warnOnce(String reason) {
        if (warned) return;
        warned = true;
        SpaceClient.LOGGER.warn("Hitboxes cannot be drawn: {}", reason);
    }

    private HitboxRenderer() {}
}
