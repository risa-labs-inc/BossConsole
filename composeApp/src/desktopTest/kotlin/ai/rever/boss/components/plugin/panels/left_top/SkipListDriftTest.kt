package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.plugin.ipc.ProviderScanFilter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The provider-contract skip-list exists in two modules that cannot share a
 * constant: composeApp's in-process scanner (scannerSkippedDirectoryNames)
 * and plugin-api-ipc's ProviderScanFilter. Their "keep in sync" comments are
 * enforced here so drift fails a build instead of shipping divergent
 * provider semantics across transports.
 */
class SkipListDriftTest {
    @Test
    fun `in-process and IPC skip-lists are identical`() {
        assertEquals(scannerSkippedDirectoryNames, ProviderScanFilter.skippedDirectoryNames)
    }

    @Test
    fun `in-process and IPC name-visibility rules agree`() {
        val cases = listOf(".hidden", "build", "node_modules", "src", ".git", "readme.md")
        for (name in cases) {
            for (showHidden in listOf(true, false)) {
                assertEquals(
                    isVisibleScanEntry(name, showHidden),
                    ProviderScanFilter.isVisibleLocalEntry(name, showHidden),
                    "Visibility rules diverge for '$name' (showHidden=$showHidden)",
                )
            }
        }
    }
}
