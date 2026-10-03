import test from 'node:test';
import assert from 'node:assert/strict';
import { AdaptiveFrameRate, WindowMediaPeer, NativeFrameCanvas, acceptEncryptedVideoOffer, waitForIce, waitForConnected, preferSmoothVideo } from '../../../desktopMain/resources/app-sharing/media.mjs';
import { createBridge, closeViewerResources, resolveBridgeRequest } from '../../../desktopMain/resources/app-sharing/bridge.mjs';
import { AppControlChannel } from '../../../desktopMain/resources/app-sharing/control.mjs';

function harness() {
  const log = [], requests = [], peers = [];
  class Peer extends EventTarget {
    constructor(options) { super(); this.options = options; this.transceivers = []; this.iceGatheringState = 'complete'; this.connectionState = 'new'; peers.push(this); }
    addTransceiver(kind, { direction, sendEncodings }) {
      this.sendEncodings = sendEncodings;
      this.tx = { mid: '0', direction, sender: { replaceTrack: async () => log.push('attach-track') }, receiver: { track: { kind, enabled: true, stop() { log.push('stop-track'); } } }, setCodecPreferences: codecs => { assert.deepEqual(codecs.map(c => c.mimeType), ['video/VP8']); } }; this.transceivers.push(this.tx); return this.tx;
    }
    getTransceivers() { return this.transceivers; }
    async createOffer() { return { type: 'offer', sdp: 'publisher-offer' }; }
    async createAnswer() { return { type: 'answer', sdp: 'subscriber-answer' }; }
    async setLocalDescription(description) { this.localDescription = description; log.push('local-description'); }
    async setRemoteDescription(description) {
      this.remoteDescription = description; this.connectionState = 'connected'; log.push('remote-description');
      // An incoming offer does not reuse an addTransceiver-created receiver.
      if (description.type === 'offer') this.addTransceiver('video', { direction: 'recvonly' });
    }
    close() { this.connectionState = 'closed'; log.push('close-peer'); }
  }
  const env = { crypto: { subtle: {} }, RTCPeerConnection: Peer, RTCRtpSender: { getCapabilities: () => ({ codecs: [{ mimeType: 'video/VP8' }, { mimeType: 'video/H264' }] }), prototype: { createEncodedStreams() {} } }, RTCRtpReceiver: { prototype: { createEncodedStreams() {} } } };
  const request = async body => {
    requests.push(body);
    if (body.action === 'mediaPublish') return { session_description: { type: 'answer', sdp: 'sfu-answer' } };
    if (body.action === 'mediaSubscribe') return { session_description: { type: 'offer', sdp: 'sfu-offer' }, mid: '0' };
    return { ice_servers: [] };
  };
  const config = { sessionId: 'app-session', generation: 'generation', peerId: 'admitted-peer', windowId: 'selected-window' };
  const encrypt = async (_, __, direction) => { log.push(`encrypt-${direction}`); return () => log.push('close-transform'); };
  const create = () => new WindowMediaPeer({ config, request, env, encrypt });
  return { log, requests, peers, env, request, config, encrypt, create };
}

