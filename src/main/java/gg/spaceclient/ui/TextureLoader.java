package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.util.Reflect;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;

/**
 * Turns raw PNG bytes into something the menu can draw.
 *
 * Server icons and world thumbnails both arrive as bytes rather than as
 * textures, and getting from one to the other means naming three classes.
 * The first attempt at this put DynamicTexture in the package NativeImage
 * lives in, which is wrong on this version - and because every step was
 * wrapped in a quiet catch, the only symptom was that no icon ever appeared
 * anywhere. A silent failure that produces a plausible looking screen is worse
 * than a loud one.
 *
 * So the class names are candidates rather than a guess, tried in turn, and
 * the combination that works is logged once. If none work that is logged too,
 * with what was tried - which is the difference between "icons are missing"
 * and knowing why.
 */
public final class TextureLoader {

    /** Where the decoder has lived. */
    private static final String[] IMAGE_CLASSES = {
            "com.mojang.blaze3d.platform.NativeImage",
            "com.mojang.blaze3d.textures.NativeImage",
            "net.minecraft.client.renderer.texture.NativeImage",
    };

    /**
     * Where the texture wrapper has lived.
     *
     * The renderer package first: TextureManager.register takes an
     * AbstractTexture, and everything in that family sits alongside it.
     */
    private static final String[] TEXTURE_CLASSES = {
            "net.minecraft.client.renderer.texture.DynamicTexture",
            "com.mojang.blaze3d.platform.DynamicTexture",
            "net.minecraft.client.renderer.texture.ReloadableTexture",
    };

    private static boolean reported = false;

    /** What happened the first time an icon was asked for. */
    private static String status = "no icon requested yet";

    public static String status() { return status; }

    public static boolean working() { return status.startsWith("using "); }

    private TextureLoader() {}

    /**
     * Registers an image under the given identifier.
     *
     * @return the identifier if it can now be drawn, or null if the image
     *         could not be decoded or registered on this version.
     */
    public static Identifier register(byte[] bytes, Identifier id) {
        if (bytes == null || bytes.length == 0 || id == null) return null;

        Object image = decode(bytes);
        if (image == null) {
            reportOnce("no usable NativeImage class", null);
            return null;
        }

        Object texture = wrap(image);
        if (texture == null) {
            reportOnce("no usable DynamicTexture class", null);
            return null;
        }

        // Through Reflect: nothing compiled here has named this accessor, and
        // the class it returns was only confirmed to exist, not the way to it.
        Object manager = Reflect.call(Minecraft.getInstance(),
                "getTextureManager", "getTextures");
        if (manager == null) {
            reportOnce("no texture manager accessor", null);
            return null;
        }

        // register takes an AbstractTexture; registerAndLoad is the newer path
        // for anything that reloads. Asked whether one ran rather than what it
        // returned - both are void, so the old check registered every icon
        // twice, once down each path.
        if (!Construct.invokedOn(manager, "register", id, texture)) {
            Construct.invokedOn(manager, "registerAndLoad", id, texture);
        }

        reportOnce(null, texture.getClass().getName());
        return id;
    }

    private static Object decode(byte[] bytes) {
        for (String className : IMAGE_CLASSES) {
            try {
                Class<?> type = Class.forName(className);
                Method read = type.getMethod("read", byte[].class);
                Object image = read.invoke(null, (Object) bytes);
                if (image != null) return image;
            } catch (Throwable ignored) {
                // Try the next home
            }
        }
        return null;
    }

    private static Object wrap(Object image) {
        for (String className : TEXTURE_CLASSES) {
            Object texture = Construct.of(className,
                    (java.util.function.Supplier<String>) () -> "space client icon", image);
            if (texture != null) return texture;

            texture = Construct.of(className, image);
            if (texture != null) return texture;
        }
        return null;
    }

    private static void reportOnce(String problem, String used) {
        if (reported) return;
        reported = true;

        if (problem != null) {
            status = problem;
            SpaceClient.LOGGER.warn("Menu icons unavailable: {} (tried {} and {})",
                    problem, String.join(", ", IMAGE_CLASSES),
                    String.join(", ", TEXTURE_CLASSES));
        } else {
            status = "using " + used;
            SpaceClient.LOGGER.info("Menu icons using {}", used);
        }
    }
}
