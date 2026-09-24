package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.services.OpenFileRequest
import ai.rever.boss.ipc.proto.services.SaveFileRequest
import ai.rever.boss.plugin.language.LanguageIds
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BossConsole#75: this used to be a hand-maintained table independent of
 * `composeApp`'s `EditorLanguages`, and the two disagreed on `.sh`/`.bash`/`.zsh`
 * (`shell` here, `bash` there). Both now read the shared `LanguageIds` table -
 * `detectLanguage("sh")` returning `bash` rather than `shell` is the actual bug this
 * consolidation fixes, not just a refactor with no observable effect.
 *
 * The saveFile tests pin the SaveFileResponse wire contract (BossConsole#1157):
 * IO failures surface as success=false with an error message - the shape the
 * write_file capability's OutputSchemaJson declares - never as a swallowed
 * success. Path refusals come from the #885/#891 confinement gate: they throw
 * gRPC statuses, and an out-of-root target reads as PERMISSION_DENIED on every
 * platform, so the POSIX-only prefix checks of the pre-#891 blocklist (which
 * passed Windows system paths untouched) are gone by construction.
 */
class EditorServiceImplTest {
    private lateinit var service: EditorServiceImpl

    @BeforeTest
    fun setUp() {
        // EditorServiceImpl confines all paths to the user's home since #885; the
        // OS temp dir is outside the real home (not a prefix on any of the
        // three CI platforms), and this suite's fixtures live in the OS temp
        // dir - so confine the service AT the OS temp dir: the rule then covers
        // exactly where these tests create their files. The root is injected
        // rather than via a process-global user.home mutation.
        service = EditorServiceImpl(root = File(System.getProperty("java.io.tmpdir")))
    }

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

    @Test
    fun `saveFile writes content and reports success on the wire`() =
        runBlocking {
            val directory = Files.createTempDirectory("boss-save-").toFile()
            try {
                val target = directory.resolve("Notes.kt")
                val request =
                    SaveFileRequest
                        .newBuilder()
                        .setPath(target.absolutePath)
                        .setContent("fun main() {}\n")
                        .build()
                val response = service.saveFile(request)
                assertTrue(response.success)
                assertEquals("", response.errorMessage)
                assertEquals("fun main() {}\n", target.readText())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `saveFile creates missing parent directories`() =
        runBlocking {
            val directory = Files.createTempDirectory("boss-save-mkdirs-").toFile()
            try {
                val target = directory.resolve("nested/deeper/notes.txt")
                val request =
                    SaveFileRequest
                        .newBuilder()
                        .setPath(target.absolutePath)
                        .setContent("saved")
                        .build()
                val response = service.saveFile(request)
                assertTrue(response.success)
                assertEquals("saved", target.readText())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `saveFile reports failed mkdirs as a wire-visible failure`() =
        runBlocking {
            val directory = Files.createTempDirectory("boss-save-blocked-").toFile()
            try {
                // A regular file where a directory is needed: mkdirs() cannot create the
                // parent, so the write must fail - visibly, not as a success-shaped response.
                directory.resolve("blocker").writeText("not a directory")
                val target = directory.resolve("blocker/child.txt")
                val request =
                    SaveFileRequest
                        .newBuilder()
                        .setPath(target.absolutePath)
                        .setContent("x")
                        .build()
                val response = service.saveFile(request)
                assertFalse(response.success)
                assertTrue(response.errorMessage.isNotEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `saveFile reports an unwritable target as a wire-visible failure`() =
        runBlocking {
            val directory = Files.createTempDirectory("boss-save-dir-").toFile()
            try {
                // Writing to an existing directory always fails on the JVM.
                val request =
                    SaveFileRequest
                        .newBuilder()
                        .setPath(directory.absolutePath)
                        .setContent("x")
                        .build()
                val response = service.saveFile(request)
                assertFalse(response.success)
                assertTrue(response.errorMessage.isNotEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `saveFile reports permission denial as a wire-visible failure`() =
        runBlocking {
            val directory = Files.createTempDirectory("boss-save-ro-").toFile()
            try {
                if (!directory.setWritable(false) || directory.canWrite()) {
                    // Permission denial cannot be simulated while writing is still permitted
                    // (e.g. running as root); the structural failure paths cover the contract.
                    return@runBlocking
                }
                // Probe the injection: the DOS read-only bit on a Windows directory
                // does NOT block file creation inside it, so the atomic save would
                // succeed and the failure assertion would be wrong - skip instead of
                // lying (same probe the save regression suite uses).
                val injectionWorks =
                    runCatching { File.createTempFile("probe", ".part", directory).delete() }.isFailure
                if (!injectionWorks) {
                    return@runBlocking
                }
                val target = directory.resolve("denied.txt")
                val request =
                    SaveFileRequest
                        .newBuilder()
                        .setPath(target.absolutePath)
                        .setContent("x")
                        .build()
                val response = service.saveFile(request)
                assertFalse(response.success)
                assertTrue(response.errorMessage.isNotEmpty())
            } finally {
                directory.setWritable(true)
                directory.deleteRecursively()
            }
        }

    @Test
    fun `saveFile rejects path traversal with PERMISSION_DENIED`() =
        runBlocking {
            // Traversal resolves first and the escaped target lands outside the
            // confinement root: the refusal is the gate's PERMISSION_DENIED - the
            // same wire shape the save regression suite pins - not the old raw
            // `..` ban's INVALID_ARGUMENT.
            val error =
                assertFailsWith<StatusRuntimeException> {
                    service.saveFile(SaveFileRequest.newBuilder().setPath("/tmp/../outside").build())
                }
            assertEquals(Status.PERMISSION_DENIED.code, error.status.code)
        }

    @Test
    fun `saveFile rejects system paths outside the confinement root with PERMISSION_DENIED`() =
        runBlocking {
            // The pre-#891 blocklist was POSIX-only: on Windows "/etc/passwd"
            // resolves against the current drive and the raw-prefix check never
            // matched it - the CI failure this suite's rebase closes. The
            // confinement gate refuses the resolved path on every platform: on
            // Windows the same input resolves to the drive's \etc\passwd, which
            // is outside the injected root, and on Linux/macOS /etc/passwd is
            // outside it too - one rule, no per-OS prefix list.
            val error =
                assertFailsWith<StatusRuntimeException> {
                    service.saveFile(SaveFileRequest.newBuilder().setPath("/etc/passwd").build())
                }
            assertEquals(Status.PERMISSION_DENIED.code, error.status.code)
        }

    @Test
    fun `saveFile rejects malformed paths with INVALID_ARGUMENT`() =
        runBlocking {
            // An illegal path string is still INVALID_ARGUMENT under the
            // confinement gate: Path.of rejects it before there is anything to
            // resolve or confine.
            val error =
                assertFailsWith<StatusRuntimeException> {
                    service.saveFile(
                        SaveFileRequest.newBuilder().setPath("bad\u0000path").build(),
                    )
                }
            assertEquals(Status.INVALID_ARGUMENT.code, error.status.code)
        }

    @Test
    fun `openFile rejects path traversal with PERMISSION_DENIED`() =
        runBlocking {
            // A relative traversal resolves against the process CWD, which is
            // outside the injected confinement root - the gate refuses it with
            // the confinement status, the same as the absolute shapes above.
            val error =
                assertFailsWith<StatusRuntimeException> {
                    service.openFile(OpenFileRequest.newBuilder().setPath("../outside").build())
                }
            assertEquals(Status.PERMISSION_DENIED.code, error.status.code)
        }
}
