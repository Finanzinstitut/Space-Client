package gg.spaceclient.font;

import gg.spaceclient.SpaceClient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an ordinary resource pack into one the pack screen will not let go of.
 *
 * Minecraft already has the three switches this needs - it uses them on its own
 * "Default" pack, which sits at the far end of the selected list with no arrow
 * to take it out. They are: required, which forces the pack into the selected
 * list and removes the arrow that would move it out; a default position of TOP
 * or BOTTOM; and fixedPosition, which stops it being dragged anywhere else.
 * Vanilla is (required, BOTTOM, fixed), pinned to the weakest slot. A Space
 * Client font wants the mirror image, (required, TOP, fixed), pinned to the
 * strongest one, where no pack loaded after it can replace the glyphs again.
 *
 * Those switches are decided when the pack object is built and there is no API
 * to ask for them from outside, so this rewrites them on the finished object.
 *
 * Two layouts are handled, because this moved between versions: newer ones keep
 * the three together in a small record hanging off the pack, older ones keep
 * them as three fields on the pack itself. Neither is found by name where it can
 * be found by shape instead, so a rename does not necessarily break it.
 *
 * Every change is reversible. pin hands back the undo, and FontPacks runs it
 * before switching away - without that, turning the font off would leave a pack
 * behind that is still marked required, and a required pack cannot be
 * deselected, so the font would never actually go.
 *
 * If none of it works, nothing happens and null comes back. The pack is then an
 * ordinary one the player can switch off, which is what this mod did a version
 * ago, so the cost is a lost restriction rather than a lost font.
 */
public final class PackPinning {

    private static boolean warned = false;

    /**
     * Rewrites a pack to required, top of the list, not movable.
     *
     * @return an undo for the change, or null if nothing could be changed
     */
    public static Runnable pin(Object pack) {
        if (pack == null) return null;

        List<Runnable> undo = new ArrayList<>();
        try {
            if (pinNested(pack, undo) || pinFlat(pack, undo)) {
                return () -> {
                    for (Runnable step : undo) {
                        try {
                            step.run();
                        } catch (Throwable ignored) {
                            // Undo is best effort; a reload rebuilds the pack anyway
                        }
                    }
                };
            }
        } catch (Throwable ignored) {
            // Falls through to the warning below
        }

        if (!warned) {
            warned = true;
            SpaceClient.LOGGER.warn(
                    "Could not pin the font pack to the top of the resource pack "
                            + "list - the font still works, but it can be turned "
                            + "off in the resource pack screen");
        }
        return null;
    }

    // ---------------- newer layout: one record beside the pack ----------------

    /**
     * Looks for a field holding a small record that carries a position and some
     * flags, and swaps in a rebuilt copy.
     *
     * The record itself is left alone - record components are read only however
     * they are reached - so a new one is built with the values wanted and put
     * where the old one was.
     */
    private static boolean pinNested(Object pack, List<Runnable> undo) throws Exception {
        for (Field field : pack.getClass().getDeclaredFields()) {
            Class<?> type = field.getType();
            if (type.isPrimitive() || !type.isRecord()) continue;

            field.setAccessible(true);
            Object before = field.get(pack);
            if (before == null) continue;

            Object rebuilt = rebuild(before);
            if (rebuilt == null) continue;

            field.set(pack, rebuilt);
            undo.add(() -> {
                try {
                    field.set(pack, before);
                } catch (Exception ignored) {
                    // Nothing useful to do; the next reload rebuilds this object
                }
            });
            return true;
        }
        return false;
    }

    /**
     * A copy of a selection record with the position set to TOP and every flag
     * turned on.
     *
     * Every boolean is set rather than only the two that matter by name. In the
     * layout this is written against there are exactly two, required and
     * fixedPosition, and both want to be true - matching on name would only add
     * a way to fail. Anything that is neither a position nor a flag is carried
     * across untouched.
     *
     * Null comes back when the record holds no position, which is how a record
     * that hangs off a pack for some unrelated reason is left alone.
     */
    private static Object rebuild(Object config) throws Exception {
        Class<?> type = config.getClass();
        RecordComponent[] parts = type.getRecordComponents();
        if (parts.length == 0) return null;

        Class<?>[] types = new Class<?>[parts.length];
        Object[] args = new Object[parts.length];
        boolean foundPosition = false;

        for (int i = 0; i < parts.length; i++) {
            RecordComponent part = parts[i];
            types[i] = part.getType();

            part.getAccessor().setAccessible(true);
            Object value = part.getAccessor().invoke(config);

            if (isPosition(part.getType())) {
                Object top = topConstant(part.getType());
                if (top != null) {
                    args[i] = top;
                    foundPosition = true;
                    continue;
                }
            }
            args[i] = part.getType() == boolean.class ? Boolean.TRUE : value;
        }
        if (!foundPosition) return null;

        Constructor<?> constructor = type.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    // ---------------- older layout: three fields on the pack ----------------

    private static boolean pinFlat(Object pack, List<Runnable> undo) throws Exception {
        boolean position = false;
        boolean flag = false;

        for (Field field : pack.getClass().getDeclaredFields()) {
            if (isPosition(field.getType())) {
                Object top = topConstant(field.getType());
                if (top == null) continue;

                field.setAccessible(true);
                Object before = field.get(pack);
                field.set(pack, top);
                undo.add(() -> {
                    try {
                        field.set(pack, before);
                    } catch (Exception ignored) {
                    }
                });
                position = true;

            } else if (field.getType() == boolean.class
                    && (field.getName().equals("required")
                    || field.getName().equals("fixedPosition"))) {

                field.setAccessible(true);
                boolean before = field.getBoolean(pack);
                field.setBoolean(pack, true);
                undo.add(() -> {
                    try {
                        field.setBoolean(pack, before);
                    } catch (Exception ignored) {
                    }
                });
                flag = true;
            }
        }
        return position && flag;
    }

    // ---------------- finding TOP without naming its type ----------------

    /**
     * An enum with both a TOP and a BOTTOM is the position enum.
     *
     * Both are checked rather than only TOP, so this cannot land on some other
     * enum that happens to have a constant of that name.
     */
    private static boolean isPosition(Class<?> type) {
        if (!type.isEnum()) return false;

        boolean top = false;
        boolean bottom = false;
        for (Object constant : type.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            if (name.equals("TOP")) top = true;
            if (name.equals("BOTTOM")) bottom = true;
        }
        return top && bottom;
    }

    private static Object topConstant(Class<?> type) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals("TOP")) return constant;
        }
        return null;
    }

    private PackPinning() {}
}
