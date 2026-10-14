package ai.rever.boss.utils

/**
 * Longest handler id, action name or parameter key BOSS will put in front of the
 * operator for confirmation. A token it cannot show in full is not one anybody
 * can meaningfully approve, so a longer one from outside the operator's own
 * invocation is dropped rather than prompted. Mirrors
 * [ai.rever.boss.cli.TERMINAL_CONFIRM_MAX_COMMAND_LENGTH], smaller because these
 * are identifiers rather than command lines.
 */
internal const val PLUGIN_ACTION_CONFIRM_MAX_TOKEN_LENGTH = 128

/**
 * Most parameter keys a confirmation prompt will list. Past this the operator is
 * reading a wall of names rather than deciding, so the request is refused.
 */
internal const val PLUGIN_ACTION_CONFIRM_MAX_PARAM_KEYS = 16

/** What BOSS does with a `boss://plugin?id=…&action=…` request. */
internal enum class PluginActionDisposition {
    /** Dispatch the action to the registered handler and report its verdict. */
    RUN,

    /** Show the operator what was asked and dispatch only if they confirm. */
    CONFIRM,

    /** Do nothing at all. */
    REJECT,
}

/**
 * Decides what happens to a plugin action request, from the request's shape and
 * who asked for it.
 *
 * The `boss://` scheme is registered with the OS, so an action link carries no
 * evidence of who made it: any web page or local program produces the same input
 * the operator's own shell does. An action therefore dispatches unattended only
 * when [origin] says the operator ran `boss` themselves. Anything else is put in
 * front of them first.
 *
 * A request whose own text cannot be displayed faithfully is refused for *every*
 * origin, not held: a handler id, action or parameter key carrying a control
 * character could forge extra lines in the prompt that is supposed to be
 * describing it. This mirrors [ai.rever.boss.cli.terminalCommandDisposition],
 * which likewise rejects a malformed command before it consults the origin.
 *
 * Parameter *values* are deliberately not examined. They never reach the prompt
 * — [ai.rever.boss.components.plugin.registries.DeepLinkActionRegistryImpl] logs
 * keys only because values may carry user data, and the same rule holds here:
 * showing an attacker-chosen value is how a prompt gets used to say something the
 * app did not mean. Handlers still own validation of the values they accept.
 */
internal fun pluginActionDisposition(
    handlerId: String,
    action: String,
    paramKeys: Collection<String>,
    origin: DeepLinkOrigin,
): PluginActionDisposition =
    when {
        handlerId.isBlank() || action.isBlank() -> PluginActionDisposition.REJECT
        !isDisplayableActionToken(handlerId) -> PluginActionDisposition.REJECT
        !isDisplayableActionToken(action) -> PluginActionDisposition.REJECT
        paramKeys.any { !isDisplayableActionToken(it) } -> PluginActionDisposition.REJECT
        origin.isOperatorInitiated -> PluginActionDisposition.RUN
        handlerId.length > PLUGIN_ACTION_CONFIRM_MAX_TOKEN_LENGTH -> PluginActionDisposition.REJECT
        action.length > PLUGIN_ACTION_CONFIRM_MAX_TOKEN_LENGTH -> PluginActionDisposition.REJECT
        paramKeys.size > PLUGIN_ACTION_CONFIRM_MAX_PARAM_KEYS -> PluginActionDisposition.REJECT
        paramKeys.any { it.length > PLUGIN_ACTION_CONFIRM_MAX_TOKEN_LENGTH } -> PluginActionDisposition.REJECT
        else -> PluginActionDisposition.CONFIRM
    }

/**
 * True when every character survives being written into a one-line prompt: no
 * blank token, no control character (so nothing can forge a line break), and no
 * invisible format character - a bidi override or U+2028 forges a line just as
 * well as a control character - so the token cannot mislead the prompt it is
 * named in.
 */
private fun isDisplayableActionToken(token: String): Boolean =
    token.isNotBlank() && token.none { it.category in HIDDEN_ACTION_TOKEN_CHARACTERS }

private val HIDDEN_ACTION_TOKEN_CHARACTERS =
    setOf(
        CharCategory.CONTROL,
        CharCategory.FORMAT,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
    )
