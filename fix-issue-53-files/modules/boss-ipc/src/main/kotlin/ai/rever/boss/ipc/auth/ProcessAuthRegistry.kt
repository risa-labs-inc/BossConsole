package ai.rever.boss.ipc.auth

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps an unguessable, spawn-time token to the identity of the process it was issued for.
 *
 * This is the "per-connection identity" several call sites across the IPC layer are missing today - see
 * `RemoteUiSurfaceRegistry.unregister` and the bind check `PluginUIServiceBridge.streamUI` did not have.
 * Every field on a proto request is something the sender wrote, `process_id` included, so nothing built
 * from a request body can answer "who is actually calling" - a hostile or buggy process can put any
 * string it likes there. Only a value the caller could not have produced itself can answer that, which is
 * what this is for: the token never travels through anything the calling process's own code assembles.
 *
 * `ProcessSpawner` mints a token before a child process exists and hands it over the same way it already
 * hands over `BOSS_PROCESS_ID` and `BOSS_KERNEL_IPC_ADDR` - as an environment variable a sibling process
 * cannot read without ptrace/procfs access, which is the same trust boundary
 * `IpcAddressResolver.secureSocketFile` already relies on for the IPC socket itself. Nothing here is
 * stronger than that boundary, and nothing needs to be: it is the boundary BOSS's whole process-isolation
 * story already stands on.
 *
 * A process that is not one of ours - or one of ours whose launcher has not been updated to read
 * `ProcessSpawner`'s token yet - simply has no token. Every caller here treats "no token" as "no verified
 * identity" rather than a fault to reject on: this is a rollout, not a flag day, and a caller with no
 * verified identity gets exactly the behaviour it would have gotten before this existed.
 */
class ProcessAuthRegistry {
    private val tokensToProcessId = ConcurrentHashMap<String, String>()

    /** Mint and record a fresh token for [processId]. Called once, before the process it names exists. */
    fun issueToken(processId: String): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tokensToProcessId[token] = processId
        return token
    }

    /** The process [token] was issued for, or `null` if it is missing, forged, or already revoked. */
    fun processIdFor(token: String): String? = tokensToProcessId[token]

    /**
     * Forget [token]. Call this when the process it names exits, so a token cannot outlive its process.
     *
     * Not a security boundary by itself - a token is a 256-bit random value, not a small space letting one
     * go stale would meaningfully weaken - but without this the map grows for the life of a long-running
     * host across every respawn of a crash-looping plugin.
     */
    fun revoke(token: String) {
        tokensToProcessId.remove(token)
    }

    companion object {
        private const val TOKEN_BYTES = 32
        private val secureRandom = SecureRandom()

        /**
         * The host-wide registry - one per kernel, matching `BossIpcServer`'s scope on the server side and
         * `ProcessSpawner`'s on the issuing side. Tests build their own instance instead, so two suites -
         * or a suite and the production wiring - can never see each other's tokens.
         */
        val shared = ProcessAuthRegistry()
    }
}
