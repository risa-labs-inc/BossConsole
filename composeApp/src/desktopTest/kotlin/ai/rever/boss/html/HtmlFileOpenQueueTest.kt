package ai.rever.boss.html

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlFileOpenQueueTest {
    @Test
    fun `requests before a collector exists remain in order`() =
        runBlocking {
            val queue = HtmlFileOpenQueue()
            queue.enqueue("/a.html", "a.html")
            queue.enqueue("/b.htm", "b.htm")
            assertTrue(queue.hasPending)
            val files = withTimeout(2000) { queue.events.take(2).toList() }
            assertEquals(listOf("/a.html", "/b.htm"), files.map { it.filePath })
            assertFalse(queue.hasPending)
            queue.close()
        }

    @Test
    fun `a busy dialog does not replace or discard subsequent requests`() =
        runBlocking {
            val queue = HtmlFileOpenQueue()
            val firstShown = CompletableDeferred<Unit>()
            val dismiss = CompletableDeferred<Unit>()
            val seen = mutableListOf<String>()
            val collector =
                async {
                    queue.events.take(2).collect { request ->
                        seen.add(request.fileName)
                        if (seen.size == 1) {
                            firstShown.complete(Unit)
                            dismiss.await()
                        }
                    }
                }
            queue.enqueue("/a.html", "a.html")
            withTimeout(2000) { firstShown.await() }
            queue.enqueue("/b.html", "b.html")
            assertEquals(listOf("a.html"), seen)
            assertTrue(queue.hasPending, "Startup must remain busy while the prompt is awaiting an answer")
            dismiss.complete(Unit)
            withTimeout(2000) { collector.await() }
            assertEquals(listOf("a.html", "b.html"), seen)
            assertFalse(queue.hasPending)
            queue.close()
        }

    @Test
    fun `windows cannot consume one anothers files and closing discards pending opens`() =
        runBlocking {
            val first = HtmlFileOpenQueue()
            val second = HtmlFileOpenQueue()
            first.enqueue("/a.html", "a.html")
            second.enqueue("/b.html", "b.html")
            assertEquals("b.html", withTimeout(2000) { second.events.first().fileName })
            first.close()
            assertFalse(first.hasPending)
            assertFalse(runCatching { first.events.first() }.isSuccess)
            second.close()
        }
}
