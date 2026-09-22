#!/usr/bin/env node
/**
 * Runs the in-page interaction collector against a fake DOM.
 *
 * BrowserInteractionScript is JavaScript living inside a Kotlin string, so the Kotlin test
 * suite can only grep it. The structural tests there pin what it must never READ; this pins
 * what it DOES - rage-click thresholds, the sibling and path budgets, scroll bucketing, the
 * per-route reset - by executing it. Both of the budget bugs this guards were found by
 * running the script, not by reading it.
 *
 * It also closes a coupling the Kotlin tests cannot see: the collector's MAX_PATH_CHARS and
 * the host's MAX_PATH_LENGTH / PATH_SHAPE are hand-matched across two files and two
 * languages, and the host REFUSES an over-long path rather than truncating it - so a drift
 * makes elementPath silently vanish. Both constants are read from source here and the paths
 * the collector actually produces are checked against the host's own rule.
 *
 * Usage: node scripts/test/test-browser-collector.js
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repoRoot = path.resolve(__dirname, '../..');
const scriptKt = path.join(
  repoRoot,
  'composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserInteractionScript.kt',
);
const analyticsKt = path.join(
  repoRoot,
  'composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserAnalytics.kt',
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
  check(name, JSON.stringify(actual) === JSON.stringify(expected), `got ${JSON.stringify(actual)}, want ${JSON.stringify(expected)}`);
}

// ---------------------------------------------------------------------------
// Extract the collector source out of the Kotlin string, resolving $CONSTANTS.
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
  // The two runtime literals the host injects. They are function parameters in the Kotlin,
  // not `const val`s, so the constant scan above cannot see them.
  js = js.split('$nonce').join(nonce).split('$slotName').join(slotName);
  const unresolved = js.split('\n').filter((l) => l.includes('$'));
  if (unresolved.length) {
    throw new Error(`unresolved interpolation in collector source: ${unresolved[0]}`);
  }
  return { js, consts };
}

/** The host's own path rule, read from BrowserAnalytics rather than restated here. */
function hostPathRule() {
  const src = fs.readFileSync(analyticsKt, 'utf8');
  const maxLength = Number(/MAX_PATH_LENGTH = (\d+)/.exec(src)[1]);
  const shape = /PATH_SHAPE = Regex\("""(.+?)"""\)/.exec(src)[1];
  return { maxLength, shape: new RegExp(`^(?:${shape})$`) };
}

// ---------------------------------------------------------------------------
// Fake DOM: only what the collector touches, so an added DOM read fails loudly.
// ---------------------------------------------------------------------------
function newPage(collectorJs) {
  const listeners = {};
  const emitted = [];
  let siblingReads = 0;
  let clockMs = 0;

  const el = (tag, props = {}) => {
    const node = {
      nodeType: 1,
      tagName: tag.toUpperCase(),
      children: [],
      parentElement: null,
      _attrs: props.attrs || {},
      get previousElementSibling() {
        const p = this.parentElement;
        if (!p) return null;
        siblingReads++;
        const i = p.children.indexOf(this);
        return i > 0 ? p.children[i - 1] : null;
      },
      getAttribute(n) {
        return this._attrs[n] !== undefined ? this._attrs[n] : null;
      },
    };
    return Object.assign(node, props.props || {});
  };
  const append = (parent, child) => {
    child.parentElement = parent;
    parent.children.push(child);
    return child;
  };

  const add = (t, f) => {
    (listeners[t] = listeners[t] || []).push(f);
  };
  const sandbox = {
    window: { addEventListener: add, innerHeight: 800, pageYOffset: 0 },
    document: {
      documentElement: { scrollHeight: 2400, scrollTop: 0 },
      body: { nodeType: 1, tagName: 'BODY' },
      addEventListener: add,
    },
    setInterval: () => 0,
    JSON,
    // A controllable clock: the rage window is a time comparison, and with a real Date every
    // click in a test lands in the same millisecond, which only ever exercises the burst case.
    Date: { now: () => clockMs },
    Math,
    String,
  };
  // The channel credential the collector is expected to present on every batch.
  const nonces = [];
  const bridgeObject = {
    emit: (nonce, s) => {
      nonces.push(nonce);
      emitted.push(...JSON.parse(s));
    },
  };
  // Exactly what the host does: publish the bridge, then inject. The collector takes the
  // bridge out of reach on the way in, so a re-injection that did not re-publish first would
  // find nothing - which is the property the tamper suite asserts.
  const publish = () => {
    sandbox.window.__bossInteraction = bridgeObject;
  };
  publish();
  vm.createContext(sandbox);
  const run = () => vm.runInContext(collectorJs, sandbox);
  run();

  return {
    el,
    append,
    emitted,
    sandbox,
    nonces,
    // What the host does on every main-frame navigation: re-publish, then re-inject.
    reinject: () => {
      publish();
      run();
    },
    fire: (type, target) => (listeners[type] || []).forEach((f) => f({ target })),
    flush: () => (listeners['pagehide'] || []).forEach((f) => f()),
    siblingReads: () => siblingReads,
    advance: (ms) => {
      clockMs += ms;
    },
    resetSiblingReads: () => {
      siblingReads = 0;
    },
    scrollTo: (y, target) => {
      sandbox.window.pageYOffset = y;
      (listeners['scroll'] || []).forEach((f) => f({ target }));
    },
  };
}

