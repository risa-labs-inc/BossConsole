#!/usr/bin/env node
/**
 * Runs the two-finger swipe detector against a fake DOM.
 *
 * The whole feature is a set of thresholds - how much travel commits, how much vertical drift
 * disqualifies, how big a single delta has to be before it is a mouse wheel rather than a
 * trackpad - and every one of them is a number that looks reasonable while being wrong. Reading
 * the script cannot tell a working set from a broken one. Running it can.
 *
 * It also pins the coupling the Kotlin suite cannot see: the bridge and state property names are
 * hand-matched between BrowserSwipeNavScript.kt and swipe-nav.js, and a drift in either direction
 * fails silently - the page installs a detector that reports to nobody, or the host publishes a
 * bridge nothing calls, and in both cases the gesture simply does nothing.
 *
 * Usage: node scripts/test/test-swipe-nav.js
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repoRoot = path.resolve(__dirname, '../..');
const scriptJs = path.join(repoRoot, 'composeApp/src/desktopMain/resources/browser/swipe-nav.js');
const scriptKt = path.join(
  repoRoot,
  'composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserSwipeNavScript.kt',
);
const bridgeKt = path.join(
  repoRoot,
  'composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserSwipeNavBridge.kt',
);

let failures = 0;
function check(name, cond, detail) {
  if (cond) {
    console.log(`  ok   ${name}`);
  } else {
    failures++;
    console.log(`  FAIL ${name}${detail === undefined ? '' : ` -> ${detail}`}`);
  }
}
/** Smallest distance between consecutive commits; Infinity when there are fewer than two. */
function minGap(times) {
  let smallest = Infinity;
  for (let i = 1; i < times.length; i++) smallest = Math.min(smallest, times[i] - times[i - 1]);
  return smallest;
}
function eq(name, actual, expected) {
  check(
    name,
    JSON.stringify(actual) === JSON.stringify(expected),
    `got ${JSON.stringify(actual)}, want ${JSON.stringify(expected)}`,
  );
}

/** The host's own property names, read from the Kotlin rather than restated here. */
function hostProperties() {
  const src = fs.readFileSync(scriptKt, 'utf8');
  const grab = (name) => {
    const m = new RegExp(`const val ${name}: String = "([^"]+)"`).exec(src);
    if (!m) throw new Error(`${name} not found in BrowserSwipeNavScript.kt`);
    return m[1];
  };
  return { bridge: grab('BRIDGE_PROPERTY'), state: grab('STATE_PROPERTY') };
}

/**
 * The host's same-direction repeat window, read from the Kotlin.
 *
 * The paused-drag case below is the one place these two files make a claim about each other: the
 * script emits two commits and the host is what collapses them. Restating 400 here would let the
 * two drift apart with both suites green.
 */
function hostRepeatMs() {
  const src = fs.readFileSync(bridgeKt, 'utf8');
  const m = /^internal const val SWIPE_NAV_REPEAT_MS = (\d+)L/m.exec(src);
  if (!m) throw new Error('SWIPE_NAV_REPEAT_MS not found in BrowserSwipeNavBridge.kt');
  return Number(m[1]);
}

