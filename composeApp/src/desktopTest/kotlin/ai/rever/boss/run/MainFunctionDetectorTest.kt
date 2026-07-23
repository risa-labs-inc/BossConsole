package ai.rever.boss.run

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks down [DesktopMainFunctionDetector]'s pure parsing surface:
 *
 * - [DesktopMainFunctionDetector.detectInFile] — per-language main/entry-point
 *   detection over raw source text (no file system involved)
 * - [DesktopMainFunctionDetector.generateCommand] — run-command construction,
 *   including the single-quote shell escaping that keeps hostile paths inert
 * - [DesktopMainFunctionDetector.findProjectRoot] — project-marker walk
 *   (exercised against real temp dirs)
 */
class MainFunctionDetectorTest {

    private val detector = DesktopMainFunctionDetector()

    private fun lines(vararg lines: String) = lines.joinToString("\n")

    // ==================== Kotlin ====================

    @Test
    fun `top-level kotlin main is detected with line number and package`() {
        val content = lines(
            "package com.example.app",
            "",
            "fun main() {",
            "    println(\"hi\")",
            "}"
        )
        val detected = detector.detectInFile("/proj/src/Main.kt", content)

        assertEquals(1, detected.size)
        with(detected.single()) {
            assertEquals(2, lineNumber)
            assertEquals("main", functionName)
            assertNull(className)
            assertEquals("com.example.app", packageName)
            assertEquals(Language.KOTLIN, language)
            assertEquals("/proj/src/Main.kt", filePath)
        }
    }

    @Test
    fun `kotlin main with args parameter is detected`() {
        val content = lines("fun main(args: Array<String>) {", "}")
        val detected = detector.detectInFile("/proj/Main.kt", content)

        assertEquals(1, detected.size)
        assertEquals(0, detected.single().lineNumber)
        assertNull(detected.single().packageName)
    }

    @Test
    fun `companion object main with JvmStatic on its own line is detected`() {
        val content = lines(
            "package com.example",
            "",
            "class App {",
            "    companion object {",
            "        @JvmStatic",
            "        fun main(args: Array<String>) {",
            "        }",
            "    }",
            "}"
        )
        val detected = detector.detectInFile("/proj/App.kt", content)

        assertEquals(1, detected.size)
        assertEquals(5, detected.single().lineNumber)
        assertEquals("com.example", detected.single().packageName)
    }

    @Test
    fun `JvmStatic and main on the same line are detected`() {
        val content = lines(
            "object App {",
            "    @JvmStatic fun main(args: Array<String>) {}",
            "}"
        )
        val detected = detector.detectInFile("/proj/App.kt", content)

        assertEquals(1, detected.size)
        assertEquals(1, detected.single().lineNumber)
    }

    @Test
    fun `line-commented kotlin main is not detected`() {
        val content = lines(
            "// fun main() {",
            "    // fun main(args: Array<String>) {}",
            "val x = 1"
        )
        assertTrue(detector.detectInFile("/proj/Main.kt", content).isEmpty())
    }

    @Test
    fun `main inside a triple-quoted string is skipped, real main is kept`() {
        val tripleQuote = "\"\"\""
        val content = lines(
            "package com.example",
            "val snippet = $tripleQuote",
            "fun main() {",
            "}",
            tripleQuote,
            "fun main() {",
            "}"
        )
        val detected = detector.detectInFile("/proj/Main.kt", content)

        assertEquals(1, detected.size)
        assertEquals(5, detected.single().lineNumber)
    }

    @Test
    fun `main inside a single-line string literal is not detected`() {
        val content = lines("val usage = \"fun main() { ... }\"")
        assertTrue(detector.detectInFile("/proj/Main.kt", content).isEmpty())
    }

    @Test
    fun `multiple main candidates are all reported in order`() {
        val content = lines(
            "fun main() {}",
            "object Alt {",
            "    fun main() {}",
            "}"
        )
        val detected = detector.detectInFile("/proj/Main.kt", content)

        assertEquals(listOf(0, 2), detected.map { it.lineNumber })
    }

    @Test
    fun `kotlin file without main yields nothing`() {
        val content = lines("package com.example", "fun helper() = 42")
        assertTrue(detector.detectInFile("/proj/Helper.kt", content).isEmpty())
    }

    // ==================== Java ====================

