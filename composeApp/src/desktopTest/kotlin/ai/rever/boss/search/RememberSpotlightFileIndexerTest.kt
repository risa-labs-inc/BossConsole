package ai.rever.boss.search

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class RememberSpotlightFileIndexerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `committed project changes replace the index while recomposition and reopen reuse it`() {
        val scope = CoroutineScope(Job())
        var created = 0
        val owner =
            SpotlightFileIndexOwner(scope) {
                created++
                FileIndexer()
            }
        val path = mutableStateOf("/first")
        val visible = mutableStateOf(true)
        val revision = mutableStateOf(0)
        var renderedRevision = -1
        var current: FileIndexer? = null
        try {
            compose.setContent {
                val counter = revision.value
                val indexer = if (visible.value) rememberSpotlightFileIndexer(owner, path.value) else null
                SideEffect {
                    current = indexer
                    renderedRevision = counter
                }
            }
            compose.waitForIdle()
            val first = assertNotNull(current)
            compose.runOnIdle { revision.value++ }
            compose.runOnIdle {
                assertEquals(1, renderedRevision)
                assertSame(first, current)
                visible.value = false
            }
            compose.runOnIdle { visible.value = true }
            compose.waitForIdle()
            compose.runOnIdle {
                assertSame(first, current)
                assertEquals(1, created)
                path.value = "/second"
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertNotNull(current)
                assertNotSame(first, current)
                assertEquals(2, created)
            }
        } finally {
            scope.cancel()
        }
    }
}
