package ai.rever.boss.plugin.browser

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * Guards the contract the JxBrowser callback thread depends on: [BrowserFaviconPipeline.submit]
 * must return without doing the conversion, a burst of FaviconChanged events must collapse to the
 * latest icon, and an oversized icon must be refused rather than decoded.
 *
 * A gated [Executor] stands in for the single daemon thread: a submission that queues work but
 * does not run it proves the heavy path - BGRA→ARGB, bitmap build, cache write - left the callback
 * thread entirely, which is the regression this card fixes.
 */
class BrowserFaviconPipelineTest {
    /** Runs nothing until [drainAll] is called; each submission only enqueues. */
    private class GatedExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()

        val queued: Int get() = queue.size

        override fun execute(command: Runnable) {
            queue.add(command)
        }

        fun drainAll() {
            while (queue.isNotEmpty()) queue.poll().run()
        }
    }

    private class Recorder {
        val saves = mutableListOf<Pair<String, ImageBitmap>>()
        val notified = mutableListOf<String?>()
        val warnings = mutableListOf<String>()
    }

    private fun fixture(executor: Executor): Pair<BrowserFaviconPipeline, Recorder> {
        val recorder = Recorder()
        val pipeline =
            BrowserFaviconPipeline(
                executor = executor,
                urlProvider = { "https://example.com/" },
                notifyListeners = { recorder.notified.add(it) },
                saveFavicon = { url, bitmap ->
                    recorder.saves.add(url to bitmap)
                    "key-${recorder.saves.size}"
                },
                warn = { message, _ -> recorder.warnings.add(message) },
            )
        return pipeline to recorder
    }

    /** Solid-colour BGRA pixels; the [b], [g], [r], [a] order matches what Chromium delivers. */
    private fun bgraPixels(
        size: Int,
        b: Int,
        g: Int,
        r: Int,
        a: Int = 0xFF,
    ) = ByteArray(size * size * 4) { i ->
        when (i % 4) {
            0 -> b.toByte()
            1 -> g.toByte()
            else -> if (i % 4 == 2) r.toByte() else a.toByte()
        }
    }

    private fun ImageBitmap.firstPixelArgb() = toAwtImage().getRGB(0, 0)

    @Test
    fun `submit returns without doing the conversion`() {
        val executor = GatedExecutor()
        val (pipeline, recorder) = fixture(executor)

        val (_, elapsed) =
            measureTimedValue {
                pipeline.submit(MEGAPIXEL_EDGE, MEGAPIXEL_EDGE) { bgraPixels(MEGAPIXEL_EDGE, 10, 20, 30) }
            }

        assertTrue(
            elapsed.inWholeMilliseconds < CALLBACK_BUDGET_MS,
            "submit took ${elapsed.inWholeMilliseconds}ms on the callback thread",
        )
        assertTrue(recorder.saves.isEmpty(), "conversion must not run inside submit")
        assertTrue(recorder.notified.isEmpty())
        assertEquals(1, executor.queued)

        executor.drainAll()
        assertEquals(1, recorder.saves.size)
        assertEquals("key-1", recorder.notified.single())
    }

    @Test
    fun `a rapid burst keeps only the latest icon`() {
        val executor = GatedExecutor()
        val (pipeline, recorder) = fixture(executor)

        pipeline.submit(8, 8) { bgraPixels(8, 0xFF, 0x00, 0x00) }
        pipeline.submit(8, 8) { bgraPixels(8, 0x00, 0xFF, 0x00) }
        pipeline.submit(8, 8) { bgraPixels(8, 0x00, 0x00, 0xFF) }

        executor.drainAll()

        assertEquals(1, recorder.saves.size, "three events must coalesce to one cache write")
        assertEquals("key-1", recorder.notified.single())
        val savedArgb =
            recorder.saves
                .single()
                .second
                .firstPixelArgb()
        assertEquals(0xFFFF0000.toInt(), savedArgb, "the last-submitted icon is the one cached")
    }

    @Test
    fun `an oversized icon is refused before its pixels are read`() {
        val executor = GatedExecutor()
        val (pipeline, recorder) = fixture(executor)
        var pixelsRead = false

        pipeline.submit(
            BrowserFaviconPipeline.MAX_FAVICON_DIMENSION_PX + 1,
            BrowserFaviconPipeline.MAX_FAVICON_DIMENSION_PX + 1,
        ) {
            pixelsRead = true
            null
        }

        assertFalse(pixelsRead, "the cap must refuse before touching the pixel array")
        assertEquals(0, executor.queued)
        assertTrue(recorder.warnings.single().contains("dimensions"))
        assertTrue(recorder.saves.isEmpty())
        assertTrue(recorder.notified.isEmpty())
    }

    @Test
    fun `a short pixel buffer is refused`() {
        val executor = GatedExecutor()
        val (pipeline, recorder) = fixture(executor)

        pipeline.submit(64, 64) { ByteArray(16) }

        assertTrue(recorder.warnings.single().contains("pixel buffer"))
        assertTrue(recorder.saves.isEmpty())
    }

    @Test
    fun `a cleared icon notifies listeners with null`() {
        val executor = GatedExecutor()
        val (pipeline, recorder) = fixture(executor)

        pipeline.submit(0, 0) { null }
        executor.drainAll()

        assertTrue(recorder.saves.isEmpty())
        assertNull(recorder.notified.single())
    }

    private companion object {
        /** 1 megapixel - large enough that inline conversion would be measurable, inside the cap. */
        const val MEGAPIXEL_EDGE = 1024

        /** Generous bound: submit is an array grab plus an atomic store, so any stall is a bug. */
        const val CALLBACK_BUDGET_MS = 2000
    }
}
