package ai.rever.boss.services.supabase

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.logging.SupabaseLoggingProcessor

/**
 * Names which Supabase client a library log line came from, and routes it through BossLogger.
 *
 * WHY THIS EXISTS. supabase-kt writes its own diagnostics with a fixed `(Supabase-Realtime)` tag
 * and nothing identifying the client. This app runs THREE Supabase clients against the same
 * project - the main one in SupabaseConfig, the app-update watcher and the plugin-store watcher -
 * and an installed plugin may run more (the Toolbox does). So a line like
 *
 *     Warn: (Supabase-Realtime) Heartbeat timeout. Trying to reconnect in 7s
 *
 * says a socket is flapping without saying whose. With several clients the lines interleave, so
 * even the timing is unattributable: pairing a "Connected" with the next "Heartbeat timeout"
 * assumes both came from one client, and that assumption is how an investigation into exactly this
 * message spent its time on the wrong component before proving otherwise.
 *
 * It also stops these lines bypassing BossLogger. They went straight to stdout, so they carried no
 * category, were not in our format, and never reached a log file.
 *
 * [name] is the client's ROLE rather than its class: what a reader needs from a flapping socket is
 * which feature is affected.
 */
class NamedSupabaseLogging(
    private val name: String,
    private val minimum: LogLevel,
) : SupabaseLoggingProcessor {
    private val logger = BossLogger.forComponent("Supabase")

    override fun isEnabled(level: LogLevel): Boolean = level.ordinal >= minimum.ordinal

    override fun processLog(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: String,
    ) {
        if (!isEnabled(level)) return
        // The name goes in the structured fields as well as the text, so a log search can group by
        // it rather than parse it back out of the message.
        //
        // The throwable is NOT handed to the logger. BossLogger writes `error.message` and a
        // stack trace to the log file, and what arrives here is whatever supabase-kt chose to
        // log: a RestException carries the PostgREST error body, which can echo column values.
        // Other throwable types can still carry server data; keep only the type here.
        // The type is the diagnostic half worth keeping; the library's own text is in `message`.
        val fields =
            mapOf<String, Any?>(
                "client" to name,
                "tag" to tag,
                "errorType" to throwable?.let { it::class.simpleName },
            )
        val text = "[$name] $message"
        // The library is chatty at debug, so that level stays opt-in via BOSS_LOG_LEVEL
        // rather than being paid for on every run.
        when (level) {
            LogLevel.ERROR -> logger.error(LogCategory.NETWORK, text, fields)
            LogLevel.WARNING -> logger.warn(LogCategory.NETWORK, text, fields)
            LogLevel.INFO -> logger.info(LogCategory.NETWORK, text, fields)
            LogLevel.DEBUG -> logger.debug(LogCategory.NETWORK, text, fields)
            LogLevel.NONE -> Unit
        }
    }
}
