package ai.rever.boss.components.plugin

import ai.rever.boss.ipc.proto.PluginStateDelta
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PluginStateDeltaTest {
    private fun delta(
        patch: String,
        base: Long = 3,
        next: Long = 4,
    ): PluginStateDelta =
        PluginStateDelta
            .newBuilder()
            .setBaseVersion(base)
            .setNewVersion(next)
            .setPatchBytes(ByteString.copyFromUtf8(patch))
            .build()

    @Test
    fun `merge patch preserves siblings removes null members and replaces arrays`() {
        val result =
            mergePluginStateDelta(
                """{"a":{"keep":1,"remove":2},"list":[1,2],"untouched":true}""".encodeToByteArray(),
                3,
                delta("""{"a":{"remove":null,"add":3},"list":[4],"missing":null}"""),
            )!!
        assertEquals(
            Json.parseToJsonElement("""{"a":{"keep":1,"add":3},"list":[4],"untouched":true}"""),
            Json.parseToJsonElement(result.decodeToString()),
        )
    }

    @Test
    fun `non object patch replaces entire document including null`() {
        for (patch in listOf("null", "[]", "42", "\"value\"")) {
            assertEquals(patch, mergePluginStateDelta("{}".encodeToByteArray(), 3, delta(patch))!!.decodeToString())
        }
    }

    @Test
    fun `object patch converts scalar target to object recursively`() {
        assertEquals(
            """{"a":{}}""",
            mergePluginStateDelta("42".encodeToByteArray(), 3, delta("""{"a":{"b":null}}"""))!!.decodeToString(),
        )
    }

    @Test
    fun `missing snapshot and mismatched base require resynchronization`() {
        assertFailsWith<IllegalArgumentException> { mergePluginStateDelta(byteArrayOf(), 0, delta("{}", 0, 1)) }
        for (base in listOf(2L, 4L)) {
            assertFailsWith<IllegalArgumentException> {
                mergePluginStateDelta("{}".encodeToByteArray(), 3, delta("{}", base))
            }
        }
    }

    @Test
    fun `stale deltas are ignored before decoding even with a mismatched base`() {
        for (next in listOf(2L, 3L)) {
            assertNull(mergePluginStateDelta("{}".encodeToByteArray(), 3, delta("invalid", 1, next)))
        }
    }

    @Test
    fun `malformed state or patch requires resynchronization`() {
        assertFailsWith<IllegalArgumentException> {
            mergePluginStateDelta("invalid".encodeToByteArray(), 3, delta("{}"))
        }
        assertFailsWith<IllegalArgumentException> {
            mergePluginStateDelta("{}".encodeToByteArray(), 3, delta("invalid"))
        }
    }

    @Test
    fun `invalid UTF8 cannot silently corrupt a string value`() {
        val malformed = byteArrayOf(34, -1, 34)
        val patch = delta("{}").toBuilder().setPatchBytes(ByteString.copyFrom(malformed)).build()
        assertFails { mergePluginStateDelta("{}".encodeToByteArray(), 3, patch) }
        assertFails { mergePluginStateDelta(malformed, 3, delta("{}")) }
    }

    @Test
    fun `invalid nested literals and excessive depth require resynchronization`() {
        for (patch in listOf("{\"a\":invalid}", "[NaN]", "01", "[".repeat(130) + "0" + "]".repeat(130))) {
            assertFailsWith<IllegalArgumentException> {
                mergePluginStateDelta("{}".encodeToByteArray(), 3, delta(patch))
            }
        }
    }

    @Test
    fun `valid JSON numeric and boolean literals remain valid patches`() {
        for (patch in listOf("true", "false", "-0.25e+3", "0", "12E-2")) {
            assertEquals(patch, mergePluginStateDelta("{}".encodeToByteArray(), 3, delta(patch))!!.decodeToString())
        }
    }

    @Test
    fun `bridge accepts an initial zero snapshot but rejects later stale snapshots`() {
        val channel = ManagedChannelBuilder.forAddress("localhost", 1).usePlaintext().build()
        val bridge = PluginStateBridge("plugin", "instance", channel)
        try {
            bridge.applyState("{\"initial\":true}".encodeToByteArray(), 0)
            assertEquals("{\"initial\":true}", bridge.state.value.decodeToString())
            bridge.applyState("{\"newer\":true}".encodeToByteArray(), 1)
            bridge.applyState("{}".encodeToByteArray(), 0)
            assertEquals(1L, bridge.version.value)
            assertEquals("{\"newer\":true}", bridge.state.value.decodeToString())
        } finally {
            bridge.dispose()
            channel.shutdownNow()
        }
    }

    @Test
    fun `a version zero snapshot is a valid patch base`() {
        assertEquals("{}", mergePluginStateDelta("{}".encodeToByteArray(), 0, delta("{}", 0, 1))!!.decodeToString())
    }
}