function rateReports() {
  let timestamp = 0, framesEncoded = 0, totalEncodeTime = 0;
  return (reason = 'none', costMs = 5, frames = 30, id = 'out') => {
    timestamp += 1000; framesEncoded += frames; totalEncodeTime += costMs * frames / 1000;
    return new Map([[id, { id, type: 'outbound-rtp', kind: 'video', timestamp, framesEncoded,
      totalEncodeTime, qualityLimitationReason: reason }]]);
  };
}
test('adaptive capture preserves smoothness under bandwidth pressure and falls back for CPU pressure', () => {
  const rate = new AdaptiveFrameRate(), report = rateReports();
  rate.sample(report());
  for (let i = 0; i < 9; i++) assert.equal(rate.sample(report()), 30);
  assert.equal(rate.sample(report()), 60);
  for (let i = 0; i < 12; i++) assert.equal(rate.sample(report('bandwidth', 5, 60)), 60);
  assert.equal(rate.sample(report('cpu', 5, 60)), 60);
  assert.equal(rate.sample(report('cpu', 5, 60)), 30);
  for (let i = 0; i < 10; i++) rate.sample(report());
  assert.equal(rate.frameRate, 60);
  rate.sample(report('none', 20));
  assert.equal(rate.sample(report('none', 20)), 30);
});
test('bandwidth adaptation can promote a sustained full-rate stream but sparse frames cannot', () => {
  const rate = new AdaptiveFrameRate(), report = rateReports();
  for (let i = 0; i < 15; i++) assert.equal(rate.sample(report('none', 1, 2)), 30);
  for (let i = 0; i < 9; i++) assert.equal(rate.sample(report('bandwidth', 5, 30)), 30);
  assert.equal(rate.sample(report('bandwidth', 5, 30)), 60);
});
test('idle counters, unavailable stats and a replaced stream never promote frame rate', () => {
  const rate = new AdaptiveFrameRate(), report = rateReports();
  for (let i = 0; i < 12; i++) assert.equal(rate.sample(report('none', 0, 0)), 30);
  for (let i = 0; i < 9; i++) rate.sample(report());
  rate.sample(undefined);
  assert.equal(rate.sample(report()), 30);
  for (let i = 0; i < 12; i++) assert.equal(rate.sample(report('none', 5, 30, String(i))), 30);
});
test('publisher changes native pacing only after the sender accepts it and stops tuning after close', async () => {
  const h = harness(), rates = [], applied = [], report = rateReports();
  const original = h.env.RTCPeerConnection.prototype.addTransceiver;
  h.env.RTCPeerConnection.prototype.addTransceiver = function(...args) {
    const tx = original.apply(this, args);
    tx.sender.getParameters = () => ({ encodings: [{}] });
    tx.sender.setParameters = async value => { applied.push(value.encodings[0].maxFramerate); };
    return tx;
  };
  const media = h.create();
  await media.publish({ stop() {} }, rate => rates.push(rate));
  for (let i = 0; i < 11; i++) await media.updateCaptureRate(report());
  assert.deepEqual(rates, [60]);
  await media.updateCaptureRate(report('cpu'));
  await media.updateCaptureRate(report('cpu'));
  assert.deepEqual(rates, [60, 30]);
  assert.deepEqual(applied, [30, 60, 30]);
  media.stop();
  for (let i = 0; i < 12; i++) await media.updateCaptureRate(report());
  assert.deepEqual(rates, [60, 30]);
});

test('continuous desktop publisher gives text bitrate headroom while remaining congestion-controlled', async () => {
  let applied;
  await preferSmoothVideo({ getParameters: () => ({ encodings: [{}] }), setParameters: async value => { applied = value; } });
  assert.equal(applied.encodings[0].maxBitrate, 8_000_000);
  assert.equal(applied.encodings[0].maxFramerate, 30);
});

test('one publisher captures once and encrypts before track attachment or SDP', async () => {
  const h = harness(), media = h.create(); let stopped = 0;
  await media.publish({ stop() { stopped++; } });
  assert.equal(h.peers.length, 1);
  assert.deepEqual(h.peers[0].sendEncodings, [{ maxBitrate: 8_000_000, maxFramerate: 30, scaleResolutionDownBy: 1 }]);
  assert.equal(h.requests.filter(r => r.action === 'mediaPublish').length, 1);
  assert.ok(h.log.indexOf('encrypt-encrypt') < h.log.indexOf('attach-track'));
  assert.ok(h.log.indexOf('encrypt-encrypt') < h.log.indexOf('local-description'));
  await assert.rejects(media.connect(), /already connected/);
  media.stop(); media.stop();
  assert.equal(stopped, 1); assert.equal(h.requests.filter(r => r.action === 'mediaClose').length, 1);
});

test('publisher applies frame-rate priority after negotiation without changing existing encoding identity', async () => {
  const h = harness(), applied = [];
  const parameters = { transactionId: 'existing', encodings: [{ rid: 'existing', maxBitrate: 6000000 }], codecs: [{ mimeType: 'video/VP8' }] };
  const original = h.env.RTCPeerConnection.prototype.addTransceiver;
  h.env.RTCPeerConnection.prototype.addTransceiver = function(...args) {
    const tx = original.apply(this, args);
    tx.sender.getParameters = () => { assert.ok(h.log.includes('remote-description')); return structuredClone(parameters); };
    tx.sender.setParameters = async value => { applied.push(value); h.log.push('smooth-video'); };
    return tx;
  };
  const media = h.create();
  await media.publish({ stop() {} });
  assert.equal(applied.length, 1);
  assert.equal(applied[0].degradationPreference, 'maintain-framerate');
  assert.equal(applied[0].encodings[0].maxFramerate, 30);
  assert.equal(applied[0].encodings[0].rid, 'existing');
  assert.equal(applied[0].encodings[0].maxBitrate, 6000000);
  assert.equal(h.requests.filter(r => r.action === 'mediaPublish').length, 1);
  assert.ok(h.log.indexOf('encrypt-encrypt') < h.log.indexOf('smooth-video'));
  media.stop();
});

