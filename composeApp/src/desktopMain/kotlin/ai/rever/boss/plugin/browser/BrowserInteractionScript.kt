package ai.rever.boss.plugin.browser

/**
 * The in-page interaction collector, as JavaScript.
 *
 * Injected into each page (see [BrowserHandleImpl.injectPageHelpers]) to report *how* a
 * site is used — what kind of control was clicked, how far the page was scrolled, whether
 * a form was submitted — and pushes batches to the host through [BrowserInteractionBridge].
 *
 * ## What this script is allowed to read
 *
 * Only these, from the element that was interacted with:
 * `tagName`, `type`, `name`, `getAttribute('role')`, and its index among its siblings.
 *
 * That list is the entire privacy design, and it is a *reading* restriction rather than a
 * filtering one. The script never touches `textContent`, `innerText`, `value`,
 * `placeholder`, `title`, `alt`, `aria-label`, `id`, `className`, `href`, `src`, `action`,
 * `dataset`, or the clipboard — so page content cannot leak through a bug in a later
 * sanitizing step, because it is never in a variable in the first place. In a healthcare
 * deployment the page body is PHI: the label says "Patient MRN", the input value *is* the
 * MRN, and the id is routinely `patient-4417`.
 *
 * The host re-validates everything this sends anyway ([BrowserAnalytics.sanitizeToken],
 * [BrowserAnalytics.sanitizeFieldName], [BrowserAnalytics.sanitizePath]) — a page controls
 * its own DOM and can name an input whatever it likes, so a second independent pass runs
 * where the page cannot reach it. Anything failing validation is dropped there.
 *
 * ## Behaviour
 *
 * Events are batched and flushed on a timer (and on `pagehide`) so a mutation-heavy page
 * cannot turn into a bridge call per DOM event. Listeners are capture-phase and passive,
 * so nothing here can intercept or delay a page's own handlers. The whole script is
 * wrapped so an exception can never surface in the page's console or break site JS.
 */
internal object BrowserInteractionScript {
    /** Property the bridge is published on. Matched by [BrowserHandleImpl]. */
    const val BRIDGE_PROPERTY: String = "__bossInteraction"

    /** Guard so re-injection into the same document is a no-op. */
    private const val STARTED_FLAG = "__bossInteractionStarted"

    private const val FLUSH_INTERVAL_MS = 2000
    private const val MAX_BATCH = 50
    private const val RAGE_CLICK_WINDOW_MS = 1000
    private const val RAGE_CLICK_THRESHOLD = 3
    private const val MAX_PATH_DEPTH = 5

    /**
     * The collector source.
     *
     * `describe()` is the only place the DOM is inspected, deliberately — one function to
     * audit, and the sole reason the exclusion list above can be stated as a fact.
     */
    val source: String =
        """
        (function () {
          if (window.$STARTED_FLAG) return;
          window.$STARTED_FLAG = true;
          try {
            var queue = [];
            var lastClick = { path: null, at: 0, count: 0 };
            var maxScrollBucket = 0;

            function send(e) {
              if (queue.length < $MAX_BATCH) queue.push(e);
            }

            function flush() {
              if (!queue.length) return;
              var batch = queue;
              queue = [];
              try {
                if (window.$BRIDGE_PROPERTY) {
                  window.$BRIDGE_PROPERTY.emit(JSON.stringify(batch));
                }
              } catch (_) {}
            }

            // The ONLY DOM inspection in this file. Reads a fixed set of structural
            // attributes and nothing else — no text, no values, no labels, no ids, no urls.
            function describe(el) {
              var out = {};
              if (!el || el.nodeType !== 1) return out;
              try {
                out.tag = (el.tagName || '').toLowerCase();
                var role = el.getAttribute ? el.getAttribute('role') : null;
                if (role) out.role = String(role).slice(0, 32);
                // Restricted to form controls: 'type' is a control kind there. Elsewhere it
                // is author-defined and could be anything.
                if (out.tag === 'input' || out.tag === 'button') {
                  if (el.type) out.inputType = String(el.type).slice(0, 32);
                }
                if (el.name) out.fieldName = String(el.name).slice(0, 64);
                out.path = pathOf(el);
              } catch (_) {}
              return out;
            }

            // Tag names and sibling positions only. No ids or classes, by construction:
            // this builds the string from tagName and an index, never from an attribute.
            function pathOf(el) {
              var parts = [];
              var node = el;
              var depth = 0;
              while (node && node.nodeType === 1 && depth < $MAX_PATH_DEPTH) {
                var tag = (node.tagName || '').toLowerCase();
                if (!tag) break;
                var index = 1;
                var sib = node.previousElementSibling;
                while (sib) {
                  if (sib.tagName === node.tagName) index++;
                  sib = sib.previousElementSibling;
                }
                parts.unshift(index > 1 ? tag + ':' + index : tag);
                node = node.parentElement;
                depth++;
              }
              return parts.join('>');
            }

            document.addEventListener('click', function (ev) {
              var d = describe(ev.target);
              var now = Date.now();
              if (d.path && d.path === lastClick.path && now - lastClick.at < $RAGE_CLICK_WINDOW_MS) {
                lastClick.count++;
                lastClick.at = now;
                // Repeatedly hitting the same control means the page is not responding the
                // way the user expects — worth reporting as its own signal, once.
                if (lastClick.count === $RAGE_CLICK_THRESHOLD) {
                  d.type = 'RAGE_CLICK';
                  d.repeatCount = lastClick.count;
                  send(d);
                  return;
                }
                if (lastClick.count > $RAGE_CLICK_THRESHOLD) return;
              } else {
                lastClick = { path: d.path, at: now, count: 1 };
              }
              d.type = 'CLICK';
              send(d);
            }, true);

            document.addEventListener('focusin', function (ev) {
              var el = ev.target;
              if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'SELECT' && el.tagName !== 'TEXTAREA')) return;
              var d = describe(el);
              d.type = 'FIELD_FOCUSED';
              send(d);
            }, true);

            document.addEventListener('submit', function (ev) {
              var d = describe(ev.target);
              d.type = 'FORM_SUBMITTED';
              send(d);
            }, true);

            // Occurrence only. The clipboard is never read: no getData, no selection.
            document.addEventListener('copy', function () {
              send({ type: 'COPY' });
            }, true);
            document.addEventListener('paste', function (ev) {
              var d = describe(ev.target);
              d.type = 'PASTE';
              // A paste target's field name is useful ("they paste into the MRN box"); the
              // pasted data is not read.
              send(d);
            }, true);

            // Quantised to quarters so a long page is a handful of events, not hundreds.
            window.addEventListener('scroll', function () {
              try {
                var doc = document.documentElement;
                var scrollable = doc.scrollHeight - window.innerHeight;
                if (scrollable <= 0) return;
                var pct = ((window.pageYOffset || doc.scrollTop) / scrollable) * 100;
                var bucket = Math.min(100, Math.floor(pct / 25) * 25);
                if (bucket > maxScrollBucket) {
                  maxScrollBucket = bucket;
                  if (bucket > 0) send({ type: 'SCROLL_DEPTH', scrollDepthPercent: bucket });
                }
              } catch (_) {}
            }, { capture: true, passive: true });

            setInterval(flush, $FLUSH_INTERVAL_MS);
            window.addEventListener('pagehide', flush, true);
            window.addEventListener('beforeunload', flush, true);
          } catch (_) {
            // Never surface anything into the page.
          }
        })();
        """.trimIndent()
}
