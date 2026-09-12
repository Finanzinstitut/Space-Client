package gg.spaceclient.util;

import java.lang.reflect.Method;

/**
 * Small reflection helpers for API surfaces that could not be verified against
 * this Minecraft version.
 *
 * The rule used throughout this mod: anything proven by a successful compile is
 * called directly, anything guessed goes through here. A wrong guess then costs
 * a null and a log line instead of a failed build, which matters when every
 * build round trip means uploading from a phone.
 */
public final class Reflect {

    /** Calls a no-argument method, trying each name in order. */
    public static Object call(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            try {
                Method method = findNoArg(target.getClass(), name);
                if (method == null) continue;
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) {
                // Try the next name
            }
        }
        return null;
    }

    /** Calls a method with arguments, matched by name and argument count. */
    public static Object callWith(Object target, String name, Object... args) {
        if (target == null) return null;
        Class<?> current = target.getClass();

        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getParameterCount() != args.length) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(target, args);
                } catch (Throwable ignored) {
                    // Try the next overload
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Method findNoArg(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && method.getName().equals(name)) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Reflect() {}
}