test('older encoders keep motion preference without optional tuning becoming a sharing failure', async () => {
  assert.equal(await preferSmoothVideo({}), false);
  assert.equal(await preferSmoothVideo({ getParameters: () => ({ encodings: [{}] }), setParameters: async () => { throw new Error('Unsupported'); } }), false);
});

test('optional desktop startup hint falls back only to the original authenticated publisher answer', async () => {
  const h = harness(), attempts = [];
  const originalAnswer = { type: 'answer', sdp: 'v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:0\r\na=rtpmap:96 VP8/90000\r\n' };
  const setRemote = h.env.RTCPeerConnection.prototype.setRemoteDescription;
  h.env.RTCPeerConnection.prototype.setRemoteDescription = async function(description) {
    attempts.push(description);
    if (description.sdp.includes('x-google-start-bitrate')) {
      this.signalingState = 'have-local-offer'; throw new Error('Optional hint unsupported');
    }
    return setRemote.call(this, description);
  };
  const media = new WindowMediaPeer({ ...h, request: async body =>
    body.action === 'mediaPublish' ? { session_description: originalAnswer } : h.request(body) });
  await media.publish({ stop() {} });
  assert.equal(attempts.length, 2);
  assert.match(attempts[0].sdp, /x-google-start-bitrate=4000/);
  assert.strictEqual(attempts[1], originalAnswer);
  assert.ok(h.log.indexOf('encrypt-encrypt') < h.log.indexOf('attach-track'));
  media.stop();
});

test('subscriber binds the actual offered receiver before answering and exposes only that track', async () => {
  const h = harness(), media = h.create(); let received;
  await media.subscribe(track => { received = track; h.log.push('expose-track'); });
  assert.equal(h.peers[0].options.encodedInsertableStreams, true);
  assert.equal(h.peers[0].getTransceivers().length, 1);
  assert.equal(received, h.peers[0].getTransceivers()[0].receiver.track);
  assert.ok(h.log.indexOf('remote-description') < h.log.indexOf('encrypt-decrypt'));
  assert.ok(h.log.indexOf('encrypt-decrypt') < h.log.indexOf('local-description'));
  assert.ok(h.log.indexOf('local-description') < h.log.indexOf('expose-track'));
  for (const request of h.requests) {
    assert.equal(request.peer_id, h.config.peerId); assert.equal(request.window_id, h.config.windowId);
    assert.equal(request.session_id, h.config.sessionId); assert.equal(request.generation, h.config.generation);
    assert.equal(request.sfu_session_id, undefined);
  }
  assert.equal(h.requests.at(-1).action, 'mediaRenegotiate'); media.stop();
});

test('subscriber refuses unexpected mids or extra media sections before installing a decryptor', async () => {
  for (const extra of [false, true]) {
    const h = harness(), peer = new h.env.RTCPeerConnection({ encodedInsertableStreams: true });
    if (extra) peer.addTransceiver('video', { direction: 'recvonly' });
    await assert.rejects(acceptEncryptedVideoOffer(peer, { type: 'offer' }, extra ? '0' : 'other', h.config, () => {}, h.env, h.encrypt), /Unexpected SFU media section/);
    assert.equal(h.log.includes('encrypt-decrypt'), false);
  }
});

test('unsupported encryption rejects before creating an SFU session', async () => {
  const h = harness(); h.env.RTCRtpSender.prototype = {};
  const media = h.create(); await assert.rejects(media.publish({ stop() {} }), /encrypted/);
  assert.equal(h.requests.filter(r => r.action === 'mediaCreate').length, 0);
});

