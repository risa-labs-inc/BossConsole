package ai.rever.boss.plugin.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class SwipeNavParityTest {
    private val root =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "scripts/test/test-swipe-nav.js").isFile }

    @Test
    fun `native cancellation constants stay coupled to the page`() {
        val source = File(root, "composeApp/src/desktopMain/resources/browser/swipe-nav.js").readText()
        val constants =
            mapOf(
                "COMMIT_PX" to SWIPE_COMMIT_PX,
                "MIN_EVENTS" to SWIPE_MIN_EVENTS.toDouble(),
                "CANCEL_STRONG_RATIO" to SWIPE_CANCEL_STRONG_RATIO,
                "CANCEL_MIXED_RATIO" to SWIPE_CANCEL_MIXED_RATIO,
                "CANCEL_VERTICAL_LOW" to SWIPE_CANCEL_VERTICAL_LOW,
                "CANCEL_VERTICAL_HIGH" to SWIPE_CANCEL_VERTICAL_HIGH,
            )
        constants.forEach { (name, value) ->
            val expression = if (name.startsWith("CANCEL_VERTICAL")) "COMMIT_PX \\* " else ""
            val match = Regex("var $name = $expression([0-9.]+);").find(source)
            assertEquals(value, match?.groupValues?.get(1)?.toDouble(), name)
        }
    }

    @Test
    fun `real native terminal evidence passes the page regression scenarios`() {
        val cases = Json.parseToJsonElement(File(root, "scripts/test/swipe-nav-cases.json").readText()).jsonArray
        val results =
            buildJsonObject {
                cases.forEach { fixture ->
                    val fields = fixture.jsonObject
                    var state = scrollGestureTransition(ScrollGestureSnapshot(0, false), 1, 0).first
                    fields.getValue("samples").jsonArray.forEach { sample ->
                        state =
                            scrollGestureTransition(
                                state,
                                2,
                                0,
                                sample.jsonArray[0].jsonPrimitive.double,
                                sample.jsonArray[1].jsonPrimitive.double,
                            ).first
                    }
                    val cancelled = fields.getValue("cancelled").jsonPrimitive.content == "true"
                    val end = scrollGestureTransition(state, if (cancelled) 8 else 4, 0).second!!
                    put(
                        fields.getValue("name").jsonPrimitive.content,
                        buildJsonObject {
                            put("statement", BrowserSwipeNavScript.release(end))
                            put("rejected", end.rejected)
                            put("cancelled", end.cancelled)
                        },
                    )
                }
            }
        val evidence = File.createTempFile("swipe-native-results", ".json")
        val output = File.createTempFile("swipe-native-page", ".log")
        try {
            evidence.writeText(results.toString(), Charsets.UTF_8)

            val process = runEvidenceProbe(evidence, output)
            val completed = process.waitFor(120, TimeUnit.SECONDS)
            if (!completed) {
                // Kill child node process on Windows/POSIX before killing wrapper
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }

            val rawOutput = if (output.isFile) output.readText(Charsets.UTF_8) else ""
            val normalizedEvidence = normalizeTerminalEvidence(rawOutput)

            assertTrue(
                completed,
                "page parity suite timed out after 120s; partial output:\n$normalizedEvidence",
            )
            assertEquals(0, process.exitValue(), normalizedEvidence)
        } finally {
            evidence.delete()
            output.delete()
        }
    }

    private fun runEvidenceProbe(
        evidence: File,
        output: File,
    ): Process {
        val script = File(root, "scripts/test/test-swipe-nav.js")
        val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)

        fun buildProcess(command: List<String>): Process =
            ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()

        return try {
            // Try direct node execution first to preserve clear missing-node diagnostics
            buildProcess(listOf("node", script.absolutePath, "--native-results", evidence.absolutePath))
        } catch (e: IOException) {
            if (isWindows) {
                // Fallback for Windows setups where node is a .cmd/.bat shim
                try {
                    buildProcess(
                        listOf(
                            "cmd.exe",
                            "/c",
                            "node",
                            script.absolutePath,
                            "--native-results",
                            evidence.absolutePath,
                        ),
                    )
                } catch (_: IOException) {
                    fail("SwipeNavParityTest requires node on PATH: ${e.message}")
                }
            } else {
                fail("SwipeNavParityTest requires node on PATH: ${e.message}")
            }
        }
    }

    private fun normalizeTerminalEvidence(raw: String): String =
        raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trimEnd()
}
