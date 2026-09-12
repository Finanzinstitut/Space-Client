package gg.spaceclient.modules;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.module.HudModule;
import gg.spaceclient.setting.BooleanSetting;
import gg.spaceclient.setting.ColorSetting;
import gg.spaceclient.setting.IntSetting;
import gg.spaceclient.setting.SettingGroup;
import gg.spaceclient.ui.Textures;
import gg.spaceclient.util.Reflect;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;

/**
 * Which server you are actually on, with its icon.
 *
 * Obvious until you have four windows open, or hop between a practice server
 * and the one that matters, or record footage and later cannot tell which
 * clip came from where. The address is also the thing people ask for in chat
 * and the thing you paste into a bug report, and F3 is a poor way to find it.
 *
 * The icon carries more than the text does at a glance - it is the thing you
 * already recognise from the server list, and reading a picture is faster than
 * reading a hostname.
 *
 * <h2>Getting the icon onto the screen</h2>
 *
 * The game keeps a server's icon as raw PNG bytes, not as anything drawable,
 * and turning those into a texture means naming three classes whose shape on
 * this version nothing here has verified. So the whole path - reading the
 * bytes, decoding them, registering the result - runs through reflection, and
 * a miss anywhere along it costs the icon rather than the build or the
 * address next to it.
 *
 * The decode happens once per server. Icons do not change while you are
 * connected, so doing it per frame would be pure waste.
 */
public class ServerModule extends HudModule {

    // --- what to show ---

    private final BooleanSetting showIcon = new BooleanSetting(
            "show_icon", "Icon", "The server's picture from the server list", true);

    private final BooleanSetting showName = new BooleanSetting(
            "show_name", "Name", "The name you saved it under", true);

    private final BooleanSetting showAddress = new BooleanSetting(
            "show_address", "Address", "The host you are connected to", true);

    private final BooleanSetting hidePort = new BooleanSetting(
            "hide_port", "Hide port", "Drop the :25565 half of the address", true);

    /**
     * Whether to say anything at all in single player.
     *
     * Off by default: the element exists to answer "which server is this",
     * and a world open on your own machine is not a question anyone has.
     */
    private final BooleanSetting showSingleplayer = new BooleanSetting(
            "show_singleplayer", "In single player", "Show the world name too", false);

    // --- appearance ---

    private final IntSetting iconSize = new IntSetting(
            "icon_size", "Icon size", "Height of the icon in pixels", 16, 8, 32);

    private final ColorSetting textColor = new ColorSetting(
            "text_color", "Text colour", "Colour of the name and address", 0xFFFFFFFF);

    public ServerModule() {
        super("server", "Server",
                "The address and icon of the server you are on",
                0.02f, 0.06f, false);
        addGroups(
                SettingGroup.of("Readout", "What the element says",
                        showIcon, showName, showAddress, hidePort, showSingleplayer),
                SettingGroup.of("Appearance", "How it looks on screen",
                        iconSize, textColor)
        );
    }

    @Override
    protected long refreshMillis() { return 1000; }

    // --- reading the connection ---

    private Object cachedServer = null;
    private long serverCachedAt = 0L;

    /**
     * The connection, looked up once per refresh rather than once per call.
     *
     * getWidth, getHeight and render each asked separately, and so did the
     * icon lookup - four reflective calls per frame for something that changes
     * when you join a server and not otherwise. The element that reports which
     * server you are on should not itself be a reason to drop frames.
     */
    private Object serverData() {
        long now = System.currentTimeMillis();
        if (now - serverCachedAt >= refreshMillis()) {
            cachedServer = Reflect.call(mc, "getCurrentServer", "getCurrentServerEntry");
            serverCachedAt = now;
        }
        return cachedServer;
    }

    private String readString(Object target, String field, String... methods) {
        Object value = Reflect.call(target, methods);
        if (value instanceof String text && !text.isEmpty()) return text;

        value = readField(target, field);
        return value instanceof String text && !text.isEmpty() ? text : null;
    }

    private String address(Object server) {
        String raw = readString(server, "ip", "getIp", "getAddress");
        if (raw == null) return null;
        if (hidePort.get()) {
            int colon = raw.lastIndexOf(':');
            // Only the last colon, and only when what follows is a port -
            // an IPv6 literal is full of colons and none of them are one
            if (colon > 0 && raw.indexOf(':') == colon) raw = raw.substring(0, colon);
        }
        return raw;
    }