// ---------------------------------------------------------------------------
// Fake DOM: only what the detector touches, so an added DOM read fails loudly.
// ---------------------------------------------------------------------------
function newPage(js, options = {}) {
  const listeners = {};
  const navigated = [];
  const navigatedAt = [];
  const registrations = [];
  let clockMs = 1000;
  let preventedDefaults = 0;

  const element = (props = {}) =>
    Object.assign(
      {
        nodeType: 1,
        scrollWidth: 0,
        clientWidth: 0,
        scrollLeft: 0,
        overflowX: 'visible',
        style: {},
        children: [],
        parentNode: null,
        setAttribute() {},
        appendChild(child) {
          child.parentNode = this;
          this.children.push(child);
          return child;
        },
        removeChild(child) {
          const i = this.children.indexOf(child);
          if (i >= 0) this.children.splice(i, 1);
          child.parentNode = null;
          return child;
        },
      },
      props,
    );

  const body = element({ tagName: 'BODY' });
  const scroller = element(
    Object.assign({ tagName: 'HTML' }, options.root || {}),
  );

  const add = (type, fn, opts) => {
    registrations.push({ type, opts });
    (listeners[type] = listeners[type] || []).push(fn);
  };

  // A real fake clock, not a fire-immediately stub. The detector ends a gesture on a TIMER -
  // that is how a lifted finger is observed at all - so a setTimeout that ran its callback on
  // the spot would end every gesture after its first event and quietly pass the whole suite.
  const timers = [];
  let nextTimerId = 1;
  const fireDue = () => {
    for (let guard = 0; guard < 100; guard++) {
      const i = timers.findIndex((t) => t.at <= clockMs);
      if (i < 0) return;
      const [t] = timers.splice(i, 1);
      t.fn();
    }
    throw new Error('timer storm');
  };

  const sandbox = {
    Math,
    JSON,
    Date: { now: () => clockMs },
    setTimeout: (fn, ms) => {
      const id = nextTimerId++;
      timers.push({ id, at: clockMs + (ms || 0), fn });
      return id;
    },
    clearTimeout: (id) => {
      const i = timers.findIndex((t) => t.id === id);
      if (i >= 0) timers.splice(i, 1);
    },
  };
  const shadowRoots = [];
  sandbox.window = {
    addEventListener: add,
    setTimeout: sandbox.setTimeout,
    clearTimeout: sandbox.clearTimeout,
    matchMedia: () => ({ matches: options.reduceMotion === true }),
    getComputedStyle: (el) => ({ overflowX: el.overflowX }),
    document: {
      body: options.noBody ? null : body,
      scrollingElement: scroller,
      documentElement: scroller,
      createElement: () => {
        const host = element({ tagName: 'DIV' });
        host.style = { cssText: '' };
        host.attachShadow = () => {
          const puck = element({ tagName: 'DIV' });
          puck.style = {};
          const root = {
            _html: '',
            set innerHTML(v) {
              this._html = v;
            },
            get innerHTML() {
              return this._html;
            },
            querySelector: () => puck,
          };
          shadowRoots.push(root);
          host._root = root;
          host._puck = puck;
          return root;
        };
        return host;
      },
    },
  };
  sandbox.window.top = options.subframe ? {} : sandbox.window;
  sandbox.document = sandbox.window.document;
  // The clock is recorded alongside the direction: the host's SWIPE_NAV_DEBOUNCE_MS rests on
  // "two commits are never closer than GESTURE_GAP_MS", and that claim lives in this script.
  sandbox.window[hostProps.bridge] = {
    navigate: (d) => {
      navigated.push(d);
      navigatedAt.push(clockMs);
    },
  };
  if (options.state !== null) {
    sandbox.window[hostProps.state] = options.state || { back: true, forward: true };
  }

  vm.createContext(sandbox);
  vm.runInContext(js, sandbox);

  const wheel = (dx, dy, over) => {
    const chain = over ? [over, scroller] : [body, scroller];
    const event = {
      deltaMode: 0,
      deltaX: dx,
      deltaY: dy,
      target: chain[0],
      composedPath: () => chain,
      preventDefault: () => {
        preventedDefaults++;
      },
    };
    (listeners.wheel || []).forEach((f) => f(event));
  };

  return {
    element,
    body,
    navigated,
    navigatedAt,
    registrations,
    wheel,
    wheelRaw: (event) => (listeners.wheel || []).forEach((f) => f(event)),
    swipe: (count, dx, dy, over, startMs) => {
      for (let i = 0; i < count; i++) {
        if (startMs !== undefined) clockMs = startMs + i;
        wheel(dx, dy || 0, over);
      }
    },
    advance: (ms) => {
      clockMs += ms;
      fireDue();
    },
    // Move the clock WITHOUT running anything that came due. Models the one ordering the script
    // cannot control: Chromium dispatches input on a higher-priority task queue than timers, so a
    // wheel event can run while the end-of-gesture timer is due but has not fired yet.
    jump: (ms) => {
      clockMs += ms;
    },
    // Replace the state object the host pushes, mid-gesture. Assigning a whole new value (or
    // taking it away) is what the host actually does; mutating the existing one is a separate case.
    setState: (value) => {
      sandbox.window[hostProps.state] = value;
    },
    // Let every pending timer run, however far in the future. What a lifted finger and a
    // finished exit animation look like from the script's side.
    settle: () => {
      for (let guard = 0; guard < 100; guard++) {
        if (!timers.length) return;
        clockMs = Math.max(clockMs, Math.min(...timers.map((t) => t.at)));
        fireDue();
      }
      throw new Error('timers never drained');
    },
    pagehide: () => (listeners.pagehide || []).forEach((f) => f()),
    puckHtml: () => (shadowRoots.length ? shadowRoots[shadowRoots.length - 1].innerHTML : null),
    hostCss: () => {
      const host = Array.prototype.filter.call(body.children, (c) => c.style && c.style.cssText)[0];
      return host ? host.style.cssText : '';
    },
    // How many overlay elements exist. One is created per document and reused, so this is what
    // catches stacking.
    liveOverlays: () => body.children.length,
    // How many are actually SHOWING. The element outlives a gesture on purpose, so "gone" means
    // faded out, not removed - which is also the thing a user can tell apart.
    visibleOverlays: () =>
      Array.prototype.filter.call(
        body.children,
        (c) => c._puck && c._puck.style.opacity && c._puck.style.opacity !== '0',
      ).length,
    preventedDefaults: () => preventedDefaults,
    installed: () => (listeners.wheel || []).length,
  };
}

