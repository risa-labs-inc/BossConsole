import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { createControlCipher, toBase64 } from '../../../desktopMain/resources/app-sharing/crypto.mjs';
import { AppControlChannel } from '../../../desktopMain/resources/app-sharing/control.mjs';

globalThis.crypto ??= webcrypto;
const key = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
const config = {
  sessionId: crypto.randomUUID(), generation: crypto.randomUUID(), windowId: 'main-window', keyEpoch: crypto.randomUUID(), peerId: crypto.randomUUID(),
  mediaRootKey: toBase64(crypto.getRandomValues(new Uint8Array(32))),
  hostPrivateKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('pkcs8', key.privateKey))),
  hostPublicKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('spki', key.publicKey))),
};
const lease = { lease_id: crypto.randomUUID(), peer_id: config.peerId, control_secret: toBase64(crypto.getRandomValues(new Uint8Array(32))), expires_at: new Date(Date.now() + 30000).toISOString() };
function harness(overrides = {}) {
  const requests = [], sent = [];
  const channel = Object.assign(new EventTarget(), { id: 4, readyState: 'open', bufferedAmount: 0, send: value => sent.push(JSON.parse(value)), close() { this.readyState = 'closed'; this.dispatchEvent(new Event('close')); } });
  const media = {
    config,
    call: async (action, body) => { requests.push({ action, ...body }); return overrides[action]?.(body) ?? (action === 'controlPoll' ? { lease } : action === 'controlAcquire' || action === 'controlRenew' ? lease : { channel_id: 4, session_description: { type: 'offer', sdp: 'data-offer' } }); },
    answer: async description => assert.equal(description.type, 'offer'),
    waitConnected: async () => {},
    peer: { createDataChannel: () => channel },
  };
  media.callWhenPublished = media.call;
  return { media, channel, requests, sent };
}
const until = async predicate => { for (let i = 0; i < 50 && !predicate(); i++) await new Promise(r => setTimeout(r, 2)); assert.ok(predicate()); };

test('viewer negotiates view-only channel before lease-gated reply and retains cipher across renewal', async () => {
  const h = harness(); const control = new AppControlChannel(h.media);
  await control.start();
  assert.deepEqual(h.requests.map(r => r.action), ['dataEstablish', 'dataSubscribe']);
  assert.equal(h.requests[1].can_reply, false);
  assert.equal(control.send({ type: 'key' }), false);
  await control.takeControl();
  assert.equal(h.requests.at(-1).can_reply, true); assert.equal(h.requests.at(-1).lease_id, lease.lease_id);
  const cipher = control.cipher;
  await control.renewLease(control.lease); assert.equal(control.cipher, cipher);
  await control.releaseControl(); assert.equal(control.lease, null);
  assert.equal(h.requests.at(-1).action, 'controlRelease');
  control.stop();
});

test('control readiness waits for SCTP open before automatic acquisition and cancels on stop', async () => {
  const h = harness(); h.channel.readyState = 'connecting';
  const viewer = new AppControlChannel(h.media); let ready = false;
  const starting = viewer.start().then(() => { ready = true; });
  await until(() => !!viewer.signing);
  assert.equal(ready, false); assert.equal(h.requests.some(request => request.action === 'controlAcquire'), false);
  h.channel.readyState = 'open'; h.channel.dispatchEvent(new Event('open'));
  await starting; await viewer.takeControl(); assert.ok(viewer.lease);
  await viewer.releaseControl(); viewer.stop();
  const other = harness(); other.channel.readyState = 'connecting';
  const stopped = new AppControlChannel(other.media);
  const waiting = stopped.start(); await until(() => !!stopped.signing);
  stopped.stop(); await assert.rejects(waiting, /channel unavailable/);
});

