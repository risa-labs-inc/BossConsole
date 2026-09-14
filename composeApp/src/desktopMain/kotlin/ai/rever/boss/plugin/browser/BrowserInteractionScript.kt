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
 * sanitizing step, because it is never in a variable in the first place. On a line-of-business
 * page the body is the sensitive part: the label says "Account Ref", the input value *is* the
 * reference, and the id is routinely `account-4417`.
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

    /** Per-route reset, called when re-injection finds the collector already running. */
    private const val RESET_FLAG = "__bossInteractionReset"

    private const val FLUSH_INTERVAL_MS = 2000
    private const val MAX_BATCH = 50
    private const val RAGE_CLICK_WINDOW_MS = 1000
    private const val RAGE_CLICK_THRESHOLD = 3
    private const val MAX_PATH_DEPTH = 5

    /**
     * Every string read out of the DOM is capped here, including the tag name - which is
     * author-controlled, since a custom element's name is whatever the page registered. One
     * element with a megabyte-long tag name pushes the batch past
     * [BrowserInteractionBridge.MAX_PAYLOAD_CHARS], which drops the *whole* batch, so a site
     * could otherwise silence its own interaction telemetry with a single hidden element.
     *
     * Deliberately ABOVE [BrowserAnalytics]'s own 32-char token cap, for the same reason
     * [MAX_FIELD_NAME_CHARS] is above its counterpart. `sanitizeToken` *refuses* a value over
     * its cap, so cutting to exactly 32 here made that refusal unreachable: a 40-character
     * `<app-account-activity-summary-card>` - not exotic in the frameworks this targets -
     * arrived as a valid-looking 32-character prefix, so the host reported a tag that does
     * not exist and two long elements sharing a prefix collapsed into one. Cutting higher
     * keeps the host the one that decides.
     */
    private const val MAX_TOKEN_CHARS = 40

    /**
     * Deliberately ABOVE the host's own 64-char field-name cap, not equal to it.
     *
     * [BrowserAnalytics.sanitizeFieldName] truncates last, so that a digit run straddling the
     * boundary is redacted before it is cut rather than leaving a one- or two-digit tail the
     * redactor no longer recognises as a run. Slicing to the same 64 here handed the host an
     * already-cut string and defeated that ordering entirely: `..._encounter_row_4417882`
     * arrives as `...44` and survives. Cutting higher keeps the host the one that does the
     * cutting, while still bounding the payload.
     */
    private const val MAX_FIELD_NAME_CHARS = 96

    /** How many preceding siblings a sibling-index scan may read. See `pathOf`. */
    private const val MAX_SIBLING_SCAN = 100

    /**
     * Total path budget, kept below [BrowserAnalytics]'s `MAX_PATH_LENGTH` of 120.
     *
     * The host *refuses* an over-long path rather than truncating it, so the collector has
     * to be the one that stays inside the limit - otherwise the path vanishes rather than
     * shortens, and it vanishes exactly on the component-framework apps this targets.
     */
    private const val MAX_PATH_CHARS = 110

    /**
     * Cap on a reported link hostname. Generous on purpose: the host reduces this to an
     * eTLD+1 and refuses anything it cannot parse, so the only job here is to bound the
     * payload against a page that sets a pathological hostname.
     */
    private const val MAX_HOST_CHARS = 255

    /**
     * How long the selection must be still before it is measured.
     *
     * `selectionchange` fires per character while dragging or shift-arrowing, so this is
     * the difference between one event per selection and one per keystroke. Long enough to
     * coalesce a drag, short enough that a selection followed immediately by a copy is
     * still reported before the page navigates away.
     */
    private const val SELECTION_DEBOUNCE_MS = 400

    /**
     * The collector source.
     *
     * `describe()` is the only place the DOM is inspected, deliberately — one function to
     * audit, and the sole reason the exclusion list above can be stated as a fact.
     */
    val source: String =
        """
        (function () {
          if (window.$STARTED_FLAG) {
            // Already collecting in this document. The host re-runs this script on every
            // main-frame NavigationFinished, and for a single-page app that is a ROUTE
            // change within one document — so this is the only signal the collector gets
            // that the user is on a different page. Without it maxScrollBucket stayed at
            // its high-water mark and every route after the first reported no scroll depth
            // at all, and a click on the old route could pair with one on the new.
            if (window.$RESET_FLAG) window.$RESET_FLAG();
            return;
          }
          try {
            var queue = [];
            var lastClick = { path: null, startedAt: 0, count: 0, event: null };
            var maxScrollBucket = 0;
            // Set by pathOf when it had to drop the sibling ordinal or an outer level, so
            // describe() can mark the path as "shape, not identity".
            var pathTruncated = false;
            var FORM_CONTROLS = ['input', 'select', 'textarea', 'form', 'button'];

            function send(e) {
              if (queue.length >= $MAX_BATCH) return false;
              queue.push(e);
              return true;
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
                out.tag = String(el.tagName || '').toLowerCase().slice(0, $MAX_TOKEN_CHARS);
                var role = el.getAttribute ? el.getAttribute('role') : null;
                if (role) out.role = String(role).slice(0, $MAX_TOKEN_CHARS);
                // Restricted to 'input' and 'button' specifically, a NARROWER set than
                // FORM_CONTROLS below and deliberately so: those are the two elements whose
                // 'type' is a control kind drawn from a fixed vocabulary. On 'select',
                // 'textarea' and 'form' it is either derived (select-one) or an author-set
                // enctype, neither of which says anything about the interaction.
                if (out.tag === 'input' || out.tag === 'button') {
                  if (el.type) out.inputType = String(el.type).slice(0, $MAX_TOKEN_CHARS);
                }
                // 'name' gets the SAME restriction, for the same reason. It is a real IDL
                // attribute on img, a, iframe, object, param, meta and map, and on a custom
                // element it is whatever getter the page defined - so read off anything else
                // it is author-controlled free text, not a form-encoding key. That is also
                // what the host's field-name sanitizer assumes when it cleans rather than
                // refuses, so reading it here from a div would undercut the sanitizer's own
                // justification.
                if (FORM_CONTROLS.indexOf(out.tag) !== -1 && el.name) {
                  out.fieldName = String(el.name).slice(0, $MAX_FIELD_NAME_CHARS);
                }
                // A click usually lands on something INSIDE the link - a span, an icon - so
                // the anchor has to be found by walking up rather than tested for directly.
                var anchor = el.closest ? el.closest('a') : null;
                if (anchor) describeLink(anchor, out);
                out.path = pathOf(el);
                out.truncated = pathTruncated;
              } catch (_) {}
              return out;
            }

            // Where a link points, WITHOUT reading its href.
            //
            // `protocol`, `hostname` and `hash` are IDL attributes on HTMLAnchorElement: the
            // browser has already parsed the href and these expose the parsed components, so
            // the path, query string and fragment content are never touched - not read into a
            // variable, not sliced, not passed on. Reading `getAttribute('href')` and parsing
            // it here would put the whole URL in a local, one refactor away from being sent.
            //
            // The hostname still goes to the host for eTLD+1 reduction rather than being
            // compared and discarded here, because `internal` vs `external` does not answer
            // "which sites do people leave for", and a subdomain is not a domain.
            function describeLink(a, out) {
              try {
                var protocol = String(a.protocol || '').toLowerCase();
                if (protocol === 'mailto:') { out.linkKind = 'mailto'; return; }
                if (protocol === 'tel:') { out.linkKind = 'tel'; return; }
                if (protocol !== 'http:' && protocol !== 'https:') return;
                // A same-document jump. Checked before the host comparison because the
                // hostname of `#section` is the current page's, which would read as an
                // ordinary internal link and inflate navigation counts.
                if (a.hash && a.pathname === location.pathname && a.hostname === location.hostname) {
                  out.linkKind = 'anchor';
                  return;
                }
                if (a.hasAttribute && a.hasAttribute('download')) { out.linkKind = 'download'; return; }
                var host = String(a.hostname || '').toLowerCase().slice(0, $MAX_HOST_CHARS);
                if (!host) return;
                out.linkHost = host;
                out.linkKind = host === String(location.hostname || '').toLowerCase() ? 'internal' : 'external';
              } catch (_) {}
            }

            // Tag names and sibling positions only. No ids or classes, by construction:
            // this builds the string from tagName and an index, never from an attribute.
            //
            // Two budgets, both of which exist because this runs synchronously in a
            // capture-phase handler, ahead of the page's own:
            //
            // - The sibling scan stops at MAX_SIBLING_SCAN. Counting an index by walking
            //   previousElementSibling to the start costs one read per preceding sibling, so
            //   clicking a cell in a 10,000-row table was ~10,000 property reads before the
            //   page could respond - and focusin paid it again on every field. Large tables
            //   are the normal case in the deployments this targets. Past the cap the index
            //   is omitted rather than guessed: a wrong ordinal is worse than none.
            // - Total length stops at MAX_PATH_CHARS, dropping OUTER levels first (the
            //   nearest ancestors are the informative ones). The host refuses a path over
            //   its own limit outright rather than truncating, so without this a component
            //   framework with long custom tag names - five levels of `app-account-card`
            //   clears it easily - loses elementPath entirely instead of losing depth.
            function pathOf(el) {
              var parts = [];
              var node = el;
              var depth = 0;
              var used = 0;
              pathTruncated = false;
              while (node && node.nodeType === 1 && depth < $MAX_PATH_DEPTH) {
                var tag = String(node.tagName || '').toLowerCase().slice(0, $MAX_TOKEN_CHARS);
                if (!tag) break;
                var index = 1;
                var scanned = 0;
                var sib = node.previousElementSibling;
                while (sib && scanned < $MAX_SIBLING_SCAN) {
                  if (sib.tagName === node.tagName) index++;
                  sib = sib.previousElementSibling;
                  scanned++;
                }
                var capped = sib || index > $MAX_SIBLING_SCAN;
                if (capped) pathTruncated = true;
                var part = capped ? tag : (index > 1 ? tag + ':' + index : tag);
                // +1 for the '>' this part will need once something precedes it.
                if (used > 0 && used + part.length + 1 > $MAX_PATH_CHARS) { pathTruncated = true; break; }
                used += part.length + (used > 0 ? 1 : 0);
                parts.unshift(part);
                node = node.parentElement;
                depth++;
              }
              return parts.join('>');
            }

            document.addEventListener('click', function (ev) {
              var d = describe(ev.target);
              var now = Date.now();
              // A truncated path is a shape, not an identity: past the sibling cap every cell
              // in a large table reduces to the same `tbody>tr`, so keying rage detection on
              // path equality would report three clicks on three different rows as rage - and
              // large tables are exactly what the cap exists for. Rage needs a path that
              // actually identifies one element.
              var identifies = d.path && !d.truncated;
              delete d.truncated;
              // The window is measured from the FIRST click of the run, not the last.
              // Re-anchoring on every click made the run end only at a gap longer than the
              // window, so a pagination arrow or stepper clicked once every ~900ms produced
              // CLICK, CLICK, RAGE_CLICK and then nothing at all for as long as it continued.
              // One click per second is slow for rage and entirely normal for someone working
              // through a worklist - which is the traffic this feature exists to measure.
              if (identifies && d.path === lastClick.path && now - lastClick.startedAt < $RAGE_CLICK_WINDOW_MS) {
                lastClick.count++;
                // Repeatedly hitting the same control means the page is not responding the
                // way the user expects — worth reporting as its own signal, once.
                if (lastClick.count === $RAGE_CLICK_THRESHOLD) {
                  d.type = 'RAGE_CLICK';
                  d.repeatCount = lastClick.count;
                  // Only track an event that was actually queued: send() drops when the queue
                  // is full, and updating a dropped object collects counts nobody serializes.
                  if (send(d)) lastClick.event = d;
                  return;
                }
                if (lastClick.count > $RAGE_CLICK_THRESHOLD) {
                  // Keep the count honest while the burst continues. The event is still in
                  // the queue until the next flush, so the common case reports what actually
                  // happened rather than the threshold - three frustrated clicks and thirty
                  // were otherwise indistinguishable, and the host allows up to 100.
                  if (lastClick.event) lastClick.event.repeatCount = lastClick.count;
                  return;
                }
              } else {
                lastClick = { path: identifies ? d.path : null, startedAt: now, count: 1, event: null };
              }
              d.type = 'CLICK';
              send(d);
            }, true);

            function report(el, type) {
              var d = describe(el);
              delete d.truncated;
              d.type = type;
              send(d);
            }

            document.addEventListener('focusin', function (ev) {
              var el = ev.target;
              if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'SELECT' && el.tagName !== 'TEXTAREA')) return;
              report(el, 'FIELD_FOCUSED');
            }, true);

            document.addEventListener('submit', function (ev) {
              report(ev.target, 'FORM_SUBMITTED');
            }, true);

            // Text selection, measured and thrown away.
            //
            // The selected string is read into ONE local, reduced to three numbers, and the
            // local is cleared before anything is queued. It is never sliced, never stored on
            // the event, and never crosses the bridge - so unlike every other field here,
            // nothing downstream is load-bearing for it. On the pages this runs on a
            // selection is the single most sensitive thing available: whatever a user
            // highlights is, by definition, the value that mattered to them.
            //
            // What survives is the shape: how much, how many words, and whether it contained
            // a digit - the difference between copying a paragraph and copying an identifier.
            var selectionTimer = null;
            function reportSelection() {
              selectionTimer = null;
              try {
                var sel = window.getSelection ? window.getSelection() : null;
                if (!sel || sel.isCollapsed) return;
                var text = String(sel);
                var chars = text.length;
                if (!chars) return;
                var words = text.split(/\s+/);
                var wordCount = 0;
                for (var i = 0; i < words.length; i++) if (words[i]) wordCount++;
                var hasDigits = /[0-9]/.test(text);
                // Cleared explicitly rather than left to fall out of scope, so that a later
                // edit adding a line below this one cannot reach the string.
                text = null;
                words = null;
                var node = sel.anchorNode;
                var el = !node ? null : (node.nodeType === 1 ? node : node.parentElement);
                var d = describe(el);
                delete d.truncated;
                d.type = 'TEXT_SELECTED';
                d.selectionChars = chars;
                d.selectionWords = wordCount;
                d.selectionHasDigits = hasDigits;
                send(d);
              } catch (_) {}
            }
            // Debounced because selectionchange fires per character while dragging or
            // shift-arrowing: undebounced, selecting one sentence fills the 50-event batch
            // by itself and starves every other interaction on the page.
            document.addEventListener('selectionchange', function () {
              if (selectionTimer) clearTimeout(selectionTimer);
              selectionTimer = setTimeout(reportSelection, $SELECTION_DEBOUNCE_MS);
            }, true);

            // Occurrence only. The clipboard is never read: no getData, no selection.
            document.addEventListener('copy', function () {
              send({ type: 'COPY' });
            }, true);
            document.addEventListener('paste', function (ev) {
              // A paste target's field name is useful ("they paste into the reference box"); the
              // pasted data is not read.
              report(ev.target, 'PASTE');
            }, true);

            // Quantised to quarters so a long page is a handful of events, not hundreds.
            window.addEventListener('scroll', function (ev) {
              try {
                var doc = document.documentElement;
                // Capture-phase on window also sees inner scrollable elements, which would
                // then be measured against the document's own height - an inner scroll that
                // never moved the page could advance the page's scroll depth.
                var t = ev && ev.target;
                if (t && t !== document && t !== doc && t !== document.body && t !== window) return;
                // scrollHeight forces a style/layout flush, and this runs per scroll
                // event on pages that mutate the DOM while scrolling - lazy loading,
                // virtualised lists - which is the normal shape here. The bucket is
                // monotonic and caps at 100, so once a page has been read to the bottom
                // there is nothing left to learn and the read can be skipped entirely.
                if (maxScrollBucket >= 100) return;
                var scrollable = doc.scrollHeight - window.innerHeight;
                if (scrollable <= 0) return;
                // One pixel of slack: at fractional DPI or a non-100% zoom the offset lands
                // sub-pixel short of scrollable, so 100% was never reachable.
                var pct = ((window.pageYOffset || doc.scrollTop) / (scrollable - 1)) * 100;
                var bucket = Math.min(100, Math.floor(pct / 25) * 25);
                if (bucket > maxScrollBucket) {
                  maxScrollBucket = bucket;
                  if (bucket > 0) send({ type: 'SCROLL_DEPTH', scrollDepthPercent: bucket });
                }
              } catch (_) {}
            }, { capture: true, passive: true });

            // Per-route state, reset when the host re-runs this script in the same document.
            // Deliberately does NOT read location: the collector never touches a URL, and it
            // does not need to - the host already knows a navigation happened.
            window.$RESET_FLAG = function () {
              maxScrollBucket = 0;
              lastClick = { path: null, startedAt: 0, count: 0, event: null };
            };

            setInterval(flush, $FLUSH_INTERVAL_MS);
            // pagehide only: it also fires on bfcache entry, which beforeunload does not, and
            // registering a beforeunload listener has engine-visible side effects for no gain.
            window.addEventListener('pagehide', flush, true);

            // Claimed LAST, once everything above actually succeeded. Set at the top, a throw
            // anywhere in between was swallowed by the catch and left the flag standing with
            // no listeners and no reset function - so every later re-injection took the
            // "already collecting" path and the collector was dead for that document with no
            // signal at all. Setting it here makes a failed injection retryable on the next
            // navigation instead.
            window.$STARTED_FLAG = true;
          } catch (_) {
            // Never surface anything into the page.
          }
        })();
        """.trimIndent()
}
