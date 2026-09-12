package gg.spaceclient.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import gg.spaceclient.SpaceClient;
import gg.spaceclient.modules.MusicModule;
import gg.spaceclient.music.Lyrics;
import gg.spaceclient.music.MediaSession;
import gg.spaceclient.music.NowPlaying;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes the local track and keeps what other people are playing.
 *
 * Two timers, deliberately different: reporting only happens when the track
 * actually changed, or once a minute so the entry does not expire; looking up
 * runs on a slower beat, because a song lasting three minutes does not need to
 * be fetched every second.
 *
 * Nothing here runs while the setting is off, with one exception - the moment
 * it is switched off, one last call goes out to clear the entry. Otherwise the
 * last song anyone saw would keep hanging over the name until the worker's own
 * expiry caught up with it, which is not what switching a thing off means.
 */
public final class NowPlayingShare {

    /** How often the local track may be sent, at the very most. */
    private static final long REPORT_MIN_MS = 5_000;

    /** A track change is news, so it waits far less than a routine report. */
    private static final long REPORT_CHANGE_MS = 800;

    /** Sent again after this long even when nothing changed, to stay alive. */
    private static final long REPORT_KEEPALIVE_MS = 60_000;

    /** How often everyone else's tracks are fetched. */
    private static final long FETCH_MS = 10_000;

    /** Still a floor, so a busy spawn cannot turn every arrival into a request. */
    private static final long FETCH_NEW_MS = 1_500;

    /** Who the last lookup covered, so an arrival can be told from a regular. */
    private static final java.util.Set<UUID> asked =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The worker caps this too; asking for more would be pointless. */
    private static final int MAX_LOOKUP = 100;

    private static final Map<UUID, String> songs = new ConcurrentHashMap<>();

    /**
     * What each visible player is playing, and where they are in it.
     *
     * The position is the point that matters. It arrives every ten seconds but
     * is carried forward locally in between, so the lyric line advances every
     * frame rather than lurching once per poll.
     */
    private record Remote(String artist, String title, double position,
                          boolean playing, long receivedAt) {}

    private static final Map<UUID, Remote> remotes = new ConcurrentHashMap<>();

    private static volatile boolean reporting = false;
    private static volatile boolean fetching = false;

    private static long lastReport = 0;
    private static long lastFetch = 0;
    private static String lastReported = "";
    private static double lastPosition = -1;
    private static boolean wasSharing = false;

    /**
     * Whether your own track should be drawn over your own head.
     *
     * Read from the render thread every frame, so it goes through here rather
     * than making the mixin dig the module out of the manager itself.
     */
    public static boolean showOnSelf() {
        MusicModule module = module();
        return module != null && module.isEnabled()
                && module.sharesOverName() && module.showsOnSelf();
    }

    private static int hookLong = 0;
    private static int hookShort = 0;
    private static int hookDrawn = 0;

    /**
     * Called by the name tag mixin every time it runs.
     *
     * The counters live here rather than in the mixin because mixin classes are
     * consumed by the transformer and never loaded as ordinary classes - so
     * calling one from mod code throws NoClassDefFoundError. The mixin may call
     * into here; nothing may call into the mixin.
     */
    public static void noteHook(boolean longOverload, boolean didDraw) {
        if (longOverload) hookLong++; else hookShort++;
        if (didDraw) hookDrawn++;
    }

    /** Whether the long overload has ever run, so the short one can stand down. */
    public static boolean longHookSeen() {
        return hookLong > 0;
    }

    /** What the name tag hook has been doing, for the diagnostics page. */
    public static String hookStatus() {
        if (hookLong == 0 && hookShort == 0) return "never fired";
        return "fired " + (hookLong + hookShort) + "x (long " + hookLong
                + ", short " + hookShort + "), drew " + hookDrawn;
    }

    /** What the last lookup came back with, for the diagnostics page. */
    public static String cacheStatus() {
        Minecraft mc = Minecraft.getInstance();
        UUID self = mc.player != null ? mc.player.getUUID() : null;
        boolean mine = self != null && songs.containsKey(self);

        if (lastFetch == 0) return "not fetched yet";
        return songs.size() + " known, yours: " + (mine ? "yes" : "no");
    }

