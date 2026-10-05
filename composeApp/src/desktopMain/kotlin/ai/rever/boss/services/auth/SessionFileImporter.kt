package ai.rever.boss.services.auth

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Adopts a session from a refresh-token file that a headless host drops at [path].
 *
 * The file is acted on only while there is no session, and only if it is a regular file (not a
 * symlink) owned by [currentUser] with mode exactly 0600; a filesystem without POSIX attributes is
 * refused. It is deleted before any network call, so a token is presented at most once. Its only
 * accepted content is `{"refresh_token":"..."}`. Nothing read from it is ever logged.
 */
internal class SessionFileImporter(
    private val path: Path,
    private val isSignedIn: () -> Boolean,
    private val adopt: suspend (refreshToken: String) -> Unit,
    private val currentUser: String? = System.getProperty("user.name"),
    private val pollInterval: Duration = 500.milliseconds,
    private val failureStatus: (Exception) -> Int? = { null },
) {
    enum class Outcome { ABSENT, SIGNED_IN, REJECTED, MALFORMED, IMPORTED, FAILED }

    private val logger = BossLogger.forComponent("SessionFileImporter")

    // Last rejection logged, so a file that cannot be removed is reported once, not every poll.
    private var lastRejection: String? = null

    /** Polls while [signedOut] is true; a sign-in cancels the poll and a sign-out restarts it. */
    suspend fun run(signedOut: Flow<Boolean>) {
        signedOut.distinctUntilChanged().collectLatest { out ->
            while (out) {
                if (importOnce() == Outcome.IMPORTED) return@collectLatest
                delay(pollInterval)
            }
        }
    }

    /** One check of [path]. */
    @Suppress("ReturnCount")
    suspend fun importOnce(): Outcome {
        if (isSignedIn()) return Outcome.SIGNED_IN
        val bytes =
            when (val read = withContext(Dispatchers.IO) { readAndDelete() }) {
                is Read.Absent -> return Outcome.ABSENT
                is Read.Rejected -> return Outcome.REJECTED
                is Read.Content -> read.bytes
            }
        val token = parseRefreshToken(bytes) ?: return Outcome.MALFORMED
        if (isSignedIn()) {
            logger.info(LogCategory.AUTH, "Session import skipped: a session appeared meanwhile")
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

        data object Rejected : Read

        class Content(
            val bytes: ByteArray,
        ) : Read
    }

    private class Refused(
        val reason: String,
    ) : IOException(reason)

    private fun readAndDelete(): Read =
        try {
            val attrs = Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            problemWith(attrs)?.let { throw Refused(it) }
            val bytes = readBounded()
            // Single use is the guarantee: a token that cannot be removed is never presented.
            Files.delete(path)
            lastRejection = null
            Read.Content(bytes)
        } catch (_: NoSuchFileException) {
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
            currentUser.isNullOrBlank() || attrs.owner().name != currentUser -> "not owned by the current user"
            attrs.permissions() != OWNER_RW -> "mode is not 0600"
            attrs.size() > MAX_BYTES -> "too large"
            !parentIsPrivate() -> "parent directory is writable by others"
            else -> null
        }

    // A directory others can write to lets them rename a different file in between check and read.
    private fun parentIsPrivate(): Boolean {
        val parent = path.parent ?: return false
        val dir = Files.readAttributes(parent, PosixFileAttributes::class.java)
        return dir.owner().name == currentUser && dir.permissions().none { it in OTHERS_WRITE }
    }

    private fun readBounded(): ByteArray =
        Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { ch ->
            val buf = ByteBuffer.allocate(MAX_BYTES + 1)
            while (buf.hasRemaining() && ch.read(buf) >= 0) Unit
            if (buf.position() > MAX_BYTES) throw Refused("too large")
            buf.array().copyOf(buf.position())
        }

    private fun reject(reason: String): Read {
        val removed =
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
                false
            }
        if (removed || reason != lastRejection) {
            logger.warn(
                LogCategory.AUTH,
                "Session import file refused",
                mapOf("reason" to reason, "removed" to removed),
            )
        }
        lastRejection = if (removed) null else reason
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
        private const val MAX_BYTES = 16 * 1024
        private val OTHERS_WRITE = setOf(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)
        private val OWNER_RW = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        /** The configured file, or null when [ENV] is unset, blank or not an absolute path. */
        @Suppress("ReturnCount")
        fun pathFromEnvironment(env: (String) -> String? = System::getenv): Path? {
            val raw = env(ENV)?.takeIf { it.isNotBlank() } ?: return null
            val path =
                try {
                    Paths.get(raw)
                } catch (_: java.nio.file.InvalidPathException) {
                    return null
                }
            return path.takeIf { it.isAbsolute }
        }
    }
}
