package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `boss://plugin?id=…&action=…` link is indistinguishable from the operator's
 * own once it reaches a handler, so [pluginActionDisposition] is the one place
 * the distinction is acted on.
 */
class PluginActionDispositionTest {
    private fun disposition(
        origin: DeepLinkOrigin,
        handlerId: String = "my.plugin",
        action: String = "sync",
        paramKeys: Collection<String> = emptyList(),
    ) = pluginActionDisposition(handlerId, action, paramKeys, origin)

    @Test
    fun `the operator's own invocation runs the action`() {
        assertEquals(PluginActionDisposition.RUN, disposition(DeepLinkOrigin.OPERATOR_CLI))
        assertEquals(
            PluginActionDisposition.RUN,
            disposition(DeepLinkOrigin.OPERATOR_CLI, paramKeys = listOf("scope", "force")),
        )
    }

    @Test
    fun `anything else is put in front of the operator first`() {
        assertEquals(PluginActionDisposition.CONFIRM, disposition(DeepLinkOrigin.EXTERNAL))
        assertEquals(
            PluginActionDisposition.CONFIRM,
            disposition(DeepLinkOrigin.EXTERNAL, paramKeys = listOf("scope", "force")),
        )
    }

    @Test
    fun `a blank handler id or action is refused whoever asked`() {
        for (origin in DeepLinkOrigin.entries) {
            assertEquals(PluginActionDisposition.REJECT, disposition(origin, handlerId = ""))
            assertEquals(PluginActionDisposition.REJECT, disposition(origin, handlerId = "   "))
            assertEquals(PluginActionDisposition.REJECT, disposition(origin, action = ""))
            assertEquals(PluginActionDisposition.REJECT, disposition(origin, action = "  "))
        }
    }

    @Test
    fun `a token that cannot be displayed faithfully is refused whoever asked`() {
        // A control character could forge extra lines in the prompt that is
        // supposed to be describing the request, so this is refused even for the
        // operator's own link, exactly as a malformed terminal command is.
        for (origin in DeepLinkOrigin.entries) {
            assertEquals(PluginActionDisposition.REJECT, disposition(origin, action = "sync\nrm -rf /"))
            assertEquals(PluginActionDisposition.REJECT, disposition(origin, handlerId = "my\r.plugin"))
            assertEquals(PluginActionDisposition.REJECT, disposition(origin, action = "sync" + Char(0)))
            assertEquals(PluginActionDisposition.REJECT, disposition(origin, paramKeys = listOf("ok", "bad\nkey")))
            assertEquals(PluginActionDisposition.REJECT, disposition(origin, action = "sync\u2028forged"))
            assertEquals(PluginActionDisposition.REJECT, disposition(origin, handlerId = "safe\u202Eevil"))
        }
    }

    @Test
    fun `a request too large to show in full is refused rather than prompted`() {
        val longToken = "a".repeat(PLUGIN_ACTION_CONFIRM_MAX_TOKEN_LENGTH + 1)
        val atLimit = "a".repeat(PLUGIN_ACTION_CONFIRM_MAX_TOKEN_LENGTH)
        assertEquals(PluginActionDisposition.REJECT, disposition(DeepLinkOrigin.EXTERNAL, action = longToken))
        assertEquals(PluginActionDisposition.REJECT, disposition(DeepLinkOrigin.EXTERNAL, handlerId = longToken))
        assertEquals(
            PluginActionDisposition.REJECT,
            disposition(DeepLinkOrigin.EXTERNAL, paramKeys = listOf(longToken)),
        )
        assertEquals(
            PluginActionDisposition.REJECT,
            disposition(
                DeepLinkOrigin.EXTERNAL,
                paramKeys = List(PLUGIN_ACTION_CONFIRM_MAX_PARAM_KEYS + 1) { "k$it" },
            ),
        )

        // The bound is inclusive, and it only exists because the request has to
        // be shown: a link the operator typed themselves is never displayed.
        assertEquals(PluginActionDisposition.CONFIRM, disposition(DeepLinkOrigin.EXTERNAL, action = atLimit))
        assertEquals(
            PluginActionDisposition.CONFIRM,
            disposition(DeepLinkOrigin.EXTERNAL, paramKeys = List(PLUGIN_ACTION_CONFIRM_MAX_PARAM_KEYS) { "k$it" }),
        )
        assertEquals(PluginActionDisposition.RUN, disposition(DeepLinkOrigin.OPERATOR_CLI, action = longToken))
        assertEquals(
            PluginActionDisposition.RUN,
            disposition(
                DeepLinkOrigin.OPERATOR_CLI,
                paramKeys = List(PLUGIN_ACTION_CONFIRM_MAX_PARAM_KEYS + 1) { "k$it" },
            ),
        )
    }

    @Test
    fun `an unstated origin gets the cautious answer`() {
        // DeepLinkOrigin.fromWireLabel maps anything unrecognised to EXTERNAL, so
        // a new caller that forgets to say never gets an unattended dispatch.
        assertEquals(
            PluginActionDisposition.CONFIRM,
            disposition(DeepLinkOrigin.fromWireLabel(null)),
        )
        assertEquals(
            PluginActionDisposition.CONFIRM,
            disposition(DeepLinkOrigin.fromWireLabel("something-new")),
        )
    }
}
