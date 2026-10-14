package ai.rever.boss.components.common

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.State
import kotlinx.coroutines.CancellationException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

private val faviconLogger = BossLogger.forComponent("FaviconLoader")

private data class FaviconTabGetters(
    val cacheKey: Method?,
    val currentUrl: Method?,
    val initialUrl: Method?,
)

// ClassValue allows an unloaded plugin classloader to be collected. Java getters avoid loading
// kotlin-reflect or enumerating every class member on the composition thread.
private val faviconTabGetters =
    object : ClassValue<FaviconTabGetters>() {
        override fun computeValue(type: Class<*>): FaviconTabGetters =
            FaviconTabGetters(
                faviconGetter(type, "getFaviconCacheKey"),
                faviconGetter(type, "getCurrentUrl"),
                faviconGetter(type, "getInitialUrl"),
            )
    }

private fun faviconGetter(
    type: Class<*>,
    name: String,
): Method? =
    try {
        type.getMethod(name).also { it.trySetAccessible() }
    } catch (_: NoSuchMethodException) {
        null
    }

internal actual fun dynamicFaviconCacheKey(tabInfo: TabInfo): String? = readFaviconProperty(tabInfo) { it.cacheKey }

internal actual fun dynamicBrowserFaviconUrl(tabInfo: TabInfo): String? =
    // Only legacy tabs with NO currentUrl getter use initialUrl. Blank, null, unsupported or
    // failing currentUrl means no current page; falling back would resurrect old artwork on Home.
    readFaviconProperty(tabInfo) { it.currentUrl ?: it.initialUrl }

private fun readFaviconProperty(
    tabInfo: TabInfo,
    getter: (FaviconTabGetters) -> Method?,
): String? =
    try {
        val method = getter(faviconTabGetters.get(tabInfo.javaClass))
        val value = method?.invoke(tabInfo)
        val unwrapped = if (value is State<*>) value.value else value
        if (unwrapped != null && unwrapped !is String) {
            logFaviconGetterType("UnsupportedValue")
        }
        unwrapped as? String
    } catch (e: InvocationTargetException) {
        logFaviconGetterFailure(e)
        null
    } catch (e: ReflectiveOperationException) {
        logFaviconGetterFailure(e)
        null
    } catch (e: SecurityException) {
        logFaviconGetterFailure(e)
        null
    } catch (e: LinkageError) {
        logFaviconGetterFailure(e)
        null
    }

private fun logFaviconGetterFailure(error: Throwable) {
    val cause = if (error is InvocationTargetException) error.cause ?: error else error
    if (cause is CancellationException || cause is VirtualMachineError || cause is ThreadDeath) throw cause
    logFaviconGetterType(cause.javaClass.simpleName)
}

private fun logFaviconGetterType(errorType: String) {
    faviconLogger.debug(
        LogCategory.BROWSER,
        "Dynamic favicon getter unavailable",
        // Getter exceptions can contain the URL or credentials; log only their type.
        mapOf("errorType" to errorType),
    )
}
