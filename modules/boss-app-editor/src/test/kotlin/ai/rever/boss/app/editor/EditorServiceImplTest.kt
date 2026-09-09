package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.services.OpenFileRequest
import ai.rever.boss.plugin.language.LanguageIds
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BossConsole#75: this used to be a hand-maintained table independent of
 * `composeApp`'s `EditorLanguages`, and the two disagreed on `.sh`/`.bash`/`.zsh`
 * (`shell` here, `bash` there). Both now read the shared `LanguageIds` table -
 * `detectLanguage("sh")` returning `bash` rather than `shell` is the actual bug this
 * consolidation fixes, not just a refactor with no observable effect.
 */
class EditorServiceImplTest {
    private val service = EditorServiceImpl()

    @Test
    fun `shell extensions now agree with the shared table, not the old local one`() {
        assertEquals("bash", service.detectLanguage("sh"))
        assertEquals("bash", service.detectLanguage("bash"))
        assertEquals("bash", service.detectLanguage("zsh"))
    }

    @Test
    fun `previously working mappings are unchanged`() {
        assertEquals("kotlin", service.detectLanguage("kt"))
        assertEquals("kotlin", service.detectLanguage("kts"))
        assertEquals("java", service.detectLanguage("java"))
        assertEquals("python", service.detectLanguage("py"))
        assertEquals("javascript", service.detectLanguage("js"))
        assertEquals("go", service.detectLanguage("go"))
        assertEquals("rust", service.detectLanguage("rs"))
        assertEquals("yaml", service.detectLanguage("yaml"))
        assertEquals("json", service.detectLanguage("json"))
    }

    @Test
    fun `proto keeps its local mapping - the shared table has no id for it`() {
        // LanguageIds is shared with boss-file-types.json's default-app extension
        // list; adding "proto" there is a separate change, so it stays a local
        // addition on top of the shared table rather than migrated into it.
        assertEquals("protobuf", service.detectLanguage("proto"))
    }

    @Test
    fun `an unrecognised extension is plaintext, not the shared table's text`() {
        // EditorServiceImpl's own default was always "plaintext", distinct from
        // EditorLanguages' "text" - preserved deliberately, since this is this
        // service's own gRPC contract, not a value composeApp reads.
        assertEquals("plaintext", service.detectLanguage("notarealextension"))
    }

    @Test
    fun `newly available ids the old local table never had`() {
        // Gained for free by reading the shared table instead of a copy that only
        // ever knew ~24 extensions.
        assertEquals("fortran", service.detectLanguage("f90"))
        assertEquals("clojure", service.detectLanguage("clj"))
        assertEquals("batch", service.detectLanguage("bat"))
        assertEquals("diff", service.detectLanguage("diff"))
    }

    @Test
    fun `every shared extension agrees with the service`() {
        LanguageIds.extensions().forEach { (extension, language) ->
            assertEquals(language, service.detectLanguage(extension), extension)
        }
    }

    @Test
    fun `opening a shell file returns the shared language through the RPC response`() =
        runBlocking {
            val file = Files.createTempFile("boss-language-", ".sh").toFile()
            try {
                file.writeText("echo hello\n")
                val response = service.openFile(OpenFileRequest.newBuilder().setPath(file.absolutePath).build())
                assertTrue(response.success)
                assertEquals("bash", response.language)
                assertEquals("echo hello\n", response.content)
            } finally {
                file.delete()
            }
        }

    @Test
    fun `opening named files uses shared filename precedence and keeps service defaults`() =
        runBlocking {
            val directory = Files.createTempDirectory("boss-language-names-").toFile()
            val cases =
                mapOf(
                    "Dockerfile" to "dockerfile",
                    "Containerfile" to "dockerfile",
                    "Makefile" to "makefile",
                    "GNUmakefile" to "makefile",
                    "Gemfile" to "ruby",
                    "Rakefile" to "ruby",
                    "Dockerfile.dev" to "dockerfile",
                    "Dockerfile.sh" to "dockerfile",
                    ".env.local" to "properties",
                    "service.proto" to "protobuf",
                    "notes.unknown" to "plaintext",
                    "Gemfile.lock" to "plaintext",
                )
            try {
                cases.forEach { (name, expected) ->
                    val file = directory.resolve(name).apply { writeText("content") }
                    val response = service.openFile(OpenFileRequest.newBuilder().setPath(file.absolutePath).build())
                    assertTrue(response.success, name)
                    assertEquals(expected, response.language, name)
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `extension lookup is case-insensitive`() {
        assertEquals("kotlin", service.detectLanguage("KT"))
    }
}
