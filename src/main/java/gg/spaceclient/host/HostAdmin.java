package gg.spaceclient.host;

import gg.spaceclient.SpaceClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserBanListEntry;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Host powers over a published world: kick, ban, op, deop.
 *
 * Everything here runs on the server thread. The screen calling it lives on the
 * client thread, and the player list is not safe to change from there - so each
 * action is handed to the server through execute rather than run where it was
 * requested.
 *
 * Mojang replaced GameProfile with NameAndId across the player list in this
 * version, which is why identities are built as NameAndId(uuid, name) instead.
 */
public final class HostAdmin {

    /** What the last action did, shown under the player list. */
    private static volatile String status = "";

    public static String status() { return status; }

    /** Everyone currently connected, host included. */
    public static List<ServerPlayer> players() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return List.of();
        try {
            return List.copyOf(server.getPlayerList().getPlayers());
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Whether this is the person hosting, who must not be kicked out of their own world. */
    public static boolean isHost(ServerPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getUUID().equals(player.getUUID());
    }

    public static boolean isOp(ServerPlayer player) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return false;
        try {
            return server.getPlayerList().isOp(identity(player));
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- actions ----------------

    public static void kick(ServerPlayer player, String reason) {
        run(player, "kicked", (list, target) -> {
            if (!disconnect(target, reason)) {
                // The polite route was not available, so use the blunt one.
                // It drops the connection without a message, which is worse
                // for the person kicked but still does the job.
                list.disconnectAllPlayersWithProfile(target.getUUID());
            }
        });
    }

    public static void ban(ServerPlayer player, String reason) {
        run(player, "banned", (list, target) -> {
            NameAndId id = identity(target);
            list.getBans().add(new UserBanListEntry(id, null, "Space Client", null, reason));

            if (!disconnect(target, reason)) {
                list.disconnectAllPlayersWithProfile(target.getUUID());
            }
        });
    }

    public static void op(ServerPlayer player) {
        run(player, "made an operator", (list, target) -> list.op(identity(target)));
    }

    public static void deop(ServerPlayer player) {
        run(player, "no longer an operator", (list, target) -> list.deop(identity(target)));
    }

    // ---------------- plumbing ----------------

    private interface Action {
        void apply(PlayerList list, ServerPlayer target) throws Throwable;
    }

    private static void run(ServerPlayer player, String past, Action action) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            status = "no world is running";
            return;
        }
        if (isHost(player)) {
            status = "that is you";
            return;
        }

        String name = player.getName().getString();
        UUID uuid = player.getUUID();

        server.execute(() -> {
            try {
                PlayerList list = server.getPlayerList();

                // Re-read the player on the server thread: the one the screen
                // handed over may have left in the meantime, and acting on a
                // stale object is how a host ends up banning nobody.
                ServerPlayer target = list.getPlayer(uuid);
                if (target == null) {
                    status = name + " has already left";
                    return;
                }

                action.apply(list, target);
                status = name + " " + past;

            } catch (Throwable t) {
                status = "failed: " + t.getMessage();
                SpaceClient.LOGGER.warn("Host action failed", t);
            }
        });
    }

    private static NameAndId identity(ServerPlayer player) {
        return new NameAndId(player.getUUID(), player.getName().getString());
    }

    /**
     * Disconnects with a reason.
     *
     * Reflective because disconnect is declared on a superclass of the game
     * packet listener, and this version's exact shape there is unverified.
     * A miss is survivable - the caller falls back to dropping the connection.
     */
    private static boolean disconnect(ServerPlayer player, String reason) {
        try {
            Object connection = player.connection;
            if (connection == null) return false;

            for (Method method : connection.getClass().getMethods()) {
                if (!method.getName().equals("disconnect")) continue;
                if (method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isAssignableFrom(Component.class)) continue;

                method.setAccessible(true);
                method.invoke(connection, Component.literal(reason));
                return true;
            }
        } catch (Throwable ignored) {
            // Fall back to the blunt route
        }
        return false;
    }

    private HostAdmin() {}
}
