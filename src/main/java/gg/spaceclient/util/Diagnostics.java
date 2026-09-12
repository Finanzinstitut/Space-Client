package gg.spaceclient.util;

import gg.spaceclient.session.LauncherAccounts;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports which of the reflective lookups actually found something.
 *
 * Every feature that could not be verified against this Minecraft version goes
 * through reflection, and a failed lookup only writes to the log - which is no
 * help when the symptom is "it does nothing". This turns each lookup into a
 * line you can read in game, so a broken feature can be pinned down in one look
 * instead of another round of guessing.
 */
public final class Diagnostics {

    public record Check(String name, boolean ok, String detail) {}

    public static List<Check> run() {
        List<Check> checks = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();

        // --- hitbox drawing ---
        Class<?> collector = findClass("net.minecraft.client.renderer.SubmitNodeCollector");
        checks.add(new Check("Submit pipeline", collector != null,
                collector != null ? "SubmitNodeCollector present" : "not found"));

        Class<?> renderTypes = findClass("net.minecraft.client.renderer.rendertype.RenderTypes");
        checks.add(new Check("Render types", renderTypes != null,
                renderTypes != null ? "RenderTypes present" : "not found"));

        // A failure is remembered, so the reason survives the module switching off
        if (gg.spaceclient.render.HitboxRenderer.hasFailed()) {
            checks.add(new Check("Hitbox drawing", false,
                    gg.spaceclient.render.HitboxRenderer.failure()));
        }

        boolean drawing = gg.spaceclient.render.HitboxRenderer.isAvailable();
        checks.add(new Check("Hitbox mixin", drawing,
                drawing ? "attached and drawing" : "not reached yet - enable the module and look around"));

        // --- raw keyboard, needed for keystrokes and the zoom key ---
        boolean keyboard = gg.spaceclient.input.RawKeyboard.isAvailable();
        checks.add(new Check("Raw keyboard", keyboard,
                keyboard ? "window handle found" : "handle not found - keys fall back to bindings"));

        // --- field of view, needed for zoom ---
        Object fov = Reflect.call(mc.options, "fov", "getFov");
        checks.add(new Check("Field of view option", fov != null,
                fov != null ? fov.getClass().getSimpleName() : "accessor not found"));

        if (fov != null) {
            Object value = Reflect.call(fov, "get", "getValue", "value");
            checks.add(new Check("FOV value readable", value != null,
                    value != null
                            ? value + " (" + value.getClass().getSimpleName() + ")"
                            : "no getter matched"));
        }

        checks.add(new Check("Zoom renderer hook",
                gg.spaceclient.modules.ZoomModule.isMixinActive(),
                gg.spaceclient.modules.ZoomModule.isMixinActive()
                        ? "CameraMixin attached - real zoom"
                        : "not attached - falling back to the clamped option (check Camera.calculateFov exists)"));

        checks.add(new Check("Zoom write",
                !gg.spaceclient.modules.ZoomModule.lastResult().startsWith("write ignored"),
                gg.spaceclient.modules.ZoomModule.lastResult()));

        // The post effect slot is shared with the game and dropped without
        // warning, so what the module last managed to do is worth reading
        checks.add(new Check("Saturation",
                !gg.spaceclient.modules.SaturationModule.lastResult().contains("no effect"),
                gg.spaceclient.modules.SaturationModule.lastResult()));

        // Two separate unknowns behind one feature, so two lines: whether the
        // pose can be turned at all, and whether the idle motion could be
        // silenced. Either one missing leaves items looking half done.
        checks.add(new Check("Item physics rotation",
                gg.spaceclient.render.PoseOps.canRotate(),
                gg.spaceclient.render.PoseOps.canRotate()
                        ? "mulPose resolved"
                        : "no mulPose(Quaternionf) - items cannot be laid down"));

        checks.add(new Check("Item physics stillness",
                !gg.spaceclient.render.ItemStateFields.status().startsWith("no age"),
                gg.spaceclient.render.ItemStateFields.status()));

        // The culling hook is declared loosely on purpose, so a hook that never
        // applied looks the same as one with nothing to do. The count is what
        // separates them.
        checks.add(new Check("FPS boost hook",
                gg.spaceclient.render.CullReport.hooked(),
                gg.spaceclient.render.CullReport.status()));

        checks.add(new Check("Block highlight",
                !gg.spaceclient.render.BlockHighlightRenderer.hasFailed(),
                gg.spaceclient.render.BlockHighlightRenderer.status()));

        // The icons have failed twice now for two different reasons, and both
        // times the only evidence was that nothing appeared. This says which
        // step of decode, wrap and register got as far as it did.
        // Four attempts at the server screen stutter have each fixed something
        // real and none has fixed the complaint. This says which section is
        // actually slow instead of offering a fifth theory.
        checks.add(new Check("Slowest sections",
                gg.spaceclient.util.Timings.clean(),
                gg.spaceclient.util.Timings.report()));

        checks.add(new Check("Menu icons",
                !gg.spaceclient.ui.TextureLoader.status().startsWith("no ")
                        || gg.spaceclient.ui.TextureLoader.working(),
                gg.spaceclient.ui.TextureLoader.status()));

        checks.add(new Check("Cape hook",
                gg.spaceclient.render.CapeReport.hooked(),
                gg.spaceclient.render.CapeReport.status()));

        checks.add(new Check("Reach target",
                !gg.spaceclient.modules.ReachModule.lastRoute().startsWith("no target"),
                gg.spaceclient.modules.ReachModule.lastRoute()));

        boolean musicSupported = gg.spaceclient.music.MusicWatcher.isSupported();
        // Which route the track came from, since the two differ in what they see
        checks.add(new Check("Music route", true, gg.spaceclient.music.MusicWatcher.status()));

        checks.add(new Check("Music processes", true,
                gg.spaceclient.music.MusicWatcher.seenProcesses()));

        boolean scroll = gg.spaceclient.input.RawMouse.isInstalled();
        checks.add(new Check("Scroll wheel hook", scroll,
                scroll ? "installed, chaining to the game" : "not installed - enable Zoom once"));

        checks.add(new Check("Music detection", musicSupported,
                musicSupported
                        ? gg.spaceclient.music.MusicWatcher.status()
                        : "Windows only - the window title lookup uses PowerShell"));

        // --- the account object, needed for switching sessions ---
        Field userField = null;
        for (Field field : Minecraft.class.getDeclaredFields()) {
            if (field.getType() == net.minecraft.client.User.class) {
                userField = field;
                break;
            }
        }
        checks.add(new Check("Account field", userField != null,
                userField != null ? "Minecraft." + userField.getName() : "no User field on Minecraft"));

        int constructors = net.minecraft.client.User.class.getDeclaredConstructors().length;
        checks.add(new Check("Account constructors", constructors > 0,
                constructors + " available"));

        // --- launcher data, needed for account switching ---
        boolean accountsFile = LauncherAccounts.isAvailable();
        checks.add(new Check("Launcher accounts", accountsFile,
                accountsFile
                        ? LauncherAccounts.load().size() + " account(s) readable"
                        : "not found at " + LauncherAccounts.accountsFile()));

        // Can an account object actually be built on this version? A field that
        // exists is no use if nothing can be put in it.
        String shape = testBuildUser();
        checks.add(new Check("Account constructor shape", shape != null, shape));

        // Does the live account carry a real token? A name alone proves nothing.
        String liveToken = null;
        for (String accessor : new String[]{"getAccessToken", "accessToken", "getSessionId"}) {
            try {
                Object value = net.minecraft.client.User.class
                        .getMethod(accessor).invoke(mc.getUser());
                if (value instanceof String text) { liveToken = text; break; }
            } catch (Exception ignored) {
                // Try the next accessor
            }
        }
        boolean tokenLooksReal = liveToken != null && liveToken.length() > 40;
        checks.add(new Check("Login token", tokenLooksReal,
                liveToken == null ? "no accessor found"
                        : tokenLooksReal
                                ? liveToken.length() + " chars, ends " + liveToken.substring(liveToken.length() - 6)
                                : "only " + liveToken.length() + " chars - not a real token"));

        // Chat signing: a key from the previous account is what produces
        // "invalid_public_key_signature" on servers with secure profiles on
        String signing = "no manager on this version";
        for (var field : Minecraft.class.getDeclaredFields()) {
            if (field.getType().getSimpleName().contains("ProfileKeyPairManager")) {
                signing = field.getType().getSimpleName() + " present";
                break;
            }
        }
        checks.add(new Check("Chat signing", !signing.startsWith("no"), signing));

        checks.add(new Check("Session status", true,
                gg.spaceclient.session.SessionManager.status().isEmpty()
                        ? "nothing attempted yet"
                        : gg.spaceclient.session.SessionManager.status()));

        checks.add(new Check("Playing as", true, mc.getUser().getName()));

        // Which modules are on, since a module that is off simply does nothing
        StringBuilder enabled = new StringBuilder();
        for (var module : gg.spaceclient.SpaceClient.getModuleManager().getAll()) {
            if (module.isEnabled()) {
                if (enabled.length() > 0) enabled.append(", ");
                enabled.append(module.getName());
            }
        }
        checks.add(new Check("Enabled modules", enabled.length() > 0,
                enabled.length() > 0 ? enabled.toString() : "none are switched on"));

        return checks;
    }

    /** The shape of the User constructor, which is what the builder must fill. */
    private static String testBuildUser() {
        for (var constructor : net.minecraft.client.User.class.getDeclaredConstructors()) {
            StringBuilder shape = new StringBuilder();
            for (Class<?> parameter : constructor.getParameterTypes()) {
                if (shape.length() > 0) shape.append(", ");
                shape.append(parameter.getSimpleName());
            }
            // Reporting the shape is more useful than a yes or no, since it
            // shows exactly what the builder has to fill in.
            return "takes (" + shape + ")";
        }
        return "no constructors at all";
    }

    private static Class<?> findClass(String... names) {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (Throwable ignored) {
                // Try the next
            }
        }
        return null;
    }

    private static Method findMethod(String className, String method, int parameters) {
        try {
            Class<?> type = Class.forName(className);
            for (Method candidate : type.getMethods()) {
                if (candidate.getName().equals(method)
                        && candidate.getParameterCount() == parameters) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // Falls through to null
        }
        return null;
    }

    private Diagnostics() {}
}
