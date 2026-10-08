package ai.rever.boss.services.auth

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Adopts a session from a refresh-token file that a headless host drops at [path].
 *
 * The file is acted on only while there is no live session, and only if it is a regular file (not
 * a symlink) owned by this process's UID with mode exactly 0600, in a directory owned by the same
 * UID that nobody else can write to. A filesystem without POSIX attributes is refused. The file must
 * also be unchanged (same inode, size and mtime) across two consecutive polls and at least one poll
 * interval old, so a write still in progress is never read. It is deleted before any network call,
 * so a token is presented at most once. A refused file is left where it is and the reason logged
 * once. Its only accepted content is `{"refresh_token":"..."}`. Nothing read from it is ever logged.
 * The read buffers are zeroed after parsing; the decoded JSON and token strings are not wipeable.
 *
 * Writer contract (also in AGENTS.md): `umask 077`, write `$path.tmp` in the same directory, then
 * `mv` it onto [path].
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class SessionFileImporter(
    private val path: Path,
    private val isSignedIn: () -> Boolean,
    private val adopt: suspend (refreshToken: String) -> Unit,
    // Numeric UIDs: a UID with no passwd entry has user.name "?" but an owner named by number.
    private val currentUid: Int? = processUid(),
    // Only consulted where the process UID is unknown (no /proc, i.e. macOS).
    private val currentUser: String? = System.getProperty("user.name"),
    private val pollInterval: Duration = 500.milliseconds,
    private val idlePollInterval: Duration = 2.seconds,
    private val fastPolls: Int = 120,
    private val failureStatus: (Exception) -> Int? = { null },
    private val now: () -> Instant = Instant::now,
) {
    enum class Outcome { ABSENT, PENDING, SIGNED_IN, REJECTED, MALFORMED, IMPORTED, FAILED }

    private val logger = BossLogger.forComponent("SessionFileImporter")

    // Last refusal logged, so a file left in place is reported once per reason, not every poll.
    @Volatile
    private var lastRejection: String? = null

    // The latest refusal repeated the previous one, so a sticky refusal does not pin the fast poll.
    @Volatile
    private var rejectionRepeated = false

    // The poll loop logs an unexpected failure once per exception type, then keeps polling.
    private var lastPollFailure: String? = null

    /** The reason the file was last refused, or null; for tests. */
    internal val lastRefusal: String? get() = lastRejection

    // What the previous poll saw; a file is read only once it has not changed for a whole poll.
    @Volatile
    private var lastSeen: Snapshot? = null

    /**
     * Polls while [signedOut] is true; a sign-in cancels the poll and a sign-out restarts it. After
     * [fastPolls] polls with no file it slows to [idlePollInterval] until a file shows up.
     */
    suspend fun run(signedOut: Flow<Boolean>) {
        signedOut.distinctUntilChanged().collectLatest { out ->
            var quiet = 0
            while (out) {
                // No return on IMPORTED: the status flip ends this loop, and if it never comes the
                // next poll sees the session and idles.
                when (pollOnce()) {
                    Outcome.ABSENT, Outcome.SIGNED_IN, null -> quiet++
                    Outcome.REJECTED -> if (rejectionRepeated) quiet++ else quiet = 0
                    else -> quiet = 0
                }
                delay(if (quiet < fastPolls) pollInterval else idlePollInterval)
            }
        }
    }

    // A throwing dependency (e.g. the Supabase client not ready) must not end the hook for good.
    private suspend fun pollOnce(): Outcome? =
        try {
            importOnce().also { lastPollFailure = null }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            val type = e::class.simpleName
            if (type != lastPollFailure) {
                logger.warn(LogCategory.AUTH, "Session import poll failed", mapOf("error" to type))
                lastPollFailure = type
            }
            null
        }

    /** One check of [path]. */
    suspend fun importOnce(): Outcome {
        if (isSignedIn()) return Outcome.SIGNED_IN
        // Once the file is deleted the token is spent, and adopt's own success flips the session
        // status that cancels this poll, so consuming and adopting must run to completion.
        return withContext(NonCancellable) { consumeAndAdopt() }
    }

    @Suppress("ReturnCount")
    private suspend fun consumeAndAdopt(): Outcome {
        val bytes =
            when (val read = withContext(Dispatchers.IO) { readAndConsume() }) {
                is Read.Absent -> return Outcome.ABSENT
                is Read.Pending -> return Outcome.PENDING
                is Read.SignedIn -> return Outcome.SIGNED_IN
                is Read.Rejected -> return Outcome.REJECTED
                is Read.Content -> read.bytes
            }
        val token =
            try {
                parseRefreshToken(bytes)
            } finally {
                bytes.fill(0)
            } ?: return Outcome.MALFORMED
        if (isSignedIn()) {
            logger.info(LogCategory.AUTH, "Session import discarded: a session appeared after the file was consumed")
            return Outcome.SIGNED_IN
        }
        return try {
            adopt(token)
            logger.info(LogCategory.AUTH, "Session imported from file")
            Outcome.IMPORTED
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Only the type and status: an auth error can echo request details.
            logger.warn(
                LogCategory.AUTH,
                "Session import failed",
                mapOf("error" to e::class.simpleName, "status" to failureStatus(e)),
            )
            Outcome.FAILED
        }
    }

    private sealed interface Read {
        data object Absent : Read

        data object Pending : Read

        data object SignedIn : Read

        data object Rejected : Read

        class Content(
            val bytes: ByteArray,
        ) : Read
    }

    private data class Snapshot(
        val key: Any?,
        val size: Long,
        val modified: FileTime,
    )

    private class Refused(
        val reason: String,
    ) : IOException(reason)

    @Suppress("ReturnCount")
    private fun readAndConsume(): Read =
        try {
            val attrs = Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            problemWith(attrs)?.let { throw Refused(it) }
            val seen = Snapshot(attrs.fileKey(), attrs.size(), attrs.lastModifiedTime())
            val age = java.time.Duration.between(attrs.lastModifiedTime().toInstant(), now())
            if (seen != lastSeen || age < pollInterval.toJavaDuration()) {
                lastSeen = seen
                return Read.Pending
            }
            val bytes = readBounded()
            if (bytes.size.toLong() != attrs.size()) {
                bytes.fill(0)
                lastSeen = null
                return Read.Pending
            }
            if (isSignedIn()) {
                bytes.fill(0)
                return Read.SignedIn
            }
            // Single use is the guarantee: a token that cannot be removed is never presented.
            try {
                Files.delete(path)
            } catch (e: NoSuchFileException) {
                bytes.fill(0)
                throw e
            } catch (e: IOException) {
                bytes.fill(0)
                throw Refused("cannot be removed: ${e::class.simpleName}")
            }
            lastRejection = null
            lastSeen = null
            Read.Content(bytes)
        } catch (_: NoSuchFileException) {
            lastRejection = null
            lastSeen = null
            Read.Absent
        } catch (e: Refused) {
            reject(e.reason)
        } catch (_: UnsupportedOperationException) {
            reject("filesystem has no POSIX attributes")
        } catch (e: IOException) {
            reject("I/O error: ${e::class.simpleName}")
        }

    private fun problemWith(attrs: PosixFileAttributes): String? =
        when {
            attrs.isSymbolicLink -> "symlink"
            !attrs.isRegularFile -> "not a regular file"
            else -> ownerProblem(path, attrs, LinkOption.NOFOLLOW_LINKS) ?: contentProblem(attrs)
        }

    private fun contentProblem(attrs: PosixFileAttributes): String? =
        when {
            attrs.permissions() != OWNER_RW -> "mode is not 0600"
            attrs.size() > MAX_BYTES -> "too large"
            else -> parentProblem()
        }

    // A directory others can write to lets them rename a different file in between check and read.
    @Suppress("ReturnCount")
    private fun parentProblem(): String? {
        val parent = path.parent ?: return "has no parent directory"
        val dir = Files.readAttributes(parent, PosixFileAttributes::class.java)
        ownerProblem(parent, dir)?.let { return "parent directory $it" }
        return if (dir.permissions().any { it in OTHERS_WRITE }) "parent directory is writable by others" else null
    }

    private fun ownerProblem(
        at: Path,
        attrs: PosixFileAttributes,
        vararg options: LinkOption,
    ): String? {
        val uid = currentUid
        if (uid != null) {
            // UIDs are not secret, and naming both makes a userns or bind-mount mismatch obvious.
            val owner = Files.getAttribute(at, "unix:uid", *options) as? Int
            return if (owner == uid) null else "is owned by uid $owner, not the process uid $uid"
        }
        val user = currentUser
        return if (!user.isNullOrBlank() && attrs.owner().name == user) null else "is not owned by the current user"
    }

    private fun readBounded(): ByteArray =
        Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { ch ->
            val buf = ByteBuffer.allocate(MAX_BYTES + 1)
            try {
                while (buf.hasRemaining() && ch.read(buf) >= 0) Unit
                if (buf.position() > MAX_BYTES) throw Refused("too large")
                buf.array().copyOf(buf.position())
            } finally {
                buf.array().fill(0)
            }
        }

    private fun reject(reason: String): Read {
        rejectionRepeated = reason == lastRejection
        if (!rejectionRepeated) {
            logger.warn(LogCategory.AUTH, "Session import file refused and left in place", mapOf("reason" to reason))
        }
        lastRejection = reason
        return Read.Rejected
    }

    @Suppress("ReturnCount")
    private fun parseRefreshToken(bytes: ByteArray): String? {
        val element =
            try {
                Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (e: SerializationException) {
                logger.warn(LogCategory.AUTH, "Session import file is not valid JSON", decodeFailure(e))
                return null
            } catch (_: CharacterCodingException) {
                logger.warn(LogCategory.AUTH, "Session import file is not UTF-8")
                return null
            }
        val field = (element as? JsonObject)?.takeIf { it.keys == setOf(FIELD) }?.get(FIELD) as? JsonPrimitive
        val token =
            field
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotBlank() }
        if (token == null) logger.warn(LogCategory.AUTH, "Session import file has an unexpected shape")
        return token
    }

    companion object {
        const val ENV = "BOSS_SESSION_IMPORT"
        private const val FIELD = "refresh_token"
        internal const val MAX_BYTES = 16 * 1024
        private val OTHERS_WRITE = setOf(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)
        private val OWNER_RW = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        /** The configured file, or null when [ENV] is unset, blank or not an absolute path. */
        @Suppress("ReturnCount")
        fun pathFromEnvironment(env: (String) -> String? = System::getenv): Path? {
            val raw = env(ENV)?.takeIf { it.isNotBlank() } ?: return null
            val path =
                try {
                    Paths.get(raw)
                } catch (_: InvalidPathException) {
                    return null
                }
            return path.takeIf { it.isAbsolute }
        }

        /** This process's UID where `/proc` exposes it (Linux), else null. */
        fun processUid(): Int? =
            try {
                Files.getAttribute(Paths.get("/proc/self"), "unix:uid") as? Int
            } catch (_: IOException) {
                null
            } catch (_: UnsupportedOperationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
