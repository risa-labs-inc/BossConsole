package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Paths

/**
 * Resolves the file a class was loaded from, for the callers that need to point
 * Windows at the running BOSS: the protocol handler, the browser-candidate
 * registration, the file-type associations and the JAR updater.
 *
 * **Why this is not `codeSource.location.toURI().path`.** `URI.getPath()` returns
 * only the path component, and a file on a Windows network share does not keep its
 * server there - it is the URI's AUTHORITY. `file://nas01/software/BOSS/app/BOSS.jar`
 * has a path of `/software/BOSS/app/BOSS.jar`, and `File` resolves that against the
 * current drive to `C:\software\BOSS\app\BOSS.jar`: a path that names a different
 * machine's disk, usually nothing at all. Nothing throws. The registry write
 * succeeds with an executable path that cannot launch, which is the silent
 * mis-registration [ai.rever.boss.filetypes.WindowsRegistryScript] warns about -
 * an association that exists but does not open BOSS.
 *
 * `Paths.get(uri)` is the portable form: it hands the URI to the platform's
 * filesystem provider, which puts the authority back as a UNC root and yields
 * `\nas01\software\BOSS\app\BOSS.jar`. For an ordinary drive-letter install it
 * returns exactly what the old expression did, so nothing changes for a local
 * install.
 *
 * On a platform with no UNC concept the provider rejects the authority outright,
 * and null is the honest answer: `\server\share` is not a path such a host can
 * address. Every caller already treats null as "could not determine the
 * executable" and falls back.
 *
 * `localhost` is the exception, because it is not a server: `file://localhost/x`
 * is the local `/x`. The Unix provider rejects every authority, `localhost`
 * included, where `URI.path` used to answer correctly, so the redundant host is
 * dropped first - the same normalisation [OsOpenArguments] applies to file URLs
 * handed over by a file manager.
 */
internal object CodeSourceLocation {
    private val logger = BossLogger.forComponent("CodeSourceLocation")

    /** The file [owner] was loaded from, or null when it cannot be determined. */
    fun fileFor(owner: Class<*>): File? =
        fileOf(
            runCatching { owner.protectionDomain?.codeSource?.location }.getOrNull(),
        )

    /**
     * The file [location] names, or null when it does not name one this host can
     * address.
     *
     * Separate from [fileFor] so the conversion is testable against a literal URL
     * on any OS, rather than only against wherever the test classes happen to live.
     *
     * Each null is logged at debug with the reason, never the path: an install
     * path carries the Windows account name (see [WindowsProtocolCleanup.maskUserPath]).
     */
    fun fileOf(location: URL?): File? {
        val uri = runCatching { location?.toURI() }.getOrNull()
        return when {
            uri == null -> {
                logger.debug(LogCategory.SYSTEM, "No code source URL to resolve")
                null
            }

            // A class loaded from inside a nested archive ("jar:file:/...!/") or over
            // the network has no single file behind it; only a plain file URL does.
            !uri.scheme.equals("file", ignoreCase = true) -> {
                logger.debug(LogCategory.SYSTEM, "Code source is not a file URL", mapOf("scheme" to uri.scheme))
                null
            }

            else -> {
                runCatching { Paths.get(withoutLocalhost(uri)).toFile() }
                    .onFailure {
                        logger.debug(
                            LogCategory.SYSTEM,
                            "Code source URL names no path this host can address",
                            mapOf("error" to it.javaClass.simpleName),
                        )
                    }.getOrNull()
            }
        }
    }

    /** [uri] without a `localhost` authority, which names this machine rather than a server. */
    private fun withoutLocalhost(uri: URI): URI =
        if (uri.authority.equals("localhost", ignoreCase = true)) {
            URI("file", null, uri.path, null, null)
        } else {
            uri
        }
}
