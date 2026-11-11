package ai.rever.boss.plugin.browser.autofill

import com.teamdev.jxbrowser.js.JsAccessible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Host-side record of the last submitted login per origin, so a SavePasswordCallback (which carries
 * url + login only) can be paired with the value. In memory only, short-lived, never logged.
 */
internal class CredentialCapture(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
) {
    class Captured(
        val origin: String,
        val username: String,
        val password: String,
        val atMs: Long,
    ) {
        override fun toString() = "Captured($origin)"
    }

    private val entries = ArrayDeque<Captured>()

    @Synchronized
    fun record(
        pageUrl: String,
        username: String,
        password: String,
    ) {
        val origin = AutofillMapping.normalizeOrigin(pageUrl) ?: return
        if (password.isEmpty() || password.length > MAX_VALUE || username.length > MAX_VALUE) return
        prune()
        entries.removeAll { it.origin == origin && sameLogin(it.username, username) }
        entries.addLast(Captured(origin, username.trim(), password, clock()))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    /**
     * The captured value for a Chromium save of ([url], [login]): same origin and same login.
     * A capture with no username only pairs with a blank login.
     */
    @Synchronized
    @Suppress("ReturnCount")
    fun take(
        url: String,
        login: String,
    ): Captured? {
        prune()
        val origin = AutofillMapping.normalizeOrigin(url) ?: return null
        val hit = entries.lastOrNull { it.origin == origin && sameLogin(it.username, login) } ?: return null
        entries.remove(hit)
        return hit
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun prune() {
        val now = clock()
        entries.removeAll { now - it.atMs !in 0..ttlMs }
    }

    companion object {
        const val TTL_MS = 3 * 60_000L
        const val MAX_ENTRIES = 16
        const val MAX_VALUE = 1024

        fun sameLogin(
            a: String,
            b: String,
        ) = a.trim().equals(b.trim(), ignoreCase = true)

        /**
         * Document-start script. Records (username, password) when a login is submitted: a form
         * submit, a click on a submit-like control, or Enter in a field, while a password field
         * holds a value. Values go only to the host bridge.
         */
        val SCRIPT: String =
            """
            (function (bridge) {
              if (!bridge || window.__bossNativeCaptureInstalled) return;
              try { Object.defineProperty(window, '__bossNativeCaptureInstalled', { value: true }); } catch (e) { }
              var last = '';
              function visible(el) { return !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length)); }
              function grab(root) {
                root = root || document;
                var pw = Array.prototype.filter.call(root.querySelectorAll('input[type=password]'),
                  function (e) { return e.value && visible(e); });
                if (!pw.length) return;
                var p = pw[0];
                var inputs = Array.prototype.slice.call(root.querySelectorAll('input'));
                var idx = inputs.indexOf(p), user = '';
                for (var i = idx - 1; i >= 0; i--) {
                  var t = (inputs[i].type || 'text').toLowerCase();
                  if ((t === 'text' || t === 'email' || t === 'tel' || t === 'url') && inputs[i].value && visible(inputs[i])) { user = inputs[i].value; break; }
                }
                var sig = user + '\u0000' + p.value;
                if (sig === last) return;
                last = sig;
                try { bridge.capture(JSON.stringify({ u: user, p: p.value })); } catch (e) { }
              }
              function scope(el) { return (el && el.closest && el.closest('form')) || document; }
              document.addEventListener('submit', function (ev) { grab(ev.target); }, true);
              document.addEventListener('click', function (ev) {
                var b = ev.target && ev.target.closest && ev.target.closest('button,input[type=submit],input[type=button],[role=button],a');
                if (b) grab(scope(b));
              }, true);
              document.addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter' && ev.target && ev.target.tagName === 'INPUT') grab(scope(ev.target));
              }, true);
            })
            """.trimIndent()

        private val JSON = Json { ignoreUnknownKeys = true }

        /** (username, password) from the script's payload, or null. */
        @Suppress("ReturnCount")
        fun parse(payload: String): Pair<String, String>? {
            if (payload.length > 4 * MAX_VALUE) return null
            val o = runCatching { JSON.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
            val p = (o["p"] as? JsonPrimitive)?.contentOrNull ?: return null
            val u = (o["u"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return u to p
        }
    }
}

/** Page -> host channel for [CredentialCapture.SCRIPT]. The page URL comes from the host, never the payload. */
class NativeCaptureBridge internal constructor(
    private val onCapture: (String) -> Unit,
) {
    @JsAccessible
    fun capture(payload: String) {
        runCatching { onCapture(payload) }
    }
}
