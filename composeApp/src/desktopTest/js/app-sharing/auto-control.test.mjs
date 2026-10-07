import test from 'node:test';
import assert from 'node:assert/strict';
import { WindowMediaPeer } from '../../../desktopMain/resources/app-sharing/media.mjs';
import { AppControlChannel } from '../../../desktopMain/resources/app-sharing/control.mjs';

test('browser and native viewers acquire control on connect, respecting view roles, opt-out and busy leases', async () => {
  class Element extends EventTarget {
    style = {};
    constructor() { super(); this.children = []; this.value = ''; this.focusCount = 0; }
    append(value) { this.children.push(value); }
    replaceChildren() { this.children = []; }
    focus() { this.focusCount++; }
  }
  const elements = new Map(['screen', 'status', 'control', 'disconnect', 'window', 'window-label', 'window-actions', 'window-actions-label'].map(id => [id, new Element()]));
  const document = new EventTarget(); document.getElementById = id => elements.get(id); document.createElement = () => new Element();
  const window = new EventTarget();
  const globals = ['document', 'location', 'fetch', 'addEventListener', '__bossAppShareConfig', '__bossAppShareBridge', 'BossAppShareViewer'];
  const saved = new Map(globals.map(name => [name, globalThis[name]]));
  const subscribe = WindowMediaPeer.prototype.subscribe, start = AppControlChannel.prototype.start,
    takeControl = AppControlChannel.prototype.takeControl, releaseControl = AppControlChannel.prototype.releaseControl,
    send = AppControlChannel.prototype.send;
  const recoveryInputs = [];
  AppControlChannel.prototype.send = function (event) { recoveryInputs.push(event); return true; };
  const config = { autoStart: false, apiUrl: 'https://api.example/api/app-sharing', csrf: 'csrf-token-long-enough',
    sessionId: 'synthetic-session', generation: 'generation', peerId: 'synthetic-peer', windowId: 'first', role: 'control',
    windows: [{ id: 'first', title: 'Synthetic window' }], canSwitchWindows: true };
  Object.assign(globalThis, { document, location: { href: 'https://api.example/app-viewer/?session=synthetic-session', origin: 'https://api.example' },
    fetch: async () => Response.json({}), addEventListener: (...args) => window.addEventListener(...args),
    __bossAppShareConfig: config, __bossAppShareBridge: undefined });
  let acquired = 0, busy = false, activeControl, transientFailures = 0, denied = false;
  WindowMediaPeer.prototype.subscribe = async function () { this.started = true; };
  AppControlChannel.prototype.start = async function () { activeControl = this; this.channel = { readyState: 'open', close() {} }; };
  AppControlChannel.prototype.takeControl = async function () {
    acquired++;
    if (busy) throw Object.assign(new Error('Controller busy'), { status: 409 });
    if (denied) throw Object.assign(new Error('Denied'), { status: 403 });
    if (transientFailures-- > 0) throw Object.assign(new Error('Temporarily unavailable'), { status: 503 });
    this.lease = { leaseId: 'synthetic-lease' }; this.onLease(this.lease);
  };
  AppControlChannel.prototype.releaseControl = async function (details) { this.clearLease(false, details); };
  try {
    await import('../../../desktopMain/resources/app-sharing/viewer.mjs');
    await BossAppShareViewer.start(config);
    assert.equal(acquired, 1); assert.equal(elements.get('control').textContent, 'Release control');
    assert.equal(elements.get('status').textContent, 'You control this BossConsole window');
    assert.equal(elements.get('screen').focusCount, 1);
    assert.equal(elements.get('window-actions-label').hidden, true, 'Old hosts advertise no recovery controls');
    activeControl.onGeometry({ width: 1000, height: 700, windowControls: ['restore', 'exit-fullscreen', 'start-capture'] });
    const actions = elements.get('window-actions');
    assert.equal(elements.get('window-actions-label').hidden, false);
    assert.equal(actions.disabled, false);
    assert.deepEqual(actions.children.map(option => option.value), ['', 'restore', 'exit-fullscreen']);
    actions.value = 'restore'; actions.dispatchEvent(new Event('change'));
    actions.value = 'exit-fullscreen'; actions.dispatchEvent(new Event('change'));
    actions.value = 'start-capture'; actions.dispatchEvent(new Event('change'));
    assert.deepEqual(recoveryInputs, [{ type: 'window', action: 'restore' }, { type: 'window', action: 'exit-fullscreen' }]);
    const originalTimeout = globalThis.setTimeout, originalClearTimeout = globalThis.clearTimeout;
    const scheduled = [];
    globalThis.setTimeout = callback => { scheduled.push(callback); return scheduled.length; };
    globalThis.clearTimeout = () => {};
    try {
      actions.value = 'exit-fullscreen'; actions.dispatchEvent(new Event('change'));
      for (let attempt = 0; attempt < 3; attempt++) scheduled.shift()();
      assert.equal(recoveryInputs.length, 6, 'Recovery is bounded to four authenticated sends');
      assert.equal(scheduled.length, 0);
      activeControl.onGeometry({ width: 1000, height: 700,
        windowControls: ['restore', 'exit-fullscreen', 'minimize', 'maximize', 'unmaximize', 'close', 'start-capture'] });
      assert.deepEqual(actions.children.map(option => option.value),
        ['', 'restore', 'exit-fullscreen', 'minimize', 'maximize', 'unmaximize', 'close']);
      const beforeOrdinary = recoveryInputs.length;
      for (const action of ['minimize', 'maximize', 'unmaximize', 'close']) {
        actions.value = action; actions.dispatchEvent(new Event('change'));
      }
      assert.equal(recoveryInputs.length, beforeOrdinary + 4);
      assert.equal(scheduled.length, 0, 'Destructive and placement commands are never automatically retried');
      actions.value = 'restore'; actions.dispatchEvent(new Event('change'));
      const pending = scheduled.shift();
      activeControl.onGeometry({ width: 1000, height: 700, windowControls: ['restore'], authorityAvailable: false });
      const beforeCancel = recoveryInputs.length;
      pending();
      assert.equal(recoveryInputs.length, beforeCancel, 'Authority loss cancels queued recovery even if timer fires');
    } finally {
      globalThis.setTimeout = originalTimeout; globalThis.clearTimeout = originalClearTimeout;
    }
    const sentRecoveries = recoveryInputs.length;
    globalThis.__bossAppShareBridge = { request(id) { queueMicrotask(() => BossAppShareViewer.resolve(id, {})); } };
    await BossAppShareViewer.start({ ...config, csrf: undefined });
    assert.equal(acquired, 2, 'Native bridge viewer also requests control by default');
    assert.equal(elements.get('screen').focusCount, 2);
    globalThis.__bossAppShareBridge = undefined;
    await BossAppShareViewer.start({ ...config, role: 'view' });
    await BossAppShareViewer.takeControl();
    assert.equal(acquired, 2); assert.equal(elements.get('control').disabled, true);
    activeControl.onGeometry({ width: 1000, height: 700, windowControls: ['restore', 'exit-fullscreen'] });
    assert.equal(actions.disabled, true, 'View-only participants cannot operate window recovery');
    actions.value = 'restore'; actions.dispatchEvent(new Event('change'));
    assert.equal(recoveryInputs.length, sentRecoveries);
    await BossAppShareViewer.start({ ...config, autoTakeControl: false });
    assert.equal(acquired, 2); assert.equal(elements.get('control').disabled, false);
    await BossAppShareViewer.takeControl(); assert.equal(acquired, 3);
    busy = true;
    await BossAppShareViewer.start(config);
    assert.equal(acquired, 6); assert.equal(elements.get('control').disabled, false);
    assert.equal(elements.get('control').textContent, 'Retry control');
    assert.match(elements.get('status').textContent, /Another viewer has control/);
    activeControl.onGeometry({ width: 1000, height: 700 });
    assert.equal(elements.get('control').textContent, 'Retry control', 'Geometry updates cannot hide an exhausted retry');
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(acquired, 6, 'Geometry broadcasts cannot restart exhausted attempts');
    assert.equal(elements.get('screen').focusCount, 3, 'A busy lease must not focus or grant input');
    busy = false; await BossAppShareViewer.start(config);
    const beforeRecovery = acquired;
    activeControl.onGeometry({ width: 1000, height: 700, authorityAvailable: false });
    assert.equal(elements.get('control').disabled, true); assert.match(elements.get('status').textContent, /Reconnecting/);
    assert.equal(activeControl.lease, null);
    activeControl.onGeometry({ width: 1000, height: 700, authorityAvailable: true });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(acquired, beforeRecovery + 1); assert.equal(elements.get('control').textContent, 'Release control');
    activeControl.onGeometry({ width: 1000, height: 700, authorityAvailable: false });
    window.dispatchEvent(new Event('blur'));
    activeControl.onGeometry({ width: 1000, height: 700, authorityAvailable: true });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(acquired, beforeRecovery + 1, 'An inactive viewer cannot reacquire control');
    assert.equal(elements.get('control').textContent, 'Take control');
    window.dispatchEvent(new Event('focus'));
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(acquired, beforeRecovery + 2, 'Returning to the viewer restores control without a click');
    await BossAppShareViewer.releaseControl();
    window.dispatchEvent(new Event('blur')); window.dispatchEvent(new Event('focus'));
    activeControl.onGeometry({ width: 1000, height: 700 });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(acquired, beforeRecovery + 2, 'Explicit release keeps the viewer in view-only mode');
    document.hidden = true;
    await BossAppShareViewer.start(config);
    assert.equal(acquired, beforeRecovery + 2, 'Opening in a background tab must not claim a lease');
    document.hidden = false; document.dispatchEvent(new Event('visibilitychange'));
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(acquired, beforeRecovery + 3);
    transientFailures = 2;
    const beforeRetry = acquired;
    const connecting = BossAppShareViewer.start(config);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(elements.get('control').textContent, 'Connecting control…');
    assert.equal(elements.get('control').disabled, true);
    activeControl.onGeometry({ width: 1000, height: 700 });
    assert.match(elements.get('status').textContent, /Connecting control/);
    await connecting;
    assert.equal(acquired, beforeRetry + 3); assert.equal(elements.get('control').textContent, 'Release control');
    const beforeRevoked = acquired;
    activeControl.clearLease(false, { reason: 'revoked', retryable: false });
    window.dispatchEvent(new Event('blur')); window.dispatchEvent(new Event('focus'));
    activeControl.onGeometry({ width: 1000, height: 700 });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(acquired, beforeRevoked, 'Host take-back must not be overridden automatically');
    assert.equal(elements.get('control').textContent, 'Take control');
    transientFailures = 10;
    const beforeCancelled = acquired;
    const cancelling = BossAppShareViewer.start(config);
    await new Promise(resolve => setImmediate(resolve));
    await BossAppShareViewer.releaseControl(); await cancelling;
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(acquired, beforeCancelled + 1, 'Explicit release cancels the retry timer');
    assert.equal(elements.get('control').textContent, 'Take control');
    transientFailures = 0;
    denied = true;
    const beforeDenial = acquired; await BossAppShareViewer.start(config);
    assert.equal(acquired, beforeDenial + 1); assert.match(elements.get('status').textContent, /not allowed/);
    assert.equal(elements.get('control').disabled, true);
    denied = false;
    await BossAppShareViewer.stop();
  } finally {
    await globalThis.BossAppShareViewer?.stop();
    WindowMediaPeer.prototype.subscribe = subscribe; AppControlChannel.prototype.start = start;
    AppControlChannel.prototype.takeControl = takeControl; AppControlChannel.prototype.releaseControl = releaseControl;
    AppControlChannel.prototype.send = send;
    for (const [name, value] of saved) { if (value === undefined) delete globalThis[name]; else globalThis[name] = value; }
  }
});