    @Test
    fun `java main is detected with class and package`() {
        val content = lines(
            "package com.example.demo;",
            "",
            "public class Main {",
            "    public static void main(String[] args) {",
            "    }",
            "}"
        )
        val detected = detector.detectInFile("/proj/Main.java", content)

        assertEquals(1, detected.size)
        with(detected.single()) {
            assertEquals(3, lineNumber)
            assertEquals("main", functionName)
            assertEquals("Main", className)
            assertEquals("com.example.demo", packageName)
            assertEquals(Language.JAVA, language)
        }
    }

    @Test
    fun `java class without main yields nothing`() {
        val content = lines(
            "package com.example;",
            "public class Util {",
            "    public static int add(int a, int b) { return a + b; }",
            "}"
        )
        assertTrue(detector.detectInFile("/proj/Util.java", content).isEmpty())
    }

    // ==================== Python ====================

    @Test
    fun `python dunder-main guard is detected with either quote style`() {
        val single = lines("def run():", "    pass", "", "if __name__ == '__main__':", "    run()")
        val double = lines("if __name__ == \"__main__\":", "    pass")

        val detectedSingle = detector.detectInFile("/proj/app.py", single)
        assertEquals(1, detectedSingle.size)
        assertEquals(3, detectedSingle.single().lineNumber)
        assertEquals("__main__", detectedSingle.single().functionName)
        assertEquals(Language.PYTHON, detectedSingle.single().language)

        assertEquals(1, detector.detectInFile("/proj/app.py", double).size)
    }

    @Test
    fun `commented python guard is not detected`() {
        val content = lines("# if __name__ == '__main__':", "pass")
        assertTrue(detector.detectInFile("/proj/app.py", content).isEmpty())
    }

    // ==================== Go ====================

    @Test
    fun `go main requires both package main and func main`() {
        val runnable = lines("package main", "", "func main() {", "}")
        val library = lines("package tools", "", "func main() {", "}")
        val noFunc = lines("package main", "", "func run() {", "}")

        val detected = detector.detectInFile("/proj/main.go", runnable)
        assertEquals(1, detected.size)
        assertEquals(2, detected.single().lineNumber)
        assertEquals("main", detected.single().packageName)

        assertTrue(detector.detectInFile("/proj/main.go", library).isEmpty())
        assertTrue(detector.detectInFile("/proj/main.go", noFunc).isEmpty())
    }

    // ==================== Rust ====================

    @Test
    fun `rust fn main is detected`() {
        val content = lines("fn main() {", "    println!(\"hi\");", "}")
        val detected = detector.detectInFile("/proj/main.rs", content)

        assertEquals(1, detected.size)
        assertEquals(0, detected.single().lineNumber)
        assertEquals(Language.RUST, detected.single().language)
    }

    @Test
    fun `rust file without main yields nothing`() {
        assertTrue(detector.detectInFile("/proj/lib.rs", "fn helper() {}").isEmpty())
    }

    // ==================== JavaScript / TypeScript ====================

    @Test
    fun `well-known js entry file names are entry points regardless of content`() {
        val detected = detector.detectInFile("/proj/index.js", "console.log('hi')")

        assertEquals(1, detected.size)
        with(detected.single()) {
            assertEquals(0, lineNumber)
            assertEquals("entry", functionName)
            assertEquals(Language.JAVASCRIPT, language)
        }
    }

    @Test
    fun `main-ts is a typescript entry point`() {
        val detected = detector.detectInFile("/proj/src/main.ts", "export {}")
        assertEquals(1, detected.size)
        assertEquals(Language.TYPESCRIPT, detected.single().language)
    }

    @Test
    fun `non-entry js file names yield nothing`() {
        assertTrue(detector.detectInFile("/proj/src/helper.js", "console.log('hi')").isEmpty())
    }

    // ==================== language resolution ====================

    @Test
    fun `unknown extension yields nothing`() {
        assertTrue(detector.detectInFile("/proj/notes.txt", "fun main() {}").isEmpty())
    }

    @Test
    fun `explicit language parameter overrides the file extension`() {
        val detected = detector.detectInFile("/proj/snippet.txt", "fun main() {}", Language.KOTLIN)
        assertEquals(1, detected.size)
        assertEquals(Language.KOTLIN, detected.single().language)
    }

    // ==================== generateCommand: quoting and injection safety ====================

    private fun detectedIn(path: String, language: Language) = DetectedMainFunction(
        lineNumber = 0,
        functionName = "main",
        className = null,
        packageName = null,
        language = language,
        filePath = path
    )

    @Test
    fun `python command single-quotes the path`() {
        val command = detector.generateCommand(detectedIn("/no-such-root/app.py", Language.PYTHON), "/no-such-root")
        assertEquals("python3 '/no-such-root/app.py'", command)
    }

