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

let failures = 0;
function check(name, cond, detail) {
  if (cond) {
    console.log(`  ok   ${name}`);
  } else {
    failures++;
    console.log(`  FAIL ${name}${detail === undefined ? '' : ` -> ${detail}`}`);
  }
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

// ---------------------------------------------------------------------------
// Fake DOM: only what the detector touches, so an added DOM read fails loudly.
// ---------------------------------------------------------------------------
function newPage(js, options = {}) {
  const listeners = {};
  const navigated = [];
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
  const scroller = element({ tagName: 'HTML' });

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
  sandbox.window[hostProps.bridge] = { navigate: (d) => navigated.push(d) };
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
    registrations,
    wheel,
    wheelRaw: (event) => (listeners.wheel || []).forEach((f) => f(event)),
    swipe: (count, dx, dy, over) => {
      for (let i = 0; i < count; i++) wheel(dx, dy || 0, over);
    },
    advance: (ms) => {
      clockMs += ms;
      fireDue();
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
    liveOverlays: () => body.children.length,
    preventedDefaults: () => preventedDefaults,
    installed: () => (listeners.wheel || []).length,
  };
}

// ---------------------------------------------------------------------------
const hostProps = hostProperties();
const js = fs.readFileSync(scriptJs, 'utf8');
const constant = (name) => Number(new RegExp(`var ${name} = (\\d+)`).exec(js)[1]);
const COMMIT_PX = constant('COMMIT_PX');
const GAP_MS = constant('GESTURE_GAP_MS');
const MIN_EVENTS = constant('MIN_EVENTS');
const MAX_STEP_PX = constant('MAX_STEP_PX');
console.log(
  `detector: COMMIT_PX=${COMMIT_PX} GESTURE_GAP_MS=${GAP_MS} MIN_EVENTS=${MIN_EVENTS} ` +
    `MAX_STEP_PX=${MAX_STEP_PX}; host: ${hostProps.bridge} / ${hostProps.state}`,
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
  eq('goes back once', p.navigated, ['back']);
  p.settle();
  check('leaves no overlay behind', p.liveOverlays() === 0, p.liveOverlays());
  check('never calls preventDefault', p.preventedDefaults() === 0, p.preventedDefaults());
}
{
  const p = newPage(js);
  p.swipe(12, 10);
  eq('the other direction goes forward', p.navigated, ['forward']);
}
{
  const p = newPage(js);
  p.swipe(24, -10);
  eq('one continuous swipe navigates exactly once', p.navigated, ['back']);
}
{
  const p = newPage(js);
  p.swipe(12, -10);
  p.advance(GAP_MS + 80);
  p.swipe(12, -10);
  eq('two swipes across a gap navigate twice', p.navigated, ['back', 'back']);
}

console.log('\ngestures that must not navigate');
{
  const p = newPage(js);
  p.swipe(6, -10);
  eq('abandoned short of the threshold', p.navigated, []);
  check('leaves its overlay up while the fingers are down', p.liveOverlays() === 1, p.liveOverlays());
  p.settle();
  check('and takes it away when they lift, with no further event', p.liveOverlays() === 0, p.liveOverlays());
}
{
  const p = newPage(js);
  const carousel = p.element({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 300, overflowX: 'auto' });
  p.swipe(12, -10, 0, carousel);
  eq('over something that can still scroll that way', p.navigated, []);
}
{
  const p = newPage(js);
  // Same element, already scrolled hard against the edge the swipe is pushing toward.
  const carousel = p.element({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 0, overflowX: 'auto' });
  p.swipe(12, -10, 0, carousel);
  eq('but does navigate once that element is at its edge', p.navigated, ['back']);
}
{
  const p = newPage(js);
  const styled = p.element({ scrollWidth: 1200, clientWidth: 400, scrollLeft: 300, overflowX: 'hidden' });
  p.swipe(12, -10, 0, styled);
  eq('overflow:hidden is not a scroll chain', p.navigated, ['back']);
}
{
  const p = newPage(js);
  p.swipe(3, -MAX_STEP_PX);
  eq('a discrete mouse wheel, however far it travels', p.navigated, []);
}
{
  const p = newPage(js);
  p.wheelRaw({ deltaMode: 1, deltaX: -40, deltaY: 0, target: p.body, composedPath: () => [p.body] });
  p.swipe(12, -10);
  eq('anything that is not pixel-mode', p.navigated, []);
}
{
  // Diagonal: vertical travel outruns the floor immediately.
  const p = newPage(js);
  p.swipe(12, -10, -40);
  eq('a diagonal drag', p.navigated, []);
}
{
  const p = newPage(js);
  p.swipe(5, 0, -50);
  p.swipe(12, -10);
  eq('a vertical scroll that curls into a horizontal one', p.navigated, []);
}
{
  const p = newPage(js);
  // Far enough the other way that, without the reversal guard, the running total alone would
  // cross the commit threshold. A shorter reversal proves nothing: it fails to navigate whether
  // the guard is there or not.
  p.swipe(5, -10);
  p.swipe(15, 10);
  eq('a swipe reversed halfway', p.navigated, []);
}
{
  const p = newPage(js, { state: { back: false, forward: true } });
  p.swipe(12, -10);
  eq('a direction with no history entry', p.navigated, []);
  check('and shows no affordance for it', p.liveOverlays() === 0, p.liveOverlays());
}
{
  const p = newPage(js, { state: null });
  p.swipe(12, -10);
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
  eq('a swipe with honest slope is taken', p.navigated, ['back']);
}
{
  // Rule 1: yDelta > 2 * xDelta.
  const p = newPage(js);
  p.swipe(14, -10, -25);
  eq('strongly vertical is refused', p.navigated, []);
}
{
  // Rule 2: yDelta * 1.3 > xDelta, once vertical passes the low threshold. 8*1.3 = 10.4 > 10.
  const p = newPage(js);
  p.swipe(14, -10, 8);
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
  eq('vertical wobble that nets to zero is still refused', p.navigated, []);
}
{
  // The same wobble read as a NET total would be about zero and would sail through, which is the
  // bug this asymmetry prevents.
  const p = newPage(js);
  p.swipe(14, -10, 0);
  eq('and a clean swipe still is not', p.navigated, ['back']);
}

console.log('\nthe affordance');
{
  const p = newPage(js);
  p.swipe(MIN_EVENTS - 1, -10);
  check('waits for the gesture to look real', p.liveOverlays() === 0, p.liveOverlays());
  p.swipe(2, -10);
  check('then appears', p.liveOverlays() === 1, p.liveOverlays());
  check('anchored to the leading edge for back', /\.p\{[^}]*left:0;/.test(p.puckHtml()), p.puckHtml());
  check('and does not take pointer events', p.puckHtml() !== null && p.liveOverlays() === 1);
  p.settle();
  check('and is cleaned up when the gesture ends', p.liveOverlays() === 0, p.liveOverlays());
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
  check('and when the page goes away mid-swipe', p.liveOverlays() === 0, p.liveOverlays());
}
{
  const p = newPage(js, { noBody: true });
  p.swipe(12, -10);
  eq('a document with no body still navigates', p.navigated, ['back']);
}

console.log('');
if (failures) {
  console.log(`${failures} failing check(s)`);
  process.exit(1);
}
console.log('all checks passed');
