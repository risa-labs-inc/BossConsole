package ai.rever.boss.app

import ai.rever.boss.layout.TrafficLightInset
import ai.rever.boss.layout.bannerStartInset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Sharing and update notices reserve a row even when focus mode hides the top bar. */
internal fun sidebarHasTopChrome(
    updateBannerVisible: Boolean,
    sharingChromeVisible: Boolean,
    topBarVisible: Boolean,
): Boolean = updateBannerVisible || sharingChromeVisible || topBarVisible

/** The update banner is above sharing, so only the topmost notice clears the traffic lights. */
internal fun sharingChromeStartInset(
    updateBannerVisible: Boolean,
    trafficLights: TrafficLightInset,
): Dp = if (updateBannerVisible) 0.dp else trafficLights.bannerStartInset()
