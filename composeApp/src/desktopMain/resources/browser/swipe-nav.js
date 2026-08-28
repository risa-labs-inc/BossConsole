// Two-finger horizontal swipe -> history back/forward, detected inside the page.
//
// Why in the page at all: on macOS the app runs Chromium in HARDWARE_ACCELERATED mode,
// where the browser is a native surface layered over the window rather than a component
// in the Compose scene. Neither Compose nor AWT sees the wheel there, and Chromium's own
// overscroll history navigation is an Aura feature that does not exist on the Mac port.
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
    // Vertical travel this large relative to horizontal means the user is scrolling, not
    // swiping. Judged over the whole gesture, not per event, because the first few events
    // of an honest swipe are noisy.
    var VERTICAL_RATIO = 0.5;
    var EXIT_MS = 180;

    var accumX = 0;
    var accumY = 0;
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
        if (!s) {
            return false;
        }
        return dir < 0 ? s.back === true : s.forward === true;
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
            var scrollable =
                overflowX === 'auto' ||
                overflowX === 'scroll' ||
                overflowX === 'overlay' ||
                el === scroller;
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

    var host = null;
    var puck = null;
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
        // XML documents and some error pages have no body. Nothing to hang the puck on, and
        // the gesture itself still works - only the affordance is skipped.
        if (!body || host) {
            return;
        }
        try {
            host = w.document.createElement('div');
            host.setAttribute('aria-hidden', 'true');
            host.style.cssText =
                'position:fixed;top:0;left:0;width:0;height:0;margin:0;padding:0;border:0;' +
                'z-index:2147483647;pointer-events:none;';
            // A shadow root so no page CSS can reach in and nothing is inherited out. A page
            // with `* { transition: all 2s }` would otherwise make the puck lag the finger.
            var root = host.attachShadow ? host.attachShadow({ mode: 'closed' }) : host;
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
            body.appendChild(host);
        } catch (e) {
            host = null;
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

    function hideAffordance(dir) {
        var dyingHost = host;
        var dyingPuck = puck;
        host = null;
        puck = null;
        if (!dyingHost) {
            return;
        }
        var remove = function () {
            try {
                if (dyingHost.parentNode) {
                    dyingHost.parentNode.removeChild(dyingHost);
                }
            } catch (e) {
                // The page may have replaced the body under us. Nothing to clean up then.
            }
        };
        if (!dyingPuck || reduceMotion) {
            remove();
            return;
        }
        try {
            dyingPuck.style.transition = 'transform ' + EXIT_MS + 'ms ease, opacity ' + EXIT_MS + 'ms ease';
            dyingPuck.style.transform = 'translate3d(' + (dir < 0 ? -58 : 58) + 'px,0,0)';
            dyingPuck.style.opacity = '0';
        } catch (e) {
            remove();
            return;
        }
        w.setTimeout(remove, EXIT_MS + 40);
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
        if (host) {
            hideAffordance(direction);
        }
        accumX = 0;
        accumY = 0;
        eventCount = 0;
        rejected = false;
        committed = false;
        direction = 0;
    }

    // Rule the current gesture out, keeping the accumulators so nothing restarts until the
    // finger actually lifts.
    function abandon() {
        rejected = true;
        if (host) {
            hideAffordance(direction);
        }
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

        if (committed || rejected) {
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
        accumY += dy;
        if (!dx) {
            return;
        }
        if (Math.abs(dx) >= MAX_STEP_PX) {
            abandon();
            return;
        }

        eventCount++;
        accumX += dx;

        // Measured against a floor rather than against accumX alone: the first few events of
        // an honest swipe carry a pixel or two of horizontal travel, and any vertical noise
        // at all would exceed a bare ratio of it.
        if (Math.abs(accumY) > Math.max(Math.abs(accumX), 24) * VERTICAL_RATIO) {
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
