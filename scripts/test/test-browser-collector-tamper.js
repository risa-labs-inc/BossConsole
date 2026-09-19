#!/usr/bin/env node
/**
 * Executes the in-page interaction collector and asserts what a hostile page can and cannot do
 * to it.
 *
 * Why this exists, and why it is a separate file from test-browser-collector.js: that one pins
 * what the collector *reports*. This one pins who can make it report, and who can stop it. The
 * collector is JavaScript inside a Kotlin string and it shares a JS world with the page -
 * JxBrowser 9.5.0 exposes no isolated world (no class in the pinned jar mentions one, and
 * neither `InjectJsCallback` nor `Frame.executeJavaScript` takes a world parameter), so every
 * guarantee here is about *reachability* rather than about a sandbox. Reading the script cannot
 * establish that: "delete the property first" and "read the property first" look equally
 * plausible, and only running it tells them apart.
 *
 * The properties asserted, and what each is worth:
 *
 *   - the bridge is removed from `window` before the collector does anything else, so a page
 *     script running after document-start injection has no reference to call;
 *   - the only thing left behind is one non-enumerable, non-configurable property holding the
 *     per-route reset hook - no credential, no channel;
 *   - a page that pre-sets the old `__bossInteractionStarted` flag no longer suppresses
 *     collection, because the collector no longer consults a page-settable name;
 *   - every batch carries the session nonce, which is what still holds if removal ever fails.
 *
 * What it does NOT assert, and cannot: a page can synthesise DOM events (`dispatchEvent`) and
 * the collector will faithfully report them, with a valid nonce. That residual is documented in
 * the PR rather than papered over here.
 *
 * Usage: node scripts/test/test-browser-collector-tamper.js
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repoRoot = path.resolve(__dirname, '../..');
const scriptKt = path.join(
  repoRoot,
  'composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserInteractionScript.kt',
);

const TEST_NONCE = 'aaaabbbbccccddddaaaabbbbccccdddd';
const TEST_SLOT = '__boss_i_00112233445566778899aabb';

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

// ---------------------------------------------------------------------------
// Extract the collector out of the Kotlin string, resolving $CONSTANTS and the
// two literals the host injects at call time.
// ---------------------------------------------------------------------------
function loadCollector(nonce, slotName) {
  const src = fs.readFileSync(scriptKt, 'utf8');
  const consts = {};
  for (const m of src.matchAll(/const val (\w+)\s*(?::\s*\w+)?\s*=\s*"?([^"\n]+?)"?\s*\n/g)) {
    consts[m[1]] = m[2];
  }
  const body = src.split('"""')[1];
  const lines = body.split('\n').slice(1);
  const indent = Math.min(
    ...lines.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
  );
  let js = lines.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
  for (const k of Object.keys(consts).sort((a, b) => b.length - a.length)) {
    js = js.split('$' + k).join(consts[k]);
  }
  js = js.split('$nonce').join(nonce).split('$slotName').join(slotName);
  const unresolved = js.split('\n').filter((l) => l.includes('$'));
  if (unresolved.length) {
    throw new Error(`unresolved interpolation in collector source: ${unresolved[0]}`);
  }
  return js;
}

// ---------------------------------------------------------------------------
// The smallest window/document the collector touches, so an added global fails loudly.
// ---------------------------------------------------------------------------
function newPage(collectorJs, options = {}) {
  const listeners = {};
  const emitted = [];
  const nonces = [];
  const add = (t, f) => (listeners[t] = listeners[t] || []).push(f);

  const windowObject = { addEventListener: add, innerHeight: 800, pageYOffset: 0 };
  const sandbox = {
    window: windowObject,
    document: {
      documentElement: { scrollHeight: 2400, scrollTop: 0 },
      body: { nodeType: 1, tagName: 'BODY' },
      addEventListener: add,
    },
    setInterval: () => 0,
    JSON,
    Math,
    String,
    Date: { now: () => 0 },
  };

  const bridge = {
    emit: (nonce, s) => {
      nonces.push(nonce);
      emitted.push(...JSON.parse(s));
    },
  };

  if (options.bridgeNonConfigurable) {
    // A host (or a page) that defined the property so it cannot be deleted: the collector has
    // to fall back to making it unreachable rather than leaving the channel standing.
    Object.defineProperty(windowObject, '__bossInteraction', {
      value: bridge,
      enumerable: false,
      configurable: false,
      writable: true,
    });
  } else if (options.bridgeMissing) {
    // Nothing published: the collector must do nothing at all rather than throw.
  } else {
    windowObject.__bossInteraction = bridge;
  }

  if (options.preSetStartedFlag) {
    // The old suppression vector: a page sets the collector's own "already running" flag
    // before the collector is injected, and the collector used to return early forever.
    windowObject.__bossInteractionStarted = true;
  }
  if (options.preSetResetSlot !== undefined) {
    // A page that knows the host-chosen slot name and got there first.
    windowObject[TEST_SLOT] = options.preSetResetSlot;
  }

  const ownBefore = Object.getOwnPropertyNames(windowObject).slice();
  vm.createContext(sandbox);
  const run = () => vm.runInContext(collectorJs, sandbox);
  run();

  const fire = (type, event) => (listeners[type] || []).forEach((f) => f(event));
  // The collector batches and flushes on a timer, and the fake clock never advances, so a
  // click only reaches the host once something flushes. `pagehide` is the collector's own
  // flush hook, so driving it is the same path a real navigation takes.
  const click = (target) => {
    fire('click', { target });
    fire('pagehide', {});
  };
  const element = { nodeType: 1, tagName: 'BUTTON', parentElement: null };

  return {
    sandbox,
    windowObject,
    ownBefore,
    emitted,
    nonces,
    click,
    element,
    clickCount: () => (listeners['click'] || []).length,
    reinject: () => {
      // Exactly what the host does on every main-frame navigation: publish, then inject.
      if (!options.bridgeNonConfigurable) {
        windowObject.__bossInteraction = bridge;
      }
      run();
    },
  };
}

// ---------------------------------------------------------------------------
const js = loadCollector(TEST_NONCE, TEST_SLOT);
console.log('collector tamper resistance');

// --- the channel is out of reach -------------------------------------------
{
  const p = newPage(js);
  check(
    'the bridge is not reachable after injection',
    p.windowObject.__bossInteraction === undefined || p.windowObject.__bossInteraction === null,
    `window.__bossInteraction is ${typeof p.windowObject.__bossInteraction}`,
  );

  const added = Object.getOwnPropertyNames(p.windowObject).filter((n) => !p.ownBefore.includes(n));
  eq('the collector adds exactly one own property to window', added, [TEST_SLOT]);

  check(
    'that property is not enumerable, so for..in and Object.keys miss it',
    !Object.keys(p.windowObject).includes(TEST_SLOT),
  );
  const descriptor = Object.getOwnPropertyDescriptor(p.windowObject, TEST_SLOT);
  check('that property is non-configurable, so the page cannot delete it', descriptor.configurable === false);
  check('that property is non-writable, so the page cannot replace it', descriptor.writable === false);
  check(
    'the property that survives holds no channel and no credential',
    typeof descriptor.value === 'function' && !String(descriptor.value).includes(TEST_NONCE),
  );
}

// --- a non-configurable bridge is made unreachable, not left standing -------
{
  const p = newPage(js, { bridgeNonConfigurable: true });
  check(
    'a bridge the host made non-configurable is still taken out of reach',
    p.windowObject.__bossInteraction === null,
    `window.__bossInteraction is ${String(p.windowObject.__bossInteraction)}`,
  );
  p.click(p.element);
  check('and collection still works afterwards', p.emitted.length === 1, `emitted ${p.emitted.length}`);
}

// --- suppression no longer works -------------------------------------------
{
  const p = newPage(js, { preSetStartedFlag: true });
  p.click(p.element);
  eq(
    'a page that pre-set __bossInteractionStarted is still collected',
    p.emitted.map((e) => e.type),
    ['CLICK'],
  );
}

{
  // A page that puts a NON-callable in the slot must not suppress collection: the guard
  // requires a callable, so a bare value is ignored and the collector proceeds.
  //
  // A page that puts a *function* there is a different story, and it is NOT asserted here
  // because it is not a guarantee this design can make: the collector would read it as its own
  // previous instance and return early. Reaching that requires a page to know the host-chosen
  // slot name AND to run before the collector in the same document. The name is drawn per tab
  // from a CSPRNG, is independent of the nonce, and is never written anywhere the page can read
  // before the collector itself defines it - so on the first document in a tab the name does
  // not exist yet, and on later navigations the collector is injected at document start, ahead
  // of page scripts. Both halves of that are ordering facts about JxBrowser, not about this
  // script, so they are listed as manual real-browser checks in the PR rather than claimed.
  const p = newPage(js, { preSetResetSlot: 'not a function' });
  p.click(p.element);
  eq(
    'a non-callable in the reset slot does not suppress collection',
    p.emitted.map((e) => e.type),
    ['CLICK'],
  );
}

{
  // What IS guaranteed, and is what closes the loop: once the collector has defined the hook,
  // the page cannot take it over. Non-writable defeats assignment, non-configurable defeats
  // defineProperty.
  const p = newPage(js);
  const before = Object.getOwnPropertyDescriptor(p.windowObject, TEST_SLOT).value;

  p.windowObject[TEST_SLOT] = () => {
    throw new Error('page-supplied hook');
  };
  check(
    'a page cannot overwrite the reset hook by assignment',
    Object.getOwnPropertyDescriptor(p.windowObject, TEST_SLOT).value === before,
  );

  let redefineThrew = false;
  try {
    Object.defineProperty(p.windowObject, TEST_SLOT, { value: () => undefined });
  } catch (_) {
    redefineThrew = true;
  }
  check(
    'a page cannot redefine the reset hook',
    redefineThrew && Object.getOwnPropertyDescriptor(p.windowObject, TEST_SLOT).value === before,
  );
  check(
    'and it cannot delete it either',
    (() => {
      try {
        return !delete p.windowObject[TEST_SLOT];
      } catch (_) {
        return true;
      }
    })(),
  );
}

// --- the nonce travels with the batch --------------------------------------
{
  const p = newPage(js);
  p.click(p.element);
  p.click(p.element);
  eq('every batch carries the session nonce', p.nonces, [TEST_NONCE, TEST_NONCE]);
  check(
    'the nonce is not written to any property name the page can enumerate',
    !Object.getOwnPropertyNames(p.windowObject).some((n) => n.includes(TEST_NONCE)),
  );
  check('and the reset slot name is not derived from it', !TEST_SLOT.includes(TEST_NONCE));
}

// --- nothing published means nothing happens -------------------------------
{
  const p = newPage(js, { bridgeMissing: true });
  p.click(p.element);
  eq('with no bridge published the collector reports nothing', p.emitted, []);
  check('and leaves nothing behind either', !(TEST_SLOT in p.windowObject));
}

// --- re-injection resets rather than double-registers ----------------------
{
  const p = newPage(js);
  const before = p.clickCount();
  p.reinject();
  eq('a re-injection does not add a second set of listeners', p.clickCount(), before);

  p.click(p.element);
  eq('so one click is still one event', p.emitted.map((e) => e.type), ['CLICK']);

  // The reset hook is what makes a route change report scroll depth again; it must still be
  // callable after the re-injection took the early-return path.
  p.reinject();
  eq('the reset hook survives re-injection', typeof p.windowObject[TEST_SLOT], 'function');
}

console.log('');
if (failures) {
  console.log(`${failures} collector tamper check(s) failed.`);
  process.exit(1);
}
console.log('All collector tamper checks passed.');