// ---------------------------------------------------------------------------
const hostProps = hostProperties();
const REPEAT_MS = hostRepeatMs();
const js = fs.readFileSync(scriptJs, 'utf8');
const constant = (name) => Number(new RegExp(`var ${name} = (\\d+)`).exec(js)[1]);
const COMMIT_PX = constant('COMMIT_PX');
const GAP_MS = constant('GESTURE_GAP_MS');
const MIN_EVENTS = constant('MIN_EVENTS');
const MAX_STEP_PX = constant('MAX_STEP_PX');
console.log(
  `detector: COMMIT_PX=${COMMIT_PX} GESTURE_GAP_MS=${GAP_MS} MIN_EVENTS=${MIN_EVENTS} ` +
    `MAX_STEP_PX=${MAX_STEP_PX}; host: ${hostProps.bridge} / ${hostProps.state}, ` +
    `SWIPE_NAV_REPEAT_MS=${REPEAT_MS}`,
);

console.log('\nwiring');
{
  const p = newPage(js);
  check('installs exactly one wheel listener', p.installed() === 1, p.installed());
  const wheelReg = p.registrations.find((r) => r.type === 'wheel');
  eq('listens capture-phase and passive', wheelReg.opts, { capture: true, passive: true });
  check(
    'script names the host bridge property',
    js.includes(`window.${hostProps.bridge}`) || js.includes(`w.${hostProps.bridge}`),
    hostProps.bridge,
  );
  check(
    'script names the host state property',
    js.includes(`w.${hostProps.state}`) || js.includes(`window.${hostProps.state}`),
    hostProps.state,
  );
}

console.log('\na real swipe');
{
  const p = newPage(js);
  p.swipe(12, -10);
  p.settle();
  eq('goes back once', p.navigated, ['back']);
  check('leaves nothing showing', p.visibleOverlays() === 0, p.visibleOverlays());
  check('never calls preventDefault', p.preventedDefaults() === 0, p.preventedDefaults());
}
{
  const p = newPage(js);
  p.swipe(12, 10);
  p.settle();
  eq('the other direction goes forward', p.navigated, ['forward']);
}
{
  const p = newPage(js);
  p.swipe(24, -10);
  p.settle();
  eq('one continuous swipe navigates exactly once', p.navigated, ['back']);
}
{
  const p = newPage(js);
  p.swipe(12, -10);
  p.advance(GAP_MS + 80);
  p.swipe(12, -10);
  p.settle();
  eq('two swipes across a gap navigate twice', p.navigated, ['back', 'back']);
}
{
  // The floor the host's SWIPE_NAV_DEBOUNCE_MS is derived from, proved rather than asserted in
  // prose: whatever the ordering of timers and events, two commits are never closer together than
  // GESTURE_GAP_MS, because that gap IS how one gesture is told from the next. If a future change
  // to decide()'s call sites falsifies that, the debounce's whole justification goes with it.
  const p = newPage(js);
  p.swipe(12, -10);
  p.advance(GAP_MS + 40);
  p.swipe(12, -10);
  p.advance(GAP_MS + 40);
  p.swipe(12, 10);
  p.settle();
  eq('three gestures navigate three times', p.navigated, ['back', 'back', 'forward']);
  check(
    'and no two commits are closer than the gesture gap',
    minGap(p.navigatedAt) >= GAP_MS,
    `${JSON.stringify(p.navigatedAt)} min gap ${minGap(p.navigatedAt)}, want >= ${GAP_MS}`,
  );
}
{
  // A slow deliberate drag that HESITATES mid-swipe. 120ms of quiet with the fingers still down is
  // byte-identical to a lift, so this is two gestures here and there is no signal that would make
  // it one. What is pinned is that the script really does emit two commits: the guard against it
  // is SWIPE_NAV_REPEAT_MS in BrowserSwipeNavBridge.kt, which has to live host-side because this
  // script's state dies with the document the first commit navigates away from.
  const p = newPage(js);
  p.swipe(9, -10);
  p.advance(GAP_MS + 1);
  p.swipe(9, -10);
  p.settle();
  eq('a drag paused past the gesture gap is two gestures to the script', p.navigated, ['back', 'back']);
  check(
    'and the two are close enough for the host repeat window to catch',
    minGap(p.navigatedAt) <= REPEAT_MS,
    `${JSON.stringify(p.navigatedAt)} min gap ${minGap(p.navigatedAt)}, want <= ${REPEAT_MS}`,
  );
}

