package ai.rever.boss.utils.logging

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [decodeFailure] against real exceptions from the kotlinx version this build uses, not against
 * hand-written messages: each case first checks that the raw message really does carry the private
 * text, so a kotlinx upgrade that changes the wording shows up here instead of silently.
 */
class DecodeFailureTest {
    @Serializable
    private data class Page(
        val url: String,
        val visitCount: Int = 0,
    )

    @Serializable
    private data class Zoom(
        val levels: Map<String, Double> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private inline fun failureOf(decode: () -> Unit): SerializationException {
        try {
            decode()
        } catch (e: SerializationException) {
            return e
        }
        fail("the input was meant to fail to decode")
    }

    private fun assertWithheld(
        secret: String,
        error: SerializationException,
    ): Map<String, Any?> {
        assertTrue(secret in error.message.orEmpty(), "premise: kotlinx quotes it: ${error.message}")
        val fields = decodeFailure(error)
        assertFalse(secret in fields.toString(), "the log fields must not carry it: $fields")
        return fields
    }

    @Test
    fun `a torn file is reported by type and path without the document kotlinx appends`() {
        val secret = "token=s3cr3t-recent-page"
        val error = failureOf { json.decodeFromString<List<Page>>("""[{"url":"https://a.example/?$secret"""") }

        val fields = assertWithheld(secret, error)

        assertEquals("JsonDecodingException", fields["decodeFailure"])
        // A tear at the end of the file carries no offset in kotlinx's wording, only the path.
        assertEquals("$[0]", fields["path"])
    }

    @Test
    fun `a value quoted in the diagnostic itself is withheld too`() {
        // Not behind the JSON input marker: "Failed to parse literal '...'" quotes the value.
        val secret = "https://b.example/?token=quoted"
        val error = failureOf { json.decodeFromString<Page>("""{"url":"x","visitCount":"$secret"}""") }

        val fields = assertWithheld(secret, error)

        assertEquals("$.visitCount", fields["path"], "the path is structure and stays useful: $fields")
        assertEquals(25, fields["offset"])
    }

    @Test
    fun `a map key in the path is masked, because the zoom settings key by domain`() {
        val domain = "private-intranet.example"
        val error = failureOf { json.decodeFromString<Zoom>("""{"levels":{"$domain":"big"}}""") }

        val fields = assertWithheld(domain, error)

        assertEquals("$.levels[*]", fields["path"])
    }

    @Test
    fun `a map key with a space in it is masked whole`() {
        // A realistic key shape; the whole key, space and all, sits inside the masked segment.
        val key = "intranetbank login.example"
        val error = failureOf { json.decodeFromString<Zoom>("""{"levels":{"$key":"big"}}""") }

        val fields = assertWithheld("intranetbank", error)

        assertEquals("$.levels[*]", fields["path"])
    }

    // Review on #1703: a value kotlinx quotes comes before the genuine markers, so a value that
    // spells one must not be taken for it.
    @Test
    fun `a value that spells a path marker is not taken for the path`() {
        val secret = "at path: \$.stolenToken"
        val error = failureOf { json.decodeFromString<Page>("""{"url":"x","visitCount":"$secret"}""") }

        val fields = assertWithheld("stolenToken", error)

        assertEquals("$.visitCount", fields["path"])
    }

    // Review on #1703: the offset digits could come from the file, and toInt() on an oversized run
    // threw inside the caller's catch, skipping its recovery.
    @Test
    fun `an oversized offset in the file neither throws nor is logged`() {
        val title = "at offset 99999999999999999999"
        val torn = failureOf { json.decodeFromString<List<Page>>("""[{"url":"https://c.example","title":"$title"""") }
        val quoted = failureOf { json.decodeFromString<Page>("""{"url":"x","visitCount":"$title"}""") }

        assertEquals(null, assertWithheld("99999999999999999999", torn)["offset"])
        assertEquals(25, assertWithheld("99999999999999999999", quoted)["offset"], "the genuine offset still reads")
    }

    @Test
    fun `a map key holding a quote and a bracket cannot end the mask early`() {
        val error = failureOf { json.decodeFromString<Zoom>("""{"levels":{"safe']leakedpart":"big"}}""") }

        val fields = assertWithheld("leakedpart", error)

        assertEquals("$.levels[*]", fields["path"])
    }

    @Test
    fun `a map key holding a Unicode line separator is still masked`() {
        val error = failureOf { json.decodeFromString<Zoom>("{\"levels\":{\"line\u2028leakedpart\":\"big\"}}") }

        val fields = assertWithheld("leakedpart", error)

        assertEquals("$.levels[*]", fields["path"])
    }

    // Review on #1703: a value with a newline in it pushes the genuine path off the first line, and
    // the last marker left there is the one the value spelled.
    @Test
    fun `a value that spans lines cannot supply the path`() {
        val error =
            failureOf {
                json.decodeFromString<Zoom>("{\"levels\":{\"k\":\"a at path: \$.leakedpart\\nrest\"}}")
            }

        val fields = assertWithheld("leakedpart", error)

        assertFalse("path" in fields, "the first line ends inside the value, so no path is read: $fields")
    }

    // A marker inside a key is later than the genuine one, so it is what "last" finds. What
    // follows it is key text, not structure, so the path is left out rather than logged.
    @Test
    fun `a path marker inside a map key drops the path instead of logging the key`() {
        val error = failureOf { json.decodeFromString<Zoom>("""{"levels":{"k at path: $.leakedpart":"big"}}""") }

        val fields = assertWithheld("leakedpart", error)

        assertFalse("path" in fields, "no structural path could be read: $fields")
    }
}
