import test from 'node:test';
import assert from 'node:assert/strict';
import { WindowMediaPeer } from '../../../desktopMain/resources/app-sharing/media.mjs';
import { AppControlChannel } from '../../../desktopMain/resources/app-sharing/control.mjs';

test('viewer sends secondary clicks and Tab chords only with control while footer keys remain local', async () => {
  class Element extends EventTarget {
    style = {};
    append() {} replaceChildren() {} focus() {}
    getBoundingClientRect() { return { left: 0, top: 0, width: 400, height: 300 }; }
    setPointerCapture(id) { this.pointer = id; }
  }
  const ids = new Map(['screen', 'status', 'control', 'disconnect', 'window', 'window-label'].map(id => [id, new Element()]));
  const document = new EventTarget(); document.getElementById = id => ids.get(id); document.createElement = () => new Element();
  const window = new EventTarget(), names = ['document', 'location', 'fetch', 'addEventListener', '__bossAppShareConfig', '__bossAppShareBridge', 'BossAppShareViewer'];
  const saved = new Map(names.map(name => [name, globalThis[name]]));
  const methods = new Map([['subscribe', WindowMediaPeer.prototype.subscribe], ...['start', 'takeControl', 'send'].map(name => [name, AppControlChannel.prototype[name]])]);
  const config = { autoStart: false, apiUrl: 'https://api.example/api/app-sharing', csrf: 'csrf-token-long-enough',
    sessionId: 'synthetic-session', generation: 'generation', peerId: 'synthetic-peer', windowId: 'first', role: 'control', windows: [] };
  const sent = [], peers = [], controls = [], destinations = [];
  Object.assign(globalThis, { document, location: { href: 'https://api.example/app-viewer/?session=synthetic-session#k=private', origin: 'https://api.example', replace: value => destinations.push(value) },
    fetch: async () => Response.json({}), addEventListener: (...args) => window.addEventListener(...args),
    __bossAppShareConfig: config, __bossAppShareBridge: undefined });
  WindowMediaPeer.prototype.subscribe = async function () { this.started = true; peers.push(this); };
  AppControlChannel.prototype.start = async function () {
    controls.push(this);
    this.channel = { readyState: 'open', close() {} }; this.onGeometry({ width: 400, height: 300 });
  };
  AppControlChannel.prototype.takeControl = async function () { this.lease = { leaseId: 'lease' }; this.onLease(this.lease); };
  AppControlChannel.prototype.send = event => { sent.push(event); return true; };
  const dispatch = (id, name, values = {}) => {
    const event = Object.assign(new Event(name, { cancelable: true }), values); ids.get(id).dispatchEvent(event); return event;
  };
  try {
    await import('../../../desktopMain/resources/app-sharing/viewer.mjs');
    await BossAppShareViewer.start(config);
    controls.at(-1).onGeometry({ width: 400, height: 300, cursor: 'ew-resize' });
    dispatch('screen', 'pointermove', { clientX: 200, clientY: 150, button: -1 });
    assert.equal(ids.get('screen').style.cursor, 'ew-resize');
    dispatch('screen', 'pointerleave'); assert.equal(ids.get('screen').style.cursor, 'default');
    controls.at(-1).onGeometry({ width: 400, height: 300, cursor: 'ns-resize' });
    assert.equal(ids.get('screen').style.cursor, 'default', 'A cursor announcement must not change the footer cursor');
    sent.length = 0;
    for (const name of ['pointerdown', 'pointerup']) {
      assert.equal(dispatch('screen', name, { clientX: 200, clientY: 150, button: 2, pointerId: 7 }).defaultPrevented, true);
    }
    assert.equal(ids.get('screen').pointer, 7);
    assert.deepEqual(sent.map(event => [event.type, event.action, event.button]), [['pointer', 'down', 2], ['pointer', 'up', 2]]);
    assert.equal(dispatch('screen', 'contextmenu').defaultPrevented, true);
    for (const name of ['keydown', 'keyup']) {
      assert.equal(dispatch('screen', name, { code: 'Tab', key: 'Tab', shiftKey: true, altKey: false, ctrlKey: false, metaKey: false }).defaultPrevented, true);
    }
    assert.deepEqual(sent.slice(2), ['down', 'up'].map(action => ({ type: 'key', action, code: 'Tab', key: 'Tab', shift: true, alt: false, ctrl: false, meta: false })));
    assert.equal(dispatch('control', 'keydown', { code: 'Tab', key: 'Tab' }).defaultPrevented, false);
    dispatch('screen', 'keydown', { code: 'ShiftLeft', key: 'Shift', shiftKey: true });
    const release = Object.assign(new Event('keyup', { cancelable: true }), { code: 'ShiftLeft', key: 'Shift', shiftKey: false });
    document.dispatchEvent(release);
    assert.equal(release.defaultPrevented, true);
    assert.equal(sent.at(-1).action, 'up'); assert.equal(sent.at(-1).code, 'ShiftLeft');
    document.dispatchEvent(release); assert.equal(sent.length, 6, 'A bubbling key release is sent once');
    await BossAppShareViewer.start({ ...config, role: 'view' });
    assert.equal(dispatch('screen', 'keydown', { code: 'Tab', key: 'Tab' }).defaultPrevented, false);
    assert.equal(dispatch('screen', 'contextmenu').defaultPrevented, false);
    assert.equal(sent.length, 6);
    assert.equal(ids.get('screen').style.cursor, 'default', 'View-only connections do not use a control cursor');
    const hosted = { ...config, returnUrl: 'https://api.example/' };
    await BossAppShareViewer.start(hosted);
    const old = peers.at(-1);
    for (const state of ['disconnected', 'recovering', 'connected']) old.onState(state);
    assert.deepEqual(destinations, [], 'Brief interruptions retain the existing retry window');
    old.onState('failed');
    assert.deepEqual(destinations, ['https://api.example/'], 'Terminal failure returns to the session list without keys');
    await BossAppShareViewer.start(hosted);
    old.onState('failed'); assert.equal(destinations.length, 1, 'A retired peer cannot navigate away from its replacement');
    dispatch('disconnect', 'click'); assert.equal(destinations.length, 2);
    await BossAppShareViewer.start({ ...config, returnUrl: 'https://untrusted.example/' });
    peers.at(-1).onState('failed'); assert.equal(destinations.length, 2, 'Return links cannot redirect to another origin');
    await BossAppShareViewer.start(config);
    dispatch('disconnect', 'click'); assert.equal(destinations.length, 2, 'Native/older configs retain local disconnect behavior');
  } finally {
    await globalThis.BossAppShareViewer?.stop();
    WindowMediaPeer.prototype.subscribe = methods.get('subscribe');
    for (const name of ['start', 'takeControl', 'send']) AppControlChannel.prototype[name] = methods.get(name);
    for (const [name, value] of saved) { if (value === undefined) delete globalThis[name]; else globalThis[name] = value; }
  }
});