// ---------------------------------------------------------------------------
/**
 * The two literals the host injects. Fixed here because this file pins BEHAVIOUR, not the
 * nonce: what a wrong or missing nonce does is pinned in
 * scripts/test/test-browser-collector-tamper.js.
 */
const TEST_NONCE = 'aaaabbbbccccddddaaaabbbbccccdddd';
const TEST_SLOT = '__boss_i_00112233445566778899aabb';

const { js, consts } = loadCollector(TEST_NONCE, TEST_SLOT);
const host = hostPathRule();
console.log(
  `collector: MAX_PATH_DEPTH=${consts.MAX_PATH_DEPTH} MAX_SIBLING_SCAN=${consts.MAX_SIBLING_SCAN} ` +
    `MAX_PATH_CHARS=${consts.MAX_PATH_CHARS}; host: MAX_PATH_LENGTH=${host.maxLength}`,
);

console.log('\nstructural attributes only');
{
  const p = newPage(js);
  const form = p.el('form');
  p.append(form, p.el('div'));
  const d2 = p.append(form, p.el('div'));
  const btn = p.append(d2, p.el('button', { props: { type: 'submit', name: 'save' } }));
  btn.textContent = 'Save patient John Smith';
  p.fire('click', btn);
  p.flush();
  const e = p.emitted[0];
  eq('reports the control kind', [e.tag, e.inputType, e.fieldName], ['button', 'submit', 'save']);
  eq('reports the structural path', e.path, 'form>div:2>button');
  check('never reports the truncation flag', !('truncated' in e), JSON.stringify(e));
  check(
    'no page content anywhere in the batch',
    !JSON.stringify(p.emitted).includes('John Smith'),
    JSON.stringify(p.emitted),
  );
}

console.log('\nname is read off form controls only');
{
  const p = newPage(js);
  const form = p.el('form');
  const cases = [
    ['div', null],
    ['img', null],
    ['a', null],
    ['input', 'patientMrn'],
    ['select', 'patientMrn'],
  ];
  const got = cases.map(([tag]) => {
    const node = p.append(form, p.el(tag, { props: { name: 'patientMrn' } }));
    p.emitted.length = 0;
    p.fire('click', node);
    p.flush();
    return p.emitted[0].fieldName === undefined ? null : p.emitted[0].fieldName;
  });
  eq('only form controls contribute a field name', got, cases.map(([, want]) => want));
}

console.log('\nsibling scan is bounded');
{
  const p = newPage(js);
  const tbody = p.el('tbody');
  let last = null;
  for (let i = 0; i < 10000; i++) last = p.append(tbody, p.el('tr'));
  p.resetSiblingReads();
  p.fire('click', last);
  p.flush();
  const scan = Number(consts.MAX_SIBLING_SCAN);
  check(
    `stays within its own declared cap of ${scan}`,
    p.siblingReads() <= scan + 1,
    p.siblingReads(),
  );
  // Deliberately an absolute number rather than the constant: this handler runs
  // synchronously ahead of the page's own, so what matters is that a click in a big table
  // is cheap, not that it is cheap relative to whatever the cap was raised to. Reading the
  // constant here would make raising it silently self-approving.
  const ABSOLUTE_SIBLING_BUDGET = 250;
  check(
    `a click in a 10,000-row table costs <= ${ABSOLUTE_SIBLING_BUDGET} sibling reads`,
    p.siblingReads() <= ABSOLUTE_SIBLING_BUDGET,
    p.siblingReads(),
  );
  eq('the ordinal is omitted rather than guessed', p.emitted[0].path, 'tbody>tr');
}

