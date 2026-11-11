package ai.rever.boss.plugin.browser.autofill

import com.teamdev.jxbrowser.engine.PasswordStore
import java.util.concurrent.TimeUnit

/**
 * Linux only: libsecret when a Secret Service answers on the session bus, otherwise BASIC
 * (plaintext-at-rest in the profile; the mirror clears its records on quit and sign-out).
 */
internal object LinuxPasswordStore {
    private const val SECRETS_NAME = "org.freedesktop.secrets"

    fun choose(probe: () -> String? = ::probeBus): PasswordStore = decide(System.getenv("DBUS_SESSION_BUS_ADDRESS"), probe)

    internal fun decide(
        busAddress: String?,
        probe: () -> String?,
    ): PasswordStore {
        if (busAddress.isNullOrBlank()) return PasswordStore.BASIC
        val names = runCatching { probe() }.getOrNull() ?: return PasswordStore.BASIC
        return if (names.contains("\"$SECRETS_NAME\"")) PasswordStore.GNOME_LIBSECRET else PasswordStore.BASIC
    }

    /** Running + activatable bus names, as dbus-send prints them; null when the probe fails. */
    private fun probeBus(): String? {
        val out = StringBuilder()
        for (method in listOf("ListNames", "ListActivatableNames")) {
            val p =
                ProcessBuilder(
                    "dbus-send",
                    "--session",
                    "--print-reply",
                    "--reply-timeout=1500",
                    "--dest=org.freedesktop.DBus",
                    "/org/freedesktop/DBus",
                    "org.freedesktop.DBus.$method",
                ).redirectErrorStream(true).start()
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return null
            }
            if (p.exitValue() != 0) return null
            out.append(p.inputStream.bufferedReader().readText()).append('\n')
        }
        return out.toString()
    }
}
