import { createBridge, closeViewerResources } from './bridge.mjs';
import { WindowMediaPeer } from './media.mjs';
import { AppControlChannel } from './control.mjs';
import { fromBase64, toBase64 } from './crypto.mjs';

// Real SFU integration, opt-in only. All input ends at this private callback;
// no native window, DOM event injection, account token or provider secret exists here.
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const rounded = value => Math.round(value * 100) / 100;
const viewers = [], stages = [], accepted = [], resources = [];
let stage = 'configuration', host, admin, captureTimer, heartbeatTimer, revoking = false, hostStoppedAt;
let fixture, scope;
const requestCounts = new Map();
function bridge() { const value = createBridge(fixture); resources.push(value); return value; }
function media(config, ownerBridge, onState = () => {}) {
  return new WindowMediaPeer({ config, onState, request: body => {
    requestCounts.set(body.action, (requestCounts.get(body.action) ?? 0) + 1);
    return ownerBridge.request(body);
  } });
}
async function until(check, code, timeout = 20000) {
  const end = performance.now() + timeout;
  while (performance.now() < end) { if (await check()) return; await delay(40); }
  throw new Error(code);
}
async function stats(peer, direction = 'inbound-rtp') {
  const report = await peer.getStats();
  let frames = 0, bytes = 0, streams = 0, processingSeconds = 0;
  for (const value of report.values()) if (value.type === direction && value.kind === 'video') {
    streams++; frames += (direction === 'inbound-rtp' ? value.framesDecoded : value.framesEncoded) ?? 0;
    bytes += (direction === 'inbound-rtp' ? value.bytesReceived : value.bytesSent) ?? 0;
    processingSeconds += (direction === 'inbound-rtp' ? value.totalDecodeTime : value.totalEncodeTime) ?? 0;
  }
  return { frames, bytes, streams, processingSeconds };
}
function syntheticCanvas() {
  const canvas = document.createElement('canvas'); canvas.width = 640; canvas.height = 360;
  document.body.append(canvas); const context = canvas.getContext('2d', { alpha: false }); let frame = 0;
  const stream = canvas.captureStream(0), track = stream.getVideoTracks()[0];
  if (!track?.requestFrame) throw new Error('canvas_capture_unavailable');
  const draw = () => {
    context.fillStyle = '#e62830'; context.fillRect(0, 0, 320, 360);
    context.fillStyle = '#20cc50'; context.fillRect(320, 0, 320, 360);
    context.fillStyle = (++frame & 1) ? '#fff' : '#000'; context.fillRect(0, 0, 16, 16);
    context.fillStyle = '#fff'; context.font = '20px monospace'; context.fillText('Synthetic encrypted BossConsole', 20, 320);
    track.requestFrame();
  };
  draw(); captureTimer = setInterval(draw, 100);
  return { track, canvas, stream };
}
function pixelProbe(video) {
  if (video.readyState < 2 || video.videoWidth !== 640 || video.videoHeight !== 360) return false;
  const canvas = document.createElement('canvas'); canvas.width = 640; canvas.height = 360;
  const context = canvas.getContext('2d', { willReadFrequently: true }); context.drawImage(video, 0, 0);
  const match = (x, expected) => expected.every((value, i) => Math.abs(context.getImageData(x, 160, 1, 1).data[i] - value) <= 35);
  return match(160, [230, 40, 48]) && match(480, [32, 204, 80]);
}
async function createHost() {
  admin = bridge();
  const preferences = await admin.request({ action: 'preferencesGet' });
  if (preferences.auto_admit !== true || preferences.auto_control !== true) throw new Error('dedicated_test_account_requires_default_preferences');
  const pair = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
  const config = {
    sessionId: fixture.sessionId, generation: fixture.generation, windowId: fixture.windowId, keyEpoch: crypto.randomUUID(),
    mediaRootKey: toBase64(crypto.getRandomValues(new Uint8Array(32))),
    hostPrivateKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey))),
    hostPublicKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey))),
  };
  const link = new URL(fixture.viewerBaseUrl); link.searchParams.set('session', config.sessionId); link.hash = `k=${config.mediaRootKey}`;
  const registered = await admin.request({ action: 'register', ...scope, device_id: fixture.deviceId, instance_id: fixture.instanceId,
    name: 'Private synthetic SFU test', windows: [{ id: config.windowId, title: 'Synthetic canvas' }], viewer_url: link.href,
    key_epoch: config.keyEpoch, host_public_key: config.hostPublicKey });
  config.peerId = registered.host_peer_id;
  const ownerBridge = bridge(); host = { config, bridge: ownerBridge, capture: syntheticCanvas() };
  host.media = media(config, ownerBridge);
  await host.media.publish(host.capture.track);
  host.control = new AppControlChannel(host.media, { host: true, onInput: value => accepted.push({ value, at: performance.now() }), onError: () => {
    hostStoppedAt ??= performance.now(); host.control.stop(); host.media.stop();
  } });
  host.control.setGeometry({ width: 640, height: 360, geometryRevision: 1 });
  await host.control.start();
  await until(() => host.control.channel?.readyState === 'open', 'host_datachannel_did_not_open');
  heartbeatTimer = setInterval(() => admin.request({ action: 'heartbeat', ...scope }).catch(() => {
    if (!revoking) { hostStoppedAt ??= performance.now(); host.control.stop(); host.media.stop(); }
  }), 25000);
}
async function addViewer() {
  const ownerBridge = bridge(), deviceId = crypto.randomUUID(), started = performance.now();
  const admission = { ...scope, device_id: deviceId, role: 'control' };
  const admitted = await ownerBridge.request({ action: 'admit', ...admission });
  const consumed = await ownerBridge.request({ action: 'consume', ...admission, ticket: admitted.ticket });
  const config = { sessionId: fixture.sessionId, generation: fixture.generation, windowId: fixture.windowId, peerId: consumed.peer_id,
    keyEpoch: admitted.key_epoch, hostPublicKey: admitted.host_public_key, mediaRootKey: new URLSearchParams(new URL(admitted.viewer_url).hash.slice(1)).get('k'), role: consumed.role };
  const video = document.createElement('video'); video.autoplay = true; video.muted = true; video.playsInline = true; document.body.append(video);
  const viewer = { config, bridge: ownerBridge, video, media: media(config, ownerBridge) }; viewers.push(viewer);
  await viewer.media.subscribe(track => { video.srcObject = new MediaStream([track]); video.play().catch(() => {}); });
  await until(() => pixelProbe(video), 'viewer_encrypted_pixels_unavailable');
  viewer.firstFrameMs = rounded(performance.now() - started);
  viewer.control = new AppControlChannel(viewer.media, { onError: () => {} });
  await viewer.control.start();
  await until(() => viewer.control.channel?.readyState === 'open' && viewer.control.geometry?.geometryRevision === 1, 'viewer_authenticated_geometry_unavailable');
  if (viewer.control.send({ type: 'pointer', action: 'move', x: 0.5, y: 0.5, button: 0 })) throw new Error('view_only_input_not_refused');
  return viewer;
}
async function sampleStage(count) {
  stage = `viewers_${count}`; const started = performance.now();
  while (viewers.length < count) await addViewer();
  const before = await Promise.all(viewers.map(viewer => stats(viewer.media.peer)));
  const publisherBefore = await stats(host.media.peer, 'outbound-rtp'), samplingStarted = performance.now();
  await delay(1500);
  const after = await Promise.all(viewers.map(viewer => stats(viewer.media.peer)));
  if (after.some((value, i) => value.frames <= before[i].frames || !pixelProbe(viewers[i].video))) throw new Error('live_fanout_stalled');
  const publisher = await stats(host.media.peer, 'outbound-rtp');
  const sampleSeconds = (performance.now() - samplingStarted) / 1000;
  if (publisher.streams !== 1 || requestCounts.get('mediaPublish') !== 1) throw new Error('publisher_was_duplicated');
  stages.push({ viewers: count, joinMs: rounded(performance.now() - started), maxFirstFrameMs: Math.max(...viewers.map(viewer => viewer.firstFrameMs)),
    minimumDecodedFramesDuringSample: Math.min(...after.map((value, i) => value.frames - before[i].frames)),
    aggregateReceiveBitsPerSecond: rounded(after.reduce((sum, value, i) => sum + value.bytes - before[i].bytes, 0) * 8 / sampleSeconds),
    publisherSendBitsPerSecond: rounded((publisher.bytes - publisherBefore.bytes) * 8 / sampleSeconds),
    publisherEncodeMilliseconds: rounded((publisher.processingSeconds - publisherBefore.processingSeconds) * 1000),
    aggregateDecodeMilliseconds: rounded(after.reduce((sum, value, i) => sum + value.processingSeconds - before[i].processingSeconds, 0) * 1000),
    publisherStreams: publisher.streams, publicationRequests: requestCounts.get('mediaPublish') });
}
async function controlProbe() {
  stage = 'persistent_control'; const controller = viewers[0], latencies = [];
  await controller.control.takeControl();
  await until(() => host.control.lease?.leaseId === controller.control.lease?.leaseId, 'host_control_lease_unavailable');
  for (let index = 1; index <= 10; index++) {
    const before = accepted.length, started = performance.now();
    if (!controller.control.send({ type: 'pointer', action: 'move', x: index / 100, y: 0.5, button: 0 })) throw new Error('control_send_refused');
    await until(() => accepted.length === before + 1, 'persistent_control_not_delivered', 5000);
    const received = accepted.at(-1);
    if (received.value.peerId !== controller.config.peerId || received.value.event.x !== index / 100) throw new Error('control_identity_mismatch');
    latencies.push(rounded(received.at - started));
  }
  const packet = await controller.control.cipher.encrypt({ type: 'pointer', action: 'move', x: 0.9, y: 0.5, button: 0 }, 1);
  const before = accepted.length; controller.control.channel.send(JSON.stringify(packet));
  await until(() => accepted.length === before + 1, 'control_packet_unavailable');
  controller.control.channel.send(JSON.stringify(packet));
  const bytes = fromBase64(packet.payload_b64); bytes[bytes.length - 1] ^= 1;
  controller.control.channel.send(JSON.stringify({ ...packet, sequence: packet.sequence + 1, payload_b64: toBase64(bytes) }));
  await delay(1200);
  if (accepted.length !== before + 1) throw new Error('control_replay_or_tamper_accepted');
  controller.control.send({ type: 'pointer', action: 'move', x: 0.91, y: 0.5, button: 0 });
  await until(() => accepted.length === before + 2, 'tamper_poisoned_control_counter');
  await controller.control.releaseControl();
  await until(() => host.control.lease === null, 'host_lease_release_not_observed');
  latencies.sort((a, b) => a - b);
  return { samples: latencies.length, p50Ms: latencies[4], p95Ms: latencies[9], replayRejected: true, tamperRejected: true, releaseObserved: true };
}
async function disconnectProbe() {
  stage = 'viewer_disconnect'; const removed = viewers.pop(), started = performance.now();
  await closeViewerResources(removed); removed.video.srcObject = null; removed.video.remove();
  const disconnectMs = rounded(performance.now() - started), before = await stats(viewers[0].media.peer);
  await delay(1500); const after = await stats(viewers[0].media.peer);
  await admin.request({ action: 'heartbeat', ...scope });
  if (after.frames <= before.frames || host.media.closed) throw new Error('disconnect_interrupted_other_viewers');
  return { disconnectMs, remainingViewers: viewers.length, otherViewersProgressing: true };
}
async function revokeProbe() {
  stage = 'remote_revocation'; revoking = true; const started = performance.now();
  // Separate owner RPC simulates revocation on another device. Host learns
  // only through its normal authority poll, not this response or a local stop.
  const result = await admin.request({ action: 'revoke', ...scope, host_peer_id: host.config.peerId, target_peer_id: viewers[0].config.peerId });
  if (result.must_stop !== true) throw new Error('revocation_not_fenced');
  await until(() => host.media.closed && hostStoppedAt !== undefined, 'revocation_did_not_stop_host');
  await delay(1000); const before = await Promise.all(viewers.map(viewer => stats(viewer.media.peer)));
  await delay(1500); const after = await Promise.all(viewers.map(viewer => stats(viewer.media.peer)));
  if (after.some((value, i) => value.frames !== before[i].frames)) throw new Error('revoked_generation_kept_streaming');
  let denied = false;
  try { await admin.request({ action: 'admit', ...scope, device_id: crypto.randomUUID(), role: 'view' }); } catch (_) { denied = true; }
  if (!denied) throw new Error('retired_generation_readmitted');
  return { hostStoppedMs: rounded(hostStoppedAt - started), quiescentVerifiedMs: rounded(performance.now() - started), staleAdmissionRejected: true };
}
async function cleanup() {
  clearInterval(captureTimer); clearInterval(heartbeatTimer);
  host?.control?.stop(); host?.capture?.stream.getTracks().forEach(track => track.stop());
  await Promise.allSettled([...viewers.map(closeViewerResources), host?.media?.stop()]);
  if (admin) await admin.request({ action: 'stop', ...scope }).catch(() => {});
  resources.forEach(value => value.close());
}
let report;
try {
  fixture = await (await fetch('./fixture', { cache: 'no-store' })).json();
  scope = { session_id: fixture.sessionId, generation: fixture.generation };
  stage = 'host_publication'; await createHost();
  for (const count of [1, 3, 10]) await sampleStage(count);
  const control = await controlProbe(), disconnect = await disconnectProbe(), revocation = await revokeProbe();
  report = { passed: true, topology: 'one_private_browser_real_sfu', stages, control, disconnect, revocation };
} catch (error) {
  // DOMExceptions may include SDP/URLs. Only our fixed symbolic codes are
  // reportable; never serialize a raw provider/runtime exception or config.
  const code = typeof error.code === 'string' ? error.code : error.message;
  report = { passed: false, stage, error: typeof code === 'string' && /^[a-z][a-z0-9_]{0,79}$/.test(code) ? code : 'media_runtime_error', stages };
}
finally { await cleanup(); }
await fetch('./result', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(report) });
