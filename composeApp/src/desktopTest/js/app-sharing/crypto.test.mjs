import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { createMediaCipher, createControlCipher, toBase64, ReplayWindow } from '../../../desktopMain/resources/app-sharing/crypto.mjs';
import { videoPoint } from '../../../desktopMain/resources/app-sharing/control.mjs';

globalThis.crypto ??= webcrypto;
const key = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
const config = {
  sessionId: crypto.randomUUID(), generation: crypto.randomUUID(), windowId: 'main-window', keyEpoch: crypto.randomUUID(),
  mediaRootKey: toBase64(crypto.getRandomValues(new Uint8Array(32))),
  hostPrivateKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('pkcs8', key.privateKey))),
  hostPublicKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('spki', key.publicKey))),
};
const frame = new Uint8Array([0, 0, 0, 0x9d, 1, 0x2a, 0x20, 3, 0x58, 2, ...Array.from({ length: 4096 }, (_, i) => i % 251)]);

test('one authenticated encrypted frame fans out independently to ten viewers', async () => {
  const sender = await createMediaCipher(config, 'encrypt');
  const encrypted = await sender.encrypt(frame);
  assert.deepEqual(new Uint8Array(encrypted).slice(0, 10), frame.slice(0, 10));
  assert.notDeepEqual(new Uint8Array(encrypted).slice(10, 100), frame.slice(10, 100));
  const viewers = await Promise.all(Array.from({ length: 10 }, () => createMediaCipher(config, 'decrypt')));
  for (const viewer of viewers) assert.deepEqual(new Uint8Array(await viewer.decrypt(encrypted)), frame);
});

test('plaintext, duplicate frames, altered payload and altered clear codec header are rejected', async () => {
  const sender = await createMediaCipher(config, 'encrypt');
  const receiver = await createMediaCipher(config, 'decrypt');
  const packet = new Uint8Array(await sender.encrypt(frame));
  await assert.rejects(receiver.decrypt(frame), /Unencrypted|Invalid/);
  const corrupt = packet.slice(); corrupt[40] ^= 1;
  await assert.rejects(receiver.decrypt(corrupt), /Unauthenticated/);
  const header = packet.slice(); header[7] ^= 1;
  await assert.rejects(receiver.decrypt(header), /Unauthenticated/);
  assert.deepEqual(new Uint8Array(await receiver.decrypt(packet)), frame);
  await assert.rejects(receiver.decrypt(packet), /Replayed/);
});

test('generation, window, epoch and host signature key bind media identity', async () => {
  const sender = await createMediaCipher(config, 'encrypt');
  const packet = await sender.encrypt(frame);
  for (const override of [{ generation: crypto.randomUUID() }, { windowId: 'different-window' }, { keyEpoch: crypto.randomUUID() }]) {
    const receiver = await createMediaCipher({ ...config, ...override }, 'decrypt');
    await assert.rejects(receiver.decrypt(packet), /Unauthenticated/);
  }
  const attacker = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
  const forgedSender = await createMediaCipher({ ...config, hostPrivateKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('pkcs8', attacker.privateKey))) }, 'encrypt');
  const receiver = await createMediaCipher(config, 'decrypt');
  await assert.rejects(receiver.decrypt(await forgedSender.encrypt(frame)), /Unauthenticated/);
});

test('out-of-order media fits finite replay window; unauthenticated high counter cannot poison it', async () => {
  const sender = await createMediaCipher(config, 'encrypt');
  const receiver = await createMediaCipher(config, 'decrypt');
  const first = new Uint8Array(await sender.encrypt(frame));
  const second = await sender.encrypt(frame);
  const forged = first.slice(); forged.fill(255, 14, 22);
  await assert.rejects(receiver.decrypt(forged));
  await receiver.decrypt(second); await receiver.decrypt(first);
  const window = new ReplayWindow(); window.commit(5000n);
  assert.equal(window.accepts(3976n), false); assert.equal(window.accepts(3977n), true);
});

test('delta VP8 headers remain three bytes and missing encryption keys fail closed', async () => {
  const delta = frame.slice(); delta[0] = 1;
  const sender = await createMediaCipher(config, 'encrypt');
  const receiver = await createMediaCipher(config, 'decrypt');
  assert.deepEqual(new Uint8Array(await receiver.decrypt(await sender.encrypt(delta))), delta);
  await assert.rejects(createMediaCipher({ ...config, mediaRootKey: 'AA' }, 'encrypt'), /256-bit/);
  await assert.rejects(createMediaCipher({ ...config, hostPrivateKey: '' }, 'encrypt'));
});