    @Test
    fun `injection-shaped path stays inert inside single quotes`() {
        val command = detector.generateCommand(
            detectedIn("/no-such-root/\$(rm -rf ~)/app.py", Language.PYTHON),
            "/no-such-root"
        )
        assertEquals("python3 '/no-such-root/\$(rm -rf ~)/app.py'", command)
    }

    @Test
    fun `single quote in path is escaped with the quote-backslash-quote idiom`() {
        val command = detector.generateCommand(detectedIn("/no-such-root/it's.py", Language.PYTHON), "/no-such-root")
        assertEquals("python3 '/no-such-root/it'\\''s.py'", command)
    }

    @Test
    fun `javascript and typescript commands use node and ts-node`() {
        assertEquals(
            "node '/no-such-root/tool.js'",
            detector.generateCommand(detectedIn("/no-such-root/tool.js", Language.JAVASCRIPT), "/no-such-root")
        )
        assertEquals(
            "npx ts-node '/no-such-root/tool.ts'",
            detector.generateCommand(detectedIn("/no-such-root/tool.ts", Language.TYPESCRIPT), "/no-such-root")
        )
    }

    @Test
    fun `go command runs the file directly`() {
        assertEquals(
            "go run '/no-such-root/main.go'",
            detector.generateCommand(detectedIn("/no-such-root/main.go", Language.GO), "/no-such-root")
        )
    }

    @Test
    fun `kts scripts run via kotlinc -script`() {
        assertEquals(
            "kotlinc -script '/no-such-root/build tool.kts'",
            detector.generateCommand(detectedIn("/no-such-root/build tool.kts", Language.KOTLIN), "/no-such-root")
        )
    }

    // ==================== generateCommand: project-aware commands ====================

    @Test
    fun `kotlin file inside a gradle module runs the module's run task`(@TempDir tempDir: File) {
        File(tempDir, "gradlew").writeText("#!/bin/sh")
        val moduleDir = File(tempDir, "app")
        File(moduleDir, "src/main/kotlin").mkdirs()
        File(moduleDir, "build.gradle.kts").writeText("")
        val source = File(moduleDir, "src/main/kotlin/Main.kt").apply { writeText("fun main() {}") }

        val command = detector.generateCommand(
            detectedIn(source.absolutePath, Language.KOTLIN),
            tempDir.absolutePath
        )
        assertEquals("./gradlew :app:run", command)
    }

    @Test
    fun `kotlin file outside any module falls back to the root run task`(@TempDir tempDir: File) {
        File(tempDir, "gradlew").writeText("#!/bin/sh")
        File(tempDir, "src/main/kotlin").mkdirs()
        val source = File(tempDir, "src/main/kotlin/Main.kt").apply { writeText("fun main() {}") }

        val command = detector.generateCommand(
            detectedIn(source.absolutePath, Language.KOTLIN),
            tempDir.absolutePath
        )
        assertEquals("./gradlew run", command)
    }

    @Test
    fun `java file in a maven project runs the fully qualified class`(@TempDir tempDir: File) {
        File(tempDir, "pom.xml").writeText("<project/>")
        File(tempDir, "src/main/java").mkdirs()
        val source = File(tempDir, "src/main/java/Main.java").apply { writeText("class Main {}") }

        val detected = DetectedMainFunction(
            lineNumber = 0,
            functionName = "main",
            className = "Main",
            packageName = "com.example",
            language = Language.JAVA,
            filePath = source.absolutePath
        )
        assertEquals(
            "mvn exec:java -Dexec.mainClass='com.example.Main'",
            detector.generateCommand(detected, tempDir.absolutePath)
        )
    }

    // ==================== findProjectRoot ====================

    @Test
    fun `findProjectRoot stops at the first marker directory`(@TempDir tempDir: File) {
        File(tempDir, ".git").mkdirs()
        val nested = File(tempDir, "a/b").apply { mkdirs() }
        val source = File(nested, "script.py").apply { writeText("pass") }

        assertEquals(tempDir.absolutePath, detector.findProjectRoot(source.absolutePath))
    }

    @Test
    fun `findProjectRoot honors package json as a marker`(@TempDir tempDir: File) {
        File(tempDir, "package.json").writeText("{}")
        val nested = File(tempDir, "src").apply { mkdirs() }
        val source = File(nested, "index.js").apply { writeText("") }

        assertEquals(tempDir.absolutePath, detector.findProjectRoot(source.absolutePath))
    }
}
