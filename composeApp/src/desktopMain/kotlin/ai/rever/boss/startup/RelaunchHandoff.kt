package ai.rever.boss.startup

import ai.rever.boss.cli.CLICommand
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.DeepLinkOrigin
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What a process that relaunches itself after the first-run engine download hands to the next one.
 *
 * Restarting loses everything held in memory, and before the main window exists that includes the
 * requests BOSS was opened with: a `boss://` link or a file arrives before the download, waits in
 * `CLICommandHandler`'s initialization queue, and `open <bundle>` brings the next process up with
 * no arguments. So the queue is written here and replayed by the next launch, together with
 * whether that launch should force the engine pre-warm the in-process path would have done.
 *
 * **Every replayed request is [DeepLinkOrigin.EXTERNAL]**, whatever it was before. The file lives
 * in the user's private `run` directory, but a file on disk is not the operator's own `argv`, so a
 * replayed `boss://terminal` gets the confirmation an external link gets rather than running
 * unattended. Files and folders carry no origin and replay as they came.
 *
 * Written and consumed only while the single-instance lock is held. Consumed once (deleted on
 * read), and ignored when older than [MAX_AGE_MILLIS] so a relaunch that never came back cannot
 * replay requests into some unrelated later launch.
 */
internal object RelaunchHandoff {
    private val logger by lazy { BossLogger.forComponent("RelaunchHandoff") }

    /** Long enough for a slow cold start, short enough that nobody expects the request any more. */
    internal const val MAX_AGE_MILLIS = 10 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    private val defaultFile: File
        get() = BossDirectories.resolve("run/relaunch-handoff.json")

    @Serializable
    internal data class Payload(
        val writtenAtMillis: Long,
        val forcePrewarm: Boolean,
        val requests: List<Request> = emptyList(),
    )

    /** One queued request. [kind] names the [CLICommand] subtype; [value] its path or URL. */
    @Serializable
    internal data class Request(
        val kind: String,
        val value: String? = null,
    )

    internal fun toRequest(command: CLICommand): Request =
        when (command) {
            is CLICommand.OpenUrl -> Request("url", command.url)
            is CLICommand.LoadWorkspace -> Request("workspace", command.configPath)
            is CLICommand.OpenFile -> Request("file", command.filePath)
            is CLICommand.OpenFolder -> Request("folder", command.folderPath)
            is CLICommand.OpenTerminal -> Request("terminal", command.command)
        }

    /** The command to replay, always [DeepLinkOrigin.EXTERNAL] where it has an origin; null if unknown. */
    internal fun toCommand(request: Request): CLICommand? {
        val value = request.value
        return when (request.kind) {
            "url" -> value?.let { CLICommand.OpenUrl(it, DeepLinkOrigin.EXTERNAL) }
            "workspace" -> value?.let { CLICommand.LoadWorkspace(it, DeepLinkOrigin.EXTERNAL) }
            "file" -> value?.let { CLICommand.OpenFile(it) }
            "folder" -> value?.let { CLICommand.OpenFolder(it) }
            "terminal" -> CLICommand.OpenTerminal(value, DeepLinkOrigin.EXTERNAL)
            else -> null
        }
    }

    /** Writes the handoff, returning false (and logging) when it could not be written. */
    @Suppress("TooGenericExceptionCaught") // Never throws: a failed write must not block the relaunch.
    fun write(
        commands: List<CLICommand>,
        forcePrewarm: Boolean,
        file: File = defaultFile,
        now: Long = System.currentTimeMillis(),
    ): Boolean =
        try {
            file.parentFile?.mkdirs()
            val payload = Payload(now, forcePrewarm, commands.map(::toRequest))
            file.atomicWriteText(json.encodeToString(Payload.serializer(), payload))
            true
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not write the relaunch handoff", error = e)
            false
        }

    /** Deletes an unconsumed handoff, for a relaunch that did not happen after all. */
    fun discard(file: File = defaultFile) {
        runCatching { file.delete() }
    }

    /**
     * Reads and deletes the handoff. Null when there is none, it is unreadable, or it is stale.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // Never throws; one return per reason.
    fun consume(
        file: File = defaultFile,
        now: Long = System.currentTimeMillis(),
    ): Payload? {
        if (!file.isFile) return null
        val text =
            try {
                file.readText()
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Could not read the relaunch handoff", error = e)
                null
            } finally {
                runCatching { file.delete() }
            } ?: return null
        val payload =
            try {
                json.decodeFromString(Payload.serializer(), text)
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Ignoring an unreadable relaunch handoff", error = e)
                return null
            }
        val age = now - payload.writtenAtMillis
        if (age !in 0..MAX_AGE_MILLIS) {
            logger.info(LogCategory.SYSTEM, "Ignoring a stale relaunch handoff", mapOf("ageMs" to age))
            return null
        }
        logger.info(
            LogCategory.SYSTEM,
            "Resuming after the engine-download relaunch",
            mapOf("requests" to payload.requests.size, "forcePrewarm" to payload.forcePrewarm),
        )
        return payload
    }
}
