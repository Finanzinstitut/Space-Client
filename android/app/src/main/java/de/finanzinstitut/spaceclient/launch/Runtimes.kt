package de.finanzinstitut.spaceclient.launch

import android.content.Context
import android.content.Intent

/**
 * Finds a runtime that can actually start Minecraft.
 *
 * This app prepares an instance; it does not run Java. Running desktop
 * Minecraft on a phone needs a JVM port and a translation layer from OpenGL to
 * what mobile GPUs speak, and both are years of somebody else's work under the
 * GPL. Pretending otherwise would mean shipping something that sets everything
 * up beautifully and then cannot start the game.
 *
 * So the handoff is explicit, and so is its absence: with nothing installed the
 * setup screen names the apps that would work rather than failing with nothing
 * to act on.
 */
object Runtimes {

    /** A runtime this app knows how to hand over to. */
    data class Runtime(val packageName: String, val label: String)

    private val KNOWN = listOf(
        Runtime("net.kdt.pojavlaunch", "PojavLauncher"),
        Runtime("net.kdt.pojavlaunch.debug", "PojavLauncher (debug)"),
        Runtime("com.movtery.zalithlauncher", "Zalith Launcher"),
        Runtime("com.tungsten.fclauncher", "Fold Craft Launcher"),
    )

    fun installed(context: Context): List<Runtime> =
        KNOWN.filter { isInstalled(context, it.packageName) }

    fun known(): List<Runtime> = KNOWN

    private fun isInstalled(context: Context, packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }

    /**
     * Opens the chosen runtime.
     *
     * Only opens it. Telling another launcher which instance to start means
     * speaking its private intent contract, and those differ between forks and
     * change without notice - so the honest version takes you there with the
     * instance already prepared and lets you press play.
     */
    fun open(context: Context, packageName: String): Boolean {
        val intent: Intent = context.packageManager
            .getLaunchIntentForPackage(packageName) ?: return false

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
