package gg.spaceclient.ui;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Builds instances of the game's own screens without naming their shapes.
 *
 * The screens this client hands off to - the server editor, the options pages,
 * the connection flow - all take different arguments than they did two
 * versions ago, and none of them has been compiled against here. Rather than
 * write out a guess per screen and find out at build time, each call describes
 * what it has to offer and takes back whatever fits.
 *
 * Arguments are matched by type, not by position, so a constructor that took
 * (Screen, Options) and now takes (Options, Screen) still resolves. Anything
 * left unfilled becomes null or false, which is the shape most of these
 * optional trailing arguments already had.
 */
public final class Construct {

    /**
     * The first constructor whose arguments can all be supplied from the pool.
     *
     * Longer constructors are tried first: where a class carries both a short
     * and a long form, the long one is usually the current one and the short
     * one a deprecated shim.
     */
    public static Object of(String className, Object... pool) {
        try {
            Class<?> type = Class.forName(className);
            Constructor<?>[] constructors = type.getConstructors();

            java.util.Arrays.sort(constructors,
                    (a, b) -> b.getParameterCount() - a.getParameterCount());

            for (Constructor<?> constructor : constructors) {
                Object[] args = match(constructor.getParameterTypes(), pool);
                if (args == null) continue;
                try {
                    return constructor.newInstance(args);
                } catch (Throwable ignored) {
                    // Built but rejected what it was given; try the next shape
                }
            }
        } catch (Throwable ignored) {
            // Not present on this version
        }
        return null;
    }

    /**
     * As above, but refusing rather than filling a gap with null.
     *
     * Passing null for an argument nothing in the pool matched is convenient
     * and occasionally right, and it is also how a settings screen ends up
     * built with no settings behind it - constructed successfully, opening
     * onto nothing. Where every argument genuinely matters, this returns null
     * instead so the caller can say so.
     */
    public static Object strict(String className, Object... pool) {
        try {
            Class<?> type = Class.forName(className);
            Constructor<?>[] constructors = type.getConstructors();

            java.util.Arrays.sort(constructors,
                    (a, b) -> b.getParameterCount() - a.getParameterCount());

            for (Constructor<?> constructor : constructors) {
                Object[] args = match(constructor.getParameterTypes(), pool);
                if (args == null) continue;

                boolean complete = true;
                Class<?>[] params = constructor.getParameterTypes();
                for (int i = 0; i < args.length; i++) {
                    if (args[i] == null && !params[i].isPrimitive()) {
                        complete = false;
                        break;
                    }
                }
                if (!complete) continue;

                try {
                    return constructor.newInstance(args);
                } catch (Throwable ignored) {
                    // Built but rejected what it was given; try the next shape
                }
            }
        } catch (Throwable ignored) {
            // Not present on this version
        }
        return null;
    }

    /**
     * Calls a static method and says whether it ran, not what it returned.
     *
     * The distinction matters because most of what this client hands off to -
     * starting a connection, opening the world creator - returns void, and a
     * void method reflected over gives back null. Reading that null as failure
     * is what made a successful server join also open the game's own server
     * list for a moment, and then land there again on disconnect.
     */
    public static boolean invoked(String className, String methodName, Object... pool) {
        try {
            Class<?> type = Class.forName(className);
            Method[] methods = type.getMethods();

            java.util.Arrays.sort(methods,
                    (a, b) -> b.getParameterCount() - a.getParameterCount());

            for (Method method : methods) {
                if (!method.getName().equals(methodName)) continue;
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;

                Object[] args = match(method.getParameterTypes(), pool);
                if (args == null) continue;
                try {
                    method.invoke(null, args);
                    return true;
                } catch (Throwable ignored) {
                    // Try the next overload
                }
            }
        } catch (Throwable ignored) {
            // Not present on this version
        }
        return false;
    }

