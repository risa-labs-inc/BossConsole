package ai.rever.boss.ipc.auth

import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Privileges assigned by host code, never by a registration request or child manifest. */
enum class ProcessAuthority { PROCESS, SUPERVISOR, HOST }

/** A replacement process has a new instance identity even when its display identifier is reused. */
class ProcessIdentity internal constructor(
    val processId: String,
    val instanceId: String,
    val authority: ProcessAuthority,
    val expectedAddress: String?,
)

/** Host-owned credentials, with per-incarnation revocation for existing calls as well as new calls. */
class ProcessTokenRegistry {
    private val credentials = mutableMapOf<String, IssuedCredential>()
    private val tokenByProcessId = mutableMapOf<String, String>()

    @JvmOverloads
    fun issue(
        processId: String,
        authority: ProcessAuthority = ProcessAuthority.PROCESS,
        expectedAddress: String? = null,
    ): String {
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { HexFormat.of().formatHex(it) }
        val identity = ProcessIdentity(processId, UUID.randomUUID().toString(), authority, expectedAddress)
        val previous =
            synchronized(this) {
                val old = tokenByProcessId.put(processId, token)?.let { credentials.remove(it) }
                credentials[token] = IssuedCredential(identity)
                old
            }
        previous?.revoke()
        return token
    }

    fun identityFor(token: String?): String? = principalFor(token)?.processId

    @Synchronized
    fun principalFor(token: String?): ProcessIdentity? = credentials[token]?.identity

    /** A late exit callback must not revoke a replacement process's credential. */
    fun revokeIfCurrent(
        processId: String,
        token: String?,
    ) {
        val removed =
            synchronized(this) {
                if (token != null && tokenByProcessId[processId] == token) {
                    tokenByProcessId.remove(processId)
                    credentials.remove(token)
                } else {
                    null
                }
            }
        removed?.revoke()
    }

    fun revoke(processId: String) {
        val removed = synchronized(this) { tokenByProcessId.remove(processId)?.let { credentials.remove(it) } }
        removed?.revoke()
    }

    /** Subscription and revocation cannot lose each other, including a revoke during call admission. */
    internal fun onRevoked(
        token: String,
        action: () -> Unit,
    ): AutoCloseable {
        val credential = synchronized(this) { credentials[token] }
        if (credential == null) {
            action()
            return AutoCloseable { }
        }
        return credential.onRevoked(action)
    }

    companion object {
        /** A child receives only its own host-controller credential, never the kernel's registry. */
        fun forHostController(token: String): ProcessTokenRegistry {
            require(token.length == 64 && token.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid host controller credential"
            }
            return ProcessTokenRegistry().apply {
                val identity = ProcessIdentity("host", UUID.randomUUID().toString(), ProcessAuthority.HOST, null)
                credentials[token] = IssuedCredential(identity)
                tokenByProcessId[identity.processId] = token
            }
        }
    }
}

private class IssuedCredential(
    val identity: ProcessIdentity,
) {
    private val revoked = AtomicBoolean()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun onRevoked(action: () -> Unit): AutoCloseable {
        val called = AtomicBoolean()
        val once = { if (called.compareAndSet(false, true)) action() }
        listeners.add(once)
        if (revoked.get()) {
            listeners.remove(once)
            once()
        }
        return AutoCloseable { listeners.remove(once) }
    }

    fun revoke() {
        if (revoked.compareAndSet(false, true)) {
            listeners.forEach { runCatching { it() } }
            listeners.clear()
        }
    }
}
