package gg.spaceclient.host;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.util.Screens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;

import java.util.concurrent.CompletableFuture;

/**
 * Opens the current singleplayer world to other people.
 *
 * Minecraft already does the hard part: publishServer starts listening on a
 * port and handles logins. The only thing missing for someone outside the house
 * is a route through the router, which is what Upnp does.
 *
 * MultiplayerScope has exactly two values on this version, OFF and LAN. There
 * is no "public" setting, and none is needed - LAN only controls whether the
 * world is announced to the local network. The socket it opens is reachable
 * from anywhere the router allows.
 */
public final class WorldHost {

    /** What to tell the player, once hosting has been attempted. */
    private static volatile String status = "not hosting";

    /** The address to hand to friends, empty when there is none. */
    private static volatile String address = "";

    private static volatile int port = 0;
    private static volatile boolean mapped = false;
    private static volatile boolean working = false;

    /**
     * Whether accounts without a Mojang session may join.
     *
     * Off by default, and it stays a per session choice rather than something
     * saved: switching authentication off is the kind of thing that should be
     * decided deliberately each time, not inherited from a decision made weeks
     * ago and forgotten.
     */
    private static volatile boolean allowOffline = false;

    public static boolean allowsOffline() { return allowOffline; }

    public static void setAllowOffline(boolean value) { allowOffline = value; }

    public static String status() { return status; }
    public static String address() { return address; }
    public static boolean busy() { return working; }

    public static boolean isHosting() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        return server != null && server.isPublished();
    }

    /**
     * Publishes the world, then tries to make it reachable from outside.
     *
     * The publish itself happens on the game thread because it touches the
     * server; the router conversation runs after it, on its own thread, since
     * discovery alone can take three seconds and the game would visibly stall.
     */
    public static void host(GameType mode, boolean cheats) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();

        if (server == null) {
            status = "no singleplayer world is running";
            return;
        }
        if (server.isPublished()) {
            status = "already hosting on port " + server.getPort();
            return;
        }

        working = true;
        status = "opening the world...";

        try {
            port = HttpUtil.getAvailablePort();
            boolean opened = server.publishServer(
                    MinecraftServer.MultiplayerScope.LAN, mode, cheats, port);

            if (!opened) {
                working = false;
                status = "Minecraft could not open the port";
                return;
            }

        } catch (Throwable t) {
            working = false;
            status = "could not open the world: " + t.getMessage();
            SpaceClient.LOGGER.warn("publishServer failed", t);
            return;
        }

        // From here the world is already playable on the local network, so a
        // failure past this point is worth reporting but not worth undoing
        say("§bSpace Client §7- world opened on port §f" + port);

        if (allowOffline) {
            say("§eOffline accounts are allowed. §7Anyone reaching this world can "
                    + "pick any name, including one already an operator.");
        }

        int opened = port;
        CompletableFuture.runAsync(() -> {
            try {
                String external = Upnp.map(opened);

                if (external.isEmpty()) {
                    mapped = false;
                    address = "";
                    status = Upnp.reason();

                    say("§7Only reachable on your local network. §f" + Upnp.reason());
                    say("§7Check that UPnP is enabled in your router, or forward "
                            + "port §f" + opened + "§7 by hand. Tailscale also works.");
                    return;
                }

                mapped = true;
                address = external + ":" + opened;
                status = "hosting at " + address;

                say("§7Friends can join at §a" + address);
                say("§8Anyone with this address can attempt to connect.");

            } catch (Throwable t) {
                status = "router step failed: " + t.getMessage();
            } finally {
                working = false;
            }
        });
    }

    /** Closes the world again and takes the port mapping down. */
    public static void stop() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();

        int opened = port;
        boolean hadMapping = mapped;

        try {
            if (server != null && server.isPublished()) server.unpublishServer();
        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("unpublishServer failed: {}", t.getMessage());
        }

        address = "";
        mapped = false;
        status = "not hosting";
        say("§bSpace Client §7- world closed");

        if (hadMapping) {
            CompletableFuture.runAsync(() -> Upnp.unmap(opened));
        }
    }

    /** Writes a line into the player's own chat. Nothing leaves this machine. */
    private static void say(String text) {
        Minecraft.getInstance().execute(() -> Screens.chat(text));
    }

    private WorldHost() {}
}