test('rapid release and resume waits for the server release and cancels a superseded resume', async () => {
  let finishRelease;
  const h = harness({ controlRelease: () => new Promise(resolve => { finishRelease = resolve; }) });
  const control = new AppControlChannel(h.media);
  await control.start(); await control.takeControl();
  const releasing = control.releaseControl({ reason: 'suspended' });
  const resuming = control.takeControl();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.requests.filter(request => request.action === 'controlAcquire').length, 1);
  const cancelled = control.releaseControl();
  finishRelease({}); await Promise.all([releasing, resuming, cancelled]);
  assert.equal(control.lease, null);
  assert.equal(h.requests.filter(request => request.action === 'controlAcquire').length, 1, 'A cancelled resume must not request a fresh lease');
  await control.takeControl(); assert.ok(control.lease);
  control.stop();
});

test('lease renewal retries temporary failures within its expiry and never retries host revocation', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let renewals = 0, denied = false;
  const h = harness({ controlRenew: () => {
    renewals++;
    if (denied || renewals < 3) throw Object.assign(new Error('Unavailable'), { status: denied ? 403 : 503 });
    return lease;
  } });
  const changes = [], control = new AppControlChannel(h.media, { onLease: (value, details) => changes.push({ value, details }) });
  try {
    await control.start(); await control.takeControl();
    const cipher = control.cipher;
    await control.renewLease(control.lease);
    assert.ok(control.lease); assert.equal(renewals, 1);
    t.mock.timers.tick(1000); await new Promise(resolve => setImmediate(resolve));
    assert.equal(renewals, 2); assert.ok(control.lease);
    t.mock.timers.tick(2000); await new Promise(resolve => setImmediate(resolve));
    assert.equal(renewals, 3); assert.equal(control.cipher, cipher);
    denied = true; await control.renewLease(control.lease);
    assert.equal(control.lease, null); assert.equal(changes.at(-1).details.reason, 'revoked');
    assert.equal(changes.at(-1).details.retryable, false);
    t.mock.timers.tick(20000); await new Promise(resolve => setImmediate(resolve));
    assert.equal(renewals, 4, 'Host revocation must not be renewed or reacquired');
  } finally { control.stop(); }
});

test('disconnect and release during acquisition retire the eventual lease without granting input', async () => {
  for (const during of ['controlAcquire', 'dataSubscribe']) {
    let resume, reached;
    const pending = new Promise(resolve => { resume = resolve; });
    const waiting = new Promise(resolve => { reached = resolve; });
    const h = harness(during === 'controlAcquire' ? { controlAcquire: async () => { reached(); await pending; return lease; } } : {});
    const leases = [];
    const viewer = new AppControlChannel(h.media, { onLease: value => leases.push(value) });
    await viewer.start();
    // Only block the reply-enabled subscription, after the view-only channel exists.
    if (during === 'dataSubscribe') {
      h.media.call = async (action, body) => {
        h.requests.push({ action, ...body });
        if (action === during) { reached(); await pending; return { channel_id: 4 }; }
        return action === 'controlAcquire' ? lease : {};
      };
    }
    const acquire = viewer.takeControl();
    await waiting;
    const release = viewer.releaseControl(); viewer.stop();
    resume(); await Promise.all([acquire, release]);
    assert.equal(viewer.lease, null);
    assert.equal(leases.some(Boolean), false);
    assert.equal(h.requests.filter(request => request.action === 'controlRelease').length, 1);
  }
});

