// Two-finger horizontal swipe -> history back/forward, detected inside the page.
//
// Why in the page at all: on macOS the app runs Chromium in HARDWARE_ACCELERATED mode,
// where the browser is a native surface layered over the window rather than a component
// in the Compose scene. Compose does not see the wheel there, and AWT discards macOS scroll
// phases before forwarding it; Chromium's own
// overscroll history navigation does nothing for a trackpad in EITHER rendering mode - measured,
// see the note at its call site in BrowserServiceImpl; it is a touchscreen feature.
// The renderer, however, always sees the wheel - that is how pages scroll - so the page is
// the one place the gesture is reliably observable. See BrowserSwipeNavScript.kt.
//
// Nothing here reads page content. It looks at wheel deltas, at scroll offsets of the
// elements under the pointer, and at nothing else.
(function () {
    'use strict';

    var w = window;
    if (w.__bossSwipeNavStarted) {
        return;
    }
    // Subframes get their own JS context and would each install a listener that navigates
    // the whole tab. Only the top document drives history.
    if (w.top !== w) {
        return;
    }
    w.__bossSwipeNavStarted = true;

    // Horizontal travel that commits the navigation, in CSS pixels.
    var COMMIT_PX = 90;
    // A trackpad emits a stream of small fractional deltas; a mouse wheel emits a few big
    // discrete ones. At the AWT layer a shift-modified mouse wheel is byte-identical to a
    // horizontal trackpad swipe, so these two bounds are the only thing separating them.
    var MIN_EVENTS = 3;
    var MAX_STEP_PX = 60;
    // Chrome's own cancellation rules, ported from history_swiper.mm
    // (shouldCancelHorizontalSwipeWithCurrentPoint). Three tiers, not one ratio:
    //
    //   if (yDelta > 2 * xDelta)                                   cancel
    //   if (yDelta * 1.3 > xDelta && yDelta > 0.01)                 cancel
    //   if (yDelta > 0.24)                                          cancel
    //
    // The second is the binding one in practice and it is LOOSER than the half-of-horizontal rule
    // that used to be here: Chrome accepts a swipe until vertical reaches about 0.77 of horizontal,
    // so gestures it would have taken were being thrown away.
    //
    // The asymmetry matters as much as the numbers. Chrome measures vertical as a PATH LENGTH -
    // the sum of every |dy|, so wobble accumulates and counts against you - and horizontal as NET
    // displacement from the start, so a reversal spends progress rather than adding to it.
    var CANCEL_STRONG_RATIO = 2;
    var CANCEL_MIXED_RATIO = 1.3;
    // Chrome's thresholds are fractions of the TRACKPAD, from NSTouch normalized positions, which
    // a page cannot see. Ours are in the only unit available here, so the two vertical limits are
    // carried over as the same fractions of the commit distance that Chrome's are of its own:
    // 0.01/0.08 and 0.24/0.08.
    var CANCEL_VERTICAL_LOW = COMMIT_PX * 0.125;
    var CANCEL_VERTICAL_HIGH = COMMIT_PX * 3;
    var EXIT_MS = 180;

    // Net horizontal displacement, and the vertical PATH length. See the cancel rules above for
    // why these two are accumulated differently.
    var accumX = 0;
    var verticalPath = 0;
    var eventCount = 0;
    // The host's CoreGraphics observer assigns one id while fingers remain on the trackpad.
    // It becomes inactive before momentum begins, so momentum wheel events cannot join this.
    var nativeGestureId = null;
    var endedGestureId = null;
    // Set once the gesture has been ruled out; stays set until the gesture ends, so a
    // rejected swipe cannot become an accepted one halfway through.
    var rejected = false;
    var direction = 0;
    // The scroll chain under the first event owns the whole gesture. Keeping the initial path
    // matters when a vertical first delta turns horizontal after the pointer has moved.
    var scrollPath = null;
    // Retain only the latest event to observe cancellation by later window listeners.
    var lastWheelEvent = null;
    var sheetBoundary = null;
    var sheetEdgeNavigation = false;
    var capturedWheel = null;
    var capturedSheetBoundary = null;
    // Latched the first time this gesture is past COMMIT_PX with enough events to be real.
    //
    // From that point the vertical tiers stop being asked. Vertical is a PATH LENGTH, so it only
    // ever grows, and every event after the crossing is one more chance for it to cancel a swipe
    // the user already completed - including events the user did not make. macOS momentum-phase
    // scroll carries 325-2500px of travel (AGENTS.md), and a tail with even a little dy in it
    // clears CANCEL_VERTICAL_HIGH on its own. Past the line only the horizontal position decides:
    // easing back below COMMIT_PX, or reversing outright. That is also what a native swipe-back
    // does - once you are past the threshold, wobble no longer counts against you.
    var reachedCommit = false;
    function state() {
        return w.__bossSwipeNavState || null;
    }

    function available(dir) {
        var s = state();
        if (!s || s.enabled === false) {
            return false;
        }
        return dir < 0 ? s.back === true : s.forward === true;
    }

    // Switched off while this page is open. The host pushes the flag the moment the setting
    // changes, so the detector stops here rather than staying live until the next navigation.
    function switchedOff() {
        var s = state();
        return !!s && s.enabled === false;
    }

    // Whether the pointer's scroll chain owns horizontal gestures. A nested carousel, map, or
    // spreadsheet keeps the whole gesture even at its current edge: the compositor may have
    // reached that edge before this passive listener observes scrollLeft, and turning that last
    // event into navigation is a surprising result. Only the document viewport yields at an edge.
    //
    // Asked once, at the start of a gesture, and then latched. Re-asking per event would
    // start navigating the moment a horizontally scrolled element reached its end, in the
    // middle of a swipe the user aimed at that element.
    function eventPath(event) {
        var path;
        try {
            path = typeof event.composedPath === 'function' ? event.composedPath() : null;
        } catch (e) {
            path = null;
        }
        if (!path || typeof path.length !== 'number' || path.length === 0) {
            path = [];
            var node = event.target;
            while (node) {
                path.push(node);
                // parentNode reaches a ShadowRoot; host gets from that root back into the page.
                node = node.parentNode || node.host || null;
            }
        }
        var doc = w.document;
        var scroller = doc.scrollingElement || doc.documentElement;
        if (scroller && path.indexOf(scroller) === -1) {
            path = path.concat([scroller]);
        }
        return path;
    }

    // Sheets renders cells on a canvas, with a sibling native scrollbar. Read only geometry,
    // never cells. Its document overscroll policy and cancelled wheels also cover the canvas
    // at its boundary, so this explicit adapter allows a NEW outward gesture there.
    function isSheetsDocument() {
        return w.location && w.location.hostname === 'docs.google.com' &&
            w.location.pathname.indexOf('/spreadsheets/') === 0;
    }

    function sheetsBoundary(path) {
        if (!isSheetsDocument()) return null;
        for (var i = 0; i < path.length; i++) {
            var el = path[i];
            if (!el || typeof el.closest !== 'function') continue;
            var grid = el.closest('.grid-container');
            if (!grid) continue;
            var bar = grid.querySelector('.native-scrollbar-x');
            if (!bar || bar.clientWidth <= 0 || bar.scrollWidth < bar.clientWidth) return null;
            var style = w.getComputedStyle(bar);
            return { left: bar.scrollLeft, range: bar.scrollWidth - bar.clientWidth,
                rtl: style.direction === 'rtl' };
        }
        return null;
    }

    function sheetCanScroll(boundary, dir) {
        return boundary.rtl
            ? (dir < 0 ? boundary.left > -boundary.range + 1 : boundary.left < -1)
            : (dir < 0 ? boundary.left > 1 : boundary.left < boundary.range - 1);
    }

    function chainCanScroll(path, dir) {
        var doc = w.document;
        var scroller = doc.scrollingElement || doc.documentElement;
        for (var i = 0; i < path.length; i++) {
            var el = path[i];
            if (!el || el.nodeType !== 1) {
                continue;
            }
            // Virtual grids and canvases often scroll a model or a sibling scrollbar instead
            // of this element. Their wheel gesture belongs to the page even at an edge.
            var role = typeof el.getAttribute === 'function' ? el.getAttribute('role') : null;
            if (el.tagName === 'CANVAS' || role === 'grid' || role === 'treegrid') {
                return true;
            }
            var range = el.scrollWidth - el.clientWidth;
            var overflowX = '';
            var cssDirection = 'ltr';
            var overscrollX = 'auto';
            try {
                var style = w.getComputedStyle(el);
                overflowX = style.overflowX;
                cssDirection = style.direction || 'ltr';
                overscrollX = style.overscrollBehaviorX || 'auto';
            } catch (e2) {
                overflowX = '';
            }
            if (overscrollX === 'contain' || overscrollX === 'none') {
                return true;
            }
            if (range <= 1) {
                continue;
            }
            // The root needs a special case - it computes `overflow-x: visible` on an ordinary page
            // even though the viewport scrolls - but it must NOT skip the test entirely, which is
            // what `el === scroller` alone did. `html { overflow-x: hidden }` with content wider
            // than the viewport still reports scrollWidth > clientWidth, so the root claimed it
            // could scroll and every forward swipe on such a page died silently.
            var hidden = overflowX === 'hidden' || overflowX === 'clip';
            var scrollable =
                overflowX === 'auto' ||
                overflowX === 'scroll' ||
                (el === scroller && !hidden);
            if (!scrollable) {
                continue;
            }
            if (el !== scroller) {
                return true;
            }
            var left = el.scrollLeft;
            // Chromium uses the negative scrollLeft model for RTL: zero is the right edge and
            // -range is the left edge. Wheel delta direction stays physical, so its edge checks
            // are the inverse of LTR's positive coordinate range.
            var canMove = cssDirection === 'rtl'
                ? (dir < 0 ? left > -range + 1 : left < -1)
                : (dir < 0 ? left > 1 : left < range - 1);
            // A root that explicitly contains overscroll also refuses history navigation at its
            // boundary, matching the page author's scroll-chain policy.
            if (canMove || overscrollX === 'contain' || overscrollX === 'none') {
                return true;
            }
        }
        return false;
    }

    // ---- affordance -------------------------------------------------------------------

    // ONE overlay for the life of the document, shown and hidden rather than built and destroyed.
    //
    // It used to be created per gesture and removed on a timer after the exit animation, which
    // stacked when a second swipe began during that animation. A single
    // element cannot stack however the gestures overlap.
    var host = null;
    var root = null;
    var puck = null;
    var puckDirection = 0;
    var reduceMotion = false;
    try {
        reduceMotion = w.matchMedia('(prefers-reduced-motion: reduce)').matches === true;
    } catch (e) {
        reduceMotion = false;
    }

    function chevron(dir) {
        var d = dir < 0 ? 'M15 5 L8 12 L15 19' : 'M9 5 L16 12 L9 19';
        return (
            '<svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">' +
            '<path d="' + d + '" fill="none" stroke="currentColor" stroke-width="2.4" ' +
            'stroke-linecap="round" stroke-linejoin="round"/></svg>'
        );
    }

    function showAffordance(dir) {
        var body = w.document.body;
        // XML documents and some error pages have no body. Nothing to hang the puck on, and the
        // gesture itself still works - only the affordance is skipped.
        if (!body) {
            return;
        }
        try {
            if (!host) {
                host = w.document.createElement('div');
                host.setAttribute('aria-hidden', 'true');
                host.style.cssText =
                    'position:fixed;top:0;left:0;width:0;height:0;margin:0;padding:0;border:0;' +
                    'z-index:2147483647;pointer-events:none;';
                // A shadow root so no page CSS can reach in and nothing is inherited out. A page
                // with `* { transition: all 2s }` would otherwise make the puck lag the finger.
                root = host.attachShadow ? host.attachShadow({ mode: 'closed' }) : host;
            }
            // A single-page app can replace its own body out from under us.
            if (host.parentNode !== body) {
                body.appendChild(host);
            }
            if (puckDirection !== dir || !puck) {
                var edge = dir < 0 ? 'left:0;' : 'right:0;';
                root.innerHTML =
                    '<style>' +
                    ':host{all:initial}' +
                    '.p{position:fixed;top:50%;' + edge +
                    'width:52px;height:52px;margin-top:-26px;border-radius:50%;' +
                    'display:flex;align-items:center;justify-content:center;' +
                    'background:rgba(250,250,250,0.94);color:#1c1c1e;' +
                    'box-shadow:0 2px 12px rgba(0,0,0,0.28);opacity:0;will-change:transform,opacity}' +
                    '@media (prefers-color-scheme: dark){' +
                    '.p{background:rgba(58,58,60,0.94);color:#f5f5f7;' +
                    'box-shadow:0 2px 12px rgba(0,0,0,0.5)}}' +
                    '</style>' +
                    '<div class="p">' + chevron(dir) + '</div>';
                puck = root.querySelector ? root.querySelector('.p') : null;
                puckDirection = dir;
            }
        } catch (e) {
            // Take the element with us. Nulling the reference alone left a 0x0 div in the page for
            // every failure, with nothing able to find it again.
            try {
                if (host && host.parentNode) {
                    host.parentNode.removeChild(host);
                }
            } catch (e2) {
                // The page may have torn its own DOM apart; there is nothing further to try.
            }
            host = null;
            root = null;
            puck = null;
        }
    }

    function trackAffordance(dir, progress) {
        if (!puck) {
            return;
        }
        var clamped = progress > 1 ? 1 : progress;
        // Slides in from behind its edge and settles just inside it.
        var offset = (dir < 0 ? 1 : -1) * (-58 + 66 * clamped);
        var scale = clamped >= 1 ? 1.08 : 1;
        puck.style.transition = '';
        puck.style.transform = 'translate3d(' + offset.toFixed(1) + 'px,0,0) scale(' + scale + ')';
        puck.style.opacity = (0.25 + 0.75 * clamped).toFixed(2);
    }

    /** Hide it. The element stays for the life of the document; see the note on `host`. */
    function hideAffordance(dir) {
        if (!puck) {
            return;
        }
        try {
            if (!reduceMotion) {
                puck.style.transition = 'transform ' + EXIT_MS + 'ms ease, opacity ' + EXIT_MS + 'ms ease';
            }
            puck.style.transform = 'translate3d(' + (dir < 0 ? -58 : 58) + 'px,0,0)';
            puck.style.opacity = '0';
        } catch (e) {
            // The page may have torn its own DOM apart. Nothing to hide, and nothing to report.
        }
    }

    // ---- gesture ----------------------------------------------------------------------

    // End of gesture: everything goes back to neutral. Never to abandon a gesture in flight -
    // clearing `rejected` mid-gesture would let a swipe this code already ruled out come back.
    //
    // A matching native release calls decide() first. Lifecycle resets deliberately do not.
    function reset() {
        hideAffordance(direction);
        accumX = 0;
        verticalPath = 0;
        eventCount = 0;
        rejected = false;
        direction = 0;
        scrollPath = null;
        lastWheelEvent = null;
        capturedWheel = null;
        capturedSheetBoundary = null;
        sheetBoundary = null;
        sheetEdgeNavigation = false;
        reachedCommit = false;
        nativeGestureId = null;
    }

    // Rule the current gesture out, keeping the accumulators so nothing restarts until the
    // finger actually lifts.
    function abandon() {
        rejected = true;
        hideAffordance(direction);
    }

    function navigate(dir) {
        var bridge = w.__bossSwipeNav;
        if (!bridge || typeof bridge.navigate !== 'function') {
            return;
        }
        try {
            bridge.navigate(dir < 0 ? 'back' : 'forward');
        } catch (e) {
            // The bridge is a host object; a failure here is a wiring problem, and throwing
            // out of a wheel listener would surface in the site's own console.
        }
    }

    // The one place "reached the commit distance" becomes an actual navigation.
    //
    // Called only when the gesture ENDS, never mid-swipe. That is the same question a native
    // trackpad swipe-back answers on release: was the LAST position past the line, not "was any
    // position ever past it". Reaching COMMIT_PX used to navigate on the spot, in the same wheel
    // event that crossed it (boss-plugin-fluck-browser#36) - fingers still down, no chance to see
    // it coming and no way to back out.
    //
    // Read against the live accumulators rather than a cached progress, so easing back below
    // COMMIT_PX before release - same direction the whole time, no reversal - cancels exactly like
    // letting go early on a real trackpad. A genuine reversal never reaches here at all: it flips
    // `direction` inside onWheel and abandons there, which is the separate, pre-existing
    // cancel-by-reversing path this does not duplicate.
    //
    // Both guards past `rejected` matter specifically BECAUSE this runs at gesture end and not
    // from onWheel: onWheel's early returns stop updating state the moment they fire, but the
    // gesture still ends, and this still runs on whatever the last onWheel call left behind.
    //
    // - eventCount < MIN_EVENTS: below that count onWheel draws no affordance, yet accumX and
    //   direction are already live. Two shift-modified mouse-wheel notches (or two big trackpad
    //   deltas) can each land under MAX_STEP_PX - so neither trips the wheel-shape guard alone -
    //   and together clear COMMIT_PX before a third event ever arrives. Without this, that pair
    //   navigates with no chevron ever drawn.
    // - available(direction): asked again here rather than reusing the answer latched in onWheel,
    //   because the host can push new state during the gesture. It subsumes switchedOff() - the
    //   setting can flip mid-gesture, and the comment on
    //   switchedOff() promises the detector stops when it does - and additionally fails closed if
    //   the direction lost its history entry, or if state went away entirely.
    //
    function decide() {
        // A page may register a window bubble listener after ours. Its preventDefault is
        // visible now, after dispatch, even though it was false inside onWheel.
        if (!sheetBoundary && lastWheelEvent && lastWheelEvent.defaultPrevented) abandon();
        if (rejected || direction === 0 || eventCount < MIN_EVENTS || !available(direction)) {
            return;
        }
        if (Math.abs(accumX) >= COMMIT_PX) {
            navigate(direction);
        }
    }

    function onWheel(event) {
        // Consume the capture snapshot before a new native contact resets old state.
        var hasCapture = capturedWheel === event;
        var eventBoundary = hasCapture ? capturedSheetBoundary : null;
        capturedWheel = null;
        capturedSheetBoundary = null;
        // Cheap filters precede the synchronous renderer-to-host claim. Do not skip vertical
        // pixel events: their initial scroll chain must retain ownership if the contact curls.
        if (switchedOff()) { reset(); return; }
        if (event.deltaMode !== 0) {
            if (nativeGestureId !== null) abandon();
            return;
        }
        var bridge = w.__bossSwipeNav;
        var activeId = null;
        try {
            activeId = bridge && typeof bridge.activeGestureId === 'function'
                ? bridge.activeGestureId()
                : null;
        } catch (e) {
            activeId = null;
        }
        // No active finger sequence means this is momentum, a mouse wheel, or native observation
        // is unavailable. All fail closed. A new id cannot finish accumulators from an older one.
        if (activeId === null || activeId === undefined) {
            return;
        }
        var tokenParts = String(activeId).split(':');
        activeId = tokenParts[0];
        var beganAt = Number(tokenParts[1]);
        // performance.timeOrigin + event.timeStamp is epoch time in Chromium. Reject anything
        // observably older than this native sequence; JxBrowser does not preserve the original
        // AWT timestamp, so this is deliberately only an extra conservative check.
        if (beganAt && w.performance &&
            w.performance.timeOrigin + event.timeStamp + 1 < beganAt) {
            return;
        }
        if (nativeGestureId !== null && nativeGestureId !== activeId) {
            reset();
        }
        if (endedGestureId === activeId) {
            return;
        }
        nativeGestureId = activeId;

        if (!scrollPath) {
            scrollPath = eventPath(event);
            sheetBoundary = hasCapture ? eventBoundary : sheetsBoundary(scrollPath);
        }

        if (!sheetBoundary && lastWheelEvent && lastWheelEvent.defaultPrevented) abandon();
        lastWheelEvent = event;
        if (rejected) {
            return;
        }
        // Run in bubble phase so a page widget gets the first chance to claim a synthetic or
        // JavaScript-driven horizontal scroller with preventDefault().
        if (event.defaultPrevented && !sheetBoundary) {
            abandon();
            return;
        }
        var dx = event.deltaX || 0;
        var dy = event.deltaY || 0;
        // Vertical travel counts from the first event of the gesture, including events with
        // no horizontal component at all. Otherwise a plain vertical scroll that curls into
        // a horizontal one at the end would arrive here looking like a fresh clean swipe.
        verticalPath += Math.abs(dy);
        if (!dx) {
            return;
        }
        // A mouse wheel is a few big discrete deltas; a trackpad is many small ones. This only
        // disqualifies a gesture that looks wheel-shaped FROM THE START - a fast trackpad flick
        // also carries deltas this large, and abandoning on one meant the harder you swiped the
        // less likely it was to work.
        if (Math.abs(dx) >= MAX_STEP_PX && eventCount < MIN_EVENTS) {
            abandon();
            return;
        }

        eventCount++;
        accumX += dx;

        // Chrome's three tiers, in its order. Asked only until the gesture is past the commit
        // distance - see reachedCommit.
        var xDelta = Math.abs(accumX);
        if (!reachedCommit &&
            (verticalPath > CANCEL_STRONG_RATIO * xDelta ||
                (verticalPath * CANCEL_MIXED_RATIO > xDelta && verticalPath > CANCEL_VERTICAL_LOW) ||
                verticalPath > CANCEL_VERTICAL_HIGH)) {
            abandon();
            return;
        }

        var dir = accumX < 0 ? -1 : 1;
        if (direction === 0) {
            direction = dir;
            // Decided once per gesture and latched, both of these: which way it goes, and
            // whether the page wanted the scroll for itself.
            sheetEdgeNavigation = sheetBoundary !== null && !sheetCanScroll(sheetBoundary, dir);
            if ((sheetBoundary ? !sheetEdgeNavigation : chainCanScroll(scrollPath, dir)) || !available(dir)) {
                abandon();
                return;
            }
        } else if (dir !== direction) {
            // The user reversed mid-swipe. Treat it as an abandon rather than flipping the
            // navigation under them.
            abandon();
            return;
        }

        if (eventCount < MIN_EVENTS) {
            return;
        }
        // Latched here rather than the moment xDelta crosses, so it means the same thing decide()
        // does: past the line AND enough events to be a trackpad at all. Latching earlier would
        // let the two-big-deltas pair decide() rejects switch the vertical tiers off on its way
        // through.
        if (xDelta >= COMMIT_PX) {
            reachedCommit = true;
        }

        // Progress is tracked all the way through the gesture now, never gated on whether it
        // has reached the commit distance yet - see decide() for why crossing COMMIT_PX no
        // longer does anything here beyond what trackAffordance already draws.
        var progress = xDelta / COMMIT_PX;
        showAffordance(direction);
        trackAffordance(direction, progress);
    }

    // Called by the host only for a native Ended/Cancelled phase with no momentum phase.
    w.__bossSwipeNavRelease = function (gestureId, cancelled, nativeX, nativeRejected) {
        endedGestureId = String(gestureId);
        if (nativeGestureId === null || String(gestureId) !== nativeGestureId) {
            return;
        }
        // CoreGraphics is authoritative at release so an earlier native Changed event delayed on
        // Chromium's renderer queue cannot make a late easing-back or reversal look committed.
        if (!cancelled && !nativeRejected && Math.abs(nativeX) >= COMMIT_PX) {
            // Native and DOM wheel signs are allowed to differ; reversal is evaluated wholly in
            // the native coordinate system and the page's latched direction chooses navigation.
            accumX = direction * Math.abs(nativeX);
            decide();
        }
        reset();
    };

    // Bubble phase lets target/page handlers claim custom scrollers with preventDefault first;
    // passive keeps this observer off Chromium's scroll-blocking path.
    w.addEventListener('wheel', function (event) {
        capturedWheel = event;
        capturedSheetBoundary = switchedOff() || event.deltaMode !== 0 || !isSheetsDocument()
            ? null : sheetsBoundary(eventPath(event));
        // A page may stop propagation before our bubble listener. Release that event's target
        // after dispatch anyway, rather than retaining a detached subtree until another wheel.
        if (typeof w.queueMicrotask === 'function') {
            w.queueMicrotask(function () {
                if (capturedWheel === event) {
                    capturedWheel = null;
                    capturedSheetBoundary = null;
                }
            });
        }
    }, { capture: true, passive: true });
    w.addEventListener('wheel', onWheel, { capture: false, passive: true });
    // pagehide is NOT routed through decide() - the page is already unloading, so navigating
    // it anywhere is moot at best; this only tears down the affordance so nothing outlives the
    // document it was drawn into.
    w.addEventListener('pagehide', reset, { capture: true });
    w.addEventListener('blur', function (event) {
        if (event.target === w) reset();
    }, { capture: false });
    w.addEventListener('visibilitychange', function () {
        if (w.document.hidden) reset();
    }, { capture: true });
})();