test('on-demand publication waits only for the explicit pending code and stops on owner cancellation', async () => {
  const h = harness(); let attempts = 0;
  const pending = () => Object.assign(new Error('pending'), { code: 'publication_pending' });
  const media = new WindowMediaPeer({ ...h, request: async () => { if (++attempts === 1) throw pending(); return { ready: true }; } });
  assert.deepEqual(await media.callWhenPublished('mediaSubscribe', {}, { retryMs: 1 }), { ready: true });
  assert.equal(attempts, 2);
  media.request = async () => { throw Object.assign(new Error('denied'), { code: 'forbidden' }); };
  await assert.rejects(media.callWhenPublished('mediaSubscribe'), /denied/);
  media.request = async () => { throw pending(); };
  await assert.rejects(media.callWhenPublished('mediaSubscribe', {}, { timeoutMs: 0 }), /pending/);
  const waiting = media.callWhenPublished('mediaSubscribe');
  await new Promise(resolve => setImmediate(resolve)); media.stop();
  await assert.rejects(waiting, /stopped/);
});

test('stop during SFU creation cannot resurrect a peer', async () => {
  const h = harness(); let resolve;
  const media = new WindowMediaPeer({ ...h, request: body => body.action === 'mediaCreate' ? new Promise(r => { resolve = r; }) : Promise.resolve({}) });
  const pending = media.publish({ stop() {} }); media.stop(); resolve({});
  await assert.rejects(pending, /stopped/); assert.equal(h.peers.length, 0);
});

test('SFU negotiation failure cleans up media and transform without fallback', async () => {
  const h = harness(); let stopped = 0;
  const media = new WindowMediaPeer({ ...h, request: async body => body.action === 'mediaPublish' ? { session_description: { type: 'offer' } } : {} });
  await assert.rejects(media.publish({ stop() { stopped++; } }), /publish answer/);
  assert.equal(stopped, 1); assert.ok(h.log.includes('close-transform')); assert.ok(h.log.includes('close-peer'));
});

test('publisher reports a safe failure reason before cleanup can clear the host owner', async () => {
  const h = harness(), states = [];
  const media = new WindowMediaPeer({ ...h, onState: (state, reason) => states.push({ state, reason }), request: async body => {
    if (body.action === 'mediaPublish') throw new Error('Media connection timed out');
    return {};
  } });
  await assert.rejects(media.publish({ stop() {} }), /timed out/);
  assert.deepEqual(states.slice(-2), [{ state: 'failed', reason: 'media_connection_timeout' }, { state: 'stopped', reason: undefined }]);
});

test('ICE wait aborts immediately when the owner closes', async () => {
  const peer = new EventTarget(); peer.iceGatheringState = 'gathering';
  const abort = new AbortController(); const wait = waitForIce(peer, abort.signal); abort.abort();
  await assert.rejects(wait, /stopped/);
});

test('connected wait does not mistake negotiation for usable media', async () => {
  const peer = new EventTarget(); peer.connectionState = 'connecting';
  let connected = false;
  const promise = waitForConnected(peer).then(() => { connected = true; });
  await Promise.resolve(); assert.equal(connected, false);
  peer.connectionState = 'connected'; peer.dispatchEvent(new Event('connectionstatechange'));
  await promise; assert.equal(connected, true);
});

test('transient disconnect can recover within grace but an abandoned peer closes after the deadline', async () => {
  const h = harness(), media = new WindowMediaPeer({ ...h, reconnectGraceMs: 5 });
  await media.subscribe(() => {}); const peer = h.peers[0];
  peer.connectionState = 'disconnected'; peer.onconnectionstatechange();
  peer.connectionState = 'connected'; peer.onconnectionstatechange();
  await new Promise(resolve => setTimeout(resolve, 15));
  assert.equal(media.closed, false); assert.equal(h.requests.some(value => value.action === 'mediaClose'), false);
  peer.connectionState = 'disconnected'; peer.onconnectionstatechange();
  await new Promise(resolve => setTimeout(resolve, 15));
  assert.equal(media.closed, true); assert.equal(h.requests.filter(value => value.action === 'mediaClose').length, 1);
});

