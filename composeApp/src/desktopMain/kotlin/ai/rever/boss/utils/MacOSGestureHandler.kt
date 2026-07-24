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

    // Threshold for triggering zoom - accumulate this much gesture magnitude before firing
    // Value 0.15 chosen empirically to match Safari's feel (not too sensitive, not too sluggish)
    private const val ZOOM_THRESHOLD = 0.15

    /**
     * Check if macOS gesture APIs are available
     */
    fun isSupported(): Boolean {
        if (isAvailable != null) return isAvailable!!

        val os = System.getProperty("os.name").lowercase()
        isAvailable = try {
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
     * @param component The Swing component to listen on
     * @param onZoomIn Called when user pinches out (zoom in)
     * @param onZoomOut Called when user pinches in (zoom out)
     * @return An opaque registration token to pass to [removeMagnificationListener],
     *         or null if gestures are unsupported or registration failed
     */
    fun addMagnificationListener(
        component: Component,
        onZoomIn: () -> Unit,
        onZoomOut: () -> Unit
    ): Any? {
        if (!isSupported()) return null

        return try {
            val gestureUtilitiesClass = Class.forName("com.apple.eawt.event.GestureUtilities")
            val magnificationListenerClass = Class.forName("com.apple.eawt.event.MagnificationListener")
            val magnificationEventClass = Class.forName("com.apple.eawt.event.MagnificationEvent")

            // Accumulator for smooth zooming (like Safari). Per-listener state so
            // several registered listeners don't feed a shared accumulator and
            // trip the threshold N times faster than a single one would.
            val accumulatorLock = Any()
            var magnificationAccumulator = 0.0

            // Create a dynamic proxy for MagnificationListener
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                magnificationListenerClass.classLoader,
                arrayOf(magnificationListenerClass)
            ) { proxy, method, args ->
                if (method.name == "magnify" && args != null && args.isNotEmpty()) {
                    val event = args[0]
                    val getMagnification = magnificationEventClass.getMethod("getMagnification")
                    val magnification = getMagnification.invoke(event) as Double

                    // Use synchronized to prevent race condition between gesture thread and UI thread
                    var shouldZoomIn = false
                    var shouldZoomOut = false

                    synchronized(accumulatorLock) {
                        magnificationAccumulator += magnification

                        // Only trigger zoom when accumulated enough
                        if (magnificationAccumulator >= ZOOM_THRESHOLD) {
                            shouldZoomIn = true
                            magnificationAccumulator = 0.0
                        } else if (magnificationAccumulator <= -ZOOM_THRESHOLD) {
                            shouldZoomOut = true
                            magnificationAccumulator = 0.0
                        }
                    }

                    // Fire callbacks outside synchronized block to avoid holding lock during UI work
                    SwingUtilities.invokeLater {
                        if (shouldZoomIn) onZoomIn()
                        else if (shouldZoomOut) onZoomOut()
                    }
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
            val addMethod = gestureUtilitiesClass.getMethod(
                "addGestureListenerTo",
                JComponent::class.java,
                Class.forName("com.apple.eawt.event.GestureListener")
            )

            if (component is JComponent) {
                addMethod.invoke(null, component, listener)
                listener
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug(LogCategory.UI,
                "Failed to register magnification listener via reflection - pinch zoom disabled for component",
                mapOf("error" to e.toString()))
            null
        }
    }

    /**
     * Remove a magnification listener previously registered with [addMagnificationListener].
     *
     * @param component The component the listener was added to
     * @param listener The registration token returned by [addMagnificationListener]
     */
    fun removeMagnificationListener(component: Component, listener: Any) {
        if (!isSupported()) return

        try {
            val gestureUtilitiesClass = Class.forName("com.apple.eawt.event.GestureUtilities")
            val removeMethod = gestureUtilitiesClass.getMethod(
                "removeGestureListenerFrom",
                JComponent::class.java,
                Class.forName("com.apple.eawt.event.GestureListener")
            )
            if (component is JComponent) {
                removeMethod.invoke(null, component, listener)
            }
        } catch (_: Exception) {
            // Best-effort; the proxy becomes unreachable either way
        }
    }
}
