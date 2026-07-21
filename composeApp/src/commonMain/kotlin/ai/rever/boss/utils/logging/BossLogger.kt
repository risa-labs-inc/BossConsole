@file:Suppress("UNUSED")
package ai.rever.boss.utils.logging

/**
 * Re-exports from plugin-logging module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.logging
 */

// Re-export all logging types from plugin-logging
typealias LogLevel = ai.rever.boss.plugin.logging.LogLevel
typealias LogCategory = ai.rever.boss.plugin.logging.LogCategory
typealias LogEntry = ai.rever.boss.plugin.logging.LogEntry
typealias LogListener = ai.rever.boss.plugin.logging.LogListener
typealias BossLoggerConfig = ai.rever.boss.plugin.logging.BossLoggerConfig
typealias ComponentLogger = ai.rever.boss.plugin.logging.ComponentLogger

// Re-export BossLogger object
// Note: We can't typealias an object, so we delegate to it
object BossLogger {
    private val delegate = ai.rever.boss.plugin.logging.BossLogger

    val globalLevel: LogLevel
        get() = delegate.globalLevel

    fun configureFromEnvironment() = delegate.configureFromEnvironment()
    fun initialize() = delegate.initialize()
    fun configure(config: BossLoggerConfig) = delegate.configure(config)
    fun setGlobalLevel(level: LogLevel) = delegate.setGlobalLevel(level)
    fun setCategoryLevel(category: LogCategory, level: LogLevel) = delegate.setCategoryLevel(category, level)
    fun clearCategoryLevel(category: LogCategory) = delegate.clearCategoryLevel(category)
    fun getEffectiveLevel(category: LogCategory): LogLevel = delegate.getEffectiveLevel(category)
    fun enableFileLogging(file: java.io.File) = delegate.enableFileLogging(file)
    fun disableFileLogging() = delegate.disableFileLogging()
    fun shutdown() = delegate.shutdown()
    fun addListener(listener: LogListener) = delegate.addListener(listener)
    fun removeListener(listener: LogListener) = delegate.removeListener(listener)
    fun getRecentLogs(limit: Int = 100, category: LogCategory? = null, minLevel: LogLevel = LogLevel.TRACE): List<LogEntry> =
        delegate.getRecentLogs(limit, category, minLevel)
    fun clearLogs() = delegate.clearLogs()
    fun forComponent(componentName: String): ComponentLogger = delegate.forComponent(componentName)
}
