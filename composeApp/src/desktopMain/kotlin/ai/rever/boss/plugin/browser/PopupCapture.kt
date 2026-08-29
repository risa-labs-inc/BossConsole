package ai.rever.boss.plugin.browser

/**
 * Captured details of a POST upload made by a popup browser, used to replay the same POST
 * when the popup is adopted as a new tab.
 *
 * Only ever carries a body. It deliberately does not get to choose where the tab goes - see
 * [popupDestination] for why.
 */
internal data class PopupCapture(
    val url: String,
    val body: ByteArray,
    val contentType: String,
)
