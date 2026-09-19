package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.services.OpenFileRequest
import ai.rever.boss.ipc.proto.services.SaveFileRequest
import ai.rever.boss.plugin.language.LanguageIds
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /**
     * BossConsole#1157: the previous saveFile caught Exception, returned Empty regardless,
     * and the manifest declared a `success` boolean the RPC could never produce. The fix:
     * a `SaveFileResponse { success, error_message }`, exception handling in the success
     * path (no swallowed failures), and Status.INVALID_ARGUMENT on path rejection.
     */
    @Test
    fun `saveFile on a writable target reports success and persists the content`() =
        runBlocking {
            val dir = Files.createTempDirectory("boss-savefile-").toFile()
            val target = File(dir, "out.txt")
            try {
                val response =
                    service.saveFile(
                        SaveFileRequest
                            .newBuilder()
                            .setPath(target.absolutePath)
                            .setContent("hello\n")
                            .build(),
                    )
                assertTrue(response.success, response.errorMessage)
                assertEquals("", response.errorMessage)
                assertEquals("hello\n", target.readText())
            } finally {
                dir.deleteRecursively()
            }
        }

    @Test
    fun `saveFile on an unwritable target reports failure with the OS error, not a silent Empty`() =
        runBlocking {
            // Targeting a child of an EXISTING FILE (not a directory) is the portable way to
            // make writeText fail across platforms: the mkdirs call inside saveFile silently
            // does NOT create a sibling under a non-directory, so the write fails with
            // ENOTDIR (POSIX) / ERROR_DIRECTORY (Windows). Either way, saveFile must
            // surface the failure rather than returning an Empty-shaped success.
            // dir.setWritable(false, false) was tried first; it is not portable on Windows
            // because admin or elevated contexts and certain filesystem ACLs ignore the
            // request and let writes succeed anyway. Writing under a non-directory parent
            // is portable because no POSIX/Windows conformance mode lets open(O_WRONLY |
            // O_CREAT) succeed when the immediate parent is not a directory.
            val tempDir = Files.createTempDirectory("boss-savefile-fail-").toFile()
            val existingFile = File(tempDir, "a-file")
            existingFile.writeText("not a directory\n")
            val target = File(existingFile, "out.txt")
            try {
                val response =
                    service.saveFile(
                        SaveFileRequest
                            .newBuilder()
                            .setPath(target.absolutePath)
                            .setContent("hello\n")
                            .build(),
                    )
                assertFalse(response.success, "writing under a non-directory parent must surface as failure")
                assertNotNull(response.errorMessage, "the OS error message must reach the wire")
                assertFalse(
                    target.exists(),
                    "the file must not have been created by a hidden write",
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }

    @Test
    fun `saveFile on a path-traversal payload raises INVALID_ARGUMENT, not IllegalArgumentException`() =
        runBlocking {
            try {
                service.saveFile(
                    SaveFileRequest
                        .newBuilder()
                        .setPath("/tmp/../etc/passwd")
                        .setContent("hello\n")
                        .build(),
                )
            } catch (e: StatusRuntimeException) {
                assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
                return@runBlocking
            }
            kotlin.test.fail("Expected StatusRuntimeException with INVALID_ARGUMENT for path traversal")
        }

    @Test
    fun `saveFile on a system path raises INVALID_ARGUMENT`() =
        runBlocking {
            try {
                service.saveFile(
                    SaveFileRequest
                        .newBuilder()
                        .setPath("/etc/hosts")
                        .setContent("hello\n")
                        .build(),
                )
            } catch (e: StatusRuntimeException) {
                assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
                return@runBlocking
            }
            kotlin.test.fail("Expected StatusRuntimeException with INVALID_ARGUMENT for /etc path")
        }
}
