package ai.rever.boss.components.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * Host-side registry carrying browser audio-playback state into the tab model
 * (issue #308).
 *
 * The speaker glyph on a tab renders from
 * [ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo.isPlayingAudio], and the
 * one thing that knows playback started is the browser — owned by the
 * dynamic fluck-browser plugin's tab component, a class the host cannot name
 * (the same reason `ActiveBrowserRegistry` exists). But the plugin DOES tell
 * the host which tab owns each browser (`BrowserHandle.setFullscreenHandler`),
 * so `BrowserHandleImpl` pushes audio events here by tab id, and the owning
 * `BossTabsComponent` — which registered a handler in [register] — updates its
 * tab model the way title/favicon already arrive.
 *
 * Last-writer-wins per tab id, mirroring `TabUpdateRegistry.registerTab`: a tab
 * moved between panels is re-registered by the destination component in
 * `adoptTab`, so the source's stale handler loses the key atomically and the
 * glyph follows the tab. Unregistration is ownership-checked (`remove(k, v)`)
 * so a source panel's close can't wipe a destination's fresh handler.
 *
 * Threading: writes arrive from JxBrowser event threads; handlers are invoked
 * as-is and their bodies must hop to the UI thread (the BossTabsComponent
 * handler mutates snapshot state — see its KDoc).
 */
object TabAudioRegistry {
    private val handlers = ConcurrentHashMap<String, (Boolean) -> Unit>()

    /** Register the handler that applies playback state to [tabId]'s tab model. */
    fun register(
        tabId: String,
        handler: (Boolean) -> Unit,
    ) {
        handlers[tabId] = handler
    }

    /**
     * Remove [handler] for [tabId]. Atomically a no-op if a move already
     * re-registered another handler for the same id — the same ownership rule
     * `TabUpdateRegistry.unregisterTab` applies.
     */
    fun unregister(
        tabId: String,
        handler: (Boolean) -> Unit,
    ) {
        handlers.remove(tabId, handler)
    }

    /** Push a playback-state change to [tabId]'s registered handler, if any. */
    fun update(
        tabId: String,
        playing: Boolean,
    ) {
        handlers[tabId]?.invoke(playing)
    }

    /** Clear all registrations. Used for testing. */
    fun clear() {
        handlers.clear()
    }
}