console.log('\nrage clicks');
{
  const p = newPage(js);
  const form = p.el('form');
  const btn = p.append(form, p.el('button'));
  for (let i = 0; i < 5; i++) p.fire('click', btn);
  p.flush();
  eq(
    'fires once, at the threshold, then stays quiet',
    p.emitted.map((e) => e.type),
    ['CLICK', 'CLICK', 'RAGE_CLICK'],
  );
  // The event sits in the queue until the next flush, so later clicks in the same burst
  // update its count in place. Reporting the threshold forever made three frustrated clicks
  // and thirty indistinguishable, while the host allows up to 100.
  eq('reports how many clicks actually happened', p.emitted[2].repeatCount, 5);
}
{
  const p = newPage(js);
  const form = p.el('form');
  const btn = p.append(form, p.el('button'));
  for (let i = 0; i < 3; i++) p.fire('click', btn);
  p.flush();
  eq(
    'a burst that stops at the threshold reports the threshold',
    p.emitted[2].repeatCount,
    Number(consts.RAGE_CLICK_THRESHOLD),
  );
}
{
  // The sibling cap collapses every deep row to one path, so path equality alone would
  // report three clicks on three different rows as rage.
  const p = newPage(js);
  const tbody = p.el('tbody');
  const rows = [];
  for (let i = 0; i < 300; i++) rows.push(p.append(tbody, p.el('tr')));
  p.fire('click', rows[150]);
  p.fire('click', rows[151]);
  p.fire('click', rows[152]);
  p.flush();
  eq(
    'different rows with a truncated path are not rage',
    p.emitted.map((e) => e.type),
    ['CLICK', 'CLICK', 'CLICK'],
  );
}

{
  // The window is anchored to the FIRST click of a run, not the last. Re-anchoring on every
  // click meant the run only ended at a gap longer than the window, so steady clicking - a
  // pagination arrow, a stepper, someone working a worklist - produced CLICK, CLICK,
  // RAGE_CLICK and then silence for as long as it continued. One per second is slow for rage
  // and entirely normal for the traffic this feature exists to measure.
  const p = newPage(js);
  const form = p.el('form');
  const btn = p.append(form, p.el('button'));
  for (let i = 0; i < 12; i++) {
    p.fire('click', btn);
    p.advance(900);
  }
  p.flush();
  eq('steady clicking keeps reporting CLICK', p.emitted.filter((e) => e.type === 'CLICK').length, 12);
  eq('and is never mistaken for rage', p.emitted.filter((e) => e.type === 'RAGE_CLICK').length, 0);
}
{
  // Genuine rage still fires: three inside the window, measured from the first.
  const p = newPage(js);
  const form = p.el('form');
  const btn = p.append(form, p.el('button'));
  for (let i = 0; i < 3; i++) {
    p.fire('click', btn);
    p.advance(200);
  }
  p.flush();
  eq(
    'a fast burst is still rage',
    p.emitted.map((e) => e.type),
    ['CLICK', 'CLICK', 'RAGE_CLICK'],
  );
}

console.log('\npath budget matches what the host accepts');
{
  // The host refuses an over-long path outright rather than truncating, so anything the
  // collector can produce has to satisfy its rule or elementPath silently disappears.
  const p = newPage(js);
  const shapes = [
    ['app-patient-summary-card', 8],
    ['x', 8],
    ['a-fairly-long-custom-element-name', 8],
  ];
  for (const [tag, depth] of shapes) {
    let node = p.el(tag);
    for (let i = 0; i < depth; i++) node = p.append(node, p.el(tag));
    p.emitted.length = 0;
    p.fire('click', node);
    p.flush();
    const produced = p.emitted[0].path;
    check(
      `<${tag}> x${depth}: within the host's ${host.maxLength}-char limit`,
      produced.length <= host.maxLength,
      `${produced.length} chars`,
    );
    check(`<${tag}> x${depth}: matches the host's PATH_SHAPE`, host.shape.test(produced), produced);
    check(`<${tag}> x${depth}: is not empty`, produced.length > 0, produced);
  }
}

