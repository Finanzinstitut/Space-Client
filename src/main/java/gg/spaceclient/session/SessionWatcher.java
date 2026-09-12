package gg.spaceclient.session;

import gg.spaceclient.SpaceClient;
import net.minecraft.client.Minecraft;

/**
 * Keeps the session usable without a restart.
 *
 * Reacting to a failed join turned out not to be enough: an "invalid session"
 * failure happens *during* the connect attempt, so no connection is ever
 * established and there is no drop to react to. The fix is to stop letting the
 * token go stale in the first place - it is refreshed shortly after launch and
 * then on a timer, well inside its lifetime.
 *
 * The drop trigger is kept as a second chance for the cases the timer misses.
 */
public class SessionWatcher {
    /** Do not retry more often than this, so a failing refresh cannot loop. */
    private static final long COOLDOWN_MS = 30_000;

    /** A Minecraft session lasts about a day; renewing hourly stays well inside. */
    private static final long RENEW_EVERY_MS = 60L * 60 * 1000;

    /** Give the game a moment to finish starting before the first renewal. */
    private static final long FIRST_RENEW_AFTER_MS = 20_000;

    private static boolean wasConnected = false;
    private static long lastAttempt = 0;
    private static final long startedAt = System.currentTimeMillis();
    private static boolean didInitialRenew = false;

    public static void tick(Minecraft client) {
        boolean connected = client.getConnection() != null;

        // Act on the transition from connected to not, not on every frame after
        if (wasConnected && !connected) {
            onDisconnected();
        }
        wasConnected = connected;

        maybeRenew();
    }

    /**
     * Renews before the token can expire. The launcher may have been open for
     * hours before the game started, so the very first renewal happens shortly
     * after launch rather than an hour in.
     */
    private static void maybeRenew() {
        if (SessionManager.isBusy()) return;
        if (!LauncherAccounts.isAvailable()) return;
        if (isOfflineProfile()) return;

        long now = System.currentTimeMillis();

        if (!didInitialRenew) {
            if (now - startedAt < FIRST_RENEW_AFTER_MS) return;
            didInitialRenew = true;
            lastAttempt = now;
            SpaceClient.LOGGER.info("Renewing the session shortly after launch");
            SessionManager.refreshCurrent();
            return;
        }

        long since = Math.max(SessionManager.lastRefresh(), lastAttempt);
        if (now - since < RENEW_EVERY_MS) return;

        lastAttempt = now;
        SpaceClient.LOGGER.info("Hourly session renewal");
        SessionManager.refreshCurrent();
    }

    private static boolean isOfflineProfile() {
        String playing = Minecraft.getInstance().getUser().getName();
        return LauncherAccounts.load().stream()
                .filter(a -> a.username().equalsIgnoreCase(playing))
                .anyMatch(LauncherAccount::offline);
    }

    private static void onDisconnected() {
        long now = System.currentTimeMillis();
        if (now - lastAttempt < COOLDOWN_MS) return;
        if (SessionManager.isBusy()) return;
        if (!LauncherAccounts.isAvailable()) return;

        if (isOfflineProfile()) return;

        lastAttempt = now;
        SpaceClient.LOGGER.info("Left a server - refreshing the session in case it expired");
        SessionManager.refreshCurrent();
    }

    private SessionWatcher() {}
}
