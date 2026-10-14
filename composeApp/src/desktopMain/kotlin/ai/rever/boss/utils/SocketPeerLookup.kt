package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val logger = BossLogger.forComponent("SocketPeerLookup")

/**
 * Names the processes actually holding a [SingleInstanceManager] endpoint as a
 * listener — the platform's answer to "who owns this socket", which is what a
 * descriptor's recorded pid must match before the endpoint can be trusted.
 *
 * The JDK exposes no peer-credential call (SO_PEERCRED / LOCAL_PEERPID) for
 * either transport, so this asks the OS directly where it can: `/proc` on
 * Linux, `lsof` on macOS, `netstat` on Windows. A platform that cannot answer
 * returns null and the caller falls back to the weaker checks — a pid merely
 * being alive and running our executable — rather than guessing.
 */
@Suppress("TooManyFunctions")
internal object SocketPeerLookup {
    private val osName = System.getProperty("os.name").lowercase()

    /**
     * The pids holding [descriptor]'s endpoint as a listener, or null when this
     * platform cannot say. Missing table rows and unreadable process directories
     * are inconclusive, not proof that the recorded process forged the endpoint.
     */
    fun ownerPidsOf(descriptor: InstanceDescriptor): Set<Long>? =
        when {
            osName.contains("linux") -> linuxOwnerPids(descriptor)
            osName.contains("mac") -> macOwnerPids(descriptor)
            osName.contains("win") -> windowsOwnerPids(descriptor)
            else -> null
        }

    // ---------- Linux: /proc ----------

    /**
     * Linux exposes socket ownership through `/proc`: the kernel tables name an
     * inode per bound socket, and `/proc/<pid>/fd` symlinks name the inodes a
     * process holds. Matching the two gives the pids that bound the endpoint —
     * no native call required.
     */
    private fun linuxOwnerPids(descriptor: InstanceDescriptor): Set<Long>? =
        try {
            when (descriptor.transport) {
                SingleInstanceTransport.UNIX -> unixOwnerPidsViaProc(descriptor.endpoint)
                SingleInstanceTransport.TCP -> tcpOwnerPidsViaProc(descriptor.endpoint)
            }
        } catch (e: IOException) {
            // An unreadable table is "cannot say", not "nobody owns it".
            logger.debug(LogCategory.SYSTEM, "Socket-owner lookup failed to read /proc: ${e.message}")
            null
        }

    private fun unixOwnerPidsViaProc(path: String): Set<Long>? {
        val table = File("/proc/net/unix")
        if (!table.isFile) return null
        return unixSocketInode(table, path)?.let { pidsHoldingSocketInode(it) }
    }

    private fun tcpOwnerPidsViaProc(endpoint: String): Set<Long>? {
        val port = endpoint.toIntOrNull()
        val tables = listOf(File("/proc/net/tcp"), File("/proc/net/tcp6")).filter { it.isFile }
        return when {
            port == null -> {
                null
            }

            tables.isEmpty() -> {
                null
            }

            else -> {
                tables
                    .firstNotNullOfOrNull { tcpListenerInode(it, port) }
                    ?.let { pidsHoldingSocketInode(it) }
            }
        }
    }

    /**
     * The inode of the unix-domain socket bound to [path], or null when no row
     * names it. Rows are `Num RefCount Protocol Flags Type St Inode Path`.
     */
    private fun unixSocketInode(
        table: File,
        path: String,
    ): Long? =
        table.useLines { lines ->
            lines
                .map { it.trim().split(Regex("\\s+"), limit = 8) }
                .firstOrNull { it.size >= 8 && it[7] == path }
                ?.get(6)
                ?.toLongOrNull()
        }

    /** The inode of the socket LISTENing on [port] within one `/proc/net/tcp*` table. */
    private fun tcpListenerInode(
        table: File,
        port: Int,
    ): Long? =
        table.useLines { lines ->
            lines
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull {
                    it.size > 9 && it[3] == "0A" && it[1].substringAfterLast(':').toIntOrNull(16) == port
                }?.get(9)
                ?.toLongOrNull()
        }

