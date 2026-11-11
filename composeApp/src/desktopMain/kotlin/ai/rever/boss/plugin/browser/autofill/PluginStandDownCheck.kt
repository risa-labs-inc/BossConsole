package ai.rever.boss.plugin.browser.autofill

import java.io.File
import java.util.zip.ZipFile

/**
 * Does the installed fluck-browser defer to native autofill? Versions up to 1.2.35 still run
 * their own saved-logins dropdown whenever Secret Manager has a match, so the user sees two.
 *
 * Decided from the jar actually loaded (store or dev-linked): a deferring build references
 * [NativeAutofill.FLAG_PROP] in its own classes. The version is the fallback when the jar can't be read.
 */
internal object PluginStandDownCheck {
    const val FLUCK_BROWSER_ID = "ai.rever.boss.plugin.dynamic.fluckbrowser"
    const val FIRST_DEFERRING_VERSION = "1.2.36"
    private const val CLASS_PREFIX = "ai/rever/boss/plugin/dynamic/fluckbrowser/"

    fun honoursFlag(
        jar: File?,
        version: String?,
        flag: String = NativeAutofill.FLAG_PROP,
    ): Boolean = jar?.let { jarReferences(it, flag) } ?: versionAtLeast(version, FIRST_DEFERRING_VERSION)

    internal fun jarReferences(
        jar: File,
        text: String,
    ): Boolean? =
        runCatching {
            val needle = text.toByteArray(Charsets.UTF_8)
            ZipFile(jar).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { it.name.startsWith(CLASS_PREFIX) && it.name.endsWith(".class") }
                    .any { e -> zip.getInputStream(e).use { contains(it.readBytes(), needle) } }
            }
        }.getOrNull()

    private fun contains(
        hay: ByteArray,
        needle: ByteArray,
    ): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Suppress("ReturnCount")
    internal fun versionAtLeast(
        version: String?,
        minimum: String,
    ): Boolean {
        val a = version?.trim()?.split('.', '-')?.map { it.toIntOrNull() ?: 0 } ?: return false
        val b = minimum.split('.').map { it.toInt() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }
}
