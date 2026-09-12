package gg.spaceclient.net;

import gg.spaceclient.SpaceClient;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is playing with Space Client right now, so the name tag can say so.
 *
 * The distinction matters and was wrong at first: the roster used to be every
 * account that had ever registered, so anyone who tried the client once kept
 * the badge forever, including while playing vanilla. Presence is now a
 * separate, expiring signal - a heartbeat while in a world, dropped on the way
 * out - and the permanent record is kept only for counting installs.
 *
 * Deliberately built the opposite way round from NowPlayingShare, and the
 * reason is the free KV quota rather than taste.
 *
 * NowPlayingShare asks the worker about the specific players it can see, every
 * ten seconds, because a song changes constantly and only matters for people
 * in view. Badge membership does neither: it changes when somebody installs
 * the client, which is roughly never, and it is the same answer for everybody.
 * So this fetches the whole roster instead - one request, one cached answer -
 * and every "does this player have a badge" check afterwards is a set lookup
 * with no network and no storage behind it.
 *
 * That distinction is what keeps the badge off the KV bill. Per-player polling
 * at now-playing rates would cost one list operation per client per poll, and
 * the free tier allows a thousand a day in total.
 *
 * The roster is not cleared on disconnect. It is not per world, and a player
 * who rejoins two minutes later would otherwise pay for a fresh fetch to learn
 * exactly what it already knew.
 */
public final class Presence {

    /**
     * How often this client re-announces itself.
     *
     * Twelve hours, not minutes. The worker keeps an entry for thirty days and
     * refreshes its own timestamp on contact, so announcing more often buys
     * nothing and costs a KV write every time. The worker also declines to
     * rewrite an entry it saw recently, so a player who restarts the game
     * repeatedly still only costs one write a day.
     */
    private static final long REGISTER_EVERY_MS = 12 * 60 * 60 * 1000L;

    /**
     * How often this client says it is still playing.
     *
     * Seven minutes against a fifteen minute expiry, so one missed call never
     * drops the badge. This is the only write that cannot be deduplicated away
     * and so it sets the cost of the feature: roughly eight writes an hour per
     * player, against a thousand a day on the free tier.
     */
    private static final long HEARTBEAT_MS = 7 * 60 * 1000L;

    /**
     * How often the roster is refreshed.
     *
     * Ninety seconds, which sounds expensive and is not: the worker serves this
     * list from its edge cache for three minutes at a time, so most of these
     * calls never reach storage at all. It has to be this frequent now that the
     * roster means "playing right now" - a badge that took half an hour to
     * appear or disappear would be worse than no badge.
     */
    private static final long FETCH_EVERY_MS = 90 * 1000L;

    /** Retry sooner than the full interval when a call did not get through. */
    private static final long RETRY_MS = 2 * 60 * 1000L;

    /**
     * How many quick retries a freshly registered account gets.
     *
     * KV list is eventually consistent: a key that was just written does not
     * show up in a listing for up to a minute or so. Registration and the
     * first roster fetch start on the same tick, so on a first run the fetch
     * reliably comes back without this account in it - and settling straight
     * into the half hour interval at that point left the person who had just
     * installed the mod with no badge and no explanation.
     *
     * So while the roster is missing this account, the short interval is used
     * instead. Bounded rather than open ended, because "not in the roster" is
     * also what a genuinely unregistered client looks like, and that one must
     * not poll every two minutes forever.
     */
    private static final int CATCHUP_ATTEMPTS = 8;

    private static final Set<UUID> badged = ConcurrentHashMap.newKeySet();

    private static volatile boolean registering = false;
    private static volatile boolean fetching = false;

    /** Tracks the world so leaving can be announced exactly once. */
    private static volatile boolean inWorld = false;
    private static volatile long nextBeat = 0;

    private static volatile long nextRegister = 0;
    private static volatile long nextFetch = 0;

    private static volatile boolean rosterLoaded = false;

    /** Counts down while waiting for this account to appear in the roster. */
    private static volatile int catchUp = 0;

    /**
     * Whether this player runs Space Client.
     *
     * Called from the render thread for every name tag on screen, so it does
     * nothing but read a set.
     */
    public static boolean hasBadge(UUID uuid) {
        return uuid != null && badged.contains(uuid);
    }

    /**
     * How many people are playing with Space Client right now.
     *
     * Negative until the first roster has arrived, so a readout can say "not
     * known yet" instead of confidently showing a zero.
     */
    public static int onlineCount() {
        return rosterLoaded ? badged.size() : -1;
    }

