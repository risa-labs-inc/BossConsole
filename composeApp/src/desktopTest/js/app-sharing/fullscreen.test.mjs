import test from 'node:test';
import assert from 'node:assert/strict';
import { setupViewerFullscreen } from '../../../desktopMain/resources/app-sharing/fullscreen.mjs';

function fixture({ supported = true, reject = false } = {}) {
  const document = new EventTarget(), button = new EventTarget();
  const attributes = new Map(); button.setAttribute = (key, value) => attributes.set(key, value);
  document.fullscreenEnabled = supported;
  let requests = 0, exits = 0, focused = 0;
  const root = { async requestFullscreen() {
    requests++;
    if (reject) throw Error('Denied');
    document.fullscreenElement = root; document.dispatchEvent(new Event('fullscreenchange'));
  } };
  document.exitFullscreen = async () => {
    exits++; document.fullscreenElement = null; document.dispatchEvent(new Event('fullscreenchange'));
  };
  setupViewerFullscreen({ document, root, button, focusStream: () => focused++ });
  return { document, root, button, attributes, counts: () => ({ requests, exits, focused }) };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

test('fullscreen enters the entire viewer root, exits, and follows an external Escape exit', async () => {
  const f = fixture();
  f.button.dispatchEvent(new Event('click')); await flush();
  assert.equal(f.document.fullscreenElement, f.root);
  assert.equal(f.button.textContent, 'Exit fullscreen'); assert.equal(f.attributes.get('aria-pressed'), 'true');
  f.button.dispatchEvent(new Event('click')); await flush();
  assert.equal(f.button.textContent, 'Fullscreen'); assert.equal(f.attributes.get('aria-pressed'), 'false');
  assert.deepEqual(f.counts(), { requests: 1, exits: 1, focused: 2 });
  f.button.dispatchEvent(new Event('click')); await flush();
  f.document.fullscreenElement = null; f.document.dispatchEvent(new Event('fullscreenchange'));
  assert.equal(f.button.textContent, 'Fullscreen'); assert.equal(f.attributes.get('aria-pressed'), 'false');
});

test('unavailable or denied fullscreen stays usable without an unhandled rejection', async () => {
  const unavailable = fixture({ supported: false });
  assert.equal(unavailable.button.disabled, true);
  unavailable.button.dispatchEvent(new Event('click')); await flush();
  assert.equal(unavailable.counts().requests, 0);
  const denied = fixture({ reject: true });
  denied.button.dispatchEvent(new Event('click')); await flush();
  assert.equal(denied.button.disabled, false); assert.equal(denied.attributes.get('aria-pressed'), 'false');
  assert.equal(denied.button.title, 'Could not change fullscreen. Try again.');
  assert.equal(denied.counts().focused, 0);
});