console.log('\nthe end of the gesture, not the threshold, is what commits');
{
  // The bug this whole change fixes (boss-plugin-fluck-browser#36): crossing COMMIT_PX used to
  // navigate on the spot, in the same wheel event, fingers still down. Held right at the commit
  // distance with no further events, it must not navigate until the gesture actually ends.
  const p = newPage(js);
  p.swipe(9, -10);
  eq('holding at the commit distance does not navigate on the crossing event', p.navigated, []);
  check('the affordance is still up, filled in', p.visibleOverlays() === 1, p.visibleOverlays());
  p.settle();
  eq('and only fires once the gesture ends', p.navigated, ['back']);
}
{
  // Same direction throughout - accumX never crosses back through zero, so this is NOT the
  // reversal guard below, just letting go before release while short of the line again.
  const p = newPage(js);
  p.swipe(12, -10);
  p.swipe(4, 10);
  p.settle();
  eq('easing back below the commit distance before release does not navigate', p.navigated, []);
}
{
  const p = newPage(js);
  p.swipe(12, -10);
  p.swipe(1, -10);
  p.settle();
  eq('holding past the commit distance through release still navigates', p.navigated, ['back']);
}
{
  const p = newPage(js);
  p.swipe(12, -10);
  p.pagehide();
  eq('the page going away mid-swipe is not routed through a commit', p.navigated, []);
}
{
  // A second review round caught this: decide() runs from the endTimer regardless of how few
  // events the gesture had, so without its own MIN_EVENTS check, two deltas of 50px each - each
  // one under MAX_STEP_PX, so neither is caught by the wheel-shape guard on its own - total
  // 100px, clear COMMIT_PX (90), and never reach the eventCount that would have shown any
  // affordance at all. That must not navigate.
  const p = newPage(js);
  p.swipe(2, -50);
  p.settle();
  eq('two events under MIN_EVENTS must not navigate however far they travel', p.navigated, []);
  check('and no affordance was ever shown for it', p.liveOverlays() === 0, p.liveOverlays());
}
{
  // The setting can flip WHILE a gesture is in flight. onWheel checks it already, so this
  // specifically exercises decide() at gesture end, after the last onWheel call latched a
  // direction and a distance. `state` is passed by reference and mutated directly - the same
  // object the host pushes the flag onto in production.
  const state = { back: true, forward: true };
  const p = newPage(js, { state });
  p.swipe(12, -10);
  state.enabled = false;
  p.settle();
  eq('switching the gesture off mid-swipe stops it committing on release too', p.navigated, []);
}
{
  // decide() asks available() rather than switchedOff(), which also fails closed on state going
  // away entirely - the host replacing the object, not mutating it.
  const p = newPage(js);
  p.swipe(12, -10);
  p.setState(null);
  p.settle();
  eq('state disappearing mid-swipe stops it committing too', p.navigated, []);
}
{
  // A direction that lost its history entry mid-gesture. onWheel latched `available` at the first
  // event on purpose (see chainCanScroll's note on latching); decide() re-asks at the end, which
  // is the conservative half of that pair.
  const state = { back: true, forward: true };
  const p = newPage(js, { state });
  p.swipe(12, -10);
  state.back = false;
  p.settle();
  eq('a direction that stopped being navigable mid-swipe does not commit', p.navigated, []);
}
{
  // The gap check at the top of onWheel is the OTHER end-of-gesture signal, and it must decide
  // too. Here the timer is due but has not run and a new gesture's first event beats it - the
  // ordering Chromium's higher-priority input queue makes possible. Without decide() there, a
  // gesture the user completed past the commit distance navigates nowhere, silently.
  const p = newPage(js);
  p.swipe(12, -10);
  p.jump(GAP_MS + 1);
  p.wheel(-10, 0);
  eq('a finished gesture still commits when the next event beats its timer', p.navigated, ['back']);
  p.settle();
  eq('and the timer firing afterwards does not commit it twice', p.navigated, ['back']);
}
{
  // Removing `committed` means the gesture keeps being evaluated after crossing COMMIT_PX, where
  // it used to stop listening entirely. Reversing past the line therefore cancels now - the
  // interesting half of the reversal guard, and consistent with reading the LAST position.
  const p = newPage(js);
  p.swipe(12, -10);
  p.swipe(20, 10);
  p.settle();
  eq('reversing after crossing the commit distance cancels', p.navigated, []);
}
{
  // The vertical rule is the opposite call, and it is `reachedCommit`. verticalPath only ever
  // grows, so once the gesture is past the line every further event is another chance to cancel a
  // swipe the user already completed - including events the user did not make, since a momentum
  // tail carries 325-2500px (AGENTS.md) and any dy in it clears CANCEL_VERTICAL_HIGH outright.
  // Past the line only the horizontal position decides, which is what a native swipe-back does.
  const p = newPage(js);
  p.swipe(10, -10);
  for (let i = 0; i < 12; i++) p.wheel(-1, i % 2 === 0 ? 9 : -9);
  p.settle();
  eq('vertical drift after crossing the commit distance no longer cancels', p.navigated, ['back']);
}
{
  // The same rule BEFORE the line still cancels, which is the half that must not be lost: this is
  // one event short of COMMIT_PX when the wobble starts.
  const p = newPage(js);
  p.swipe(8, -10);
  for (let i = 0; i < 12; i++) p.wheel(-1, i % 2 === 0 ? 9 : -9);
  p.settle();
  eq('vertical drift before the commit distance still cancels', p.navigated, []);
}
{
  // The closest this harness gets to the momentum shape, sized to the top of the range measured in
  // AGENTS.md: 2400px of same-direction travel after the gesture is past the line, with a little
  // over 10% of it as vertical path. That clears CANCEL_VERTICAL_HIGH (270px) on its own - which
  // is exactly how a tail the user never made could have cancelled a swipe they had completed.
  // It must still commit, exactly once: late rather than lost is the whole claim decide() makes.
  const p = newPage(js);
  p.swipe(10, -10);
  for (let i = 0; i < 60; i++) p.wheel(-40, i % 2 === 0 ? 5 : -5);
  p.settle();
  eq('a long same-direction tail past the line still commits once', p.navigated, ['back']);
}
{
  // reachedCommit is latched only once the gesture also has MIN_EVENTS, so the two-big-deltas pair
  // decide() refuses on its own cannot switch the vertical tiers off on its way past the line.
  // Two 50px notches clear COMMIT_PX at eventCount 2; then real vertical scrolling (which
  // accumulates verticalPath without running the tiers, since those events carry no dx); then one
  // stray horizontal event, which is the tiers' single chance to see the whole shape. It has to
  // still be taken, or the pair has bought itself immunity it never earned.
  const p = newPage(js);
  p.swipe(2, -50);
  p.swipe(8, 0, 40);
  p.wheel(-1, 0);
  p.settle();
  eq('two big deltas past the line do not switch the vertical tiers off', p.navigated, []);
}

