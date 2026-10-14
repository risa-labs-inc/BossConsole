package ai.rever.boss.testsupport

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RepoRootTest {
    @Test
    fun `source scan excludes generated build trees`() {
        val root = Files.createTempDirectory("boss-source-scan").toFile()
        try {
            val source =
                root.resolve("module/src/main/kotlin/Source.kt").apply {
                    parentFile.mkdirs()
                    writeText("class Source")
                }
            root.resolve("module/src/main/kotlin/notes.txt").writeText("not Kotlin")
            root.resolve("module/src/build/generated/kotlin/Generated.kt").apply {
                parentFile.mkdirs()
                writeText("class Generated")
            }

            assertEquals(listOf(source), kotlinSourcesUnder(root, "module/src"))
        } finally {
            root.deleteRecursively()
        }
    }
}
