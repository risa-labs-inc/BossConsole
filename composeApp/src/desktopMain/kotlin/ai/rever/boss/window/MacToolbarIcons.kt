package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import com.sun.jna.Pointer
import com.sun.jna.Structure

/** Native images are retained here and accessed only on the AppKit queue. */
internal class MacToolbarIcons : AutoCloseable {
    private val images = mutableMapOf<String, Pair<ByteArray, Pointer>>()

    fun update(icons: Map<String, ByteArray>) {
        val obsolete = images.keys - icons.keys
        obsolete.forEach { id -> images.remove(id)?.second?.let { send(it, "release") } }
        icons.forEach { (id, png) ->
            if (images[id]?.first !== png) {
                val data = pointer(clazz("NSData"), "dataWithBytes:length:", png, png.size.toLong())
                val image = checkNotNull(pointer(pointer(clazz("NSImage"), "alloc"), "initWithData:", data))
                send(image, "setSize:", ToolbarIconSize(16.0, 16.0))
                send(image, "setTemplate:", if (id == "browser_url") 0.toByte() else 1.toByte())
                images.put(id, png to image)?.second?.let { send(it, "release") }
            }
        }
    }

    operator fun get(id: String): Pointer? = images[id]?.second

    override fun close() {
        images.values.forEach { send(it.second, "release") }
        images.clear()
    }
}

@Structure.FieldOrder("width", "height")
internal class ToolbarIconSize(
    @JvmField var width: Double = 0.0,
    @JvmField var height: Double = 0.0,
) : Structure(),
    Structure.ByValue
