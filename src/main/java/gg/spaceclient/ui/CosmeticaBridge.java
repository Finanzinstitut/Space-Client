package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;

import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything Space Client knows about Cosmetica, reached by reflection.
 *
 * Cosmetica is a separate mod a player may or may not have. Importing its
 * classes would make it a hard build dependency and refuse to compile without
 * it, so every name below is a string and every failure is a null.
 *
 * All of these names were read out of the Cosmetica 2 source for 26.2 rather
 * than guessed, but they are still someone else's internals: the ones that are
 * package private there are reached with setAccessible, which works because
 * Fabric mods share a classloader and sit in the unnamed module.
 *
 * What this class does NOT do is draw. Cosmetica 2 no longer builds its menus
 * on Minecraft's Screen at all - they are Kupe components, a separate
 * declarative toolkit with its own screen stack and renderer. None of its
 * widgets can be placed inside a Space Client screen, so its data is read here
 * and drawn again in the house style, and the few actions that genuinely need
 * Cosmetica's own interface are handed over to it.
 */
public final class CosmeticaBridge {

    // --- class names, all read out of the Cosmetica 2 source for 26.2 ---
    private static final String COSMETICA = "cc.cosmetica.cosmetica.Cosmetica";
    private static final String API       = "cc.cosmetica.core.api.CosmeticaAPI";
    private static final String SCREENS   = "cc.cosmetica.kupe.api.Screens";
    private static final String KEY       = "cc.cosmetica.kupe.api.ResourceKey";
    private static final String WHEEL     = "cc.cosmetica.cosmetica.gui.OutfitWheelScreen";

    /** One outfit, flattened so nothing outside this class touches a Cosmetica type. */
    public record OutfitRef(String id, String name, Identifier thumbnail,
                            boolean usable, Object handle) {}

    /** One worn cosmetic, ready to draw. */
    public record Worn(String slot, String name, Identifier thumbnail) {}

    private static final Map<String, Class<?>> CLASSES = new HashMap<>();
    private static Boolean present = null;

    /** Set when something that should have worked did not, for the screen to show. */
    private static String note = null;

    private CosmeticaBridge() {}

    // ------------------------------------------------------------------
    // presence
    // ------------------------------------------------------------------

    /** Whether the loader knows the mod at all. */
    public static boolean modLoaded() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cosmetica");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether Cosmetica is loaded and looks like the version this build knows.
     *
     * Both classes are checked because they come from different jars - the mod
     * itself and the Kupe toolkit it bundles. One without the other means a
     * version that will not answer any of the calls below.
     */
    public static boolean installed() {
        if (present == null) {
            present = type(COSMETICA) != null && type(SCREENS) != null;
            if (!present) {
                SpaceClient.LOGGER.warn("Cosmetica loaded={} but its 26.2 classes were not found",
                        modLoaded());
            }
        }
        return present;
    }

    public static String note() { return note; }

    // ------------------------------------------------------------------
    // account
    // ------------------------------------------------------------------

    /** Whether the player is signed in to Cosmetica. */
    public static boolean authenticated() {
        Object value = callStatic(API, "isAuthenticated");
        return value instanceof Boolean flag && flag;
    }

    // ------------------------------------------------------------------
    // outfits
    // ------------------------------------------------------------------

    /**
     * The player's saved outfits.
     *
     * Read through State.peek rather than State.acquire: acquire subscribes the
     * caller so a Kupe component rebuilds when the value changes, and a
     * Minecraft screen is not a Kupe component. peek is the plain read.
     */
    public static List<OutfitRef> outfits() {
        List<OutfitRef> out = new ArrayList<>();
        Object list = peek(staticField(COSMETICA, "OWN_OUTFITS"));
        if (!(list instanceof List<?> options)) return out;

        for (Object option : options) {
            if (option == null) continue;
            String id = string(field(option, "id"));
            String name = string(field(option, "name"));
            Object usable = field(option, "usable");
            out.add(new OutfitRef(
                    id == null ? "" : id,
                    name == null || name.isBlank() ? "Unnamed" : name,
                    location(field(option, "thumbnail")),
                    !(usable instanceof Boolean flag) || flag,
                    option));
        }
        return out;
    }