    /**
     * What the badge roster is doing, for the diagnostics page.
     *
     * Reports the network side through SpaceApi rather than a local copy, so a
     * registration that was refused says so here instead of being hidden
     * behind a roster fetch that technically succeeded - which is exactly how
     * an empty roster looked the first time round.
     */
    public static String status() {
        String detail = SpaceApi.badgeStatus();
        if (!rosterLoaded) return detail;
        if (catchUp > 0) {
            return badged.size() + " playing (" + detail
                    + ", waiting for yours to propagate)";
        }
        return badged.size() + " playing (" + detail + ")";
    }

    /** Called every client tick. */
    public static void tick() {
        try {
            Minecraft mc = Minecraft.getInstance();

            if (mc.level == null || mc.player == null) {
                // Left a world: drop the badge now rather than letting it sit
                // there for the rest of the expiry window
                if (inWorld) {
                    inWorld = false;
                    nextBeat = 0;
                    CompletableFuture.runAsync(SpaceApi::leave);
                }
                return;
            }
            inWorld = true;

            long now = System.currentTimeMillis();

            if (now >= nextBeat) {
                nextBeat = now + RETRY_MS;
                CompletableFuture.runAsync(() -> {
                    if (!SpaceApi.heartbeat()) return;

                    long done = System.currentTimeMillis();
                    nextBeat = done + HEARTBEAT_MS;

                    // The heartbeat is what puts an account on the roster, so
                    // it is the heartbeat that has to wait for it to show up.
                    // KV list does not see a key the instant it is written, and
                    // the first heartbeat and the first fetch start together,
                    // so without this a player would sit unbadged until the
                    // next beat seven minutes later.
                    Minecraft self = Minecraft.getInstance();
                    UUID mine = self.player != null ? self.player.getUUID() : null;
                    if (mine != null && !badged.contains(mine)) {
                        catchUp = CATCHUP_ATTEMPTS;
                        nextFetch = done;
                    }
                });
            }

            if (!registering && now >= nextRegister) {
                registering = true;
                // Set before the call, not after: a failure pushes it out by
                // the short retry rather than letting a broken worker be
                // hammered once per tick
                nextRegister = now + RETRY_MS;

                CompletableFuture.runAsync(() -> {
                    try {
                        // Only the permanent "has this been installed" record.
                        // The badge no longer depends on it.
                        if (SpaceApi.register()) {
                            nextRegister = System.currentTimeMillis() + REGISTER_EVERY_MS;
                        }
                    } finally {
                        registering = false;
                    }
                });
            }

            if (!fetching && now >= nextFetch) {
                fetching = true;
                nextFetch = now + RETRY_MS;

                CompletableFuture.runAsync(() -> {
                    try {
                        List<String> users = SpaceApi.badgeUsers();
                        // Null means the call did not get through, which is not
                        // the same as an empty roster - keep what we had
                        if (users == null) return;

                        // Rebuilt rather than merged. Unlike a song, an absent
                        // entry here is meaningful: it is somebody whose
                        // registration expired, and they should lose the badge.
                        Set<UUID> fresh = ConcurrentHashMap.newKeySet();
                        for (String raw : users) {
                            try {
                                fresh.add(UUID.fromString(raw));
                            } catch (Throwable ignored) {
                                // One malformed id must not cost the roster
                            }
                        }

                        badged.clear();
                        badged.addAll(fresh);
                        rosterLoaded = true;

                        // Settle onto the slow interval only once this account
                        // is actually in the list. Until then the write has not
                        // finished propagating, and half an hour is far too
                        // long to wait to find that out.
                        Minecraft self = Minecraft.getInstance();
                        UUID mine = self.player != null ? self.player.getUUID() : null;
                        boolean waitingForSelf = mine != null
                                && !badged.contains(mine) && catchUp > 0;

                        if (waitingForSelf) {
                            catchUp--;
                            nextFetch = System.currentTimeMillis() + RETRY_MS;
                        } else {
                            catchUp = 0;
                            nextFetch = System.currentTimeMillis() + FETCH_EVERY_MS;
                        }

                    } finally {
                        fetching = false;
                    }
                });
            }

        } catch (Throwable t) {
            SpaceClient.LOGGER.warn("Presence failed: {}", t.getMessage());
        }
    }

    private Presence() {}
}
