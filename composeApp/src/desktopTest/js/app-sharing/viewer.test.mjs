import test from 'node:test';
import assert from 'node:assert/strict';
import { WindowMediaPeer } from '../../../desktopMain/resources/app-sharing/media.mjs';
import { AppControlChannel } from '../../../desktopMain/resources/app-sharing/control.mjs';

test('hosted window switching admits afresh and waits for old control/media cleanup before subscribing', async () => {
  class Element extends EventTarget {
    style = {};
    constructor() { super(); this.children = []; this.value = ''; }
    append(value) { this.children.push(value); }
    replaceChildren() { this.children = []; }
    focus() {}
  }
  const ids = new Map(['screen', 'status', 'control', 'disconnect', 'window', 'window-label'].map(id => [id, new Element()]));
  const document = new EventTarget(); document.getElementById = id => ids.get(id); document.createElement = () => new Element();
  const window = new EventTarget(), globals = ['document', 'location', 'fetch', 'addEventListener', '__bossAppShareConfig', '__bossAppShareBridge', 'BossAppShareViewer'];
  const saved = new Map(globals.map(name => [name, globalThis[name]]));
  const originalSubscribe = WindowMediaPeer.prototype.subscribe, originalStart = AppControlChannel.prototype.start, originalRelease = AppControlChannel.prototype.releaseControl;
  const session = { session_id: 'synthetic-session', generation: 'generation', windows: [{ id: 'first', title: 'Editor' }, { id: 'second', title: 'Terminal' }], viewer_url: 'https://api.example/viewer#k=ROOT', key_epoch: 'epoch', host_public_key: 'PUBLIC' };
  const events = [], requests = [];
  let release, mediaClose;
  const config = { autoStart: false, apiUrl: 'https://api.example/api/app-sharing', csrf: 'csrf-token-long-enough', sessionId: session.session_id, generation: session.generation, peerId: 'original-peer', windowId: 'first', role: 'control', windows: session.windows, canSwitchWindows: true };
  Object.assign(globalThis, { document, location: { href: `https://api.example/app-viewer/?session=${session.session_id}`, origin: 'https://api.example' }, addEventListener: (...args) => window.addEventListener(...args), __bossAppShareConfig: config, __bossAppShareBridge: undefined });
  globalThis.fetch = async (url, options) => {
    assert.equal(String(url).includes('ROOT'), false);
    if (!options.body) return Response.json({ csrf: 'csrf-token-long-enough' });
    const body = JSON.parse(options.body); requests.push(body);
    if (body.action === 'list') return Response.json({ sessions: [session] });
    if (body.action === 'admit') return Response.json({ ticket: 'fresh-ticket', viewer_url: session.viewer_url });
    if (body.action === 'consume') return Response.json({ peer_id: 'replacement-peer', role: 'control' });
    if (body.action === 'mediaClose' && body.peer_id === 'original-peer') return new Promise(resolve => { mediaClose = () => resolve(Response.json({})); });
    return Response.json({});
  };
  WindowMediaPeer.prototype.subscribe = async function () { this.started = true; events.push(`subscribe:${this.config.windowId}`); };
  AppControlChannel.prototype.start = async function () { this.onLease({ leaseId: 'old-lease' }); };
  AppControlChannel.prototype.releaseControl = function () {
    this.clearLease(); events.push(`release:${this.config.windowId}`);
    return this.config.windowId === 'first' ? new Promise(resolve => { release = resolve; }) : Promise.resolve();
  };
  const flush = () => new Promise(resolve => setImmediate(resolve));
  try {
    await import('../../../desktopMain/resources/app-sharing/viewer.mjs');
    await BossAppShareViewer.start(config);
    assert.equal(ids.get('window-label').hidden, false); assert.equal(ids.get('window').children[1].textContent, 'Terminal');
    ids.get('window').value = 'second'; ids.get('window').dispatchEvent(new Event('change'));
    await flush();
    assert.ok(requests.some(body => body.action === 'consume' && body.ticket === 'fresh-ticket'));
    assert.deepEqual(events, ['subscribe:first', 'release:first']);
    release(); await flush();
    assert.equal(events.includes('subscribe:second'), false, 'media cleanup must also settle');
    mediaClose(); await flush(); await flush();
    assert.equal(events.at(-1), 'subscribe:second');
    assert.equal(ids.get('window').value, 'second');
    await BossAppShareViewer.stop();
  } finally {
    release?.(); mediaClose?.(); await globalThis.BossAppShareViewer?.stop();
    WindowMediaPeer.prototype.subscribe = originalSubscribe; AppControlChannel.prototype.start = originalStart; AppControlChannel.prototype.releaseControl = originalRelease;
    for (const [name, value] of saved) { if (value === undefined) delete globalThis[name]; else globalThis[name] = value; }
  }
});
