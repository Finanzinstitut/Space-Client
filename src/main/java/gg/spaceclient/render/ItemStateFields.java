package gg.spaceclient.render;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Silences the spin and the float by emptying what drives them.
 *
 * The turning and the bobbing are worked out inside the drawing method from
 * values the render state carries, and the injection this client uses runs
 * before that. So there is no way to undo them afterwards - but there is a way
 * to make them come out as nothing, which is to zero what they are computed
 * from while the state is still being filled in.
 *
 * Which field that is has not been established on this version, so the names
 * are candidates rather than a guess, and every float on the state is a last
 * resort. What was found is reported, because a module that quietly does half
 * of what it says is the failure mode this client keeps running into.
 */
public final class ItemStateFields {

    /** Names the age and bob have gone by. */
    private static final String[] CANDIDATES = {
            "ageInTicks", "age", "bobOffset", "bob",
    };

    private static Field[] resolved = null;
    private static String report = "not looked up yet";

    private ItemStateFields() {}

    public static String status() { return report; }

    /**
     * Zeroes whatever drives the idle motion on this state.
     *
     * @return true if anything was found to zero.
     */
    public static boolean still(Object state) {
        if (state == null) return false;

        Field[] fields = resolve(state.getClass());
        if (fields.length == 0) return false;

        boolean wrote = false;
        for (Field field : fields) {
            try {
                field.setFloat(state, 0f);
                wrote = true;
            } catch (Throwable ignored) {
                // Leave that one alone
            }
        }
        return wrote;
    }

    private static Field[] resolve(Class<?> type) {
        if (resolved != null) return resolved;

        java.util.List<Field> found = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();

        for (String name : CANDIDATES) {
            Class<?> current = type;
            while (current != null) {
                try {
                    Field field = current.getDeclaredField(name);
                    if (field.getType() == float.class
                            && !Modifier.isStatic(field.getModifiers())) {
                        field.setAccessible(true);
                        found.add(field);
                        names.add(name);
                    }
                    break;
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                } catch (Throwable ignored) {
                    break;
                }
            }
        }

        resolved = found.toArray(new Field[0]);
        report = found.isEmpty()
                ? "no age or bob field found - items will still spin"
                : "stilling " + String.join(", ", names);
        return resolved;
    }
}
