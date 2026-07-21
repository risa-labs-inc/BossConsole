package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * JavaScript for the co-browse (DOM state-sync) tab-sharing feature.
 *
 * The host injects the rrweb *recorder* into a shared tab's pages; rrweb emits
 * serialized DOM events (full snapshot + incremental mutations + scroll/input)
 * which are pushed to the host via the [CoBrowseBridge] (`window.__bossCoBrowse`).
 * For remote control, the host resolves an rrweb mirror node id back to the live
 * DOM node and dispatches a synthetic event.
 *
 * The recorder library is vendored at `/cobrowse/rrweb-record.min.js` — a UMD
 * bundle exposing the global `rrwebRecord` (which is the `record` function and
 * also carries `rrwebRecord.mirror` and `rrwebRecord.takeFullSnapshot`).
 */
internal object CoBrowseScripts {
    private val logger = BossLogger.forComponent("CoBrowseScripts")

    /** Lazily-loaded rrweb recorder UMD source (cached for the process lifetime). */
    val recorderLib: String by lazy { loadResource("/cobrowse/rrweb-record.min.js") }

    private fun loadResource(path: String): String =
        try {
            CoBrowseScripts::class.java.getResourceAsStream(path)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: run {
                logger.error(LogCategory.BROWSER, "Co-browse resource missing: $path")
                ""
            }
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Failed to load co-browse resource $path", error = e)
            ""
        }

    /**
     * Bootstrap that starts the rrweb recorder once per page, wiring rrweb's
     * `emit` to the host bridge and exposing the mirror + stop handle on `window`.
     * Idempotent via `window.__bossRrwebStarted`.
     *
     * When [maskInputs] is true, rrweb's `maskAllInputs` hides typed form values
     * (passwords are masked regardless); otherwise all rendered content streams and
     * protection rests on E2E encryption + explicit approval in the share server.
     */
    private fun recordBootstrap(maskInputs: Boolean): String = """
        (function(){
          if (window.__bossRrwebStarted) return;
          if (typeof rrwebRecord !== 'function') return;
          window.__bossRrwebStarted = true;
          try {
            window.__bossRrwebStop = rrwebRecord({
              emit: function(e){
                try { if (window.__bossCoBrowse) window.__bossCoBrowse.emit(JSON.stringify(e)); } catch (_) {}
              },
              recordCanvas: false,
              collectFonts: false,
              maskAllInputs: $maskInputs,
              checkoutEveryNms: 120000,
              // Tighter sampling for a more responsive mirror: rrweb batches mouse
              // moves and coalesces scroll/input; the defaults add ~50ms+ latency.
              sampling: { mousemove: 20, scroll: 30, input: 'last' }
            });
            window.__bossRrwebMirror = rrwebRecord.mirror;
          } catch (err) {
            window.__bossRrwebStarted = false;
            try { console.warn('boss cobrowse record failed', err); } catch (_) {}
          }
        })();
    """.trimIndent()

    /** Full injection payload = recorder library + bootstrap. Injected per frame. */
    fun recordInjection(maskInputs: Boolean): String = recorderLib + "\n" + recordBootstrap(maskInputs)

    /** Stop recording on the current page and clear the started flag. */
    val recordStop: String = """
        (function(){
          try { if (window.__bossRrwebStop) window.__bossRrwebStop(); } catch (_) {}
          window.__bossRrwebStarted = false;
          window.__bossRrwebStop = null;
        })();
    """.trimIndent()

    /** Set or clear the in-page control guard (defence-in-depth alongside the host gate). */
    fun setControlGuard(granted: Boolean): String = "window.__bossControlGranted = $granted;"

