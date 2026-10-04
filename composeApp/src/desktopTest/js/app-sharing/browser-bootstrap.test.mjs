import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { browserViewerConfig, sharedWindows } from '../../../desktopMain/resources/app-sharing/browser-bootstrap.mjs';
import { createBridge } from '../../../desktopMain/resources/app-sharing/bridge.mjs';

globalThis.crypto ??= webcrypto;

test('browser admission uses cookie+CSRF, pins account registry key and consumes scoped ticket', async () => {
  const beforeFetch = globalThis.fetch, beforeLocation = globalThis.location;
  const session = { session_id: crypto.randomUUID(), generation: crypto.randomUUID(), windows: [{ id: 'owned' }, { id: 'second', title: 'Other window' }], viewer_url: 'https://api.example/viewer#k=AUTHORIZED', key_epoch: crypto.randomUUID(), host_public_key: 'PUBLIC' };
  globalThis.location = { href: `https://api.example/functions/v1/live-sessions/app-viewer/?session=${session.session_id}#k=ATTACKER`, origin: 'https://api.example' };
  const calls = [];
  globalThis.fetch = async (url, options) => {
    assert.equal(options.credentials, 'same-origin');
    const body = JSON.parse(options.body); calls.push(body);
    assert.equal(options.headers['X-App-Sharing-CSRF'], 'csrf-token-long-enough');
    assert.equal(new URL(url).pathname, '/functions/v1/live-sessions/api/app-sharing');
    const data = body.action === 'list' ? { sessions: [session] } : body.action === 'admit' ? { ticket: 'single-use-ticket' } : { peer_id: 'viewer-peer' };
    return Response.json(data);
  };
  try {
    const result = await browserViewerConfig(globalThis.location, async () => Response.json({ csrf: 'csrf-token-long-enough' }));
    assert.equal(result.mediaRootKey, 'AUTHORIZED'); assert.equal(result.windowId, 'owned');
    assert.equal(result.returnUrl, 'https://api.example/functions/v1/live-sessions/');
    assert.deepEqual(calls.map(call => call.action), ['list', 'admit', 'consume']);
    assert.equal(calls[1].role, 'control'); assert.equal(calls[2].ticket, 'single-use-ticket');
    assert.equal(calls[1].device_id, calls[2].device_id);
    assert.equal(result.canSwitchWindows, true); assert.equal(result.windows[0].title, 'BossConsole window');
    const switched = await browserViewerConfig(globalThis.location, async () => Response.json({ csrf: 'csrf-token-long-enough' }), 'second');
    assert.equal(switched.windowId, 'second'); assert.equal(switched.windows[1].title, 'Other window');
    assert.notEqual(calls[4].device_id, calls[1].device_id, 'switching requires a fresh admission');
    const before = calls.length;
    await assert.rejects(browserViewerConfig(globalThis.location, async () => Response.json({ csrf: 'csrf-token-long-enough' }), 'unshared'), /no longer available/);
    assert.equal(calls.length, before + 1, 'unshared window is refused before admission');
  } finally { globalThis.fetch = beforeFetch; globalThis.location = beforeLocation; }
});

test('window descriptors refuse duplicate and malformed identities while keeping older untitled windows compatible', () => {
  assert.deepEqual(sharedWindows([{ id: 'main' }]), [{ id: 'main', title: 'BossConsole window' }]);
  for (const value of [[], [{ id: 'bad/id' }], [{ id: 'one' }, { id: 'one' }], [{ id: 'one', title: 3 }]]) assert.throws(() => sharedWindows(value), /unavailable/);
});

test('unauthenticated browser receives a same-origin sign-in link and no keys', async () => {
  await assert.rejects(browserViewerConfig({ href: 'https://api.example/live-sessions/app-viewer/?session=x' }, async () => new Response('', { status: 401 })), error => error.loginUrl === 'https://api.example/live-sessions/' && /Sign in/.test(error.message));
});

test('web cookie bridge never sends account CSRF to another origin or API path', () => {
  const previous = globalThis.location; globalThis.location = { origin: 'https://api.example' };
  try {
    assert.throws(() => createBridge({ apiUrl: 'https://evil.example/api/app-sharing', csrf: 'secret' }), /Untrusted/);
    assert.throws(() => createBridge({ apiUrl: 'https://api.example/other', csrf: 'secret' }), /Untrusted/);
    assert.throws(() => createBridge({ apiUrl: 'http://api.example/api/app-sharing', csrf: 'secret' }), /Untrusted/);
  } finally { globalThis.location = previous; }
});

test('authenticated HTTP cleanup survives pagehide without enabling keepalive for ordinary RPCs', async () => {
  const previousFetch = globalThis.fetch, previousLocation = globalThis.location, requests = [];
  globalThis.location = { origin: 'https://api.example' };
  globalThis.fetch = async (_, options) => { requests.push(options); return Response.json({}); };
  try {
    const bridge = createBridge({ apiUrl: 'https://api.example/api/app-sharing', csrf: 'secret' });
    for (const action of ['controlRelease', 'mediaClose', 'mediaSubscribe']) await bridge.request({ action });
    assert.deepEqual(requests.map(options => options.keepalive), [true, true, false]);
    for (const options of requests) {
      assert.equal(options.credentials, 'same-origin'); assert.equal(options.headers['X-App-Sharing-CSRF'], 'secret');
      assert.equal(options.signal.aborted, false);
    }
    bridge.close();
  } finally { globalThis.fetch = previousFetch; globalThis.location = previousLocation; }
});