    /** Every pid whose fd table holds `socket:[inode]` — same-uid processes only. */
    private fun pidsHoldingSocketInode(inode: Long): Set<Long>? {
        val target = "socket:[$inode]"
        val pidDirs =
            File("/proc").listFiles { file -> file.isDirectory && file.name.all { it.isDigit() } }
                ?: return null
        var unreadable = false
        val owners =
            pidDirs
                .mapNotNull { pidDir ->
                    val fds = File(pidDir, "fd").listFiles()
                    if (fds == null) unreadable = true
                    val holdsInode =
                        fds?.any { fd ->
                            runCatching { Files.readSymbolicLink(fd.toPath()).toString() }.getOrNull() == target
                        }
                    if (holdsInode == true) pidDir.name.toLongOrNull() else null
                }.toSet()
        return owners.takeIf { it.isNotEmpty() || !unreadable }
    }

    // ---------- macOS: lsof ----------

    private fun macOwnerPids(descriptor: InstanceDescriptor): Set<Long>? =
        when (descriptor.transport) {
            SingleInstanceTransport.UNIX -> {
                unixSocketOwnerPidsViaLsof(descriptor.endpoint)
            }

            SingleInstanceTransport.TCP -> {
                val port = descriptor.endpoint.toIntOrNull() ?: return emptySet()
                tcpListenerPidsViaLsof(port)
            }
        }

    /**
     * `lsof -Fpn -U` emits machine-readable records (`p<pid>`, `n<name>`) per
     * unix socket, so a socket path survives spaces intact.
     */
    private fun unixSocketOwnerPidsViaLsof(path: String): Set<Long>? {
        val lines = runForLines("lsof", "-nP", "-U", "-Fpn") ?: return null
        val pids = mutableSetOf<Long>()
        var currentPid: Long? = null
        for (line in lines) {
            when {
                line.startsWith("p") -> currentPid = line.drop(1).toLongOrNull()
                line.startsWith("n") && line.drop(1) == path -> currentPid?.let { pids.add(it) }
            }
        }
        return pids
    }

    /** `-t` answers with the listener pids alone, one per line. */
    private fun tcpListenerPidsViaLsof(port: Int): Set<Long>? =
        runForLines("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN", "-t")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toSet()

    // ---------- Windows: netstat ----------

    /**
     * `netstat -ano` carries the owning pid as the last column for TCP
     * LISTENING rows. There is no unix-socket equivalent worth reaching for —
     * Windows never publishes one — so that transport reports undeterminable.
     */
    private fun windowsOwnerPids(descriptor: InstanceDescriptor): Set<Long>? =
        when (descriptor.transport) {
            SingleInstanceTransport.UNIX -> {
                null
            }

            SingleInstanceTransport.TCP -> {
                val port = descriptor.endpoint.toIntOrNull() ?: return emptySet()
                tcpListenerPidsViaNetstat(port)
            }
        }

    private fun tcpListenerPidsViaNetstat(port: Int): Set<Long>? =
        runForLines("netstat", "-ano")
            ?.mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                val matches =
                    fields.size >= 5 &&
                        fields[0].startsWith("TCP") &&
                        fields[3] == "LISTENING" &&
                        fields[1].substringAfterLast(':').toIntOrNull() == port
                if (matches) fields[4].toLongOrNull() else null
            }?.toSet()

    // ---------- shared ----------

    /**
     * Runs a short-lived lookup tool, returning its stdout lines; null on any
     * failure — a missing tool, a non-zero exit, a hang, or output we cannot
     * drain — because a lookup that did not run cleanly cannot tell us who owns
     * the endpoint, and "cannot say" must not be mistaken for "nobody".
     *
     * stdout drains on a separate thread so a child writing more than a pipe
     * buffer cannot deadlock against [Process.waitFor].
     */
    private fun runForLines(vararg command: String): List<String>? =
        try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            val bytes = runCatching { output.get(5, TimeUnit.SECONDS) }.getOrNull() ?: return null
            String(bytes, Charsets.UTF_8).lines()
        } catch (e: IOException) {
            logger.debug(LogCategory.SYSTEM, "Socket-owner lookup tool could not run: ${e.message}")
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
}
