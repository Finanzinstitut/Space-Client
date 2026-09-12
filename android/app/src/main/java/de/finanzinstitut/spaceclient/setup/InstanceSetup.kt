package de.finanzinstitut.spaceclient.setup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Builds a Space Client instance in a place a runtime can find it.
 *
 * This is the part that is genuinely portable. Whatever ends up starting the
 * game - a fork of somebody's launcher, or an installed one this app hands over
 * to - it needs the same things in the same shape: a Fabric profile for the
 * right Minecraft version, the client jar, and a control layout with a button
 * for the menu. None of that changes if the runtime question is answered
 * differently later, which is why it is worth building first.
 */
class InstanceSetup(private val context: Context) {

    companion object {
        const val MINECRAFT_VERSION = "26.2"

        /** Where the mod's releases are published. */
        private const val RELEASES =
            "https://api.github.com/repos/Finanzinstitut/Space-Client-Mod/releases/latest"

        private const val FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader"
    }

    /** What the screen shows while this runs. */
    fun interface Progress {
        fun step(message: String)
    }

    /**
     * The instance directory.
     *
     * Under the app's own files rather than shared storage. Shared storage
     * would let an installed runtime read it without asking, which sounds
     * convenient until you remember that everything else on the phone can read
     * it too - and the point of the jar being verified is lost if anything can
     * replace it afterwards.
     */
    fun instanceDir(): File = File(context.filesDir, "instances/spaceclient")

    fun modsDir(): File = File(instanceDir(), "mods")

    /**
     * Downloads what the instance needs.
     *
     * Returns the directory so the caller can say where it is - somebody is
     * going to have to point a runtime at it by hand until this app can start
     * the game itself.
     */
    fun prepare(progress: Progress): File {
        val dir = instanceDir()
        dir.mkdirs()
        modsDir().mkdirs()

        progress.step("Looking up the Fabric loader")
        val loader = latestFabricLoader()

        progress.step("Writing the profile")
        writeProfile(dir, loader)

        progress.step("Fetching Space Client")
        fetchMod(progress)

        progress.step("Writing the control layout")
        writeControlLayout(dir)

        progress.step("Ready")
        return dir
    }

    /**
     * The newest stable loader for this Minecraft version.
     *
     * Stable only. An unstable loader on a version the mod is already pinned to
     * would add a second moving part to a setup whose whole purpose is finding
     * out whether the first one works.
     */
    private fun latestFabricLoader(): String {
        val body = Downloads.text("$FABRIC_META/$MINECRAFT_VERSION")
        val entries = JSONArray(body)

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val loader = entry.optJSONObject("loader") ?: continue
            if (loader.optBoolean("stable", false)) {
                return loader.optString("version")
            }
        }

        if (entries.length() > 0) {
            return entries.getJSONObject(0)
                .optJSONObject("loader")
                ?.optString("version")
                ?: throw IllegalStateException("Fabric published nothing for $MINECRAFT_VERSION")
        }

        throw IllegalStateException("Fabric has no loader for $MINECRAFT_VERSION yet")
    }

    /**
     * A description of the instance, for whoever ends up reading it.
     *
     * Deliberately not any launcher's private format. Each fork stores its
     * instances differently and none of them promise not to change it, so this
     * writes what is true - which version, which loader, which mod - and leaves
     * translating it to whatever picks the instance up.
     */
    private fun writeProfile(dir: File, loaderVersion: String) {
        val profile = JSONObject().apply {
            put("name", "Space Client")
            put("minecraft", MINECRAFT_VERSION)
            put("loader", "fabric")
            put("loaderVersion", loaderVersion)
            put("javaRequired", 25)
        }
        File(dir, "space-client-instance.json").writeText(profile.toString(2))
    }

    /**
     * Downloads the mod, checking it against the release's own checksums.
     *
     * The same SHA256SUMS file the desktop updater looks for. Publishing one
     * covers both, and skipping it leaves both unverified - which is worth
     * knowing before the first Android release goes out rather than after.
     */
    private fun fetchMod(progress: Progress) {
        val release = JSONObject(Downloads.text(RELEASES))
        val assets = release.optJSONArray("assets")
            ?: throw IllegalStateException("The latest release publishes no files")

        var jarUrl: String? = null
        var jarName: String? = null
        var sumsUrl: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")

            when {
                name.equals("SHA256SUMS", true) || name.equals("SHA256SUMS.txt", true) ->
                    sumsUrl = url

                // The mod for this Minecraft version, not merely the first jar
                // in the release. A release can carry builds for more than one
                // version and picking the wrong one fails at load time with a
                // message nobody can act on.
                name.endsWith(".jar", true) && name.contains(MINECRAFT_VERSION) -> {
                    jarUrl = url
                    jarName = name
                }
            }
        }

        if (jarUrl == null || jarName == null) {
            throw IllegalStateException(
                "The latest release has no build for Minecraft $MINECRAFT_VERSION"
            )
        }

        val expected = sumsUrl?.let { sums ->
            progress.step("Checking the published checksums")
            Downloads.text(sums)
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 2) null
                    else parts[1].removePrefix("*") to parts[0].lowercase()
                }
                .firstOrNull { it.first == jarName }
                ?.second
        }

        if (expected == null) {
            // Said out loud rather than swallowed. An unverified jar is still
            // better than no client, but nobody should find out later that the
            // check they assumed was happening never was.
            progress.step("No checksum published - fetching unverified")
        }

        Downloads.toFile(jarUrl, File(modsDir(), jarName), sha256 = expected)
    }

    /**
     * A touch layout with the things a client needs and a phone has no keys for.
     *
     * The menu button is the reason this file exists. Space Client's menu opens
     * on Right Shift, which no touchscreen has - but every runtime worth using
     * can bind a drawn button to a key, so the menu works untouched as long as
     * something puts that button on the screen.
     */
    private fun writeControlLayout(dir: File) {
        val buttons = JSONArray()

        fun button(name: String, key: String, x: Float, y: Float, size: Int = 60) {
            buttons.put(JSONObject().apply {
                put("name", name)
                put("keycode", key)
                put("xPercent", x)
                put("yPercent", y)
                put("width", size)
                put("height", size)
            })
        }

        // Top right, away from the movement hand and out of the way of the
        // hotbar - the menu is opened between fights, not during one
        button("Menu", "KEY_RIGHT_SHIFT", 0.90f, 0.06f, 70)

        button("Perspective", "KEY_F5", 0.90f, 0.20f)
        button("Inventory", "KEY_E", 0.90f, 0.34f)
        button("Drop", "KEY_Q", 0.78f, 0.34f)
        button("Sneak", "KEY_LEFT_SHIFT", 0.06f, 0.80f, 70)
        button("Jump", "KEY_SPACE", 0.84f, 0.66f, 80)

        val layout = JSONObject().apply {
            put("name", "Space Client")
            put("version", 1)
            put("buttons", buttons)
        }

        File(dir, "space-client-controls.json").writeText(layout.toString(2))
    }
}
