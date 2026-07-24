package ai.rever.boss.utils

/**
 * Common formatting utilities for displaying human-readable values.
 */
object FormatUtils {
    /**
     * Formats bytes to human-readable string (e.g., "1.5 MB", "512 KB").
     * Uses binary units (1 KB = 1024 bytes).
     *
     * @param bytes The value in bytes
     * @return Formatted string with appropriate unit (B, KB, MB, GB, TB, PB)
     */
    fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes < 1024L * 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes < 1024L * 1024 * 1024 * 1024 * 1024 -> "%.2f TB".format(bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0))
            else -> "%.2f PB".format(bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0 * 1024.0))
        }

    /**
     * Formats megabytes to human-readable string.
     * Uses binary units (1 GB = 1024 MB).
     *
     * @param megabytes The value in megabytes
     * @param compact If true, uses shorter format without space (e.g., "1.5GB" vs "1.50 GB")
     * @return Formatted string with appropriate unit (MB, GB, TB, PB)
     */
    fun formatMegabytes(
        megabytes: Float,
        compact: Boolean = false,
    ): String =
        when {
            megabytes >= 1024 * 1024 * 1024 -> {
                // Petabytes
                val pb = megabytes / (1024 * 1024 * 1024)
                if (compact) "%.1fPB".format(pb) else "%.2f PB".format(pb)
            }

            megabytes >= 1024 * 1024 -> {
                // Terabytes
                val tb = megabytes / (1024 * 1024)
                if (compact) "%.1fTB".format(tb) else "%.2f TB".format(tb)
            }

            megabytes >= 1024 -> {
                // Gigabytes
                val gb = megabytes / 1024
                if (compact) "%.1fGB".format(gb) else "%.2f GB".format(gb)
            }

            else -> {
                // Megabytes
                if (compact) "${megabytes.toInt()}MB" else "${megabytes.toInt()} MB"
            }
        }

    /**
     * Formats speed to human-readable string (e.g., "1.5 MB/s").
     *
     * @param bytesPerSecond The speed in bytes per second
     * @return Formatted string with appropriate unit and "/s" suffix
     */
    fun formatSpeed(bytesPerSecond: Double): String = "${formatBytes(bytesPerSecond.toLong())}/s"
}
