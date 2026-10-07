package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Records that THIS process is waiting for a sign-in callback, and for which account, so the
 * callback can find its way back when several BOSS profiles are running.
 *
 * The OS has one handler for `boss://`, and it always delivers to the main profile's process,
 * whichever process started the sign-in. Each process therefore drops a marker in its own `run/`
 * directory when it starts a flow, and the main profile routes a callback by those markers (see
 * `ProfileAuthRelay`). A magic link carries no hint of who asked for it - the token is an opaque
 * hash - so the marker records the email it was sent to, hashed, which [MagicLinkExchange] checks
 * the minted session against before anything is imported.
 *
 * Every flow has an [Flow.id], its generation. A flow is consumed at most once - [claim] and
 * [takeForExchange] rename the marker away atomically - and only the newest generation this process
 * issued is ever put back ([restore]) or exchanged ([isCurrent]), so an old link or an old completion
 * can never displace or spend a newer flow.
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
    }

    @Serializable
    data class Flow(
        val kind: Kind,
        val startedAtMs: Long,
        /** SHA-256 of the normalised email the link was sent to. */
        val emailHash: String? = null,
        /** This flow's generation: a new one per [mark], never reused. */
        val id: String = "",
    ) {
        fun isLive(
            kind: Kind,
            now: Long,
        ): Boolean = this.kind == kind && now - startedAtMs in 0..kind.maxAgeMs
    }

    /** A flow this process accepted a relayed callback for, bound to that callback's token. */
    private data class Claim(
        val flow: Flow,
        val tokenDigest: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Guards [newestIssued] and every write or take of this process's marker. */
    private val lock = Any()

    /** The last flow this process started, kept after it is consumed: the generation boundary. */
    private var newestIssued: Flow? = null

    private val claimed = AtomicReference<Claim?>(null)

    fun fileFor(root: File): File = File(File(root, "run"), FILE_NAME)

    fun hashEmail(email: String): String = sha256(email.trim().lowercase())

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** This process started a sign-in that will complete through a `boss://auth` link. */
    fun mark(
        kind: Kind,
        email: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Flow =
        synchronized(lock) {
            // An accepted claim is left alone: it stays bound to its own token and generation, so
            // its link can never consume this new flow ([isCurrent] decides whether it may sign in).
            Flow(kind, now, email?.let(::hashEmail), UUID.randomUUID().toString()).also {
                newestIssued = it
                write(it)
            }
        }

    /**
     * Whether [flow] is still the newest sign-in this process asked for. A flow superseded by a
     * later [mark] is never exchanged, so an old link cannot sign in an account the user moved on
     * from. True when this process has issued none (a marker left by an earlier run).
     */
    fun isCurrent(flow: Flow): Boolean = synchronized(lock) { newestIssued.let { it == null || it.id == flow.id } }

    /**
     * Whether this process asked for a magic link that may still arrive. While it has, a link with
     * no flow to spend (a duplicate delivery, or one already taken by another caller) is refused
     * rather than given the unchecked legacy exchange.
     */
    fun hasIssuedLive(now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) { newestIssued?.let { now - it.startedAtMs in 0..it.kind.maxAgeMs } ?: false }

    /** [root]'s pending flow of [kind], or null when it is not waiting for one (or it expired). */
    fun pending(
        root: File,
        kind: Kind,
        now: Long = System.currentTimeMillis(),
    ): Flow? = read(fileFor(root))?.takeIf { it.isLive(kind, now) }

    /**
     * Accepts a relayed callback carrying [token]: takes this process's pending flow of [kind] so
     * no other callback can, and binds it to that token, so only an exchange of the SAME token can
     * redeem it ([takeForExchange]).
     */
    fun claim(
        kind: Kind,
        token: String,
        now: Long = System.currentTimeMillis(),
    ): Flow? = takeLive(kind, now)?.also { claimed.set(Claim(it, sha256(token))) }

    internal fun resetForTest() {
        synchronized(lock) { newestIssued = null }
        claimed.set(null)
    }

    /**
     * The flow an exchange of [token] may spend, consumed so it can be spent once: the flow claimed
     * for exactly this token if it is still live, else this process's own pending magic-link flow.
     * Null when this process asked for no link, or only holds a claim bound to another token.
     */
    fun takeForExchange(
        token: String,
        now: Long = System.currentTimeMillis(),
    ): Flow? {
        val claim = claimed.get()
        return if (claim != null && claim.tokenDigest == sha256(token)) {
            claim.flow.takeIf { claimed.compareAndSet(claim, null) && it.isLive(Kind.MAGIC_LINK, now) }
        } else {
            takeLive(Kind.MAGIC_LINK, now)
        }
    }

    /**
     * Puts [flow] back after an exchange that did not sign it in, so the link that does belong to
     * it can still arrive. Generation-safe: only the newest flow this process issued goes back, even
     * when that newer flow has itself been consumed, and the check and the write hold one lock.
     */
    fun restore(
        flow: Flow,
        now: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            val newest = newestIssued
            val superseded = newest != null && newest.id != flow.id
            if (!superseded && !fileFor(BossDirectories.rootDir).exists() && flow.isLive(flow.kind, now)) write(flow)
        }
    }

    /**
     * Takes this process's marker only if it holds a live [kind] flow: a callback of one kind
     * never consumes a flow of another. Whatever was taken that turns out not to match goes back.
     */
    @Suppress("ReturnCount") // nothing to take, nothing taken, or taken and matching
    private fun takeLive(
        kind: Kind,
        now: Long,
    ): Flow? =
        synchronized(lock) {
            val expected = pending(BossDirectories.rootDir, kind, now) ?: return null
            val taken = takeMarker() ?: return null
            if (taken.id == expected.id && taken.isLive(kind, now)) return taken
            restore(taken, now)
            null
        }

    /** Renames the marker away (atomic: exactly one taker) and returns what it held. */
    private fun takeMarker(): Flow? {
        val file = fileFor(BossDirectories.rootDir)
        val taken = File(file.parentFile, "$FILE_NAME.taken-${UUID.randomUUID()}")
        val flow = if (file.isFile && file.renameTo(taken)) read(taken) else null
        taken.delete()
        return flow
    }

    private fun write(flow: Flow) {
        val file = fileFor(BossDirectories.rootDir)
        // Atomic, because another process reads it; any failure only means no relay, never a crash.
        runCatching {
            file.parentFile.mkdirs()
            file.atomicWriteText(json.encodeToString(Flow.serializer(), flow))
        }.onFailure { logger.warn(LogCategory.AUTH, "Could not record the pending sign-in", error = it) }
    }

    private fun read(file: File): Flow? =
        if (file.isFile) {
            runCatching { json.decodeFromString(Flow.serializer(), file.readText()) }.getOrNull()
        } else {
            null
        }
}
