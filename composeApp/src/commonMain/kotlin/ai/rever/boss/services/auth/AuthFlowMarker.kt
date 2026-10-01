package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Records that THIS process is waiting for a sign-in callback, and for which account, so the
 * callback can find its way back when several BOSS profiles are running.
 *
 * The OS has one handler for `boss://`, and it always delivers to the main profile's process,
 * whichever process started the sign-in. Each process therefore drops a marker in its own `run/`
 * directory when it starts a flow, and the main profile routes a callback by those markers (see
 * `ProfileAuthRelay`). A magic link carries no hint of who asked for it - the token is an opaque
 * hash - so the marker records the email it was sent to, hashed: that is what lets routing refuse
 * an ambiguous link and lets the receiver refuse a session for the wrong account
 * ([expectedEmailHash], checked by `AuthService.verifyEmail`).
 *
 * A marker is claimed at most once: [claim] renames it away atomically, so of two processes that
 * might act on one link, exactly one does.
 */
@Suppress("TooManyFunctions") // one small record and the questions asked of it
object AuthFlowMarker {
    private val logger = BossLogger.forComponent("AuthFlowMarker")

    private const val FILE_NAME = "auth-pending"

    /** What kind of sign-in a marker waits for, and how long it stays waiting. */
    enum class Kind(
        val maxAgeMs: Long,
    ) {
        /** A magic link (`boss://auth/verify`). Valid as long as the link: `otp_expiry` is an hour. */
        MAGIC_LINK(60 * 60 * 1000L),

        /** A Google / Apple sign-in (`boss://auth/callback`), whose own time limit is 15 minutes. */
        OAUTH(15 * 60 * 1000L),
    }

    @Serializable
    data class Flow(
        val kind: Kind,
        val startedAtMs: Long,
        /** SHA-256 of the normalised email the link was sent to; null for OAuth. */
        val emailHash: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The flow this process claimed from its marker (a profile accepting a relayed link), held
     * until the exchange it was claimed for has been checked against it.
     */
    @Volatile
    private var claimed: Flow? = null

    fun fileFor(root: File): File = File(File(root, "run"), FILE_NAME)

    fun hashEmail(email: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(email.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** This process started a sign-in that will complete through a `boss://auth` link. */
    fun mark(
        kind: Kind,
        email: String? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        val flow = Flow(kind, now, email?.let(::hashEmail))
        val file = fileFor(BossDirectories.rootDir)
        // Atomic, because another process reads it; any failure only means no relay, never a crash.
        runCatching {
            file.parentFile.mkdirs()
            file.atomicWriteText(json.encodeToString(Flow.serializer(), flow))
        }.onFailure { logger.warn(LogCategory.AUTH, "Could not record the pending sign-in", error = it) }
        claimed = null
    }

    /** The sign-in completed or was abandoned; stop claiming callbacks. */
    fun clear() {
        runCatching { fileFor(BossDirectories.rootDir).delete() }
        claimed = null
    }

    /** Forgets a claimed flow whose link turned out not to match it. The pending marker is untouched. */
    fun dropClaim() {
        claimed = null
    }

    /** [root]'s pending flow of [kind], or null when it is not waiting for one (or it expired). */
    fun pending(
        root: File,
        kind: Kind,
        now: Long = System.currentTimeMillis(),
    ): Flow? = read(fileFor(root))?.takeIf { it.isLive(kind, now) }

    /**
     * Takes this process's pending flow of [kind] for a callback that just arrived, so no other
     * claim can take it too. The marker is renamed away first and read second: the rename is
     * atomic, so of two concurrent claims exactly one gets the file.
     */
    fun claim(
        kind: Kind,
        now: Long = System.currentTimeMillis(),
    ): Flow? {
        val file = fileFor(BossDirectories.rootDir)
        val taken = File(file.parentFile, "$FILE_NAME.claimed-${System.nanoTime()}")
        val flow = if (file.isFile && file.renameTo(taken)) read(taken) else null
        taken.delete()
        return flow?.takeIf { it.isLive(kind, now) }?.also { claimed = it }
    }

    /**
     * The account a magic link arriving here is expected to sign in: the flow this process
     * claimed for it, else its own pending magic-link flow. Null when this process asked for
     * no link, which is when nothing can be checked.
     */
    fun expectedEmailHash(now: Long = System.currentTimeMillis()): String? =
        (claimed ?: pending(BossDirectories.rootDir, Kind.MAGIC_LINK, now))?.emailHash

    /** Whether this process has a magic-link flow a link can belong to, claimed or pending. */
    fun awaitsMagicLink(now: Long = System.currentTimeMillis()): Boolean =
        claimed?.kind == Kind.MAGIC_LINK || pending(BossDirectories.rootDir, Kind.MAGIC_LINK, now) != null

    /**
     * Whether a magic link must be refused before it is exchanged: a separate-account profile
     * spends only a link it asked for, so a stray or misrouted one is neither used up nor able to
     * sign the profile in. The main profile keeps its old behaviour for a link nobody waits for.
     */
    internal fun refuseBeforeExchange(
        isProfile: Boolean,
        awaitsLink: Boolean,
    ): Boolean = isProfile && !awaitsLink

    /** Whether a completed exchange signed in an account other than the one the link was sent to. */
    internal fun isWrongAccount(
        expectedEmailHash: String?,
        signedInEmailHash: String?,
    ): Boolean = expectedEmailHash != null && signedInEmailHash != expectedEmailHash

    private fun Flow.isLive(
        kind: Kind,
        now: Long,
    ): Boolean = this.kind == kind && now - startedAtMs in 0..kind.maxAgeMs

    private fun read(file: File): Flow? =
        if (file.isFile) {
            runCatching { json.decodeFromString(Flow.serializer(), file.readText()) }.getOrNull()
        } else {
            null
        }
}
