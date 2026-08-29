#!/usr/bin/env node
/**
 * Runs the cmd+click handler against a fake DOM.
 *
 * BrowserJavaScripts.injectCmdClickHandler decides whether BOSS opens a link in a new tab or
 * leaves the click to Chromium, and it lives as JavaScript inside a Kotlin string - so the Kotlin
 * suite can only grep it. Grepping pins the spelling, not the behaviour: rewriting
 * `event.button !== 0` as `0 !== event.button` fails a passing test, and any guard reworded rather
 * than removed passes a broken one.
 *
 * The case that most needs executing is the SVG anchor. `closest('a')` matches an SVG `<a>` as
 * happily as an HTML one, but its `href` is an `SVGAnimatedString` object - truthy, and useless.
 * The old handler passed it straight to window.open, which opened a tab on the literal string
 * "[object SVGAnimatedString]". Reading the script cannot tell you that; running it can. The
 * plugin's own middle-click script handles the same case explicitly via `href.baseVal`, which is
 * how we know it is real rather than theoretical.
 *
 * Everything this handler declines falls through to Chromium's native cmd+click, which reaches
 * CreatePopupCallback with a correct target URL - so "not opened by us" is always a safe outcome,
 * and these tests assert exactly that split.
 *
 * Same technique and directory as test-find-key-probe.js, for the same reason.
 *
 * Usage: node scripts/test/test-cmd-click.js
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repoRoot = path.resolve(__dirname, '../..');
const scriptsKt = path.join(
  repoRoot,
  'composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserJavaScripts.kt',
);

let failures = 0;
function eq(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (ok) {
    console.log(`  ok   ${name}`);
  } else {
    failures++;
    console.log(`  FAIL ${name} -> got ${JSON.stringify(actual)}, want ${JSON.stringify(expected)}`);
  }
}

// ---------------------------------------------------------------------------
// Extract injectCmdClickHandler's body out of the Kotlin string.
// ---------------------------------------------------------------------------
function loadHandler() {
  const src = fs.readFileSync(scriptsKt, 'utf8');
  const marker = 'val injectCmdClickHandler =';
  const at = src.indexOf(marker);
  if (at < 0) throw new Error('injectCmdClickHandler not found - did it get renamed?');
  const body = src.slice(at).split('"""')[1];
  if (!body) throw new Error('no raw string after injectCmdClickHandler');
  const lines = body.split('\n').slice(1);
  const indent = Math.min(
    ...lines.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
  );
  return lines.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
}

const HANDLER = loadHandler();

// ---------------------------------------------------------------------------
// A fake DOM: just enough to register a capture-phase listener and dispatch to it.
// ---------------------------------------------------------------------------
function makeAnchor(attrs) {
  const href = attrs.href;
  const anchor = {
    tagName: 'A',
    href,
    // Real anchors expose the scheme via .protocol. An SVGAElement does not, which is the whole
    // point of one of the cases below.
    protocol: attrs.protocol !== undefined ? attrs.protocol : schemeOf(href),
    _attrs: attrs.download !== undefined ? { download: attrs.download } : {},
    hasAttribute(n) {
      return Object.prototype.hasOwnProperty.call(this._attrs, n);
    },
  };
  anchor.closest = () => anchor;
  return anchor;
}

function schemeOf(href) {
  if (typeof href !== 'string') return undefined;
  const m = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(href);
  return m ? `${m[1].toLowerCase()}:` : undefined;
}

function run(event) {
  const opened = [];
  let prevented = false;
  let propagationStopped = false;

  const listeners = [];
  const context = {
    window: {},
    document: {
      addEventListener(type, fn, capture) {
        listeners.push({ type, fn, capture });
      },
    },
  };
  context.window.open = (url) => opened.push(url);
  context.window.document = context.document;

  vm.runInNewContext(HANDLER, context);

  const ev = Object.assign(
    {
      button: 0,
      metaKey: false,
      ctrlKey: false,
      defaultPrevented: false,
      preventDefault() {
        prevented = true;
      },
      stopPropagation() {
        propagationStopped = true;
      },
    },
    event,
  );

  for (const l of listeners) if (l.type === 'click') l.fn(ev);
  return { opened, prevented, propagationStopped, capture: listeners.every((l) => l.capture === true) };
}

const link = (href, extra) => makeAnchor(Object.assign({ href }, extra || {}));
const clickOn = (anchor, extra) =>
  run(Object.assign({ metaKey: true, target: anchor }, extra || {}));

console.log('cmd+click handler');

// --- the thing it is for -----------------------------------------------------
{
  const r = clickOn(link('https://example.com/page'));
  eq('cmd+click on an http link opens one tab', r.opened, ['https://example.com/page']);
  eq('and suppresses the page navigation', r.prevented, true);
  eq('registered on the capture phase', r.capture, true);
}
{
  const r = clickOn(link('http://localhost:8080/x'));
  eq('http is opened too', r.opened, ['http://localhost:8080/x']);
}
{
  const r = run({ ctrlKey: true, target: link('https://example.com/ctrl') });
  eq('ctrl+click opens as well (Windows/Linux)', r.opened, ['https://example.com/ctrl']);
}

// --- what it must leave alone ------------------------------------------------
{
  const r = run({ target: link('https://example.com/plain') });
  eq('a plain click is not ours', r.opened, []);
  eq('and is not prevented', r.prevented, false);
}
{
  const r = clickOn(link('https://example.com/mid'), { button: 1 });
  eq('a middle click with the modifier is not ours', r.opened, []);
}
{
  const r = clickOn(link('https://example.com/x'), { defaultPrevented: true });
  eq('an already-cancelled click is left alone', r.opened, []);
}
{
  const r = clickOn(link('https://example.com/file.zip', { download: '' }));
  eq('a download anchor is left to the page', r.opened, []);
  eq('and is not prevented, so the download still happens', r.prevented, false);
}
for (const href of [
  'javascript:alert(1)',
  'mailto:a@b.com',
  'blob:https://example.com/abc',
  'data:text/html,<h1>x',
  'file:///etc/passwd',
]) {
  const r = clickOn(link(href));
  eq(`${href.split(':')[0]}: is left to the page`, r.opened, []);
}
{
  // An SVG <a>: closest('a') matches it, href is an SVGAnimatedString, and there is no protocol.
  const svg = makeAnchor({ href: { baseVal: 'https://example.com/svg' }, protocol: undefined });
  const r = clickOn(svg);
  eq('an SVG anchor is not opened as [object SVGAnimatedString]', r.opened, []);
}
{
  const bare = { tagName: 'DIV', closest: () => null };
  const r = clickOn(bare);
  eq('a click on nothing clickable is a no-op', r.opened, []);
}

// --- re-injection ------------------------------------------------------------
{
  const context = { window: {}, document: { addEventListener: () => count++ } };
  context.window.document = context.document;
  let count = 0;
  vm.runInNewContext(HANDLER, context);
  vm.runInNewContext(HANDLER, context);
  eq('re-injection does not stack a second listener', count, 1);
}

console.log(failures === 0 ? '\nall passed' : `\n${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
