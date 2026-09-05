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
 * [identityFor] is therefore the only path from "a caller presented X" to "X is who they are" —
 * nothing in this class derives an identity from a request body, and nothing outside it can mint one.
 *
 * One instance is shared by whatever mints credentials (a [ai.rever.boss.process.ProcessSpawner]) and
 * whatever verifies them (a [ProcessIdentityInterceptor] on the kernel's IPC server), so both sides of
 * a spawn agree on the same table. Thread-safe: a token is issued from the spawning thread and looked
 * up from gRPC's own threads.
 */
class ProcessTokenRegistry {
    private val processIdByToken = ConcurrentHashMap<String, String>()
    private val tokenByProcessId = ConcurrentHashMap<String, String>()

    /**
     * Mint a fresh credential for [processId], replacing and invalidating whatever it held before.
     *
     * Always a new token, even for a `processId` this registry has already issued one for — that is
     * what stops a restart inheriting its predecessor's credential (#53: "process restart must not
     * accidentally inherit the previous process's credential"), since a respawn calls this again for
     * the same id and the old token stops resolving to anything the moment the new one is stored.
     */
    fun issue(processId: String): String {
        val token = newProcessToken()
        tokenByProcessId.put(processId, token)?.let { previous -> processIdByToken.remove(previous, processId) }
        processIdByToken[token] = processId
        return token
    }

    /**
     * The process identity [token] was issued for, or null when it names nothing this registry
     * currently holds — absent, blank, unknown, or a token a later [issue] or [revoke] has since
     * invalidated.
     */
    fun identityFor(token: String?): String? {
        if (token.isNullOrBlank()) return null
        return processIdByToken[token]
    }

    /** Invalidate [processId]'s current credential, if it has one. Idempotent. */
    fun revoke(processId: String) {
        tokenByProcessId.remove(processId)?.let { processIdByToken.remove(it, processId) }
    }
}
