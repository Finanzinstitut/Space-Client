package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Draws a texture into the menu.
 *
 * Everything else this menu draws is a rectangle, so nothing in the codebase
 * has ever had to name the texture call - which means its signature on 26.2 is
 * unverified. Guessing it in a direct call would cost a failed build; the whole
 * lookup therefore happens at runtime, once, and a wrong guess costs a
 * placeholder tile and a log line instead.
 *
 * The signature that is found is logged the first time a texture is drawn. Send
 * that line back and this class can be replaced by a two line direct call.
 */
public final class Textures {

    /** Resolved on first use. Null means every candidate failed. */
    private static Method blit;
    private static Object pipeline;
    private static boolean resolved = false;
    private static boolean takesPipeline = false;
    private static int shape = 0;   // 1 = ten argument form, 2 = twelve argument form
    private static boolean tinted = false;

    private Textures() {}

    /**
     * Draws the whole texture into the given box.
     *
     * @return false if no usable texture call was found, so the caller can draw
     *         something else in the space rather than leaving a hole.
     */
    public static boolean draw(GuiGraphicsExtractor graphics, Identifier texture,
                               int x, int y, int width, int height) {
        return drawFrame(graphics, texture, x, y, width, height, 1, 0);
    }

    /**
     * Draws one frame of a vertically stacked animation strip.
     *
     * Cosmetica's thumbnails are animation sheets rather than single images, so
     * drawing the whole texture would squash every frame into the card. Taking
     * a slice shows one frame at the right proportions.
     *
     * @param frames how many frames are stacked in the texture; 1 draws it whole
     * @param frame  which frame to show, from the top
     */
    public static boolean drawFrame(GuiGraphicsExtractor graphics, Identifier texture,
                                    int x, int y, int width, int height,
                                    int frames, int frame) {
        if (texture == null) return false;
        resolve();
        if (blit == null) return false;

        // The sampled region is expressed in the texture's own units. Declaring
        // the texture to be frames tall and one wide makes the maths land on
        // whole frames without knowing the real pixel size.
        float v = Math.max(0, Math.min(frames - 1, frame));

        try {
            Object[] args = switch (shape) {
                case 1 -> new Object[]{texture, x, y, 0f, v, width, height, width, height};
                case 2 -> new Object[]{texture, x, y, 0f, v, width, height, 1, 1, 1, frames};
                default -> null;
            };
            if (args == null) return false;

            Object[] full = build(args);
            blit.invoke(graphics, full);
            return true;
        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Texture draw failed, falling back to a plain tile", t);
            blit = null;
            return false;
        }
    }

    /** Prepends the pipeline and appends the tint, if the chosen call wants them. */
    private static Object[] build(Object[] core) {
        int extra = (takesPipeline ? 1 : 0) + (tinted ? 1 : 0);
        Object[] out = new Object[core.length + extra];
        int at = 0;
        if (takesPipeline) out[at++] = pipeline;
        for (Object o : core) out[at++] = o;
        if (tinted) out[at] = 0xFFFFFFFF;
        return out;
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;

        pipeline = findPipeline();

        for (Method method : GuiGraphicsExtractor.class.getMethods()) {
            String name = method.getName();
            if (!name.startsWith("blit") && !name.startsWith("submitBlit")
                    && !name.startsWith("drawTexture")) continue;

            Class<?>[] params = method.getParameterTypes();
            int at = 0;

            boolean wantsPipeline = params.length > 0
                    && !Identifier.class.isAssignableFrom(params[0]);
            if (wantsPipeline) {
                if (pipeline == null || !params[0].isInstance(pipeline)) continue;
                at = 1;
            }

            if (params.length <= at || !Identifier.class.isAssignableFrom(params[at])) continue;
            at++;

            int rest = params.length - at;
            boolean hasTint = rest == 10 || rest == 12;   // trailing ARGB colour
            int core = hasTint ? rest - 1 : rest;

            int found;
            if (core == 8) {
                found = 1;          // x y u v w h tw th
            } else if (core == 10) {
                found = 2;          // x y u v w h uw vh tw th
            } else {
                continue;
            }

            // u and v are the only floats in either shape, at the same place
            if (params[at + 2] != float.class || params[at + 3] != float.class) continue;

            blit = method;
            takesPipeline = wantsPipeline;
            tinted = hasTint;
            shape = found;

            SpaceClient.LOGGER.info("Using {} for menu textures (pipeline={}, tint={})",
                    describe(method), wantsPipeline, hasTint);
            return;
        }

        SpaceClient.LOGGER.warn("No usable texture call found on GuiGraphicsExtractor - "
                + "cosmetic thumbnails will show as plain tiles");
    }

    /** Looks for the GUI texture pipeline the blit calls now take. */
    private static Object findPipeline() {
        String[] classes = {
                "net.minecraft.client.renderer.RenderPipelines",
                "com.mojang.blaze3d.pipeline.RenderPipelines",
        };
        for (String className : classes) {
            try {
                Class<?> type = Class.forName(className);
                Field best = null;
                for (Field field : type.getFields()) {
                    String name = field.getName();
                    if (!name.contains("GUI")) continue;
                    if (name.contains("TEXTURED")) {
                        // Prefer the plain one over any variant
                        if (best == null || name.equals("GUI_TEXTURED")) best = field;
                    }
                }
                if (best != null) {
                    best.setAccessible(true);
                    return best.get(null);
                }
            } catch (Throwable ignored) {
                // Try the next location
            }
        }
        return null;
    }

    private static String describe(Method method) {
        StringBuilder out = new StringBuilder(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) out.append(", ");
            out.append(params[i].getSimpleName());
        }
        return out.append(')').toString();
    }
}