    /** What the local track last sent was, for the diagnostics page. */
    public static String reportStatus() {
        if (lastReport == 0) return "nothing sent yet";
        return lastReported.isEmpty() ? "sent: (cleared)" : "sent: " + lastReported;
    }

    /**
     * The lyric path, step by step, for the diagnostics page.
     *
     * Five things have to line up for a line to appear, and from the outside
     * they all fail the same way. This says which one gave out.
     */
    public static String lyricStatus() {
        if (!showsLyrics()) return "setting is off";

        Minecraft mc = Minecraft.getInstance();
        UUID self = mc.player != null ? mc.player.getUUID() : null;

        if (remotes.isEmpty()) return "no positions received (" + songs.size() + " songs)";

        Remote remote = self != null ? remotes.get(self) : null;
        if (remote == null) {
            return remotes.size() + " with position, none of them you";
        }
        if (remote.position() < 0) return "your position never arrived";

        double position = remote.playing()
                ? remote.position() + (System.currentTimeMillis() - remote.receivedAt()) / 1000.0
                : remote.position();

        String line = Lyrics.line(remote.artist(), remote.title(), position);
        return String.format("%.0fs -> %s", position,
                line.isEmpty() ? "(no line at this point)" : line);
    }

    /** What this player is playing, or null. Called from the render thread. */
    public static String songFor(UUID uuid) {
        return uuid == null ? null : songs.get(uuid);
    }

    /**
     * The lyric line for a player, or null.
     *
     * Both sides have to want this: the other player has to be sharing a line,
     * and you have to have the setting on yourself. Somebody else's choice to
     * broadcast lyrics does not put them on your screen.
     */
    public static String lyricFor(UUID uuid) {
        if (uuid == null || !showsLyrics()) return null;

        Remote remote = remotes.get(uuid);
        if (remote == null || remote.title().isEmpty()) return null;

        // A paused track stays where it was left; a playing one has moved on
        // by however long ago this reading arrived
        double position = remote.playing()
                ? remote.position() + (System.currentTimeMillis() - remote.receivedAt()) / 1000.0
                : remote.position();

        return Lyrics.line(remote.artist(), remote.title(), position);
    }

    /** Whether this player wants lyrics at all. */
    public static boolean showsLyrics() {
        MusicModule module = module();
        return module != null && module.isEnabled() && module.showsLyrics();
    }

