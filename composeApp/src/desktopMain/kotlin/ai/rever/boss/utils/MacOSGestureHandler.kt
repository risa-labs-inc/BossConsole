package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.Component
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * macOS-specific gesture handler for trackpad pinch-to-zoom gestures.
 *
 * Uses com.apple.eawt.event.GestureUtilities which is available on macOS JVMs.
 * Requires JVM args: --add-opens java.desktop/com.apple.eawt.event=ALL-UNNAMED
 *
 * This is the standard way to handle trackpad magnification gestures on macOS
 * since they are NOT delivered as Ctrl+Wheel events to Java applications.
 */
object MacOSGestureHandler {
    private val logger = BossLogger.forComponent("MacOSGestureHandler")

    private var isAvailable: Boolean? = null

    /**
     * Check if macOS gesture APIs are available
     */
    fun isSupported(): Boolean {
        if (isAvailable != null) return isAvailable!!

        val os = System.getProperty("os.name").lowercase()
        isAvailable =
            try {
                if (!os.contains("mac")) {
                    false
                } else {
                    Class.forName("com.apple.eawt.event.GestureUtilities")
                    true
                }
            } catch (e: Exception) {
                logger.debug(
                    LogCategory.UI,
                    "GestureUtilities unavailable - trackpad pinch gestures disabled",
                    mapOf("error" to e.toString()),
                )
                false
            }

        return isAvailable!!
    }

    /**
     * Add a magnification (pinch) gesture listener to a component.
     *
     * Delivers every raw magnification delta rather than zoom steps, because the caller has to
     * offer each one to the page before it knows whether to zoom at all. Callers that do zoom
     * smooth the deltas with a [PinchZoomAccumulator].
     *
     * @param component The Swing component to listen on
     * @param onMagnify Called on the EDT with each event's magnification; positive when the
     *        user pinches out (zoom in), negative when they pinch in (zoom out)
     * @return An opaque registration token to pass to [removeMagnificationListener],
     *         or null if gestures are unsupported or registration failed
     */
    fun addMagnificationListener(
        component: Component,
        onMagnify: (Double) -> Unit,
    ): Any? {
        if (!isSupported()) return null

        return try {
            val gestureUtilitiesClass = Class.forName("com.apple.eawt.event.GestureUtilities")
            val magnificationListenerClass = Class.forName("com.apple.eawt.event.MagnificationListener")
            val magnificationEventClass = Class.forName("com.apple.eawt.event.MagnificationEvent")

            // Create a dynamic proxy for MagnificationListener
            val listener =
                java.lang.reflect.Proxy.newProxyInstance(
                    magnificationListenerClass.classLoader,
                    arrayOf(magnificationListenerClass),
                ) { proxy, method, args ->
                    if (method.name == "magnify" && args != null && args.isNotEmpty()) {
                        val event = args[0]
                        val getMagnification = magnificationEventClass.getMethod("getMagnification")
                        val magnification = getMagnification.invoke(event) as Double

                        SwingUtilities.invokeLater { onMagnify(magnification) }
                    } else if (method.name == "toString") {
                        return@newProxyInstance "MacOSGestureHandler.MagnificationListener"
                    } else if (method.name == "hashCode") {
                        return@newProxyInstance System.identityHashCode(proxy)
                    } else if (method.name == "equals") {
                        // Identity equals is load-bearing: Apple's GestureHandler stores
                        // listeners in a LinkedList and removeGestureListenerFrom uses
                        // List.remove(Object), which calls equals. Always-false equals
                        // would make removal a silent no-op and leak the listener.
                        return@newProxyInstance args != null && args.isNotEmpty() && proxy === args[0]
                    }
                    null
                }

            // Call GestureUtilities.addGestureListenerTo(component, listener)
            val addMethod =
                gestureUtilitiesClass.getMethod(
                    "addGestureListenerTo",
                    JComponent::class.java,
                    Class.forName("com.apple.eawt.event.GestureListener"),
                )

            if (component is JComponent) {
                addMethod.invoke(null, component, listener)
                listener
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.UI,
                "Failed to register magnification listener via reflection - pinch zoom disabled for component",
                mapOf("error" to e.toString()),
            )
            null
        }
    }

    /**
     * Remove a magnification listener previously registered with [addMagnificationListener].
     *
     * @param component The component the listener was added to
     * @param listener The registration token returned by [addMagnificationListener]
     */
    fun removeMagnificationListener(
        component: Component,
        listener: Any,
    ) {
        if (!isSupported()) return

        try {
            val gestureUtilitiesClass = Class.forName("com.apple.eawt.event.GestureUtilities")
            val removeMethod =
                gestureUtilitiesClass.getMethod(
                    "removeGestureListenerFrom",
                    JComponent::class.java,
                    Class.forName("com.apple.eawt.event.GestureListener"),
                )
            if (component is JComponent) {
                removeMethod.invoke(null, component, listener)
            }
        } catch (_: Exception) {
            // Best-effort; the proxy becomes unreachable either way
        }
    }
}

/**
 * Turns a stream of raw pinch magnification deltas into discrete page-zoom steps.
 *
 * One instance per listener, so several registered listeners don't feed a shared total and trip
 * the threshold N times faster than a single one would. BrowserHandleImpl only calls it on the
 * EDT; it is synchronized anyway so a caller on another thread cannot corrupt the total.
 */
internal class PinchZoomAccumulator(
    private val threshold: Double = ZOOM_THRESHOLD,
) {
    enum class Step { IN, OUT }

    private var total = 0.0

    /** Adds [magnification] and returns the step it completes, if any. */
    @Synchronized
    fun add(magnification: Double): Step? {
        total += magnification
        return when {
            total >= threshold -> Step.IN.also { total = 0.0 }
            total <= -threshold -> Step.OUT.also { total = 0.0 }
            else -> null
        }
    }

    /**
     * Drops a partial step. Called when the page claims a pinch, so a gesture it handled cannot
     * leave behind a remainder that tips a later, unclaimed delta into a page zoom.
     */
    @Synchronized
    fun reset() {
        total = 0.0
    }

    companion object {
        // Accumulate this much gesture magnitude before firing a step. Value 0.15 chosen
        // empirically to match Safari's feel (not too sensitive, not too sluggish)
        const val ZOOM_THRESHOLD = 0.15
    }
}