test('host authenticates input and rejects replay, stale geometry and expired leases', async () => {
  const h = harness(), received = [];
  const host = new AppControlChannel(h.media, { host: true, onInput: input => received.push(input) });
  await host.start(); await until(() => !!host.lease);
  host.setGeometry({ width: 1200, height: 800, geometryRevision: 2 });
  const sender = await createControlCipher(config, { leaseId: lease.lease_id, peerId: config.peerId, controlSecret: lease.control_secret });
  const good = await sender.encrypt({ type: 'pointer', action: 'down', x: 0.2, y: 0.3, button: 0 }, 2);
  h.channel.onmessage({ data: JSON.stringify(good) }); await until(() => received.length === 1);
  h.channel.onmessage({ data: JSON.stringify(good) }); await until(() => host.queue.length === 0); assert.equal(received.length, 1);
  const stale = await sender.encrypt({ type: 'pointer', action: 'down', x: 0.2, y: 0.3, button: 0 }, 1);
  h.channel.onmessage({ data: JSON.stringify(stale) }); await until(() => host.queue.length === 0); assert.equal(received.length, 1);
  host.lease.expiresAt = Date.now() - 1;
  const expired = await sender.encrypt({ type: 'key', action: 'down', code: 'KeyA' }, 2);
  h.channel.onmessage({ data: JSON.stringify(expired) }); await until(() => host.queue.length === 0); assert.equal(received.length, 1);
  host.stop();
});

test('geometry comes only from host signatures, with replay and other window rejection', async () => {
  const publisher = harness(), subscriber = harness(), seen = [];
  const host = new AppControlChannel(publisher.media, { host: true });
  const viewer = new AppControlChannel(subscriber.media, { onGeometry: value => seen.push(value) });
  await host.start(); await viewer.start();
  host.setGeometry({ width: 1000, height: 700, geometryRevision: 3 });
  await host.announceGeometry();
  const geometry = publisher.sent.at(-1);
  subscriber.channel.onmessage({ data: JSON.stringify(geometry) }); await until(() => seen.length === 1);
  subscriber.channel.onmessage({ data: JSON.stringify(geometry) }); await until(() => viewer.queue.length === 0); assert.equal(seen.length, 1);
  const forged = structuredClone(geometry); forged.body.sequence++; forged.body.windowId = 'unrelated';
  subscriber.channel.onmessage({ data: JSON.stringify(forged) }); await until(() => viewer.queue.length === 0); assert.equal(seen.length, 1);
  host.stop(); viewer.stop();
});

test('changed geometry is signed and sent immediately without waiting for lease polling', async () => {
  const h = harness();
  const host = new AppControlChannel(h.media, { host: true });
  await host.start();
  try {
    await until(() => !!host.timer);
    host.setGeometry({ width: 1000, height: 700, geometryRevision: 4 });
    await host.geometryAnnouncement;
    assert.equal(h.sent.length, 1);
    assert.equal(h.sent[0].body.geometryRevision, 4);
    assert.equal(await crypto.subtle.verify('Ed25519', key.publicKey,
      Uint8Array.from(Buffer.from(h.sent[0].signature, 'base64')),
      new TextEncoder().encode(JSON.stringify(h.sent[0].body))), true);
    host.setGeometry({ width: 1000, height: 700, geometryRevision: 4 });
    await host.geometryAnnouncement;
    assert.equal(h.sent.length, 1);
    host.stop();
    host.setGeometry({ width: 1000, height: 700, geometryRevision: 5 });
    await host.geometryAnnouncement;
    assert.equal(h.sent.length, 1);
  } finally { host.stop(); }
});

