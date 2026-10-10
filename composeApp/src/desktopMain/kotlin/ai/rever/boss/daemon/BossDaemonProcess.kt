package ai.rever.boss.daemon

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform

/** Closing a development shell's foreground process group must not terminate background workers. */
internal object BossDaemonProcess {
    private interface Posix : Library {
        fun getpid(): Int

        fun getsid(pid: Int): Int

        fun setsid(): Int

        fun setpgid(
            pid: Int,
            group: Int,
        ): Int
    }

    fun detach() {
        if (Platform.isWindows()) return
        runCatching {
            val posix = Native.load(Platform.C_LIBRARY_NAME, Posix::class.java)
            // Login service managers may already have made this process a session leader.
            if (posix.getsid(0) != posix.getpid()) {
                check(posix.setsid() >= 0 || posix.setpgid(0, 0) == 0) { "Could not isolate daemon process group" }
            }
        }.onFailure {
            BossLogger.forComponent("BossDaemon").warn(
                LogCategory.SYSTEM,
                "Could not detach background process",
                mapOf("type" to it.javaClass.simpleName),
            )
        }
    }
}