    /**
     * Build the control-applier call for one semantic event. [payloadJsonLiteral]
     * is a JSON object literal produced by the host from a typed control message
     * (e.g. `{"kind":"click","id":42}`, `{"kind":"input","id":7,"value":"hi"}`,
     * `{"kind":"scroll","id":1,"x":0,"y":600}`). Resolves the rrweb node id against
     * the live mirror and dispatches a synthetic DOM event. Returns a status
     * string: "ok" / "denied" / "nomirror" / "stale" / "unknown" / "err:<msg>".
     *
     * Navigation actions (navigate/back/forward/reload) are applied host-side via
     * [BrowserHandle] navigation methods, not here.
     */
    fun applyControl(payloadJsonLiteral: String): String = """
        (function(p){
          try {
            if (!window.__bossControlGranted) return "denied";
            if (!window.__bossRrwebMirror) return "nomirror";
            var n = (p && p.id != null) ? window.__bossRrwebMirror.getNode(p.id) : null;
            if (!n && p && p.kind === 'scroll') { n = document.scrollingElement || document.documentElement; }
            if (!n) return "stale";
            // Only Elements receive click/input/key; scroll may target the document.
            if (n.nodeType !== 1 && p.kind !== 'scroll') return "stale";
            switch (p.kind) {
              case 'click':
                try { if (typeof n.focus === 'function') n.focus(); } catch (_) {}
                var init = {bubbles:true, cancelable:true, view:window};
                try { n.dispatchEvent(new PointerEvent('pointerdown', init)); } catch (_) {}
                n.dispatchEvent(new MouseEvent('mousedown', init));
                try { n.dispatchEvent(new PointerEvent('pointerup', init)); } catch (_) {}
                n.dispatchEvent(new MouseEvent('mouseup', init));
                // dispatchEvent(click) fires JS listeners but NOT the default action
                // (link nav, form submit, checkbox toggle). Native click() does both —
                // run it on the nearest activatable ancestor (the viewer's target is
                // often a span/icon inside the button or link).
                var act = (n.closest && n.closest('a,button,input,select,option,textarea,label,summary,[role="button"],[onclick]')) || n;
                if (typeof act.click === 'function') act.click();
                else n.dispatchEvent(new MouseEvent('click', init));
                break;
              case 'input':
                try { n.focus(); } catch (_) {}
                if ('value' in n) { n.value = (p.value != null ? p.value : ''); }
                else if (n.isContentEditable) { n.textContent = (p.value != null ? p.value : ''); }
                n.dispatchEvent(new Event('input',  {bubbles:true}));
                n.dispatchEvent(new Event('change', {bubbles:true}));
                break;
              case 'key':
                try { n.focus(); } catch (_) {}
                var opts = {bubbles:true, cancelable:true, key:(p.key || ''), code:(p.code || '')};
                var proceed = n.dispatchEvent(new KeyboardEvent('keydown', opts));
                n.dispatchEvent(new KeyboardEvent('keyup', opts));
                // Synthetic keydown never runs the browser's default action, so Enter
                // in a form field must submit explicitly (honoring preventDefault from
                // the page's own keydown handler). Textareas keep Enter-as-newline
                // unless they act as a search box (role=combobox, e.g. Google).
                if (proceed && p.key === 'Enter') {
                  var f = n.form || (n.closest && n.closest('form'));
                  var searchy = n.getAttribute && (n.getAttribute('role') === 'combobox' || n.getAttribute('enterkeyhint') === 'search');
                  if (f && (n.tagName !== 'TEXTAREA' || searchy)) {
                    try { f.requestSubmit ? f.requestSubmit() : f.submit(); } catch (_) {}
                  }
                }
                break;
              case 'scroll':
                var x = (p.x != null ? p.x : 0), y = (p.y != null ? p.y : 0);
                if (n === document.scrollingElement || n === document.documentElement || n === document.body) {
                  window.scrollTo(x, y);
                } else if (typeof n.scrollTo === 'function') {
                  n.scrollTo(x, y);
                } else { n.scrollLeft = x; n.scrollTop = y; }
                break;
              default: return "unknown";
            }
            return "ok";
          } catch (err) {
            return "err:" + (err && err.message ? err.message : String(err));
          }
        })($payloadJsonLiteral);
    """.trimIndent()
}