    /** Called every client tick. */
    public static void tick() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                songs.clear();
                remotes.clear();
                return;
            }

            MusicModule module = module();
            boolean sharing = module != null && module.isEnabled() && module.sharesOverName();

            // Fetched for the local track whenever the setting is on, whether
            // or not anything is being shared - the HUD wants the line too
            if (module == null || !module.isEnabled() || !module.showsLyrics()) {
                Lyrics.clear();
            } else {
                // Nothing used to start a lookup except drawing somebody's name
                // tag, so switching lyrics on while alone did nothing at all and
                // the diagnostics line stayed on "off". The local track is reason
                // enough to fetch: the HUD wants the line whether or not anyone
                // else is around to show it to.
                NowPlaying own = module.track();
                if (!own.isEmpty()) {
                    Lyrics.line(own.artist(), own.title(), MediaSession.position());
                }
            }

            if (sharing) {
                maybeReport(module);
            } else if (wasSharing) {
                // Switched off - take the entry down rather than let it linger
                wasSharing = false;
                lastReported = "";
                clearRemote();
            }
            wasSharing = sharing;

            maybeFetch(mc);

        } catch (Throwable t) {
            // A sharing problem must never disturb the tick loop
            SpaceClient.LOGGER.warn("Now playing share failed: {}", t.getMessage());
        }
    }

    private static MusicModule module() {
        var manager = SpaceClient.getModuleManager();
        if (manager == null) return null;
        return manager.get("music") instanceof MusicModule music ? music : null;
    }

    private static void maybeReport(MusicModule module) {
        if (reporting) return;

        long now = System.currentTimeMillis();

        NowPlaying playing = module.track();
        String line = playing.isEmpty() ? "" : playing.display();

        // The floor used to be checked before the track was even read, so a
        // song that changed one second after the last report sat unsent for
        // four more. A change now only waits out a much shorter floor; the
        // long one still applies to keepalives and seek corrections, which is
        // what it was there to hold back.
        boolean fresh = !line.equals(lastReported);
        long floor = fresh ? REPORT_CHANGE_MS : REPORT_MIN_MS;
        if (now - lastReport < floor) return;

        double position = MediaSession.position();

        // Sent when the track changes, when the keepalive is due, or when the
        // position no longer matches what listeners would have extrapolated -
        // which is how seeking gets corrected without reporting constantly
        double expected = lastPosition + (now - lastReport) / 1000.0;
        boolean seeked = position >= 0 && lastPosition >= 0
                && Math.abs(position - expected) > 2.0;

        boolean changed = !line.equals(lastReported);
        boolean stale = now - lastReport > REPORT_KEEPALIVE_MS;
        if (!changed && !stale && !seeked) return;

        lastReport = now;
        lastReported = line;
        lastPosition = position;
        reporting = true;

        CompletableFuture.runAsync(() -> {
            try {
                SpaceApi.report(playing.source(), playing.artist(),
                        playing.title(), playing.playing(), position);
            } finally {
                reporting = false;
            }
        });
    }

    private static void clearRemote() {
        CompletableFuture.runAsync(() -> SpaceApi.report("", "", "", false, -1));
    }

    private static void maybeFetch(Minecraft mc) {
        if (fetching) return;

        long now = System.currentTimeMillis();

        List<UUID> wanted = new ArrayList<>();

        // Deliberately fetched rather than read from the local module: the
        // point of the self setting is to prove the round trip, and taking a
        // shortcut here would prove nothing.
        if (showOnSelf() && mc.player.getUUID() != null) {
            wanted.add(mc.player.getUUID());
        }

        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            UUID uuid = player.getUUID();
            if (uuid != null) wanted.add(uuid);
            if (wanted.size() >= MAX_LOOKUP) break;
        }

        if (wanted.isEmpty()) {
            songs.clear();
            remotes.clear();
            asked.clear();
            return;
        }

        // Somebody who walked into view a moment ago used to wait out the whole
        // ten second cycle before anyone could see their song, which is most of
        // why this looked like it simply did not work. An unfamiliar player
        // brings the next lookup forward instead.
        boolean newcomer = false;
        for (UUID uuid : wanted) {
            if (!asked.contains(uuid)) { newcomer = true; break; }
        }

        long wait = newcomer ? FETCH_NEW_MS : FETCH_MS;
        if (now - lastFetch < wait) return;

        lastFetch = now;
        asked.clear();
        asked.addAll(wanted);

        fetching = true;
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject answer = SpaceApi.songsFor(wanted);
                if (answer == null) return;

                // Replaced wholesale rather than merged: someone who stopped
                // sharing has to disappear, and a merge would keep them.
                Map<UUID, String> fresh = new ConcurrentHashMap<>();
                Map<UUID, Remote> freshRemotes = new ConcurrentHashMap<>();
                long arrived = System.currentTimeMillis();

                for (Map.Entry<String, JsonElement> entry : answer.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        JsonObject value = entry.getValue().getAsJsonObject();

                        String line = value.has("song") ? value.get("song").getAsString() : "";
                        if (!line.isEmpty()) fresh.put(uuid, line);

                        String title = value.has("title") ? value.get("title").getAsString() : "";
                        if (title.isEmpty()) continue;

                        // The worker reports how long ago the position was
                        // taken, so a reading that sat in storage for a while
                        // is not treated as if it had just been measured
                        double position = value.has("position")
                                ? value.get("position").getAsDouble() : -1;
                        double age = value.has("age") ? value.get("age").getAsDouble() : 0;
                        boolean isPlaying = value.has("playing")
                                && value.get("playing").getAsBoolean();

                        // A record written by an older worker has no usable
                        // timestamp, and NaN would poison every sum after it
                        if (!Double.isFinite(age)) age = 0;
                        if (!Double.isFinite(position)) position = -1;

                        freshRemotes.put(uuid, new Remote(
                                value.has("artist") ? value.get("artist").getAsString() : "",
                                title,
                                position + age,
                                isPlaying,
                                arrived));

                    } catch (Throwable ignored) {
                        // Skip anything malformed rather than lose the batch
                    }
                }

                songs.clear();
                songs.putAll(fresh);
                remotes.clear();
                remotes.putAll(freshRemotes);

            } catch (Throwable t) {
                SpaceClient.LOGGER.warn("Could not fetch tracks: {}", t.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    private NowPlayingShare() {}
}
