#!/usr/bin/env node
/**
 * Runs the form-field detection scripts against a fake DOM.
 *
 * FormFieldDetector is JavaScript living inside a Kotlin string, and unlike the interaction
 * collector it has no Kotlin test at all - so nothing currently reads it, by grep or by
 * execution. It is injected into every frame of every page from
 * BrowserHandleImpl.injectPageHelpers, so what it does on a page is not a detail.
 *
 * This runs both of its scripts and pins what they DO. Three of the checks below are
 * defects rather than desired behaviour; each is marked and names the follow-up. They are
 * pinned rather than fixed here so that the fix is a reviewable diff against a known state
 * instead of an unmeasured claim.
 *
 * It also closes a coupling the Kotlin side cannot see: the injected script emits nine keys
 * and parseFieldInfoJson picks them back out with one hand-written regex per key, across two
 * files and two languages. A rename on either side does not fail - it silently yields "" for
 * that field forever. Both key sets are read from source here and compared.
 *
 * Usage: node scripts/test/test-form-field-detector.js
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repoRoot = path.resolve(__dirname, '../..');

/** Sources are read line-ending-normalised: a Windows checkout with core.autocrlf on has CRLF. */
function readSource(file) {
  return fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
}
const detectorKt = path.join(
  repoRoot,
  'composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/FormFieldDetector.kt',
);
const collectorKt = path.join(
  repoRoot,
  'composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserInteractionScript.kt',
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

// ---------------------------------------------------------------------------
// Extract both scripts out of the Kotlin string literals.
//
// Neither block interpolates a Kotlin constant, so unlike the collector harness there is
// nothing to resolve - but an interpolation added later would reach the page as a literal
// "$NAME", so it is rejected here rather than silently executed.
// ---------------------------------------------------------------------------
function dedent(block) {
  const lines = block.split('\n').slice(1);
  const body = lines.filter((l) => l.trim());
  if (!body.length) throw new Error('empty script block');
  const indent = Math.min(...body.map((l) => l.length - l.trimStart().length));
  return lines.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
}

function loadScripts() {
  const src = readSource(detectorKt);
  const parts = src.split('"""');
  if (parts.length !== 5) {
    throw new Error(`expected exactly 2 script blocks in FormFieldDetector.kt, found ${(parts.length - 1) / 2}`);
  }
  const inject = dedent(parts[1]);
  const enumerate = dedent(parts[3]);
  for (const [name, js] of [['inject', inject], ['enumerate', enumerate]]) {
    const interpolated = js.split('\n').filter((l) => /\$\w|\$\{/.test(l));
    if (interpolated.length) {
      throw new Error(`unresolved Kotlin interpolation in ${name} script: ${interpolated[0]}`);
    }
  }
  return { inject, enumerate };
}

/** The keys parseFieldInfoJson pulls back out, read from the Kotlin rather than restated. */
function kotlinParsedKeys() {
  const src = readSource(detectorKt);
  return [...src.matchAll(/extractValue\("(\w+)"\)/g)].map((m) => m[1]).sort();
}

/** The regex parseFieldInfoJson builds per key, reconstructed from the Kotlin source. */
function kotlinExtractRegex(key) {
  const src = readSource(detectorKt);
  // val pattern = "\"$key\":\"([^\"]*)\""
  const literal = /val pattern = "(.+?)"\n/.exec(src);
  if (!literal) throw new Error('could not find the extractValue pattern in FormFieldDetector.kt');
  const pattern = literal[1]
    .replace(/\\"/g, '"')
    .replace('$key', key);
  return new RegExp(pattern);
}

/** Whether the sibling collector guards against being injected twice into one document. */
function collectorHasReinjectionGuard() {
  const src = readSource(collectorKt);
  return /STARTED_FLAG/.test(src) && /if \(window\.\$STARTED_FLAG\)/.test(src);
}

// ---------------------------------------------------------------------------
// Fake DOM: only what these two scripts touch, so an added DOM read fails loudly.
// ---------------------------------------------------------------------------
function newPage() {
  const listeners = [];
  const logs = [];
  const timers = [];

  const el = (tag, attrs = {}) => {
    const node = {
      nodeType: 1,
      tagName: tag.toUpperCase(),
      children: [],
      parentElement: null,
      _attrs: { ...attrs },
      get type() {
        return node._attrs.type;
      },
      get name() {
        return node._attrs.name;
      },
      get id() {
        return node._attrs.id;
      },
      get placeholder() {
        return node._attrs.placeholder;
      },
      get value() {
        return node._attrs.value;
      },
      get className() {
        return node._attrs.class;
      },
      get action() {
        return node._attrs.action;
      },
      getAttribute(n) {
        return Object.prototype.hasOwnProperty.call(node._attrs, n) ? node._attrs[n] : null;
      },
      closest(sel) {
        let walk = node;
        while (walk) {
          if (walk.tagName === sel.toUpperCase()) return walk;
          walk = walk.parentElement;
        }
        return null;
      },
    };
    return node;
  };

  const append = (parent, child) => {
    child.parentElement = parent;
    parent.children.push(child);
    return child;
  };

  /** Depth-first flatten, so querySelectorAll can answer without a real selector engine. */
  const flatten = (node, out = []) => {
    for (const c of node.children) {
      out.push(c);
      flatten(c, out);
    }
    return out;
  };

  const document = {
    activeElement: null,
    _root: el('body'),
    addEventListener(type, fn, capture) {
      listeners.push({ type, fn, capture });
    },
    querySelectorAll(sel) {
      const wanted = sel.split(',').map((s) => s.trim().toUpperCase());
      return flatten(document._root).filter((n) => wanted.includes(n.tagName));
    },
  };

  const window = {};
  const sandbox = {
    window,
    document,
    console: { log: (...args) => logs.push(args) },
    setTimeout: (fn, ms) => timers.push({ fn, ms }),
    Array,
    Object,
    JSON,
    String,
  };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);

  return {
    sandbox,
    window,
    document,
    listeners,
    logs,
    timers,
    el,
    append,
    run: (js) => vm.runInContext(js, sandbox),
    fire: (type, target) => {
      for (const l of listeners) {
        if (l.type === type) l.fn({ target });
      }
    },
    runTimers: () => {
      const queued = timers.splice(0, timers.length);
      for (const t of queued) t.fn();
    },
  };
}

const { inject, enumerate } = loadScripts();

// ---------------------------------------------------------------------------

console.log('\nlistener registration');
{
  const p = newPage();
  p.run(inject);
  eq('listener types', p.listeners.map((l) => l.type), ['focusin', 'focusout']);
  check(
    'both are capture-phase',
    p.listeners.every((l) => l.capture === true),
    JSON.stringify(p.listeners.map((l) => l.capture)),
  );
}

console.log('\nfocus tracking');
{
  const p = newPage();
  p.run(inject);

  const input = p.el('input', { type: 'text', name: 'mrn' });
  p.fire('focusin', input);
  check('focusin on an input is tracked', p.window.__BOSS_FOCUSED_FIELD === input);

  const div = p.el('div');
  p.fire('focusin', div);
  check('focusin on a non-field leaves the previous field in place', p.window.__BOSS_FOCUSED_FIELD === input);
}

console.log('\nDEFECT: the script has no re-injection guard');
// The host re-runs injectPageHelpers on every main-frame NavigationFinished, and for a
// single-page app that is a route change WITHIN one document - so the same document accrues
// another pair of capture-phase listeners per route, permanently. The sibling collector
// guards exactly this case with its own started-flag; this script has no equivalent.
// Follow-up: guard the script the way BrowserInteractionScript guards its own.
{
  const p = newPage();
  p.run(inject);
  const afterFirst = p.listeners.length;
  p.run(inject);
  const afterSecond = p.listeners.length;
  eq('listeners after one injection', afterFirst, 2);
  eq('listeners after a second injection into the same document', afterSecond, 4);
  check(
    'the sibling collector does guard this, so the omission is not house style',
    collectorHasReinjectionGuard(),
  );

  const input = p.el('input', { type: 'text', name: 'mrn' });
  p.logs.length = 0;
  p.fire('focusin', input);
  eq('one focus now logs once per injection', p.logs.length, 2);
}

console.log('\nDEFECT: every focus is narrated into the page console');
// The sibling collector states the opposite contract in its own KDoc: "The whole script is
// wrapped so an exception can never surface in the page's console." This logs a field's
// name or id on every focus, on every page, for the life of the document.
// Follow-up: drop the per-focus log.
{
  const p = newPage();
  p.run(inject);
  p.logs.length = 0;
  p.fire('focusin', p.el('input', { type: 'password', name: 'pwd' }));
  eq('a single focus logs', p.logs.length, 1);
  eq('and carries the field type and name', p.logs[0], ['[BOSS] Field focused:', 'password', 'pwd']);
}

console.log('\nwhat the page-reachable accessor returns');
// window.__BOSS_GET_FOCUSED_FIELD is a plain main-world global - no isolated world is used
// anywhere in this repo. Recorded here because the sibling collector's KDoc names value,
// placeholder, id, className and aria-label as the exact fields it refuses to read, citing
// a healthcare deployment where the input value is the patient MRN.
{
  const p = newPage();
  p.run(inject);

  const form = p.append(p.document._root, p.el('form', { action: 'https://example.test/login' }));
  const input = p.append(
    form,
    p.el('input', {
      type: 'password',
      name: 'pwd',
      id: 'patient-4417',
      placeholder: 'Patient MRN',
      value: 'hunter2',
      class: 'form-control secret',
      autocomplete: 'current-password',
      'aria-label': 'Patient MRN',
    }),
  );
  p.fire('focusin', input);

  const got = p.window.__BOSS_GET_FOCUSED_FIELD();
  eq('the live field value is returned', got.value, 'hunter2');
  eq('as are id, className, placeholder and ariaLabel', [got.id, got.className, got.placeholder, got.ariaLabel], [
    'patient-4417',
    'form-control secret',
    'Patient MRN',
    'Patient MRN',
  ]);
  eq('and the owning form action', got.formAction, 'https://example.test/login');
}

console.log('\nnull-safety of the focusout handler');
// document.activeElement is nullable per spec (a detached document, or teardown between the
// blur and the 500ms timer). The handler dereferences .tagName on it unguarded, so the
// deferred clear throws and __BOSS_FOCUSED_FIELD keeps pointing at the blurred field.
// Follow-up: guard the dereference.
{
  const p = newPage();
  p.run(inject);
  const input = p.el('input', { type: 'text', name: 'mrn' });
  p.fire('focusin', input);
  p.fire('focusout', input);
  p.document.activeElement = null;

  let threw = null;
  try {
    p.runTimers();
  } catch (e) {
    threw = e;
  }
  check('a null activeElement throws out of the deferred clear', threw !== null, String(threw));
  check('so the blurred field is still referenced afterwards', p.window.__BOSS_FOCUSED_FIELD === input);
}

console.log('\nthe enumeration script returns rows');
// findAllFormFields runs this script and then completes with a list it never wrote to, so
// the Kotlin returns an empty list whatever the page contains. That the script itself
// produces data is what makes the discard a loss rather than a no-op.
// Follow-up: parse the result, or drop the function.
{
  const p = newPage();
  const form = p.append(p.document._root, p.el('form'));
  p.append(form, p.el('input', { type: 'text', name: 'user', id: 'u', placeholder: 'User', autocomplete: 'username' }));
  p.append(form, p.el('input', { type: 'password', name: 'pwd', id: 'p', placeholder: 'Password', autocomplete: 'current-password' }));
  p.append(form, p.el('textarea', { name: 'notes', id: 'n', placeholder: 'Notes' }));

  const rows = p.run(enumerate);
  eq('every input and textarea is reported', rows.length, 3);
  eq('names, in document order', rows.map((r) => r.name), ['user', 'pwd', 'notes']);
  eq('a textarea reports the default type', rows[2].type, 'text');
}

console.log('\ncross-language key coupling');
// The injected script emits these keys; parseFieldInfoJson pulls them back out with one
// hand-written regex per key. A rename on either side yields "" for that field silently.
{
  const p = newPage();
  p.run(inject);
  const input = p.el('input', { type: 'text' });
  p.fire('focusin', input);
  const emitted = Object.keys(p.window.__BOSS_GET_FOCUSED_FIELD()).sort();
  eq('the Kotlin extracts exactly the keys the script emits', kotlinParsedKeys(), emitted);
}

console.log('\nDEFECT: the extract regex is not a JSON parser');
// parseFieldInfoJson matches "key":"([^"]*)" against the JSON.stringify output. [^"]* stops
// at the first quote, which for an escaped one is the escape's own - so a value containing a
// double quote is truncated and keeps a trailing backslash.
// Follow-up: decode with a JSON parser.
{
  const json = JSON.stringify({ placeholder: 'Say "hi"', name: 'greeting' });
  const got = kotlinExtractRegex('placeholder').exec(json);
  eq('an unremarkable placeholder does not survive the round trip', got && got[1], 'Say \\');
  const clean = kotlinExtractRegex('name').exec(json);
  eq('a value with no quote in it is fine', clean && clean[1], 'greeting');
}

console.log(
  failures === 0 ? '\nAll form-field detector checks passed.' : `\n${failures} check(s) FAILED.`,
);
process.exit(failures === 0 ? 0 : 1);
