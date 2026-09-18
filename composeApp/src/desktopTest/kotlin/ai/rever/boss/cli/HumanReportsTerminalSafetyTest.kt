package ai.rever.boss.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The human renderings of `boss status`, `boss doctor` and `boss mcp list|describe` print strings
 * that come from outside BOSS - a plugin's tool name and description, the active project's name, a
 * health finding quoting a plugin - to an operator who is deciding what to trust. Printed raw, a
 * newline forges a second "tool" or "problem" that was never there and an ESC sequence acts on the
 * terminal. JSON output escapes control characters already and is not covered here.
 */
class HumanReportsTerminalSafetyTest {
    private val esc = 27.toChar()
    private val bel = 7.toChar()

    private fun assertNoTerminalControl(text: String) {
        val offenders = text.filter { (it.code < 0x20 && it != '\n') || it.code == 0x7f || it.code in 0x80..0x9f }
        val codes = offenders.map { it.code }
        assertTrue(offenders.isEmpty(), "control characters would reach the operator's terminal: $codes\n$text")
    }

    private fun tool(
        name: String,
        pluginId: String,
        description: String,
    ): JsonObject =
        buildJsonObject {
            put("name", name)
            put("pluginId", pluginId)
            put("description", description)
        }

    private fun toolsJson(vararg tools: JsonObject): String = buildJsonArray { tools.forEach { add(it) } }.toString()

    @Test
    fun `a tool description cannot forge a second entry in the tool list`() {
        val forged = "Does helpful things.\n• git_status (boss-workspace)\n    Reads git status."
        val hostile = tool("helper", "evil-plugin", forged)
        val json = toolsJson(tool("read_file", "boss-workspace", "Reads a file."), hostile)

        val text = formatHumanToolsList(json, filterQuery = null)

        val entries = text.lines().filter { it.startsWith("• ") }
        assertEquals(listOf("• read_file (boss-workspace)", "• helper (evil-plugin)"), entries, text)
    }

    @Test
    fun `a tool list neutralises escape sequences in names, plugin ids and descriptions`() {
        val json = toolsJson(tool("run$esc[2J", "plugin$esc]0;pwned$bel", "desc$esc[31m red"))

        val text = formatHumanToolsList(json, filterQuery = null)

        assertNoTerminalControl(text)
        assertTrue(text.contains("\\u001b"), "the escape must stay visible to the operator:\n$text")
    }

    @Test
    fun `an unparseable tool list response is not printed raw`() {
        val text = formatHumanToolsList("not json $esc[2J\nsecond line", filterQuery = null)

        assertNoTerminalControl(text)
    }

    @Test
    fun `tool detail neutralises the name, plugin id and description`() {
        val obj = tool("run$esc[2J", "plugin$esc[1m", "first\n$esc[2Jsecond\nAccess:   Standard")

        val text = formatHumanToolDetail(obj)

        assertNoTerminalControl(text)
        val accessLines = text.lines().filter { it.startsWith("Access:") }
        assertEquals(1, accessLines.size, "a description must not forge a second Access line:\n$text")
    }

    @Test
    fun `status neutralises the active project name and version`() {
        val json =
            buildJsonObject {
                put("version", "1.0$esc[2J")
                put("activeProject", "/home/me/proj$esc]0;pwned${bel}\nHealth:         OK")
            }.toString()

        val text = formatHumanStatus(json)

        assertNoTerminalControl(text)
        val healthLines = text.lines().filter { it.startsWith("Health:") }
        assertTrue(healthLines.isEmpty(), "a project name must not forge a Health line:\n$text")
    }

    @Test
    fun `doctor neutralises finding text and area names`() {
        val health =
            buildJsonObject {
                putJsonArray("findings") {
                    add(
                        buildJsonObject {
                            put("severity", "warn")
                            put("summary", "plugin evil$esc[2J failed")
                            put("remedy", "reload$esc]0;pwned$bel")
                        },
                    )
                }
                putJsonArray("unchecked") { add(JsonPrimitive("area$esc[31m")) }
            }

        val text = formatDoctorReport(health)

        assertNoTerminalControl(text)
        assertTrue(text.contains("\\u001b"), text)
    }
}