console.log('\ngestures that must not navigate');
{
  const p = newPage(js);
  p.swipe(6, -10);
  eq('abandoned short of the threshold', p.navigated, []);
  check('leaves its overlay up while the fingers are down', p.visibleOverlays() === 1, p.visibleOverlays());
  p.settle();
  check('and hides it when they lift, with no further event', p.visibleOverlays() === 0, p.visibleOverlays());
}
{
  const p = newPage(js);
  const carousel = p.element({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 300, overflowX: 'auto' });
  p.swipe(12, -10, 0, carousel);
  p.settle();
  eq('over something that can still scroll that way', p.navigated, []);
}
{
  const p = newPage(js);
  // Same element, already scrolled hard against the edge the swipe is pushing toward.
  const carousel = p.element({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 0, overflowX: 'auto' });
  p.swipe(12, -10, 0, carousel);
  p.settle();
  eq('but does navigate once that element is at its edge', p.navigated, ['back']);
}
{
  const p = newPage(js);
  const styled = p.element({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 300, overflowX: 'hidden' });
  p.swipe(12, -10, 0, styled);
  p.settle();
  eq('overflow:hidden is not a scroll chain', p.navigated, ['back']);
}
{
  const p = newPage(js);
  p.swipe(3, -MAX_STEP_PX);
  p.settle();
  eq('a discrete mouse wheel, however far it travels', p.navigated, []);
}
{
  const p = newPage(js);
  p.wheelRaw({ deltaMode: 1, deltaX: -40, deltaY: 0, target: p.body, composedPath: () => [p.body] });
  p.swipe(12, -10);
  p.settle();
  eq('anything that is not pixel-mode', p.navigated, []);
}
{
  // Diagonal: vertical travel outruns the floor immediately.
  const p = newPage(js);
  p.swipe(12, -10, -40);
  p.settle();
  eq('a diagonal drag', p.navigated, []);
}
{
  const p = newPage(js);
  p.swipe(5, 0, -50);
  p.swipe(12, -10);
  p.settle();
  eq('a vertical scroll that curls into a horizontal one', p.navigated, []);
}
{
  const p = newPage(js);
  // Far enough the other way that, without the reversal guard, the running total alone would
  // cross the commit threshold. A shorter reversal proves nothing: it fails to navigate whether
  // the guard is there or not.
  p.swipe(5, -10);
  p.swipe(15, 10);
  p.settle();
  eq('a swipe reversed halfway', p.navigated, []);
}
{
  const p = newPage(js, { state: { back: false, forward: true } });
  p.swipe(12, -10);
  p.settle();
  eq('a direction with no history entry', p.navigated, []);
  check('and shows no affordance for it', p.visibleOverlays() === 0, p.visibleOverlays());
}
{
  const p = newPage(js, { state: null });
  p.swipe(12, -10);
  p.settle();
  eq('before the host has said what is navigable', p.navigated, []);
}
{
  const p = newPage(js, { subframe: true });
  check('a subframe installs nothing at all', p.installed() === 0, p.installed());
}

