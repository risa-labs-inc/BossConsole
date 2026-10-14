package ai.rever.boss.sharing

/**
 * Source-over for tightly packed, premultiplied BGRA bytes. ScreenCaptureKit's BGRA alpha is
 * verified by the transparent-dialog native test. Opaque adapters (including current native
 * Windows BSC1 helper) remain compatible because their alpha is 255. Straight-alpha adapters
 * must normalize before supplying a frame; channel order alone does not establish alpha mode.
 */
internal fun blendPremultipliedBgra(
    frame: AppRawWindowFrame,
    destination: ByteArray,
    destinationWidth: Int,
    x: Int,
    y: Int,
) {
    require(frame.format == "BGRA")
    require(frame.width > 0 && frame.height > 0)
    require(frame.bgra.size.toLong() == frame.width.toLong() * frame.height * 4)
    require(destinationWidth > 0 && destination.size.toLong() % (destinationWidth.toLong() * 4) == 0L)
    val destinationHeight = destination.size / 4 / destinationWidth
    require(x in 0 until destinationWidth && y in 0 until destinationHeight)
    val width = minOf(frame.width, destinationWidth - x)
    val height = minOf(frame.height, destinationHeight - y)
    for (row in 0 until height) {
        blendBgraRow(frame.bgra, destination, row * frame.width * 4, ((y + row) * destinationWidth + x) * 4, width)
    }
}

private fun blendBgraRow(
    source: ByteArray,
    destination: ByteArray,
    sourceOffset: Int,
    destinationOffset: Int,
    width: Int,
) {
    var pixel = 0
    while (pixel < width) {
        val sourceAt = sourceOffset + pixel * 4
        val alpha = source[sourceAt + 3].toInt() and 255
        when (alpha) {
            0 -> {
                pixel++
            }

            255 -> {
                val start = pixel
                do {
                    pixel++
                } while (pixel < width && (source[sourceOffset + pixel * 4 + 3].toInt() and 255) == 255)
                source.copyInto(destination, destinationOffset + start * 4, sourceAt, sourceOffset + pixel * 4)
            }

            else -> {
                val destinationAt = destinationOffset + pixel * 4
                for (channel in 0..3) {
                    val foreground = source[sourceAt + channel].toInt() and 255
                    val background = destination[destinationAt + channel].toInt() and 255
                    val blended = foreground + (background * (255 - alpha) + 127) / 255
                    destination[destinationAt + channel] = blended.coerceAtMost(255).toByte()
                }
                pixel++
            }
        }
    }
}
