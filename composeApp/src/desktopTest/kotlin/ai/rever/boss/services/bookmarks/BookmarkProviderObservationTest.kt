package ai.rever.boss.services.bookmarks

import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.TabRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertNull
import kotlin.test.assertSame

class BookmarkProviderObservationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `initial absence observes host initialization and delayed API registration`() {
        val source = BookmarkAPIAccess.initialization as MutableStateFlow<DefaultPlugin?>
        val previous = source.value
        val host = DefaultPlugin(PanelRegistry(), TabRegistry(), windowProjectState = null)
        val replacement = DefaultPlugin(PanelRegistry(), TabRegistry(), windowProjectState = null)
        val provider =
            Proxy.newProxyInstance(
                BookmarkDataProvider::class.java.classLoader,
                arrayOf(BookmarkDataProvider::class.java),
            ) { instance, method, args ->
                when (method.name) {
                    "equals" -> instance === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(instance)
                    "toString" -> "Test bookmark provider"
                    else -> error("Unexpected provider call: ${method.name}")
                }
            } as BookmarkDataProvider
        try {
            source.value = null
            var observed: BookmarkDataProvider? = provider
            compose.setContent { observed = rememberBookmarkProvider() }
            compose.runOnIdle { assertNull(observed) }
            compose.runOnIdle { BookmarkAPIAccess.initialize(host) }
            compose.runOnIdle { assertNull(observed) }
            compose.runOnIdle { host.registerPluginAPI(provider) }
            compose.runOnIdle { assertSame(provider, observed) }
            compose.runOnIdle { BookmarkAPIAccess.initialize(replacement) }
            compose.runOnIdle { assertNull(observed) }
            compose.runOnIdle { replacement.registerPluginAPI(provider) }
            compose.runOnIdle { assertSame(provider, observed) }
        } finally {
            source.value = previous
            replacement.dispose()
            host.dispose()
        }
    }
}
