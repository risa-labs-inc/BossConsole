package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon

internal const val CARD_FAVICON_MIN_SIZE = 64

internal fun hasSharpCardFavicon(icon: TabIcon.Image?): Boolean {
    val size = icon?.painter?.intrinsicSize ?: return false
    return size.width.isFinite() && size.height.isFinite() &&
        size.width >= CARD_FAVICON_MIN_SIZE && size.height >= CARD_FAVICON_MIN_SIZE
}
