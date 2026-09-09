package ai.rever.boss.components.auth.forms

import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.plugin.browser.installBrowserChromeOrClose
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import boss_kotlin.composeapp.generated.resources.Res
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.navigation.event.LoadFinished
import com.teamdev.jxbrowser.view.compose.BrowserView
import com.teamdev.jxbrowser.view.compose.BrowserViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.Window
import java.nio.file.Files
import kotlin.io.path.writeBytes

private val logger = BossLogger.forComponent("AuthBrandSite")

/** Environment variable that opts a deployment OUT. */
private const val BRAND_SITE_KEY = "BOSS_AUTH_BRAND_SITE"

/** System property equivalent, so `-Dboss.auth.brand.site=false` works as well as the env var. */
private const val BRAND_SITE_PROPERTY = "boss.auth.brand.site"

/**
 * On unless a deployment turns it off, and the direction of that default is the whole subtlety.
 *
 * **It reads as an opt-OUT**, so the check is falsiness: `0`, `false`, `no` and `off` all disable it
 * (see [FluckEngine.isFalsyFlag]), matching the vocabulary of the browser-telemetry kill switch. A
 * default-on flag that only understood *truthy* values would have no off position at all - which is
 * exactly the failure that guard exists to prevent, and is why this is not a one-character change from
 * the opt-in version.
 *
 * `isNotBlank` on the env var for the same reason the telemetry switch has it: an env var set to the
 * empty string is still non-null, so `BOSS_AUTH_BRAND_SITE=` (a common way to "unset" one in a launcher
 * script) would otherwise shadow `-Dboss.auth.brand.site=false` and silently re-enable this.
 *
 * Read once per composition, never cached across launches.
 */
@Composable
internal actual fun authBrandSiteEnabled(): Boolean =
    remember {
        val env = System.getenv(BRAND_SITE_KEY)?.takeIf { it.isNotBlank() }
        !FluckEngine.isFalsyFlag(env ?: System.getProperty(BRAND_SITE_PROPERTY))
    }

/**
 * Unpacks the bundled page and its stylesheet to a temp directory and returns a `file:` URL.
 *
 * A file URL rather than `data:`, because the page links its stylesheet relatively - a data URL has no
 * base to resolve that against, and the sections would render unstyled. The two files are written
 * together for the same reason.
 *
 * `deleteOnExit` rather than an explicit cleanup: the page outlives the browser that loads it (Chromium
 * reads lazily), and the directory is a few tens of kilobytes in the system temp dir.
 */
private suspend fun unpackBrandPage(): String {
    val dir = withContext(Dispatchers.IO) { Files.createTempDirectory("boss-auth-brand") }
    dir.toFile().deleteOnExit()
    val page = dir.resolve("index.html")
    withContext(Dispatchers.IO) {
        page.writeBytes(Res.readBytes(AUTH_BRAND_PAGE))
        // Written by their own file names, because the page references them relatively: it links
        // "site.css" and "brand.js", so the names on disk are part of the contract.
        for (asset in AUTH_BRAND_ASSETS) {
            val target = dir.resolve(asset.substringAfterLast('/'))
            target.writeBytes(Res.readBytes(asset))
            target.toFile().deleteOnExit()
        }
    }
    page.toFile().deleteOnExit()
    return page.toUri().toString()
}

