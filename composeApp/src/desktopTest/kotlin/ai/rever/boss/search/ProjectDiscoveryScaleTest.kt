package ai.rever.boss.search

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals

class ProjectDiscoveryScaleTest {
    @Test
    fun `bounded discovery handles a multi-module source tree`(
        @TempDir root: File,
    ) = runBlocking {
        repeat(120) { module ->
            val directory = File(root, "module-$module/src").apply { mkdirs() }
            File(directory, ".gitignore").writeText("*.generated\n")
            repeat(100) { File(directory, "Source$it.kt").writeText("needle") }
            File(directory, "ignored.generated").writeText("needle")
        }
        val elapsed =
            measureTimeMillis {
                val discovered = ProjectFileDiscovery.discover(root.absolutePath, acceptFile = { it.endsWith(".kt") })
                assertEquals(12_000, discovered.files.size)
                assertEquals(null, discovered.incompleteReason)
                assertEquals(null, discovered.warning)
            }
        // Diagnostic rather than a flaky wall-clock assertion on shared CI runners.
        println("Project discovery: 120 modules, 12000 source files, ${elapsed}ms")
    }
}
