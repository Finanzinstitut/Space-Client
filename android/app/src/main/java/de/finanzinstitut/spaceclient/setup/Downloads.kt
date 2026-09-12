package de.finanzinstitut.spaceclient.setup

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetching files, with the checks the desktop launcher had to learn.
 *
 * Same rule here and for the same reason: a file is verified before it is put
 * where something might run it, not after. On a phone that matters more rather
 * than less, because the storage these land in is shared with every other app
 * that asked for it.
 */
object Downloads {

    /** How long to wait before deciding a server will not answer. */
    private const val TIMEOUT_MS = 30_000

    fun text(url: String): String {
        val connection = open(url)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun bytes(url: String): ByteArray {
        val connection = open(url)
        return try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads to a file, checking the hash before anything lands.
     *
     * @param sha256 the expected hash, or null when the source publishes none.
     * @param sha1 the same for sources that still publish SHA-1, which is what
     *   Mojang and Modrinth hand out. Too weak to prove who made a file, strong
     *   enough to catch one that was truncated or swapped, which is what it is
     *   used for here.
     */
    fun toFile(url: String, target: File, sha256: String? = null, sha1: String? = null) {
        val data = bytes(url)

        sha256?.let { expected ->
            val actual = hash(data, "SHA-256")
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IllegalStateException("${target.name} does not match its published SHA-256")
            }
        }

        sha1?.let { expected ->
            val actual = hash(data, "SHA-1")
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IllegalStateException("${target.name} does not match its published SHA-1")
            }
        }

        target.parentFile?.mkdirs()

        // Written beside the target and moved into place, so a download that
        // dies halfway leaves nothing that looks like a finished file
        val partial = File(target.parentFile, target.name + ".part")
        partial.writeBytes(data)
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            throw IllegalStateException("Could not put ${target.name} in place")
        }
    }

    fun hash(data: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm)
            .digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "SpaceClient-Android")

        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("$url answered $code")
        }
        return connection
    }
}
