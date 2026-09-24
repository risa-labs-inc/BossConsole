package ai.rever.boss.mcp

import ai.rever.boss.testsupport.repoRoot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins that the introspection provider is actually registered, and that its desktop
 * suppliers are actually wired.
 *
 * A convention test in the style of [McpPolicyForDeclarationDriftTest], and source-text
 * for the same reason: asserting this by booting [McpToolRegistryImpl] would construct
 * the policy engine, the approval bus and the ledger, and those reach `~/.boss` on the
 * machine running the suite - the hazard #1162 closed for `plugin-path-utils`. Reading
 * the two call sites costs nothing and cannot touch the operator's files.
 *
 * The failure this guards is silent and specific: the provider object can compile,
 * expose its tools and pass every unit test in
 * [IntrospectionMcpToolProviderTest] while never being registered, or while being
 * registered with no suppliers behind it - in which case every call answers
 * `available: false` on a host that is perfectly healthy, and the tools look broken
 * rather than absent. Raised as the desktopMain-seam gap in review on #1364.
 */
class IntrospectionRegistrationDriftTest {
    @Test
    fun `the provider is registered with the other host provider`() {
        val source =
            repoRoot()
                .resolve("composeApp/src/commonMain/kotlin/ai/rever/boss/mcp/McpToolRegistryImpl.kt")
                .readText()

        assertTrue(
            "registerProvider(IntrospectionMcpToolProvider)" in source,
            "IntrospectionMcpToolProvider is no longer registered - its tools would vanish" +
                " from the registry while every one of its own unit tests still passed",
        )
    }

    @Test
    fun `both desktop suppliers are wired at startup`() {
        val source =
            repoRoot()
                .resolve("composeApp/src/desktopMain/kotlin/ai/rever/boss/main.kt")
                .readText()

        // Without these the provider is registered but answers "not wired" forever, which
        // reads to a caller as a broken tool rather than an absent one.
        assertTrue(
            "IntrospectionMcpToolProvider.healthSupplier" in source,
            "the health supplier is no longer wired in main.kt - get_workspace_health would" +
                " answer available=false on a healthy host",
        )
        assertTrue(
            "IntrospectionMcpToolProvider.performanceSupplier" in source,
            "the performance supplier is no longer wired in main.kt - get_performance_metrics" +
                " would answer available=false on a healthy host",
        )
    }
}