// Broad catches on purpose, both of them: this panel is decoration on the one screen a user cannot
// get past, so ANY way the engine can fail - no licence, headless, a Chromium that did not unpack, a
// browser closed under us - has to end at the drawn art rather than at a crashed sign-in screen.
// Narrowing to JxBrowser's own exception types would let exactly the unforeseen failure through.
@Suppress("TooGenericExceptionCaught")
@Composable
internal actual fun AuthBrandSite(
    onReady: () -> Unit,
    onFailed: () -> Unit,
) {
    var browser by remember { mutableStateOf<Browser?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // One effect owns the browser for its whole life: created here, closed in this function's own
    // `finally`. It used to be created here and closed by a separate `DisposableEffect(current)`, which
    // leaked - that effect is keyed on a value that is still null until the recomposition AFTER creation,
    // so a panel leaving composition in that window (a resize under 900dp, a navigation) left a Chromium
    // instance open with nothing holding a reference to close it.
    LaunchedEffect(Unit) {
        var created: Browser? = null
        try {
            val page = unpackBrandPage()
            // OFF THE UI THREAD. `FluckEngine.engine` is a synchronous, multi-second Chromium boot under
            // a lock - `CrossDeviceBrowserManager` documents exactly that, which is why the engine is not
            // booted on the startup path either. A LaunchedEffect body runs on the composition
            // dispatcher, so calling it directly froze the sign-in screen, email field included, for as
            // long as the boot took. The startup prewarm does not save it: that is skipped entirely on a
            // first-ever launch, when no browser profile exists yet, and mid-prewarm this would block on
            // the same lock.
            created =
                withContext(Dispatchers.IO) {
                    FluckEngine.engine.newBrowser().also { installBrowserChromeOrClose(it) }
                }
            created.navigation().on(LoadFinished::class.java) {
                scope.launch(Dispatchers.Main) { loaded = true }
            }
            created.navigation().loadUrl(page)
            browser = created
            // Park until cancelled, so the `finally` below is this panel's disposal hook.
            awaitCancellation()
        } catch (e: CancellationException) {
            // Normal disposal, not a failure: no report, and the `finally` still closes the browser.
            throw e
        } catch (e: Throwable) {
            // THROWABLE, NOT EXCEPTION, and that is the whole point of catching here. The engine's
            // characteristic failure is `UnsatisfiedLinkError` from a Chromium bundle that does not match
            // this build - a `LinkageError`, which an `Exception` catch lets straight through into the
            // composition. `FluckEngine.prewarmInBackground` catches `Throwable` and names that error for
            // the same reason. A crashed sign-in screen on the machines least able to recover from it is
            // the one outcome this panel must never cause.
            logger.warn(
                LogCategory.BROWSER,
                "Brand page unavailable; keeping the drawn panel",
                error = e,
            )
            onFailed()
        } finally {
            // The login screen is transient - it goes away on the first successful sign-in - so this
            // browser must not outlive it. A leaked Chromium instance per sign-in attempt would be the
            // worst kind of cost for a decorative panel.
            runCatching { created?.close() }
                .onFailure { logger.warn(LogCategory.BROWSER, "Error closing brand page browser", error = it) }
        }
    }

    val current = browser
    // Reveal only once the page has actually painted; the art stays up until then.
    LaunchedEffect(current, loaded) {
        if (current != null && loaded) onReady()
    }

    if (current == null) return
    val localWindow = LocalAwtWindow.current
    val window =
        remember(localWindow) {
            localWindow ?: Window.getWindows().firstOrNull() ?: Frame()
        }
    // Guarded, because attaching the view is its own failure mode and it is NOT covered by the catch
    // around the engine above. `BrowserViewState` asks the AWT window for its display, which throws
    // "Can't obtain the display ID of a closed window" when there is no usable window - and an
    // exception here escapes composition and takes the whole sign-in screen with it, which is the one
    // outcome this panel must never cause. Falling through leaves the art, like every other failure.
    val state =
        remember(current, window) {
            runCatching { BrowserViewState(current, MainScope(), window) }
                .onFailure { e ->
                    logger.warn(
                        LogCategory.BROWSER,
                        "Brand page view could not attach; keeping the drawn panel",
                        error = e,
                    )
                }.getOrNull()
        }
    if (state == null) {
        // Reported outside the remember, so it survives the recomposition that reads it.
        LaunchedEffect(Unit) { onFailed() }
        return
    }
    BrowserView(state = state, modifier = Modifier.fillMaxSize())
}