    private String name(Object server) {
        return readString(server, "name", "getName");
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
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

    // --- the icon ---

    /** The identifier the current server's icon was registered under. */
    private Identifier iconId = null;

    /** What that identifier was built from, so a change is noticed. */
    private int iconKey = 0;

    /**
     * The icon for the server we are on, registering it the first time.
     *
     * Returns null whenever anything on the path is missing, which is a normal
     * outcome rather than an error: plenty of servers simply have no icon.
     */
    private Identifier icon(Object server) {
        byte[] bytes = iconBytes(server);
        if (bytes == null || bytes.length == 0) return null;

        int key = java.util.Arrays.hashCode(bytes);
        if (iconId != null && key == iconKey) return iconId;

        Identifier registered = register(bytes, key);
        if (registered != null) {
            iconId = registered;
            iconKey = key;
        }
        return registered;
    }

    private byte[] iconBytes(Object server) {
        Object value = Reflect.call(server, "getIconBytes", "getIcon");

        // Newer shapes wrap the bytes rather than handing them over
        if (value != null && !(value instanceof byte[])) {
            Object unwrapped = Reflect.call(value, "iconBytes", "bytes", "getBytes");
            if (unwrapped instanceof byte[]) return (byte[]) unwrapped;
        }
        if (value instanceof byte[] raw) return raw;

        Object field = readField(server, "iconBytes");
        return field instanceof byte[] raw ? raw : null;
    }

    /**
     * Decodes PNG bytes and hands the result to the texture manager.
     *
     * Three unverified shapes in a row, so each step is guarded separately and
     * the whole thing gives up quietly. The identifier is keyed by the image's
     * own hash, which means switching servers and switching back reuses what
     * was already registered instead of piling up textures.
     */
    /**
     * Hands the bytes to the loader, under an identifier keyed by the image.
     *
     * Keyed by content rather than by address, so hopping between two servers
     * reuses what is already registered instead of adding a texture each time.
     */
    private Identifier register(byte[] bytes, int key) {
        return gg.spaceclient.ui.TextureLoader.register(bytes,
                Identifier.fromNamespaceAndPath(
                        SpaceClient.MOD_ID, "server_icon/" + Integer.toHexString(key)));
    }

    // --- the text ---

    private java.util.List<String> lines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        Object server = serverData();

        if (server == null) {
            if (showSingleplayer.get()) {
                String world = Reflect.call(mc, "getSingleplayerServer") == null
                        ? "Not connected"
                        : "Single player";
                out.add(world);
            }
            return out;
        }

        if (showName.get()) {
            String name = name(server);
            if (name != null) out.add(name);
        }
        if (showAddress.get()) {
            String address = address(server);
            if (address != null) out.add(address);
        }
        return out;
    }

    private String[] rows() {
        return cachedLines(() -> String.join("\n", lines()));
    }

    private int iconWidth() {
        if (!showIcon.get()) return 0;
        Object server = serverData();
        return server != null && icon(server) != null ? iconSize.get() + 4 : 0;
    }

    @Override
    public int getWidth() {
        int width = 0;
        for (String row : rows()) width = Math.max(width, mc.font.width(row));
        int total = width + iconWidth();
        return Math.max(total, 16);
    }

    @Override
    public int getHeight() {
        int text = rows().length * (mc.font.lineHeight + 1);
        return Math.max(text, showIcon.get() && iconWidth() > 0 ? iconSize.get() : 0);
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        int textX = x;

        if (showIcon.get()) {
            Object server = serverData();
            Identifier id = server == null ? null : icon(server);
            if (id != null) {
                int size = iconSize.get();
                // Falls back to no icon rather than a hole if the blit call
                // could not be resolved, and the address still stands
                if (Textures.draw(graphics, id, x, y, size, size)) {
                    textX = x + size + 4;
                }
            }
        }

        String[] rows = rows();

        // Centred against the icon when it is the taller of the two, so a
        // single line does not sit against its top edge
        int textHeight = rows.length * (mc.font.lineHeight + 1);
        int offset = Math.max(0, (getHeight() - textHeight) / 2);

        for (int i = 0; i < rows.length; i++) {
            graphics.text(mc.font, rows[i],
                    textX, y + offset + i * (mc.font.lineHeight + 1),
                    textColor.get(), true);
        }
    }
}