test('control requires the lease secret and exact generation, window, geometry and monotonic sequence', async () => {
  const lease = { leaseId: crypto.randomUUID(), peerId: crypto.randomUUID(), controlSecret: toBase64(crypto.getRandomValues(new Uint8Array(32))) };
  const sender = await createControlCipher(config, lease);
  const receiver = await createControlCipher(config, lease);
  const command = await sender.encrypt({ type: 'key', action: 'down', code: 'KeyA' }, 4);
  await assert.rejects(receiver.decrypt(command, 5), /Stale/);
  const body = await receiver.decrypt(command, 4);
  assert.equal(body.event.code, 'KeyA'); assert.equal(body.peerId, lease.peerId);
  await assert.rejects(receiver.decrypt(command, 4), /Replayed/);
  for (const override of [{ leaseId: crypto.randomUUID() }, { peerId: crypto.randomUUID() }, { controlSecret: config.mediaRootKey }]) {
    const other = await createControlCipher(config, { ...lease, ...override });
    await assert.rejects(other.decrypt(command, 4));
  }
  const wrongGeneration = await createControlCipher({ ...config, generation: crypto.randomUUID() }, lease);
  await assert.rejects(wrongGeneration.decrypt(command, 4));
  const next = await sender.encrypt({ type: 'pointer', action: 'up', x: 0.5, y: 0.5, button: 0 }, 4);
  assert.equal((await receiver.decrypt(next, 4)).sequence, 2);
});

test('control payloads are bounded and invalid sequences cannot decrypt', async () => {
  const lease = { leaseId: crypto.randomUUID(), peerId: crypto.randomUUID(), controlSecret: config.mediaRootKey };
  const cipher = await createControlCipher(config, lease);
  await assert.rejects(cipher.encrypt({ text: 'x'.repeat(9000) }, 0), /too large/);
  await assert.rejects(cipher.decrypt({ sequence: -1, payload_b64: 'AA' }, 0), /sequence/);
});

test('keyboard geometry can lag a popup but coordinate input and future geometry remain strict', async () => {
  const lease = { leaseId: crypto.randomUUID(), peerId: crypto.randomUUID(), controlSecret: config.mediaRootKey };
  const sender = await createControlCipher(config, lease), receiver = await createControlCipher(config, lease);
  const key = await sender.encrypt({ type: 'key', action: 'down', code: 'KeyA' }, 1);
  assert.equal((await receiver.decrypt(key, 2, true)).event.code, 'KeyA');
  await assert.rejects(receiver.decrypt(key, 2, true), /Replayed/);
  await assert.rejects(receiver.decrypt(await sender.encrypt({ type: 'pointer', action: 'down', x: .5, y: .5 }, 1), 2, true), /Stale/);
  await assert.rejects(receiver.decrypt(await sender.encrypt({ type: 'key', action: 'down', code: 'KeyA' }, 3), 2, true), /Stale/);
});

test('window recovery is encrypted, replay protected and limited to current or prior positive geometry', async () => {
  const lease = { leaseId: crypto.randomUUID(), peerId: crypto.randomUUID(), controlSecret: config.mediaRootKey };
  const sender = await createControlCipher(config, lease), receiver = await createControlCipher(config, lease);
  const restore = await sender.encrypt({ type: 'window', action: 'restore' }, 1);
  assert.equal((await receiver.decrypt(restore, 2, true)).event.action, 'restore');
  await assert.rejects(receiver.decrypt(restore, 2, true), /Replayed/);
  const exit = await sender.encrypt({ type: 'window', action: 'exit-fullscreen' }, 2);
  assert.equal((await receiver.decrypt(exit, 2, true)).event.action, 'exit-fullscreen');
  for (const [action, revision] of [['restore', 0], ['restore', 3], ['start-capture', 1], ['close', 1], ['minimize', 1], ['maximize', 1], ['unmaximize', 1]]) {
    await assert.rejects(receiver.decrypt(await sender.encrypt({ type: 'window', action }, revision), 2, true), /Stale/);
  }
  for (const action of ['close', 'minimize', 'maximize', 'unmaximize']) {
    assert.equal((await receiver.decrypt(await sender.encrypt({ type: 'window', action }, 2), 2, true)).event.action, action);
  }
  const wrongWindow = await createControlCipher({ ...config, windowId: 'unrelated-window' }, lease);
  await assert.rejects(wrongWindow.decrypt(await sender.encrypt({ type: 'window', action: 'restore' }, 2), 2, true));
});

test('letterbox coordinate mapping excludes bars and maps source edges exactly', () => {
  const rect = { left: 10, top: 20, width: 1000, height: 1000 };
  assert.equal(videoPoint(500, 100, rect, 1000, 500), null);
  assert.deepEqual(videoPoint(510, 520, rect, 1000, 500), { x: 0.5, y: 0.5 });
  assert.deepEqual(videoPoint(10, 270, rect, 1000, 500), { x: 0, y: 0 });
  assert.equal(videoPoint(10, 10, { ...rect, width: 0 }, 100, 100), null);
});
