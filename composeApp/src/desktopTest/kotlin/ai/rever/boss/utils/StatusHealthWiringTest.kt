package ai.rever.boss.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The CLI tests replace the whole status JSON, so this pins the real response's `health` field. */
class StatusHealthWiringTest {
    @Test
    fun `the real status response carries the health report beside the existing fields`() {
        val health = buildJsonObject { put("degraded", false) }

        val response = buildStatusResponse(statusProviderOverride = null) { health }

        assertTrue(response.startsWith(RESPONSE_STATUS_PREFIX), response)
        val status = decode(response)
        assertEquals(health, status["health"])
        for (field in listOf("running", "version", "os", "arch", "activeProject", "memory")) {
            assertTrue(field in status, "status lost its existing field '$field'")
        }
    }

    @Test
    fun `a status override still replaces the whole response`() {
        val response = buildStatusResponse(statusProviderOverride = { """{"running":true}""" }) { error("not read") }

        assertEquals("""{"running":true}""", decode(response).toString())
    }

    private fun decode(response: String): JsonObject {
        val base64 = response.removePrefix(RESPONSE_STATUS_PREFIX).trim()
        return Json.parseToJsonElement(String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8)).jsonObject
    }
}
