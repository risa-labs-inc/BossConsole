package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.util.concurrent.atomic.AtomicReference

/**
 * Rollout gate for plugin signature enforcement.
 *
 * Invalid signatures ALWAYS hard-fail — this gate only controls what happens
 * to store plugins with NO signature. During the rollout window unsigned
 * versions are warn-and-allow; note that until this flips, an attacker with
 * DB write access can null the signature column to land in the warn path, so
 * the control's full value materializes only with enforcement on (tracked in
 * BossConsole#872).
 *
 * Gated on configuration rather than a source edit so the flip (and a fast
 * rollback, e.g. if the backfill missed rows) doesn't require cutting a host
 * release: set the system property or env var below. The compiled-in default
 * flips to true in the enforcement release.
 */
object PluginSignatureEnforcement {
    const val PROPERTY = "boss.plugin.signature.enforce"
    const val ENV_VAR = "BOSS_PLUGIN_SIGNATURE_ENFORCE"

    private const val DEFAULT = false // flips to true per BossConsole#872

    private val logger = BossLogger.forComponent("PluginSignatureEnforcement")

    private val warnedValue = AtomicReference<String?>(null)

    /**
     * Whether store plugins without a signature are rejected instead of
     * warned. Read on every check so a property set at runtime (or by tests)
     * takes effect immediately.
     *
     * Values are parsed case-insensitively and accept the common boolean
     * spellings (true/false, 1/0, yes/no, on/off). Anything else falls back
     * to the compiled default WITH a warning — an operator typo must never
     * silently leave the control in the weaker mode.
     */
    val enforceUnsigned: Boolean
        get() {
            val raw = System.getProperty(PROPERTY) ?: System.getenv(ENV_VAR) ?: return DEFAULT
            return when (raw.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> {
                    // getAndSet keeps warn-once atomic under concurrent reads
                    if (warnedValue.getAndSet(raw) != raw) {
                        logger.warn(LogCategory.SYSTEM, "Unrecognized plugin-signature enforcement flag value — flag IGNORED, falling back to default (enforcement ${if (DEFAULT) "ON" else "OFF"})", mapOf(
                            "value" to raw
                        ))
                    }
                    DEFAULT
                }
            }
        }
}