console.log("\nChrome's cancellation tiers (history_swiper.mm)");
{
  // Vertical at ~60% of horizontal. The old half-of-horizontal rule threw this away; Chrome takes
  // it, and this is the shape of an ordinary slightly-sloped swipe.
  const p = newPage(js);
  p.swipe(14, -10, 6);
  p.settle();
  eq('a swipe with honest slope is taken', p.navigated, ['back']);
}
{
  // Rule 1: yDelta > 2 * xDelta.
  const p = newPage(js);
  p.swipe(14, -10, -25);
  p.settle();
  eq('strongly vertical is refused', p.navigated, []);
}
{
  // Rule 2: yDelta * 1.3 > xDelta, once vertical passes the low threshold. 8*1.3 = 10.4 > 10.
  const p = newPage(js);
  p.swipe(14, -10, 8);
  p.settle();
  eq('vertical past about three quarters of horizontal is refused', p.navigated, []);
}
{
  // The path-length asymmetry, which is the point of measuring vertical the way Chrome does.
  // This wobble nets out to roughly zero vertical, so a rule reading the NET total would take it;
  // as a path length it accumulates and rule 2 refuses it.
  //
  // Rule 3 (yDelta > 3x the commit distance) is deliberately not exercised: it cannot be reached
  // before a gesture commits, here or in Chrome, since both use the same 3:1 ratio between it and
  // the navigation threshold. It is a backstop for a gesture that wanders without committing.
  const p = newPage(js);
  for (let i = 0; i < 20; i++) p.wheel(-10, i % 2 === 0 ? 8 : -8);
  p.settle();
  eq('vertical wobble that nets to zero is still refused', p.navigated, []);
}
{
  // The same wobble read as a NET total would be about zero and would sail through, which is the
  // bug this asymmetry prevents.
  const p = newPage(js);
  p.swipe(14, -10, 0);
  p.settle();
  eq('and a clean swipe still is not', p.navigated, ['back']);
}