    /**
     * Runs whichever static method can be fully satisfied from the pool.
     *
     * For classes that offer exactly one way in but do not agree with previous
     * versions on what it is called. Preferred names are tried first; failing
     * that, any static method whose every argument can be filled from what is
     * on offer will do - a factory that wants a Minecraft and a Screen and
     * nothing else is not something a class has two of by accident.
     *
     * Nothing is filled with null here. A screen built with a hole where its
     * context should be opens and then ignores every button on it, which is
     * harder to diagnose than a button that does nothing at all.
     *
     * @return the name that ran, or null if nothing did.
     */
    public static String invokedBest(String className, String[] preferred, Object... pool) {
        try {
            Class<?> type = Class.forName(className);

            for (String name : preferred) {
                if (invoked(className, name, pool)) return name;
            }

            Method[] methods = type.getMethods();
            java.util.Arrays.sort(methods,
                    (a, b) -> b.getParameterCount() - a.getParameterCount());

            for (Method method : methods) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterCount() == 0) continue;

                Class<?>[] params = method.getParameterTypes();
                Object[] args = match(params, pool);
                if (args == null) continue;

                boolean complete = true;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] == null && !params[i].isPrimitive()) {
                        complete = false;
                        break;
                    }
                }
                if (!complete) continue;

                try {
                    method.invoke(null, args);
                    return method.getName();
                } catch (Throwable ignored) {
                    // Try the next one
                }
            }
        } catch (Throwable ignored) {
            // Not present on this version
        }
        return null;
    }

    /**
     * The same for an instance method, matched by name and fillable arguments.
     *
     * Returns whether one ran, so a void method is not mistaken for a miss and
     * retried with a different shape - which is how opening a world ended up
     * being attempted twice in a row.
     */
    public static boolean invokedOn(Object target, String methodName, Object... pool) {
        if (target == null) return false;
        try {
            Method[] methods = target.getClass().getMethods();

            java.util.Arrays.sort(methods,
                    (a, b) -> b.getParameterCount() - a.getParameterCount());

            for (Method method : methods) {
                if (!method.getName().equals(methodName)) continue;
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;

                Object[] args = match(method.getParameterTypes(), pool);
                if (args == null) continue;
                try {
                    method.setAccessible(true);
                    method.invoke(target, args);
                    return true;
                } catch (Throwable ignored) {
                    // Try the next overload
                }
            }
        } catch (Throwable ignored) {
            // Nothing usable
        }
        return false;
    }

    /**
     * The static methods a class actually offers, for a log line when nothing
     * matched. Guessing twice is worse than reporting once.
     */
    public static String describeStatics(String className, String methodName) {
        try {
            Class<?> type = Class.forName(className);
            StringBuilder out = new StringBuilder();
            for (Method method : type.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                if (methodName != null && !method.getName().contains(methodName)) continue;
                if (out.length() > 0) out.append("; ");
                out.append(method.getName()).append('(');
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) out.append(", ");
                    out.append(params[i].getSimpleName());
                }
                out.append(')');
            }
            return out.length() == 0 ? "no matching static methods" : out.toString();
        } catch (Throwable ignored) {
            return "class not present";
        }
    }

    /** The same idea for a static factory method. */
    public static Object call(String className, String methodName, Object... pool) {
        try {
            Class<?> type = Class.forName(className);
            Method[] methods = type.getMethods();

            java.util.Arrays.sort(methods,
                    (a, b) -> b.getParameterCount() - a.getParameterCount());

            for (Method method : methods) {
                if (!method.getName().equals(methodName)) continue;
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;

                Object[] args = match(method.getParameterTypes(), pool);
                if (args == null) continue;
                try {
                    return method.invoke(null, args);
                } catch (Throwable ignored) {
                    // Try the next overload
                }
            }
        } catch (Throwable ignored) {
            // Not present on this version
        }
        return null;
    }

    /**
     * Stands in for a callback whose interface is not known here.
     *
     * The game's editor screens take a one method interface to report what the
     * user chose, and which interface that is has varied - a plain Consumer,
     * fastutil's BooleanConsumer, something else again. Offering one concrete
     * type means the argument simply does not match and the slot is filled
     * with null, which is how a screen opens with every button on it inert.
     *
     * Offering this instead lets the parameter decide: whatever single method
     * interface is wanted, it gets built to order.
     */
    public static final class Callback {
        private final java.util.function.Consumer<Object[]> action;

        public Callback(java.util.function.Consumer<Object[]> action) {
            this.action = action;
        }

        /** A proxy implementing whatever interface was asked for. */
        private Object as(Class<?> type) {
            try {
                return java.lang.reflect.Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[]{type},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == args[0];
                                    default -> "space client callback";
                                };
                            }
                            action.accept(args == null ? new Object[0] : args);
                            return defaultFor(method.getReturnType());
                        });
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Object defaultFor(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) return null;
            if (type == boolean.class) return Boolean.FALSE;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            return null;
        }

        /** Whether an interface has exactly one method to implement. */
        private static boolean isFunctional(Class<?> type) {
            if (!type.isInterface()) return false;
            int abstractMethods = 0;
            for (Method method : type.getMethods()) {
                if (method.isDefault()) continue;
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                abstractMethods++;
            }
            return abstractMethods == 1;
        }
    }

    /**
     * Fills a parameter list from the pool, one value per slot.
     *
     * A value is used at most once, so a constructor wanting two screens does
     * not get the same one twice - it gets the second screen if one was
     * offered and null if not, which is the right answer for a parent that has
     * genuinely not been supplied.
     */
    private static Object[] match(Class<?>[] params, Object[] pool) {
        Object[] args = new Object[params.length];
        boolean[] used = new boolean[pool.length];

        for (int i = 0; i < params.length; i++) {
            Class<?> want = params[i];
            Object found = null;

            for (int p = 0; p < pool.length; p++) {
                if (used[p] || pool[p] == null) continue;
                if (!want.isInstance(pool[p])) continue;
                found = pool[p];
                used[p] = true;
                break;
            }

            // Nothing matched outright: if the slot wants a one method
            // interface and a callback was offered, build one to fit
            if (found == null && Callback.isFunctional(want)) {
                for (int p = 0; p < pool.length; p++) {
                    if (used[p] || !(pool[p] instanceof Callback callback)) continue;
                    Object proxy = callback.as(want);
                    if (proxy == null) continue;
                    found = proxy;
                    used[p] = true;
                    break;
                }
            }

            if (found != null) {
                args[i] = found;
            } else if (want == boolean.class) {
                args[i] = Boolean.FALSE;
            } else if (want == int.class) {
                args[i] = 0;
            } else if (want.isPrimitive()) {
                // Some other primitive with no sensible blank; give up rather
                // than pass a number that means something
                return null;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private Construct() {}
}