console.log('\nscroll depth');
{
  const p = newPage(js);
  p.scrollTo(400, p.sandbox.document); // 25%
  p.scrollTo(800, p.sandbox.document); // 50%
  p.scrollTo(400, p.sandbox.document); // back up: high-water mark holds
  p.flush();
  eq(
    'quantised to quarters, high-water only',
    p.emitted.map((e) => e.scrollDepthPercent),
    [25, 50],
  );

  p.emitted.length = 0;
  p.scrollTo(1600, p.el('div')); // an inner scrollable element
  p.flush();
  eq('an inner scroller does not advance page depth', p.emitted, []);

  p.emitted.length = 0;
  p.reinject(); // the host re-runs the script: an SPA route change
  p.scrollTo(800, p.sandbox.document);
  p.flush();
  eq(
    'a route change reports depth again rather than staying at the mark',
    p.emitted.map((e) => e.scrollDepthPercent),
    [50],
  );
}

console.log('\nre-injection is otherwise a no-op');
{
  const p = newPage(js);
  const form = p.el('form');
  const btn = p.append(form, p.el('button'));
  p.reinject();
  p.reinject();
  p.fire('click', btn);
  p.flush();
  eq('one listener set, so one event per click', p.emitted.length, 1);
}

console.log('\nstructural tokens are cut above the host cap, not at it');
{
  // sanitizeToken REFUSES a value over its cap. Cutting to exactly that cap here made the
  // refusal unreachable: a 40-char <app-patient-encounter-summary-card> arrived as a
  // valid-looking 32-char prefix, so the host reported a tag that does not exist and two
  // long elements sharing a prefix collapsed into one. Same trap as the field-name cap
  // below, one constant apart, and nothing caught the equality.
  const analytics = fs.readFileSync(analyticsKt, 'utf8');
  const hostCap = Number(/MAX_TAG_LENGTH = (\d+)/.exec(analytics)[1]);
  const collectorCap = Number(consts.MAX_TOKEN_CHARS);
  check(
    `collector cap ${collectorCap} > host cap ${hostCap}`,
    collectorCap > hostCap,
    `${collectorCap} vs ${hostCap}`,
  );

  const p = newPage(js);
  const longTag = 'app-patient-encounter-summary-card'; // 34 chars, over the host cap
  p.fire('click', p.el(longTag));
  p.flush();
  check(
    'a long custom element reaches the host over its cap, to be refused there',
    p.emitted[0].tag.length > hostCap,
    `${p.emitted[0].tag} (${p.emitted[0].tag.length})`,
  );
}

console.log('\nfield names are cut above the host cap, not at it');
{
  // The host redacts digit runs and THEN truncates, so that a run straddling its 64-char
  // boundary is not left as a stray one- or two-digit tail. Slicing to the same 64 here
  // would hand it an already-cut string and defeat that ordering.
  const analytics = fs.readFileSync(analyticsKt, 'utf8');
  const hostCap = Number(/MAX_FIELD_NAME_LENGTH = (\d+)/.exec(analytics)[1]);
  const collectorCap = Number(consts.MAX_FIELD_NAME_CHARS);
  check(
    `collector cap ${collectorCap} > host cap ${hostCap}`,
    collectorCap > hostCap,
    `${collectorCap} vs ${hostCap}`,
  );

  const p = newPage(js);
  const form = p.el('form');
  const longName = 'a'.repeat(60) + '_encounter_row_4417882';
  const input = p.append(form, p.el('input', { props: { type: 'text', name: longName } }));
  p.fire('click', input);
  p.flush();
  const sent = p.emitted[0].fieldName;
  check(
    'the whole digit run survives the collector, for the host to redact',
    sent.endsWith('4417882'),
    sent,
  );
}

console.log('\ncopy and paste record occurrence only');
{
  const p = newPage(js);
  const form = p.el('form');
  const input = p.append(form, p.el('input', { props: { type: 'text', name: 'mrn' } }));
  p.fire('copy', input);
  p.fire('paste', input);
  p.flush();
  eq('types', p.emitted.map((e) => e.type), ['COPY', 'PASTE']);
  eq('copy carries nothing at all', Object.keys(p.emitted[0]), ['type']);
  check(
    'paste carries structure but no clipboard',
    p.emitted[1].fieldName === 'mrn' && !('value' in p.emitted[1]),
    JSON.stringify(p.emitted[1]),
  );
}

console.log(failures === 0 ? '\nAll collector checks passed.' : `\n${failures} check(s) FAILED.`);
process.exit(failures === 0 ? 0 : 1);