console.log('\nswitching it off while a page is open');
{
  const p = newPage(js, { state: { enabled: false, back: true, forward: true } });
  p.swipe(12, -10);
  p.settle();
  eq('a page told the gesture is off does nothing', p.navigated, []);
  check('and draws no chevron', p.visibleOverlays() === 0, p.visibleOverlays());
}
{
  // The host pushes the flag onto the existing state object, so a page loaded before this shipped
  // has a state with no `enabled` key at all. Absent must mean on, not off.
  const p = newPage(js, { state: { back: true, forward: true } });
  p.swipe(12, -10);
  p.settle();
  eq('a state without the flag still works', p.navigated, ['back']);
}

console.log('\nthe root scroller');
{
  // `html { overflow-x: hidden }` with content wider than the viewport. scrollWidth still exceeds
  // clientWidth under hidden, so a root that skipped the overflow test claimed it could scroll and
  // killed the forward swipe on every such page, silently.
  const p = newPage(js, {
    root: { scrollWidth: 4000, clientWidth: 800, scrollLeft: 0, overflowX: 'hidden' },
  });
  p.swipe(12, 10);
  p.settle();
  eq('a root that cannot scroll does not block the swipe', p.navigated, ['forward']);
}
{
  // And the case the special case exists for: an ordinary page whose root really does scroll
  // sideways still keeps the gesture for itself.
  const p = newPage(js, {
    root: { scrollWidth: 4000, clientWidth: 800, scrollLeft: 500, overflowX: 'visible' },
  });
  p.swipe(12, -10);
  p.settle();
  eq('a root that can still scroll keeps the gesture', p.navigated, []);
}

console.log('\na fast flick');
{
  // Deltas that grow past the mouse-wheel bound partway through a gesture. Abandoning on those
  // meant the harder you swiped the less likely it was to work.
  const p = newPage(js);
  p.swipe(3, -8);
  p.swipe(6, -MAX_STEP_PX - 10, 0, undefined, 1_003);
  p.settle();
  eq('a flick that accelerates still commits', p.navigated, ['back']);
}
{
  const p = newPage(js);
  p.swipe(4, -MAX_STEP_PX - 10);
  p.settle();
  eq('but wheel-shaped from the first event is still refused', p.navigated, []);
}

console.log('\nthe affordance');
{
  const p = newPage(js);
  p.swipe(MIN_EVENTS - 1, -10);
  check('waits for the gesture to look real', p.visibleOverlays() === 0, p.visibleOverlays());
  p.swipe(2, -10);
  check('then appears', p.visibleOverlays() === 1, p.visibleOverlays());
  check('anchored to the leading edge for back', /\.p\{[^}]*left:0;/.test(p.puckHtml()), p.puckHtml());
  // Asserted against the host's own inline style, not merely "an overlay exists": pointer-events
  // is the one line stopping the chevron eating clicks over the page, and a test that never reads
  // it passes just as happily with the line deleted.
  check('and does not take pointer events', /pointer-events\s*:\s*none/.test(p.hostCss()), p.hostCss());
  p.settle();
  check('and is hidden when the gesture ends', p.visibleOverlays() === 0, p.visibleOverlays());
}
{
  // A second gesture starting while the previous chevron is still fading out. The exit animation
  // runs EXIT_MS against a GESTURE_GAP_MS of quiet, so this window is not a corner case - it is
  // what swiping
  // twice in a row looks like.
  const p = newPage(js);
  p.swipe(5, -10);
  p.advance(GAP_MS + 80);
  p.swipe(5, -10);
  check('never stacks a second chevron', p.liveOverlays() === 1, p.liveOverlays());
}
{
  const p = newPage(js);
  p.swipe(5, 10);
  check('anchored to the trailing edge for forward', /\.p\{[^}]*right:0;/.test(p.puckHtml()), p.puckHtml());
}
{
  const p = newPage(js);
  p.swipe(5, -10);
  p.pagehide();
  p.settle();
  check('and when the page goes away mid-swipe', p.visibleOverlays() === 0, p.visibleOverlays());
}
{
  const p = newPage(js, { noBody: true });
  p.swipe(12, -10);
  p.settle();
  eq('a document with no body still navigates', p.navigated, ['back']);
}

console.log('');
if (failures) {
  console.log(`${failures} failing check(s)`);
  process.exit(1);
}
console.log('all checks passed');
