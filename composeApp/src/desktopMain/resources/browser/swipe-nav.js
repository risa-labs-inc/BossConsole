// Two-finger horizontal swipe -> history back/forward, detected inside the page.
//
// Why in the page at all: on macOS the app runs Chromium in HARDWARE_ACCELERATED mode,
// where the browser is a native surface layered over the window rather than a component
// in the Compose scene. Neither Compose nor AWT sees the wheel there, and Chromium's own
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
    // No wheel event for this long ends the gesture. AWT does not surface NSEvent's scroll
    // phases, so a time gap is the only segmentation signal available.
    var GESTURE_GAP_MS = 120;
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
    var lastEventAt = 0;
    // Set once the gesture has been ruled out; stays set until the gesture ends, so a
    // rejected swipe cannot become an accepted one halfway through.
    var rejected = false;
    // Set once the navigation has fired, so one continuous swipe navigates exactly once.
    var committed = false;
    var direction = 0;
    // Ends the gesture when the fingers lift.
    //
    // The gap check at the top of onWheel cannot do this on its own: it only runs when a NEXT
    // event arrives, so a swipe abandoned halfway leaves the affordance parked in the page until
    // the user happens to scroll again, which may be never. A timer is the only thing that
    // observes a finger lifting, since AWT does not surface NSEvent's scroll phases.
    var endTimer = 0;

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

    // Whether anything in the pointer's scroll chain can still move horizontally the way
    // this gesture is pushing it. That is the carousel/map/spreadsheet guard, and it is the
    // same question Chromium asks before treating an overscroll as a navigation.
    //
    // Asked once, at the start of a gesture, and then latched. Re-asking per event would
    // start navigating the moment a horizontally scrolled element reached its end, in the
    // middle of a swipe the user aimed at that element.
    function chainCanScroll(event, dir) {
        var path;
        try {
            path = typeof event.composedPath === 'function' ? event.composedPath() : null;
        } catch (e) {
            path = null;
        }
        if (!path) {
            path = [];
            var node = event.target;
            while (node) {
                path.push(node);
                node = node.parentNode;
            }
        }
        var doc = w.document;
        var scroller = doc.scrollingElement || doc.documentElement;
        if (scroller && path.indexOf(scroller) === -1) {
            path = path.concat([scroller]);
        }
        for (var i = 0; i < path.length; i++) {
            var el = path[i];
            if (!el || el.nodeType !== 1) {
                continue;
            }
            var range = el.scrollWidth - el.clientWidth;
            if (range <= 1) {
                continue;
            }
            var overflowX = '';
            try {
                overflowX = w.getComputedStyle(el).overflowX;
            } catch (e2) {
                overflowX = '';
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
            var left = el.scrollLeft;
            if (dir < 0 ? left > 1 : left < range - 1) {
                return true;
            }
        }
        return false;
    }

    // ---- affordance -------------------------------------------------------------------

    // ONE overlay for the life of the document, shown and hidden rather than built and destroyed.
    //
    // It used to be created per gesture and removed on a timer after the exit animation, which
    // stacked: the exit runs EXIT_MS and a gesture ends after GESTURE_GAP_MS of quiet - 180 against
    // 120 - so swiping twice in a row
    // - not a corner case, just swiping twice - left two chevrons on screen at once. A single
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

    // End of gesture: everything goes back to neutral. Only ever called on a gap in the
    // wheel stream or on pagehide - never to abandon a gesture in flight, because clearing
    // `rejected` mid-gesture would let a swipe this code already ruled out come back.
    function reset() {
        if (endTimer) {
            w.clearTimeout(endTimer);
            endTimer = 0;
        }
        hideAffordance(direction);
        accumX = 0;
        verticalPath = 0;
        eventCount = 0;
        rejected = false;
        committed = false;
        direction = 0;
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

    function onWheel(event) {
        var now = Date.now();
        if (now - lastEventAt > GESTURE_GAP_MS) {
            reset();
        }
        lastEventAt = now;
        // Kept armed for as long as events keep arriving; fires once they stop.
        if (endTimer) {
            w.clearTimeout(endTimer);
        }
        endTimer = w.setTimeout(function () {
            endTimer = 0;
            reset();
        }, GESTURE_GAP_MS);

        if (committed || rejected || switchedOff()) {
            return;
        }
        // Line and page modes come from sources that are never a trackpad.
        if (event.deltaMode !== 0) {
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

        // Chrome's three tiers, in its order.
        var xDelta = Math.abs(accumX);
        if (verticalPath > CANCEL_STRONG_RATIO * xDelta ||
            (verticalPath * CANCEL_MIXED_RATIO > xDelta && verticalPath > CANCEL_VERTICAL_LOW) ||
            verticalPath > CANCEL_VERTICAL_HIGH) {
            abandon();
            return;
        }

        var dir = accumX < 0 ? -1 : 1;
        if (direction === 0) {
            direction = dir;
            // Decided once per gesture and latched, both of these: which way it goes, and
            // whether the page wanted the scroll for itself.
            if (chainCanScroll(event, dir) || !available(dir)) {
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

        var progress = Math.abs(accumX) / COMMIT_PX;
        showAffordance(direction);
        trackAffordance(direction, progress);

        if (progress >= 1) {
            committed = true;
            hideAffordance(direction);
            navigate(direction);
        }
    }

    // Capture-phase and passive: the gesture only commits when nothing was going to scroll,
    // so there is never anything to preventDefault, and passive keeps this off Chromium's
    // scroll-blocking path.
    w.addEventListener('wheel', onWheel, { capture: true, passive: true });
    w.addEventListener('pagehide', reset, { capture: true });
})();
