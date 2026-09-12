package gg.spaceclient.ui;

import gg.spaceclient.SpaceClient;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

/**
 * Adds a widget to a screen the mod does not own.
 *
 * Fabric's helper for this changed shape in 26.2, so the widget is handed to
 * the screen's own protected {@code addRenderableWidget} through reflection.
 * The method is found by name and parameter count rather than an exact type, so
 * a signature change does not break the build - it logs and skips instead.
 */
public final class ScreenInjector {
    private static boolean warned = false;

    public static void addWidget(Screen screen, Object widget) {
        try {
            for (Method method : findAddMethods(screen.getClass())) {
                try {
                    method.setAccessible(true);
                    method.invoke(screen, widget);
                    return;
                } catch (Exception ignored) {
                    // Try the next candidate
                }
            }
            warnOnce();
        } catch (Throwable t) {
            warnOnce();
        }
    }

    /**
     * Candidates, most wanted first.
     *
     * Order matters and cannot be left to chance: getDeclaredMethods returns
     * methods in no defined order, so an earlier version of this sometimes hit
     * addWidget first - which registers the widget as a listener but never
     * draws it. The button was then clickable and invisible, or missing
     * entirely, depending on the run.
     */
    private static java.util.List<Method> findAddMethods(Class<?> type) {
        java.util.List<Method> renderable = new java.util.ArrayList<>();
        java.util.List<Method> plain = new java.util.ArrayList<>();
        Class<?> current = type;

        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() != 1) continue;
                switch (method.getName()) {
                    case "addRenderableWidget" -> renderable.add(method);
                    case "addWidget" -> plain.add(method);
                    default -> { }
                }
            }
            current = current.getSuperclass();
        }

        renderable.addAll(plain);
        return renderable;
    }

    private static void warnOnce() {
        if (warned) return;
        warned = true;
        SpaceClient.LOGGER.warn("Could not add the Space Client button to that screen");
    }

    private ScreenInjector() {}
}