test('host metrics are signed, view-only accessible, replay protected and independent of geometry', async () => {
  const publisher = harness({ controlPoll: () => ({}) }), subscriber = harness(), seen = [], geometries = [];
  const host = new AppControlChannel(publisher.media, { host: true });
  const viewer = new AppControlChannel(subscriber.media, { onMetrics: value => seen.push(value), onGeometry: value => geometries.push(value) });
  await host.start(); await viewer.start();
  const metrics = { fps: 30, fpsSource: 'encoded', bitrateKbps: 8000, rttMs: 20 };
  const deliver = async message => { subscriber.channel.onmessage({ data: JSON.stringify(message) }); await until(() => viewer.queue.length === 0); };
  const sign = async body => ({ type: 'metrics', body, signature: toBase64(new Uint8Array(await crypto.subtle.sign('Ed25519', key.privateKey, new TextEncoder().encode(JSON.stringify(body))))) });
  try {
    await host.announceMetrics(metrics);
    const original = publisher.sent.at(-1);
    await deliver(original); assert.deepEqual(seen, [metrics]); assert.equal(viewer.lease, undefined);
    await deliver(original); assert.equal(seen.length, 1);
    const tampered = structuredClone(original); tampered.body.sequence = 2; tampered.body.metrics.fps = 60;
    await deliver(tampered); assert.equal(seen.length, 1);
    for (const body of [{ ...original.body, sequence: 2, windowId: 'other' }, { ...original.body, sequence: 2, keyEpoch: 'old' },
      { ...original.body, sequence: 2, metrics: { ...metrics, fps: -1 } }, { ...original.body, sequence: 2, metrics: { ...metrics, address: 'private' } }]) {
      await deliver(await sign(body)); assert.equal(seen.length, 1);
    }
    host.setGeometry({ width: 1000, height: 700, geometryRevision: 1 }); await host.geometryAnnouncement;
    await deliver(publisher.sent.at(-1)); assert.equal(geometries.length, 1);
    await host.announceMetrics(metrics); await deliver(publisher.sent.at(-1)); assert.equal(seen.length, 2);
    assert.equal(viewer.lastGeometrySequence, 1); assert.equal(viewer.lastMetricsSequence, 2);
    publisher.channel.bufferedAmount = 100000; const count = publisher.sent.length;
    await host.announceMetrics(metrics); assert.equal(publisher.sent.length, count);
    host.stop(); await host.announceMetrics(metrics); assert.equal(publisher.sent.length, count);
    viewer.stop(); await deliver(await sign({ ...original.body, sequence: 3 })); assert.equal(seen.length, 2);
  } finally { host.stop(); viewer.stop(); }
});

test('pointer moves coalesce while discrete input is encrypted in order; backpressure relinquishes lease', async () => {
  const h = harness(); const viewer = new AppControlChannel(h.media);
  await viewer.start(); await viewer.takeControl(); viewer.geometry = { geometryRevision: 7 };
  for (let i = 0; i < 20; i++) viewer.send({ type: 'pointer', action: 'move', x: i / 20, y: 0.5 });
  viewer.send({ type: 'key', action: 'down', code: 'KeyA' }); await viewer.chain;
  assert.equal(h.sent.length, 2);
  const receiver = await createControlCipher(config, { leaseId: lease.lease_id, peerId: config.peerId, controlSecret: lease.control_secret });
  assert.equal((await receiver.decrypt(h.sent[0], 7)).event.x, 0.95);
  assert.equal((await receiver.decrypt(h.sent[1], 7)).event.code, 'KeyA');
  h.channel.bufferedAmount = 100000;
  assert.equal(viewer.send({ type: 'key', action: 'up', code: 'KeyA' }), false);
  assert.equal(viewer.lease, null); viewer.stop();
});

test('permission polling failure immediately clears host control authority', async () => {
  let fail = false;
  const h = harness({ controlPoll: () => { if (fail) throw new Error('revoked'); return { lease }; } });
  const host = new AppControlChannel(h.media, { host: true });
  await host.start(); await until(() => !!host.lease);
  clearTimeout(host.timer); fail = true; await host.pollLease();
  assert.equal(host.lease, null); assert.equal(host.cipher, null); host.stop();
});

test('transient permission polling pauses publication and clears input until authority recovers', async () => {
  let fail = false;
  const states = [], errors = [], leases = [];
  const h = harness({ controlPoll: () => { if (fail) throw Object.assign(new Error('upstream'), { status: 503 }); return { lease }; } });
  const host = new AppControlChannel(h.media, { host: true, onAuthority: state => states.push(state), onError: error => errors.push(error), onLease: (lease, status) => leases.push({ lease, status }) });
  await host.start(); await until(() => !!host.timer);
  const cipher = host.cipher;
  clearTimeout(host.timer); fail = true; await host.pollLease();
  assert.equal(states.at(-1), false); assert.equal(host.lease, null); assert.equal(host.cipher, null);
  assert.equal(host.closed, false); assert.equal(errors.length, 0);
  assert.equal(leases.at(-1).status.suspended, true);
  clearTimeout(host.timer); fail = false; await host.pollLease();
  assert.equal(states.at(-1), true); assert.equal(host.cipher, cipher);
  assert.equal(host.authorityDeadline, null); assert.equal(errors.length, 0); host.stop();
});

