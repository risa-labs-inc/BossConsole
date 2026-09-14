package ai.rever.boss.config

import java.util.Properties

/**
 * Parse the `env_vars` file into properties, using the exact line semantics
 * [ai.rever.boss.settings.MicrokernelModePreference]'s toggle reader uses (see
 * [parseEnvVarsLines]) so the two readers of the same file cannot disagree
 * (BossConsole#450's review: an `export BOSS_MODE=KERNEL` read by one but not the other).
 */
internal fun parseEnvVars(lines: List<String>): Properties {
    val properties = Properties()
    parseEnvVarsLines(lines).forEach { (key, value) -> properties.setProperty(key, value) }
    return properties
}