test('viewer heartbeat pauses video on transient failure and resumes the same encrypted receiver', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const h = harness(), states = []; let fail = true;
  const media = new WindowMediaPeer({ ...h, onState: state => states.push(state), request: async body => {
    if (body.action === 'peerHeartbeat' && fail) throw Object.assign(new Error('backend unavailable'), { status: 503 });
    return h.request(body);
  } });
  await media.subscribe(() => {}); clearTimeout(media.heartbeat);
  const track = media.track;
  await media.keepAlive(); clearTimeout(media.heartbeat);
  t.mock.timers.tick(9000); assert.equal(media.closed, false); assert.equal(track.enabled, false);
  assert.equal(states.at(-1), 'recovering');
  media.peer.onconnectionstatechange(); assert.equal(states.at(-1), 'recovering', 'Transport readiness cannot override a pending authority check');
  fail = false; await media.keepAlive(); clearTimeout(media.heartbeat);
  assert.equal(media.track, track); assert.equal(track.enabled, true); assert.equal(states.at(-1), 'connected');
  assert.equal(h.requests.filter(request => request.action === 'mediaSubscribe').length, 1);
  assert.equal(media.heartbeatDeadline, null); media.stop();
});

test('viewer heartbeat denial stops immediately and hung recovery cannot revive a stopped peer', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  for (const status of [401, 403, 409, 503]) {
    const h = harness(), media = h.create(); await media.subscribe(() => {}); clearTimeout(media.heartbeat);
    media.request = async body => {
      if (body.action === 'peerHeartbeat') throw Object.assign(new Error('unavailable'), { status });
      return {};
    };
    await media.keepAlive(); clearTimeout(media.heartbeat);
    if (status === 503) {
      let resume;
      media.request = body => body.action === 'peerHeartbeat' ? new Promise(resolve => { resume = resolve; }) : Promise.resolve({});
      const waiting = media.keepAlive(); t.mock.timers.tick(30000);
      assert.equal(media.closed, true); resume({}); await waiting;
      assert.equal(media.track.enabled, false);
    }
    assert.equal(media.closed, true); assert.equal(media.heartbeatDeadline, null);
  }
});

test('native frame canvas is latest-only and releases decoded frames on close', async () => {
  let requests = 0, closes = 0, stopped = 0, resolve;
  const draws = [], geometry = [];
  const track = { requestFrame() { requests++; }, stop() { stopped++; } };
  const canvas = { captureStream: () => ({ getVideoTracks: () => [track], getTracks: () => [track] }), getContext: () => ({ drawImage: img => draws.push(img.width) }) };
  const capture = new NativeFrameCanvas(canvas, value => geometry.push(value), () => new Promise(r => { resolve = r; }));
  assert.equal(track.contentHint, 'motion');
  const f = width => ({ png: 'AA==', width, height: 10, geometryRevision: width });
  const drain = capture.frame(f(10)); capture.frame(f(20)); capture.frame(f(30));
  resolve({ width: 10, height: 10, close() { closes++; } }); await new Promise(r => setImmediate(r));
  resolve({ width: 30, height: 10, close() { closes++; } }); await drain;
  assert.deepEqual(draws, [10, 30]); assert.equal(requests, 2); assert.equal(closes, 2); assert.equal(geometry.at(-1).geometryRevision, 30);
  capture.stop(); assert.equal(stopped, 1); assert.equal(canvas.width, 2);
});

test('default native decoder calls the browser API with its required global receiver', async t => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'createImageBitmap');
  let decoded = 0, requests = 0;
  Object.defineProperty(globalThis, 'createImageBitmap', { configurable: true, value: async function(blob) {
    assert.equal(this, globalThis, 'Window decoder rejects an unrelated receiver');
    assert.ok(blob instanceof Blob); decoded++;
    return { width: 16, height: 16, close() {} };
  } });
  t.after(() => {
    if (previous) Object.defineProperty(globalThis, 'createImageBitmap', previous);
    else delete globalThis.createImageBitmap;
  });
  const track = { requestFrame() { requests++; }, stop() {} };
  const canvas = { captureStream: () => ({ getVideoTracks: () => [track], getTracks: () => [track] }), getContext: () => ({ drawImage() {} }) };
  const capture = new NativeFrameCanvas(canvas);
  t.after(() => capture.stop());
  await capture.frame({ png: 'AA==', width: 16, height: 16, geometryRevision: 1 });
  assert.equal(decoded, 1); assert.equal(requests, 1);
});