test('permission denial stops immediately and transient recovery has a deadline even if retry hangs', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  for (const status of [401, 403, 409, 503]) {
    let fail = false, errors = 0;
    const h = harness({ controlPoll: () => { if (fail) throw Object.assign(new Error('unavailable'), { status }); return { lease }; } });
    const host = new AppControlChannel(h.media, { host: true, onError: () => errors++ });
    await host.start(); await host.pollLease(); clearTimeout(host.timer);
    fail = true; await host.pollLease();
    if (status === 503) {
      assert.equal(errors, 0);
      clearTimeout(host.timer);
      h.media.call = () => new Promise(() => {});
      host.pollLease(); t.mock.timers.tick(5000);
      assert.equal(errors, 0, 'A five-second interruption no longer destroys publication');
      t.mock.timers.tick(25000);
    }
    assert.equal(errors, 1); assert.equal(host.closed, true); assert.equal(host.lease, null);
  }
});

test('repeated transient failures survive a longer interruption, broadcast pause and recover without a new publisher', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let failing = false; const states = [], errors = [];
  const h = harness({ controlPoll: () => { if (failing) throw new TypeError('network reset'); return { lease }; } });
  const host = new AppControlChannel(h.media, { host: true, onAuthority: value => states.push(value), onError: error => errors.push(error) });
  await host.start(); await host.pollLease(); clearTimeout(host.timer);
  host.geometry = { width: 1000, height: 700, geometryRevision: 2 };
  failing = true;
  for (let i = 0; i < 5; i++) { await host.pollLease(); clearTimeout(host.timer); }
  t.mock.timers.tick(9000);
  assert.equal(host.closed, false); assert.equal(errors.length, 0); assert.equal(states.at(-1), false);
  assert.equal(h.sent.filter(message => message.type === 'geometry').at(-1).body.authorityAvailable, false);
  failing = false; await host.pollLease(); clearTimeout(host.timer);
  assert.equal(states.at(-1), true); assert.equal(host.authorityDeadline, null);
  assert.equal(h.sent.filter(message => message.type === 'geometry').at(-1).body.authorityAvailable, true);
  assert.equal(h.requests.filter(request => request.action === 'dataPublish').length, 1);
  host.stop(); t.mock.timers.tick(30000); assert.equal(errors.length, 0);
});

test('reacquiring the same lease retains send counter and host replay history', async () => {
  const h = harness(); const viewer = new AppControlChannel(h.media);
  await viewer.start(); await viewer.takeControl(); viewer.geometry = { geometryRevision: 1 };
  viewer.send({ type: 'key', action: 'down', code: 'KeyA' }); await viewer.chain;
  await viewer.releaseControl(); await viewer.takeControl();
  viewer.send({ type: 'key', action: 'up', code: 'KeyA' }); await viewer.chain;
  assert.deepEqual(h.sent.map(command => command.sequence), [1, 2]); viewer.stop();
  const hostHarness = harness(), host = new AppControlChannel(hostHarness.media, { host: true });
  await host.start(); await until(() => !!host.cipher);
  const firstCipher = host.cipher;
  await host.cipher.decrypt(h.sent[0], 1);
  clearTimeout(host.timer); host.clearLease(); await host.pollLease();
  assert.equal(host.cipher, firstCipher);
  await assert.rejects(host.cipher.decrypt(h.sent[0], 1), /Replayed/);
  host.stop();
});
