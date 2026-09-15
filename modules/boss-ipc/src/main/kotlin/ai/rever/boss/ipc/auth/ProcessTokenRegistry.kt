package ai.rever.boss.ipc.auth

import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/** 32 random bytes, hex encoded — the same shape as `SingleInstanceManager`'s channel token. */
private const val TOKEN_BYTES = 32

private val secureRandom = SecureRandom()

/** Mints a fresh per-process credential. Never log the result. */
private fun newProcessToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    secureRandom.nextBytes(bytes)
    return HexFormat.of().formatHex(bytes)
}

/**
 * Issues and resolves per-process IPC credentials.
 *
 * A caller's identity must be established by the kernel independently of any `process_id` an IPC
 * request happens to carry (BossConsole#53): a request field is whatever the caller chose to write,
 * while a token here is minted by the kernel itself and handed to exactly one process at spawn time.
 * [identityFor] and [credentialFor] are therefore the only paths from "a caller presented X" to
 * "X is who they are" — nothing in this class derives an identity from a request body, and nothing
 * outside it can mint one.
 *
 * One instance is shared by whatever mints credentials (a [ai.rever.boss.process.ProcessSpawner]) and
 * whatever verifies them (a [ProcessIdentityInterceptor] on the kernel's IPC server), so both sides of
 * a spawn agree on the same table. Thread-safe: a token is issued from the spawning thread and looked
 * up from gRPC's own threads.
 *
 * This authenticates possession of a child credential, not an OS sandbox boundary. The environment
 * can be inherited by descendants or inspected by same-user processes. Plugin runtimes should strip
 * BOSS_PROCESS_TOKEN before launching unrelated subprocesses and must never print their environment.
 */
class ProcessTokenRegistry {
    private val credentialByToken = ConcurrentHashMap<String, ProcessCredential>()
    private val tokenByProcessId = ConcurrentHashMap<String, String>()
    private var nextCredentialGeneration = 0L

    /**
     * Mint a fresh credential for [processId], replacing and invalidating whatever it held before.
     *
     * Always a new token, even for a `processId` this registry has already issued one for — that is
     * what stops a restart inheriting its predecessor's credential (#53: "process restart must not
     * accidentally inherit the previous process's credential"), since a respawn calls this again for
     * the same id and the old token stops resolving to anything the moment the new one is stored.
     */
    @Synchronized
    fun issue(processId: String): String {
        val token = newProcessToken()
        val credential = ProcessCredential(processId, ++nextCredentialGeneration)
        tokenByProcessId.put(processId, token)?.let { previous -> credentialByToken.remove(previous) }
        credentialByToken[token] = credential
        return token
    }

    /**
     * The process identity [token] was issued for, or null when it names nothing this registry
     * currently holds — absent, blank, unknown, or a token a later [issue] or [revoke] has since
     * invalidated.
     */
    @Synchronized
    fun identityFor(token: String?): String? = credentialForLocked(token)?.processId

    /**
     * Resolve the currently-issued credential represented by [token]. The generation is intentionally
     * opaque to callers: it lets the kernel distinguish a legitimate respawn from a second registration
     * on the same credential without retaining the secret token in process state or logs.
     */
    @Synchronized
    fun credentialFor(token: String?): ProcessCredential? = credentialForLocked(token)

    private fun credentialForLocked(token: String?): ProcessCredential? {
        if (token.isNullOrBlank()) return null
        return credentialByToken[token]
    }

    /** A late exit callback must not revoke a replacement process's credential. */
    @Synchronized
    fun revokeIfCurrent(
        processId: String,
        token: String?,
    ) {
        if (token != null && tokenByProcessId[processId] == token) revoke(processId)
    }

    /** Invalidate [processId]'s current credential, if it has one. Idempotent. */
    @Synchronized
    fun revoke(processId: String) {
        tokenByProcessId.remove(processId)?.let { credentialByToken.remove(it) }
    }
}
