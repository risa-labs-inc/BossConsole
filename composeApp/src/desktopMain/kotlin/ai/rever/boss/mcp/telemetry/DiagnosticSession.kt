package ai.rever.boss.mcp.telemetry

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.tools.attach.AttachNotSupportedException
import com.sun.tools.attach.VirtualMachine
import java.io.IOException
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.RuntimeMXBean
import java.lang.management.ThreadMXBean
import javax.management.MBeanServerConnection
import javax.management.remote.JMXConnector
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL

private val logger = BossLogger.forComponent("TelemetryAttach")

/**
 * The management beans of one JVM, however that JVM was reached.
 *
 * Two implementations, because a target that happens to be BOSS itself cannot be attached to: the
 * JDK refuses self attach unless `-Djdk.attach.allowAttachSelf=true` was set at launch, which is
 * not something this feature can require of an already running host. Reaching the host's own beans
 * directly is both allowed and strictly cheaper, so `telemetry_*` works on the BOSS process too
 * rather than returning an error for the one pid an operator is most likely to ask about.
 *
 * Always used through `use {}`. A JMX connector left open holds an RMI connection and a daemon
 * thread in the target, and would survive a plugin reload.
 */
internal interface DiagnosticSession : AutoCloseable {
    val threads: ThreadMXBean
    val memory: MemoryMXBean
    val garbageCollectors: List<GarbageCollectorMXBean>
    val runtime: RuntimeMXBean

    /**
     * The raw MBean connection, for beans with no typed platform interface.
     *
     * `com.sun.management:type=DiagnosticCommand` is the one that matters here: it exposes the
     * jcmd verbs, including the class histogram, as a supported and exported operation. The
     * alternative is reflecting into `sun.tools.attach.HotSpotVirtualMachine`, whose package
     * jdk.attach does not export, so under Java 17 strong encapsulation that needs an
     * `--add-opens` on the host launcher. Going through JMX needs nothing.
     */
    val mBeans: MBeanServerConnection

    /** True when the beans belong to this very process, so no attach happened. */
    val isSelf: Boolean
}

/** Why a target could not be reached, in terms an agent can act on. */
internal sealed class AttachFailure(
    val message: String,
) {
    /** The pid is alive but is not a JVM, or has no attach listener. */
    class NotAttachable(
        pid: Long,
        detail: String,
    ) : AttachFailure(
            "Process $pid is not an attachable JVM ($detail). Only JVM targets can be profiled; " +
                "use telemetry_list_targets to see which processes report attachable=true.",
        )

    /** The pid did not exist, or exited between discovery and attach. */
    class ProcessGone(
        pid: Long,
    ) : AttachFailure("Process $pid is no longer running.")

    /** The OS refused, typically a permissions or same-user problem. */
    class Denied(
        pid: Long,
        detail: String,
    ) : AttachFailure(
            "Attaching to process $pid was refused by the operating system ($detail). " +
                "BOSS can only attach to processes owned by the same user.",
        )

    /** Anything else, with the underlying reason preserved. */
    class Failed(
        pid: Long,
        detail: String,
    ) : AttachFailure("Could not attach to process $pid: $detail")
}

/** The host's own beans. [close] is a no op: nothing was opened. */
private class SelfSession : DiagnosticSession {
    override val threads: ThreadMXBean = ManagementFactory.getThreadMXBean()
    override val memory: MemoryMXBean = ManagementFactory.getMemoryMXBean()
    override val garbageCollectors: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans()
    override val runtime: RuntimeMXBean = ManagementFactory.getRuntimeMXBean()
    override val mBeans: MBeanServerConnection = ManagementFactory.getPlatformMBeanServer()
    override val isSelf: Boolean = true

    override fun close() = Unit
}

/**
 * A session over an attached JVM.
 *
 * Takes the connector rather than eight pre-built beans and derives them here, so there is one
 * place that knows how a platform bean is obtained from a connection and no way for a caller to
 * pass a bean belonging to a different target than the connector it came with.
 */
