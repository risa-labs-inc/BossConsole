package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.MainFunctionInfo
import ai.rever.boss.plugin.run.Language
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [runLanguageForMainFunction], the resolution behind the editor's "Run main function" action.
 *
 * The bug: `MainFunctionInfo.language` is serialised as `Language.name.lowercase()` (a NAME, e.g.
 * `"kotlin"`), and the old code passed that to `Language.fromExtension`, which matches file
 * EXTENSIONS (`kt`, `kts`). Name and extension coincide only for `java` and `go`, so every other
 * language resolved to [Language.UNKNOWN] and the generated run command became
 * `echo 'Unknown language'`. The fix resolves from the file path instead.
 */
class RunLanguageForMainFunctionTest {
    private fun info(
        filePath: String,
        language: String,
    ) = MainFunctionInfo(
        filePath = filePath,
        lineNumber = 1,
        functionName = "main",
        language = language,
        className = null,
        metadata = emptyMap(),
    )

    @Test
    fun `language comes from the file path, not the stored language name`() {
        // The buggy NAME form is passed as `language` too, to prove it is ignored in favour of the
        // path. Each of these returned UNKNOWN under the old fromExtension(name) lookup.
        assertEquals(Language.KOTLIN, runLanguageForMainFunction(info("/p/src/Main.kt", "kotlin")))
        assertEquals(Language.PYTHON, runLanguageForMainFunction(info("/p/app.py", "python")))
        assertEquals(Language.JAVASCRIPT, runLanguageForMainFunction(info("/p/index.js", "javascript")))
        assertEquals(Language.TYPESCRIPT, runLanguageForMainFunction(info("/p/index.ts", "typescript")))
        assertEquals(Language.RUST, runLanguageForMainFunction(info("/p/main.rs", "rust")))
    }

    @Test
    fun `java and go, which coincidentally worked before, still resolve`() {
        assertEquals(Language.JAVA, runLanguageForMainFunction(info("/p/Main.java", "java")))
        assertEquals(Language.GO, runLanguageForMainFunction(info("/p/main.go", "go")))
    }

    @Test
    fun `alternate extensions use the same language as detection`() {
        assertEquals(Language.KOTLIN, runLanguageForMainFunction(info("/p/build tool.kts", "kotlin")))
        assertEquals(Language.JAVASCRIPT, runLanguageForMainFunction(info("/p/app.jsx", "javascript")))
        assertEquals(Language.JAVASCRIPT, runLanguageForMainFunction(info("/p/index.mjs", "javascript")))
        assertEquals(Language.TYPESCRIPT, runLanguageForMainFunction(info("/p/app.tsx", "typescript")))
    }

    @Test
    fun `path remains authoritative for missing or conflicting stored names`() {
        assertEquals(Language.PYTHON, runLanguageForMainFunction(info("/p/app.py", "unknown")))
        assertEquals(Language.PYTHON, runLanguageForMainFunction(info("/p/app.py", "java")))
        assertEquals(Language.UNKNOWN, runLanguageForMainFunction(info("/p/script", "python")))
    }

    @Test
    fun `the old extension-of-the-name lookup would have returned UNKNOWN`() {
        // Why the fix is needed: fromExtension on the language NAME misses for all but java/go.
        assertEquals(Language.UNKNOWN, Language.fromExtension("kotlin"))
        assertEquals(Language.UNKNOWN, Language.fromExtension("python"))
    }
}
