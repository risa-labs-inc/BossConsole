package ai.rever.boss.logging

/**
 * Global singleton for log capture.
 *
 * This starts capturing logs from app startup (initialized in main.kt)
 * so that the Console panel can show ALL logs, not just logs after the panel opens.
 *
 * Usage:
 * - main.kt: Call GlobalLogCapture.start() at app startup
 * - LogDataProviderImpl: Use GlobalLogCapture.getLogCapture() to access captured logs
 */
object GlobalLogCapture {
    private val logCapture = DesktopLogCapture()

    /**
     * Start capturing logs globally from app startup.
     * Should be called once in main.kt.
     */
    fun start() {
        logCapture.start()
    }

    /**
     * Get the global log capture instance.
     * Used by LogDataProviderImpl to access captured logs.
     */
    fun getLogCapture(): DesktopLogCapture = logCapture
}