private class AttachedSession(
    private val pid: Long,
    private val vm: VirtualMachine,
    private val connector: JMXConnector,
) : DiagnosticSession {
    override val mBeans: MBeanServerConnection = connector.mBeanServerConnection

    override val threads: ThreadMXBean = proxy(ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean::class.java)

    override val memory: MemoryMXBean = proxy(ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean::class.java)

    override val runtime: RuntimeMXBean = proxy(ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean::class.java)

    override val garbageCollectors: List<GarbageCollectorMXBean> =
        ManagementFactory.getPlatformMXBeans(mBeans, GarbageCollectorMXBean::class.java)

    override val isSelf: Boolean = false

    private fun <T : Any> proxy(
        name: String,
        type: Class<T>,
    ): T = ManagementFactory.newPlatformMXBeanProxy(mBeans, name, type)

    /**
     * Closes the connector and then detaches, and does the second even if the first threw.
     *
     * Skipping the detach leaves an attach listener thread in the target for the rest of its life,
     * which is exactly the kind of leak a profiler must not introduce into the process it is
     * measuring.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        try {
            connector.close()
        } catch (t: Throwable) {
            logger.warn(LogCategory.SYSTEM, "Closing JMX connector for pid $pid failed", error = t)
        } finally {
            try {
                vm.detach()
            } catch (t: Throwable) {
                logger.warn(LogCategory.SYSTEM, "Detaching from pid $pid failed", error = t)
            }
        }
    }
}

internal object DiagnosticSessions {
    /**
     * Opens a session against [pid], or explains why not.
     *
     * Never throws: every failure an agent can provoke by naming the wrong pid is a normal result
     * here, and the handler turns it into an error payload rather than a stack trace.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun open(pid: Long): Result<DiagnosticSession> {
        if (pid == ProcessHandle.current().pid()) return Result.success(SelfSession())
        if (ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)) {
            return Result.failure(AttachException(AttachFailure.ProcessGone(pid)))
        }

        var vm: VirtualMachine? = null
        return try {
            val attached = VirtualMachine.attach(pid.toString())
            vm = attached
            val address = attached.startLocalManagementAgent()
            val connector = JMXConnectorFactory.connect(JMXServiceURL(address))
            Result.success(AttachedSession(pid = pid, vm = attached, connector = connector))
        } catch (e: AttachNotSupportedException) {
            detachQuietly(vm, pid)
            Result.failure(AttachException(AttachFailure.NotAttachable(pid, e.message ?: "no attach provider")))
        } catch (e: SecurityException) {
            detachQuietly(vm, pid)
            Result.failure(AttachException(AttachFailure.Denied(pid, e.message ?: "security manager")))
        } catch (e: IOException) {
            detachQuietly(vm, pid)
            // An IOException here is usually the target dying mid handshake, which reads better
            // as "gone" than as an I/O fault the operator is invited to debug.
            val gone = ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)
            val failure =
                if (gone) {
                    AttachFailure.ProcessGone(pid)
                } else {
                    AttachFailure.Failed(pid, e.message ?: "I/O error")
                }
            Result.failure(AttachException(failure))
        } catch (t: Throwable) {
            detachQuietly(vm, pid)
            Result.failure(AttachException(AttachFailure.Failed(pid, t.message ?: t::class.simpleName ?: "unknown")))
        }
    }

    /**
     * Detaches a half built session.
     *
     * The attach can succeed and the JMX connect then fail, which would otherwise leave the target
     * holding an attach listener for a session nobody owns.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun detachQuietly(
        vm: VirtualMachine?,
        pid: Long,
    ) {
        if (vm == null) return
        try {
            vm.detach()
        } catch (t: Throwable) {
            logger.warn(LogCategory.SYSTEM, "Detaching partially opened session for pid $pid failed", error = t)
        }
    }
}

/** Carries an [AttachFailure] through [Result]. */
internal class AttachException(
    val failure: AttachFailure,
) : Exception(failure.message)
