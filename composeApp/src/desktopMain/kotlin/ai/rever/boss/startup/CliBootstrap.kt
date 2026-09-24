package ai.rever.boss.startup

import ai.rever.boss.cli.configureHeadlessLogging
import ai.rever.boss.cli.configureHeadlessOutputEncoding
import ai.rever.boss.cli.createBossCLI
import ai.rever.boss.llm.RisaLlmTokenCommand
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.DeepLinkOrigin
import ai.rever.boss.utils.OsOpenArguments
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.WindowsProtocolHandler
import ai.rever.boss.utils.canFrameOpenUrl
import ai.rever.boss.utils.forwardDeepLinkWithRetry
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets

/**
 * Result of early / headless CLI dispatch before GUI initialization.
 */
sealed interface CliDispatchResult {
    data class Exit(
        val code: Int,
    ) : CliDispatchResult

    data object Continue : CliDispatchResult
}

/**
 * Encapsulates early CLI handling, headless command dispatch, installer action dispatch,
 * single-instance link forwarding, and post-lock CLI argument processing.
 */
object CliBootstrap {
    private val logger by lazy { BossLogger.forComponent("CliBootstrap") }

    /**
     * Determines whether the given CLI arguments represent a headless command that must
     * execute without booting the GUI.
     */
    fun isHeadlessCli(args: Array<String>): Boolean {
        val firstNonFlag = args.firstOrNull { !it.startsWith("-") }?.lowercase()
        return firstNonFlag in setOf("status", "doctor", "mcp", "pack", "completion", "plugin", "context") ||
            (args.isNotEmpty() && args.all { it in setOf("-h", "--help") })
    }

    /**
     * Dispatches headless CLI commands before AWT, logging, plugins, or single-instance locks.
     *
     * Returns [CliDispatchResult.Exit] if the process should terminate immediately with an exit code,
     * or [CliDispatchResult.Continue] if GUI bootstrap should proceed.
     */
    @Suppress("TooGenericExceptionCaught")
    fun dispatchHeadless(args: Array<String>): CliDispatchResult =
        when {
            // Codex invokes this headless credential helper. Handle it before AWT,
            // plugins, logging, or the single-instance lock so stdout stays token-only.
            RisaLlmTokenCommand.isRequested(args) -> {
                CliDispatchResult.Exit(RisaLlmTokenCommand.execute())
            }

            // Headless CLI commands (status, doctor, mcp, completion, --help) target the running
            // instance or generate output headlessly.
            isHeadlessCli(args) -> {
                configureHeadlessOutputEncoding()
                configureHeadlessLogging()
                try {
                    createBossCLI().main(args)
                    CliDispatchResult.Exit(0)
                } catch (e: ProgramResult) {
                    CliDispatchResult.Exit(e.statusCode)
                } catch (e: Exception) {
                    System.err.println("Error: ${e.message ?: "Failed to execute CLI command"}")
                    CliDispatchResult.Exit(1)
                }
            }

            else -> {
                CliDispatchResult.Continue
            }
        }

    /**
     * Handles early protocol unregistration flag before single-instance lock or window creation.
     */
    fun handleProtocolUnregistration(args: Array<String>): CliDispatchResult {
        if (args.contains("--unregister-protocol")) {
            return CliDispatchResult.Exit(WindowsProtocolHandler.unregisterProtocolExitCode())
        }
        return CliDispatchResult.Continue
    }

    /**
     * Forwards open requests to an already running instance when single-instance lock acquisition fails.
     * Returns true if all requests were successfully forwarded or there were no URLs to forward.
     */
    fun forwardToExistingInstance(
        args: Array<String>,
        send: (String, DeepLinkOrigin) -> Boolean = { link, origin ->
            SingleInstanceManager.sendToExistingInstance(link, origin)
        },
    ): Boolean {
        val deepLinks = OsOpenArguments.requestsFrom(args)
        if (deepLinks.isEmpty()) {
            logger.info(LogCategory.SYSTEM, "No URL to send - existing BOSS window should be visible")
            return true
        }

        logger.info(
            LogCategory.SYSTEM,
            "Sending open requests to existing instance",
            mapOf("count" to deepLinks.size),
        )

        // Every link is attempted, and success means every one landed.
        // Not `all`, which would short-circuit and silently drop the rest of a
        // multi-file selection after one failure. Per-link retries follow
        // forwardDeepLinkWithRetry's policy: action links are never replayed,
        // other open requests retry like auth callbacks.
        // runBlocking is acceptable here: this runs during pre-UI
        // initialization, before the Compose application starts.
        var refusedUnframeable = false
        var sendFailed = false
        deepLinks.forEach { (link, origin) ->
            // A URL that cannot occupy one framed line is refused here, once,
            // before the retry loop: sendToExistingInstance would refuse it on
            // every attempt, so entering the loop would only spend a second of
            // dead time and log failures that read as the running instance
            // being unreachable when it was never contacted.
            if (!canFrameOpenUrl(link)) {
                refusedUnframeable = true
                logger.warn(
                    LogCategory.SYSTEM,
                    "Refusing to forward a URL that cannot be framed safely",
                    mapOf("urlBytes" to link.toByteArray(StandardCharsets.UTF_8).size),
                )
            } else if (
                !forwardDeepLinkWithRetry(
                    link = link,
                    send = { attempt ->
                        val accepted = send(link, origin)
                        // dev's per-attempt positive confirmation - the auth deep-link path is
                        // where it was most used during sign-in debugging (BossConsole#450's
                        // review: the extraction dropped it, leaving only the failure WARN).
                        logger.info(
                            LogCategory.SYSTEM,
                            "Open request forwarding completed",
                            mapOf("attempt" to attempt, "accepted" to accepted),
                        )
                        if (!accepted) {
                            logger.warn(LogCategory.SYSTEM, "Failed to send URL", mapOf("attempt" to attempt))
                        }
                        accepted
                    },
                    pause = { runBlocking { delay(500) } },
                )
            ) {
                sendFailed = true
            }
        }

        // The two failures say different things: a refusal means the URL never
        // reached the channel, while exhausted retries mean the running
        // instance did not accept it.
        if (refusedUnframeable) {
            logger.error(
                LogCategory.SYSTEM,
                "Refused to forward a URL that cannot be framed safely",
            )
        }
        if (sendFailed) {
            logger.error(
                LogCategory.SYSTEM,
                "Could not send URL to existing instance after retries",
            )
        }
        return !refusedUnframeable && !sendFailed
    }

    @Suppress("TooGenericExceptionCaught")
    fun dispatchPostLock(args: Array<String>) {
        if (args.isNotEmpty()) {
            try {
                val osOpenRequests = OsOpenArguments.deepLinksFrom(args)
                // OS links/files are processed once below, never through both dispatch paths.
                if (osOpenRequests.isEmpty()) {
                    logger.debug(
                        LogCategory.SYSTEM,
                        "Processing CLI arguments",
                        mapOf("args" to args.joinToString(" ")),
                    )
                    createBossCLI().main(args)
                }
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "CLI error", error = e)
            }
        }

        DeepLinkHandler.processCommandLineArgs(args)
    }
}
