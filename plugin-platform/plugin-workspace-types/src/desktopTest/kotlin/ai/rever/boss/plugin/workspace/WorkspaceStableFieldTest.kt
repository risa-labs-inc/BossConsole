package ai.rever.boss.plugin.workspace

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins that these types carry the Compose `$stable` field, because `boss-plugin-api` ships the
 * same FQCNs and — being a Compose project — always emits it.
 *
 * Both copies exist at runtime and the host's (this one) shadows the api jar's parent-first inside
 * plugin classloaders. A plugin compiled against the api that holds one of these as a **property**
 * emits `getstatic <Type>.$stable`, which links at build time and is missing at load time;
 * `BinaryCompatibilityValidator` then rejects the *entire* plugin as binary incompatible.
 *
 * This module was found broken by diffing every api package the host also bundles: 15 classes
 * across three modules had the field in the api and not here. `ComponentLogger` is the one that
 * actually bit us — it made secret-manager 1.2.6 and 1.2.7 unloadable on every host — but these
 * types are more exposed, since plugins hold workspace/tab/panel config routinely.
 *
 * If this fails, the Compose compiler plugin has been dropped from this module's
 * `build.gradle.kts`. Restore it rather than deleting the test. See BossConsole#81 for the
 * durable guard (diffing public members against the api jar `plugin-api-core` already downloads).
 */
class WorkspaceStableFieldTest {
    @Test
    fun `public workspace types expose the Compose stable field`() {
        val missing =
            listOf(
                LayoutWorkspace::class.java,
                TabConfig::class.java,
                PanelConfig::class.java,
                SplitConfig::class.java,
                BreadcrumbConfig::class.java,
                WorkspaceSerializer::class.java,
            ).filter { type -> type.declaredFields.none { it.name == "\$stable" } }

        assertTrue(
            missing.isEmpty(),
            "No \$stable field on: " + missing.joinToString { it.name } +
                ". Any plugin holding one of these as a property will be rejected as binary " +
                "incompatible. The Compose compiler plugin was probably removed from this module.",
        )
    }
}
