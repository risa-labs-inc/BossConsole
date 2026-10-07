package ai.rever.boss.components.plugin

import java.security.MessageDigest
import java.util.Properties

/** String-only shared process state; retains no host/plugin objects or access contexts. */
internal object PluginUpdateProcessRegistry {
    fun owners(): Properties = System.getProperties()

    fun ownerKey(canonicalPath: String): String =
        "boss.plugins.updateLease.protocol3." +
            MessageDigest
                .getInstance("SHA-256")
                .digest(canonicalPath.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
}