    /** The id of the outfit currently worn, or an empty string. */
    public static String selectedOutfitId() {
        Object value = peek(staticField(COSMETICA, "SELECTED_OUTFIT_ID"));
        if (value instanceof Optional<?> maybe) {
            return maybe.map(String::valueOf).orElse("");
        }
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Wears an outfit.
     *
     * OutfitOption.equipAsync is package private in Cosmetica, which is why the
     * handle is carried around rather than the id: calling their own method
     * keeps the optimistic state update and the follow up refresh that the
     * website and the outfit wheel both depend on, instead of half
     * reimplementing the request here and leaving the two out of step.
     */
    public static void equip(OutfitRef outfit) {
        if (outfit == null || outfit.handle() == null) return;
        if (invoke(outfit.handle(), "equipAsync") == FAILED) {
            note = "Could not wear that outfit.";
        } else {
            note = null;
        }
    }

    /** Takes off whatever is worn. */
    public static void clearOutfit() {
        if (callStatic(WHEEL, "clearOutfit") == FAILED) {
            note = "Could not clear the outfit.";
        } else {
            note = null;
        }
    }

    /** Asks Cosmetica to fetch the outfit list again. */
    public static void refresh() {
        callStatic(COSMETICA, "fetchOutfits");
    }

    // ------------------------------------------------------------------
    // what is being worn
    // ------------------------------------------------------------------

    /**
     * The cosmetics on the player right now: cape, elytra and accessories.
     *
     * Capes and elytras are ImageCosmetics and carry their own texture, so a
     * card can show the real thing. Accessories are models rather than images
     * and have no flat texture to draw, so they get a name and a plain tile.
     */
    public static List<Worn> worn() {
        List<Worn> out = new ArrayList<>();
        Object cosmetics = peek(staticField(COSMETICA, "OWN_COSMETICS"));
        if (cosmetics == null) return out;

        add(out, "Cape", invoke(cosmetics, "getCloak"));
        add(out, "Elytra", invoke(cosmetics, "getElytra"));

        Object accessories = invoke(cosmetics, "getAccessories");
        if (accessories instanceof List<?> items) {
            for (Object accessory : items) {
                String name = string(invoke(accessory, "getName"));
                out.add(new Worn("Accessory", name == null ? "Accessory" : name,
                        location(invoke(accessory, "getImage"))));
            }
        }
        return out;
    }

    private static void add(List<Worn> out, String slot, Object maybe) {
        if (!(maybe instanceof Optional<?> optional) || optional.isEmpty()) return;
        Object cosmetic = optional.get();
        String name = string(invoke(cosmetic, "getName"));
        out.add(new Worn(slot, name == null ? slot : name,
                location(invoke(cosmetic, "getImage"))));
    }

    // ------------------------------------------------------------------
    // handing over to Cosmetica's own screens
    // ------------------------------------------------------------------

    /**
     * Opens one of Cosmetica's screens.
     *
     * Only the five ids registered in Cosmetica.registerScreens can be opened
     * this way, which is why the methods below name them one by one rather than
     * taking a free string: an unregistered id silently does nothing.
     *
     * These are not Minecraft screens. Kupe pushes its own screen onto the
     * game, and closing it returns wherever Kupe decides rather than to the
     * Space Client menu. This is a handover, not a subscreen.
     */
    private static void open(String path) {
        Class<?> screens = type(SCREENS);
        Class<?> keyType = type(KEY);
        if (screens == null || keyType == null) {
            note = "Cosmetica's own menu could not be opened.";
            return;
        }
        try {
            Object key = keyType.getConstructor(String.class, String.class)
                    .newInstance("cosmetica", path);
            for (Method method : screens.getMethods()) {
                if (!method.getName().equals("setScreen")) continue;
                if (method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isInstance(key)) continue;
                method.setAccessible(true);
                method.invoke(null, key);
                note = null;
                return;
            }
            note = "Cosmetica's own menu could not be opened.";
        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Could not open the Cosmetica screen {}", path, t);
            note = "Cosmetica's own menu could not be opened.";
        }
    }

    public static void openHome()      { open("home"); }
    public static void openBrowse()    { open("browse"); }
    public static void openOutfits()   { open("outfit_select"); }
    public static void openNewOutfit() { open("create_new_outfit"); }
    public static void openNametag()   { open("name_tag"); }

    /** Opens the Cosmetica website in the player's browser. */
    public static void openWebPanel(String page) {
        Class<?> type = type(COSMETICA);
        if (type == null) return;
        try {
            Method method = type.getMethod("openWebPanel", String.class);
            method.invoke(null, page);
        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Could not open the Cosmetica web panel", t);
        }
    }

    // ------------------------------------------------------------------
    // reflection plumbing
    // ------------------------------------------------------------------

    /** Distinct from null, so a call that failed can be told from one that returned nothing. */
    private static final Object FAILED = new Object();

    private static Class<?> type(String name) {
        return CLASSES.computeIfAbsent(name, key -> {
            try {
                return Class.forName(key);
            } catch (Throwable ignored) {
                return null;
            }
        });
    }

    private static Object staticField(String className, String fieldName) {
        Class<?> type = type(className);
        if (type == null) return null;
        try {
            Field field = type.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object callStatic(String className, String methodName) {
        Class<?> type = type(className);
        if (type == null) return FAILED;
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) continue;
                if (method.getParameterCount() != 0) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(null);
                } catch (Throwable ignored) {
                    // Try another overload
                }
            }
            current = current.getSuperclass();
        }
        return FAILED;
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null || target == FAILED) return FAILED;
        Class<?> current = target.getClass();
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) continue;
                if (method.getParameterCount() != 0) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Throwable ignored) {
                    // Try another overload
                }
            }
            current = current.getSuperclass();
        }
        return FAILED;
    }

    private static Object field(Object target, String fieldName) {
        if (target == null || target == FAILED) return null;
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
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

    /** Reads State.peek, the read that does not subscribe the caller. */
    private static Object peek(Object state) {
        Object value = invoke(state, "peek");
        return value == FAILED ? null : value;
    }

    /**
     * Pulls the texture out of a CachedImage.
     *
     * The argument may be a CachedImage already or an ImageCosmetic that holds
     * one, so both shapes are tried before giving up.
     */
    private static Identifier location(Object image) {
        if (image == null || image == FAILED) return null;
        Object direct = field(image, "location");
        if (direct instanceof Identifier id) return id;

        Object inner = invoke(image, "getImage");
        if (inner != FAILED && inner != null) {
            Object nested = field(inner, "location");
            if (nested instanceof Identifier id) return id;
        }
        return null;
    }

    private static String string(Object value) {
        return value == null || value == FAILED ? null : String.valueOf(value);
    }
}