test('idle native publisher emits frames before a viewer joins and stops its keepalive on close', t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  let requests = 0, stopped = 0, paints = 0;
  const track = { requestFrame() { requests++; }, stop() { stopped++; } };
  const canvas = {
    captureStream: rate => { assert.equal(rate, 0); return { getVideoTracks: () => [track], getTracks: () => [track] }; },
    getContext: () => ({ drawImage: image => { assert.equal(image, canvas); paints++; } }),
  };
  const capture = new NativeFrameCanvas(canvas, () => {}, () => { throw new Error('Idle keepalive must not decode native frames'); });
  t.mock.timers.tick(40000);
  assert.equal(requests, 8, 'publication must not sit idle past the SFU 30s timeout');
  assert.equal(paints, requests);
  assert.equal(canvas.width, 2, 'keepalive must not initiate native capture');
  capture.stop();
  t.mock.timers.tick(40000);
  assert.equal(requests, 8);
  assert.equal(stopped, 1);
});

test('bridge rejects untrusted RPC origin and cancels all pending native requests', async () => {
  assert.throws(() => createBridge({ rpcUrl: 'https://attacker.example/rpc', rpcToken: 'secret' }, null), /Untrusted/);
  const sent = [];
  const bridge = createBridge({}, { request: (id, json) => sent.push([id, json]) });
  const first = bridge.request({ action: 'mediaCreate' }); bridge.resolve(sent[0][0], '{"peer_id":"ok"}');
  assert.deepEqual(await first, { peer_id: 'ok' });
  const second = bridge.request({ action: 'mediaSubscribe' }); bridge.close(); await assert.rejects(second, /stopped/);
});

test('viewer teardown keeps bridge requests alive until both lease release and media cleanup settle', async () => {
  const h = harness(), held = new Map(); let closed = false, controlStopped = false;
  const bridge = createBridge({}, { request(id, json) {
    const body = JSON.parse(json);
    if (['mediaClose', 'controlRelease'].includes(body.action)) held.set(body.action, id);
    else h.request(body).then(result => bridge.resolve(id, result));
  } });
  const media = new WindowMediaPeer({ ...h, request: body => bridge.request(body) });
  await media.publish({ stop() {} });
  const closing = closeViewerResources({
    media,
    control: { releaseControl: () => bridge.request({ action: 'controlRelease' }), stop: () => { controlStopped = true; } },
    bridge: { close() { closed = true; bridge.close(); } },
  });
  assert.equal(controlStopped, true); assert.equal(media.closed, true);
  const mediaClose = media.stop(); assert.equal(media.stop(), mediaClose);
  assert.equal(held.size, 2); assert.equal(closed, false);
  // A new UI owner may already have its own native bridge by this point.
  const replacementIds = [];
  const replacement = createBridge({}, { request: id => replacementIds.push(id) });
  const replacementRequest = replacement.request({ action: 'mediaCreate' });
  assert.equal([...held.values()].includes(replacementIds[0]), false);
  resolveBridgeRequest(held.get('controlRelease'), {});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(closed, false, 'media cleanup must not be aborted after lease release');
  resolveBridgeRequest(held.get('mediaClose'), {});
  await closing; await mediaClose;
  assert.equal(closed, true);
  await assert.rejects(bridge.request({ action: 'mediaCreate' }), /stopped/);
  resolveBridgeRequest(replacementIds[0], { peer_id: 'replacement' });
  assert.deepEqual(await replacementRequest, { peer_id: 'replacement' }); replacement.close();
});

test('a late control grant survives media teardown only long enough to release its server lease', async () => {
  const h = harness(), requests = []; let grant, bridgeClosed = false;
  const media = new WindowMediaPeer({ ...h, request: body => {
    requests.push(body);
    return body.action === 'controlAcquire' ? new Promise(resolve => { grant = resolve; }) : Promise.resolve({});
  } });
  media.started = true;
  const control = new AppControlChannel(media);
  control.channel = { id: 4, readyState: 'open', close() {} };
  const acquiring = control.takeControl();
  const closing = closeViewerResources({ control, media, bridge: { close() { bridgeClosed = true; } } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(media.closed, true); assert.equal(bridgeClosed, false);
  grant({ lease_id: 'synthetic-late-lease', peer_id: h.config.peerId, expires_at: Date.now() + 30000 });
  await Promise.all([acquiring, closing]);
  assert.equal(control.lease, null); assert.equal(bridgeClosed, true);
  assert.equal(requests.filter(body => body.action === 'controlRelease').length, 1);
  assert.equal(requests.at(-1).lease_id, 'synthetic-late-lease');
  await assert.rejects(media.call('controlAcquire'), /stopped/);
});
