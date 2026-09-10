package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins how the host decides whether, where and at what level to write a log file.
 *
 * Nothing in the host has ever turned file logging on: `BossLoggerConfig.fileLoggingEnabled` is
 * applied only by `configure()`, which has no caller, and `configureFromEnvironment()` read only
 * the console level. The result was that a plugin disabled by the restart budget left an ERROR on
 * stdout and nothing on disk (#394). This resolver is what `configureFromEnvironment()` now asks.
 *
 * Testable for the same reason [LogLevelResolutionTest] is: a JVM cannot set its own environment
 * variables, so the sources come in as parameters. The blank-is-unset rule is the same one.
 *
 * Whether file logging is on by default is not decided here. The caller passes [defaultPath]: a
 * path for default-on, null for opt-in. Every other rule is identical either way.
 */
class FileLoggingResolutionTest {
    private val default = "/home/x/.boss/logs/boss.log"

    private fun resolve(
        envPath: String? = null,
        propPath: String? = null,
        envLevel: String? = null,
        propLevel: String? = null,
        defaultPath: String? = default,
    ) = BossLogger.resolveFileLogging(
        envPath = envPath,
        propPath = propPath,
        envLevel = envLevel,
        propLevel = propLevel,
        defaultPath = defaultPath,
    )

    @Test
    fun `with nothing set, the default path is used at ERROR`() {
        assertEquals(FileLogTarget(default, LogLevel.ERROR), resolve())
    }

    @Test
    fun `an explicit path wins over the default, and env outranks the system property`() {
        assertEquals("/tmp/a.log", resolve(envPath = "/tmp/a.log")?.path)
        assertEquals("/tmp/b.log", resolve(propPath = "/tmp/b.log")?.path)
        // Same precedence as the console level and every other config path in the app.
        assertEquals("/tmp/a.log", resolve(envPath = "/tmp/a.log", propPath = "/tmp/b.log")?.path)
    }

    @Test
    fun `a blank path falls through instead of shadowing the sources below it`() {
        for (blank in listOf("", "   ", "\t")) {
            assertEquals(
                "/tmp/b.log",
                resolve(envPath = blank, propPath = "/tmp/b.log")?.path,
                "blank env '$blank' must not shadow the system property",
            )
            assertEquals(
                default,
                resolve(envPath = blank, propPath = blank)?.path,
                "blank env and property '$blank' must both fall through to the default",
            )
        }
    }

    @Test
    fun `off as a path disables file logging even when a default exists`() {
        // The one way to say "no file" when the default is on. Case-insensitive because it is
        // typed by a person into a shell, and surrounding whitespace is a shell artefact.
        for (off in listOf("off", "OFF", "Off", " off ")) {
            assertNull(resolve(envPath = off), "'$off' must disable")
            assertNull(resolve(propPath = off), "'$off' must disable")
        }
        // ...and an explicit path below an `off` above it does not resurrect the file.
        assertNull(resolve(envPath = "off", propPath = "/tmp/b.log"))
    }

    @Test
    fun `with no default and nothing set, file logging is off`() {
        // Opt-in mode: the caller passes no default path, and only an explicit path turns it on.
        assertNull(resolve(defaultPath = null))
        assertEquals("/tmp/a.log", resolve(envPath = "/tmp/a.log", defaultPath = null)?.path)
    }

    @Test
    fun `the file level is env, then system property, then ERROR`() {
        assertEquals(LogLevel.WARN, resolve(envLevel = "WARN")?.minLevel)
        assertEquals(LogLevel.INFO, resolve(propLevel = "INFO")?.minLevel)
        assertEquals(LogLevel.WARN, resolve(envLevel = "WARN", propLevel = "INFO")?.minLevel)
        assertEquals(LogLevel.ERROR, resolve()?.minLevel)
    }

    @Test
    fun `a blank level falls through`() {
        for (blank in listOf("", "   ", "\t")) {
            assertEquals(LogLevel.INFO, resolve(envLevel = blank, propLevel = "INFO")?.minLevel)
            assertEquals(LogLevel.ERROR, resolve(envLevel = blank, propLevel = blank)?.minLevel)
        }
    }

    @Test
    fun `level is case-insensitive`() {
        assertEquals(LogLevel.WARN, resolve(envLevel = "warn")?.minLevel)
        assertEquals(LogLevel.DEBUG, resolve(envLevel = "Debug")?.minLevel)
    }

    @Test
    fun `an unrecognised level falls to the default, not to INFO`() {
        // LogLevel.fromString answers INFO for anything it does not know, which is fine for a
        // console and wrong for a file: a typo in BOSS_LOG_FILE_LEVEL would quietly write every
        // INFO line to disk. Unknown means "keep the default", and the default is ERROR.
        assertEquals(LogLevel.ERROR, resolve(envLevel = "VERBOSE")?.minLevel)
        // A typo in env must not hide a valid property either.
        assertEquals(LogLevel.WARN, resolve(envLevel = "VERBOSE", propLevel = "WARN")?.minLevel)
    }

    @Test
    fun `a level of OFF disables file logging`() {
        assertNull(resolve(envLevel = "OFF"))
        assertNull(resolve(envLevel = "off", propLevel = "WARN"))
    }
}
