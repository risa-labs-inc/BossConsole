package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.CoBrowseRtcProvider
import ai.rever.boss.plugin.api.ScreenCaptureProvider
import ai.rever.boss.plugin.api.UrlHistoryProvider
import ai.rever.boss.plugin.api.ZoomSettingsProvider

/**
 * Factory functions for browser-related providers.
 * Uses expect/actual pattern to allow desktop-specific implementations.
 */
expect fun createZoomSettingsProvider(): ZoomSettingsProvider

expect fun createUrlHistoryProvider(): UrlHistoryProvider

expect fun createScreenCaptureProvider(): ScreenCaptureProvider

/** WebRTC peer provider for low-latency co-browse transport; null if unsupported. */
expect fun createCoBrowseRtcProvider(): CoBrowseRtcProvider?
