package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.ComponentLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins that this repo's `ai.rever.boss.plugin.logging` classes carry the Compose `$stable`
 * field, because boss-plugin-api ships the *same package* and — being a Compose project —
 * always emits it.
 *
 * ## Why this matters
 *
 * Both copies exist at runtime. The host's (this one) shadows the api jar's parent-first inside
 * plugin classloaders. A plugin compiled against the api that merely holds a `ComponentLogger`
 * **property** gets a Compose-generated `$stable` field on its own class whose initialiser reads
 * `ComponentLogger.$stable`. That links against the api jar at build time and is missing at load
 * time, so [BinaryCompatibilityValidator] rejects the plugin and the host disables it as binary
 * incompatible — the entire plugin, not just the offending class.
 *
 * That is not hypothetical: secret-manager 1.2.6 and 1.2.7 were both unloadable on every host
 * and the store served them for hours, with
 * `ComponentLogger.$stable: field not found` as the only clue.
 *
 * `$stable` was verified (member-by-member, via javap) to be the ONLY public difference between
 * the two copies, so emitting it here makes them interchangeable and repairs already-built
 * plugins without an api release or a plugin rebuild.
 *
 * If this test fails, the Compose compiler plugin has been dropped from
 * `plugin-platform/plugin-logging/build.gradle.kts` — restore it (with the `compileOnly` Compose
 * runtime; nothing Compose is needed at runtime) rather than deleting this test.
 */
class LoggingStableFieldTest {
    /**
     * The classes the api jar emits `$stable` on. Enums are excluded deliberately — the Compose
     * compiler treats them as inherently stable and emits no field, so `LogCategory`/`LogLevel`
     * have none in *either* copy. Verified against boss-plugin-api-1.0.71.jar class by class.
     */
    private val typesNeedingStable =
        listOf(
            ComponentLogger::class.java,
            BossLogger::class.java,
        )

    @Test
    fun `the logging types the api emits stable on have it here too`() {
        val missing =
            typesNeedingStable.filter { type ->
                type.declaredFields.none { it.name == "\$stable" }
            }

        assertTrue(
            missing.isEmpty(),
            "These logging classes have no \$stable field, so any plugin compiled against " +
                "boss-plugin-api that references it will be rejected as binary incompatible: " +
                missing.joinToString { it.name } +
                ". The Compose compiler plugin has probably been removed from plugin-logging.",
        )
    }

    @Test
    fun `enums are expected NOT to have it, matching the api`() {
        // Pinned in both directions: if a future Compose version starts emitting $stable on
        // enums, the api jar and this jar would diverge again — in the opposite direction —
        // and a plugin holding a LogCategory property would break the same way.
        listOf(LogCategory::class.java).forEach { enumType ->
            assertTrue(
                enumType.declaredFields.none { it.name == "\$stable" },
                "$enumType unexpectedly has \$stable; check whether boss-plugin-api now emits " +
                    "it too, or the two copies have diverged again",
            )
        }
    }

    @Test
    fun `the stable field is public static, which is how a plugin reads it`() {
        // A plugin's generated code does `getstatic ComponentLogger.$stable`, so a
        // non-public or instance field would still fail to link.
        val field = ComponentLogger::class.java.getDeclaredField("\$stable")

        assertTrue(Modifier.isPublic(field.modifiers), "not public: $field")
        assertTrue(Modifier.isStatic(field.modifiers), "not static: $field")
        assertTrue(field.type == Int::class.javaPrimitiveType, "not an int: ${field.type}")
    }

    @Test
    fun `logging still works without the Compose runtime on the classpath`() {
        // The Compose runtime is compileOnly, so it is absent here exactly as it is in a
        // packaged host. If anything Compose-generated needed it at runtime, this would throw
        // NoClassDefFoundError rather than log a line.
        val logger = BossLogger.forComponent("LoggingStableFieldTest")
        logger.info(LogCategory.SYSTEM, "stable-field test probe", mapOf("ok" to true))
    }
}
