import { RawFramePainter, RawVideoFrameTrack, acceptEncryptedVideoOffer, attachEncryption, encryptionSupport, vp8Only, waitForIce, waitForConnected } from './media.mjs';
import { createMediaCipher, toBase64 } from './crypto.mjs';
import { MediaStatsSampler, validMediaMetrics } from './stats.mjs';

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function configuration() {
  const pair = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
  return {
    sessionId: crypto.randomUUID(), generation: crypto.randomUUID(), keyEpoch: crypto.randomUUID(), windowId: crypto.randomUUID(),
    mediaRootKey: toBase64(crypto.getRandomValues(new Uint8Array(32))),
    hostPrivateKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey))),
    hostPublicKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey))),
  };
}
async function attachAudit(endpoint, config, direction, report) {
  if (globalThis.RTCRtpScriptTransform) {
    const worker = new Worker(new URL('./smoke-worker.mjs', import.meta.url), { type: 'module' });
    worker.onmessage = ({ data }) => Object.assign(report, data);
    worker.onerror = () => { report.error = 'Audit transform worker failed'; };
    endpoint.transform = new RTCRtpScriptTransform(worker, { config, direction });
    return () => worker.terminate();
  }
  const cipher = await createMediaCipher(config, direction);
  const { readable, writable } = endpoint.createEncodedStreams();
  const abort = new AbortController(); let audited = false;
  readable.pipeThrough(new TransformStream({ async transform(frame, controller) {
    if (direction === 'encrypt') {
      const before = frame.data.byteLength;
      frame.data = await cipher.encrypt(frame.data);
      if (!audited) { report.encrypted = frame.data.byteLength === before + 92; audited = true; }
    } else if (!audited) {
      const encrypted = frame.data.slice(0), tampered = new Uint8Array(encrypted.slice(0)); tampered[tampered.length - 1] ^= 1;
      try { await cipher.decrypt(tampered); } catch (_) { report.tamperRejected = true; }
      frame.data = await cipher.decrypt(encrypted); report.decrypted = true;
      try { await cipher.decrypt(encrypted); } catch (_) { report.replayRejected = true; }
      if (!report.tamperRejected || !report.replayRejected) throw new Error('Encoded frame authentication/replay guard failed');
      audited = true;
    } else frame.data = await cipher.decrypt(frame.data);
    controller.enqueue(frame);
  } })).pipeTo(writable, { signal: abort.signal }).catch(error => { if (!abort.signal.aborted) report.error = String(error.message); });
  return () => abort.abort();
}
function matches(pixel, expected) { return expected.every((value, index) => Math.abs(pixel[index] - value) <= 35); }
async function diagnostics(sender, receiver, track, video, report, frameCount) {
  const fields = ['type', 'kind', 'mimeType', 'framesEncoded', 'framesSent', 'framesReceived', 'framesDecoded', 'packetsSent', 'packetsReceived', 'bytesSent', 'bytesReceived', 'framesDropped', 'keyFramesEncoded', 'keyFramesDecoded'];
  const summarize = async peer => Array.from((await peer.getStats()).values()).filter(value => ['inbound-rtp', 'outbound-rtp', 'codec'].includes(value.type)).map(value => Object.fromEntries(fields.filter(field => value[field] !== undefined).map(field => [field, value[field]])));
  return { sender: await summarize(sender), receiver: await summarize(receiver), capture: { readyState: track.readyState, muted: track.muted, settings: track.getSettings(), frameCount }, playback: { readyState: video.readyState, paused: video.paused, width: video.videoWidth, height: video.videoHeight, tracks: video.srcObject?.getTracks().map(value => ({ kind: value.kind, readyState: value.readyState, muted: value.muted })) }, transforms: report };
}
async function loopback(audit, raw = false, directMode = false, pixelFormat = 'BGRA') {
  if (!encryptionSupport()) throw new Error('This JxBrowser lacks encoded media transforms');
  const config = await configuration();
  const sender = new RTCPeerConnection({ iceServers: [], encodedInsertableStreams: true });
  const receiver = new RTCPeerConnection({ iceServers: [], encodedInsertableStreams: true });
  const canvas = document.createElement('canvas'), video = document.createElement('video'), sample = document.createElement('canvas');
  canvas.width = sample.width = 320; canvas.height = sample.height = 160;
  video.autoplay = true; video.muted = true; video.playsInline = true;
  document.body.append(canvas, video, sample);
  // The production publisher canvas is hidden and does not preserve its WebGL drawing buffer.
  canvas.hidden = raw;
  const direct = directMode ? RawVideoFrameTrack.create() : null;
  if (directMode && !direct) throw new Error('Direct video frames unavailable in production Chromium');
  const painter = raw ? (direct ?? new RawFramePainter(canvas)) : null;
  const context = raw ? null : canvas.getContext('2d', { alpha: false });
  const rawBytes = raw ? new Uint8Array(320 * 160 * 4) : null;
  if (raw) {
    for (let y = 0; y < 160; y++) for (let x = 0; x < 320; x++) {
      const rgb = y < 80 ? (x < 160 ? [230, 40, 48] : [32, 204, 80]) : (x < 160 ? [35, 70, 220] : [220, 200, 30]);
      rawBytes.set([rgb[2], rgb[1], rgb[0], 255], (y * 320 + x) * 4);
    }
  }
  let nv12;
  if (pixelFormat === 'NV12') {
    if (!direct?.supportsFormat('NV12')) throw new Error('Native NV12 VideoFrame input unavailable');
    nv12 = new Uint8Array(320 * 160 * 3 / 2);
    const clamp = value => Math.max(0, Math.min(255, Math.round(value)));
    for (let y = 0; y < 160; y++) for (let x = 0; x < 320; x++) {
      const at = (y * 320 + x) * 4, b = rawBytes[at], g = rawBytes[at + 1], r = rawBytes[at + 2];
      nv12[y * 320 + x] = clamp(16 + 0.182586 * r + 0.614231 * g + 0.062007 * b);
      if (!(x % 2) && !(y % 2)) {
        const uv = 320 * 160 + y / 2 * 320 + x;
        nv12[uv] = clamp(128 - 0.100644 * r - 0.338572 * g + 0.439216 * b);
        nv12[uv + 1] = clamp(128 + 0.439216 * r - 0.398942 * g - 0.040274 * b);
      }
    }
  }
  let frameCount = 0;
  async function draw() {
    if (raw) {
      rawBytes.fill((++frameCount & 1) ? 255 : 0, 0, 3);
      if (nv12) nv12[0] = (frameCount & 1) ? 235 : 16;
      await painter.paint(nv12 ?? (direct || painter.gl ? rawBytes.buffer : rawBytes.buffer.slice(0)), 320, 160, pixelFormat);
      return;
    }
    context.fillStyle = '#e62830'; context.fillRect(0, 0, 160, 160);
    context.fillStyle = '#20cc50'; context.fillRect(160, 0, 160, 160);
    // Make every frame different without affecting the center probes.
    context.fillStyle = (++frameCount & 1) ? '#fff' : '#000'; context.fillRect(0, 0, 8, 8);
  }
  await draw();
  const stream = direct?.stream ?? canvas.captureStream(0), track = direct?.track ?? stream.getVideoTracks()[0];
  if (!direct && !track?.requestFrame) throw new Error('Canvas capture requestFrame is unavailable');
  const report = {}, cleanup = [], abort = new AbortController();
  let interval;
  try {
    const outgoing = sender.addTransceiver('video', { direction: 'sendonly' });
    vp8Only(outgoing);
    const failure = error => { report.error = String(error.message ?? error).slice(0, 240); };
    cleanup.push(audit ? await attachAudit(outgoing.sender, config, 'encrypt', report) : await attachEncryption(outgoing.sender, config, 'encrypt', failure, globalThis, value => { report.encrypt = { ...report.encrypt, ...value }; }));
    await outgoing.sender.replaceTrack(track);
    await sender.setLocalDescription(await sender.createOffer());
    await waitForIce(sender, abort.signal);
    const decrypt = (endpoint, options, direction) => audit ? attachAudit(endpoint, options, direction, report) : attachEncryption(endpoint, options, direction, failure, globalThis, value => { report.decrypt = { ...report.decrypt, ...value }; });
    const received = await acceptEncryptedVideoOffer(receiver, sender.localDescription, outgoing.mid, config, failure, globalThis, decrypt);
    cleanup.push(received.cleanup);
    video.srcObject = new MediaStream([received.track]); video.play().catch(failure);
    await receiver.setLocalDescription(await receiver.createAnswer());
    await waitForIce(receiver, abort.signal);
    await sender.setRemoteDescription(receiver.localDescription);
    let drawing = false;
    interval = setInterval(async () => {
      if (drawing) return;
      drawing = true;
      try { await draw(); track.requestFrame?.(); } catch (error) { failure(error); }
      finally { drawing = false; }
    }, 60);
    await Promise.all([waitForConnected(sender, abort.signal), waitForConnected(receiver, abort.signal)]);
    const sampling = sample.getContext('2d', { willReadFrequently: true });
    const deadline = performance.now() + 15000;
    let red, green, blue, yellow, decoded = false;
    while (performance.now() < deadline) {
      if (report.error) throw new Error(report.error);
      if (video.readyState >= 2 && video.videoWidth === 320 && video.videoHeight === 160) {
        sampling.drawImage(video, 0, 0, 320, 160);
        red = Array.from(sampling.getImageData(80, 40, 1, 1).data);
        green = Array.from(sampling.getImageData(240, 40, 1, 1).data);
        blue = Array.from(sampling.getImageData(80, 120, 1, 1).data);
        yellow = Array.from(sampling.getImageData(240, 120, 1, 1).data);
        if (matches(red, [230, 40, 48]) && matches(green, [32, 204, 80]) &&
            (!raw || (matches(blue, [35, 70, 220]) && matches(yellow, [220, 200, 30])))) { decoded = true; break; }
      }
      await delay(50);
    }
    if (!decoded) throw new Error(`Decoded synthetic pixels unavailable: ${JSON.stringify(await diagnostics(sender, receiver, track, video, report, frameCount))}`);
    if (audit && !(report.encrypted && report.decrypted && report.tamperRejected && report.replayRejected)) throw new Error('Audit did not observe authenticated encrypted frames');
    if (!raw) {
      // Exercise real stats on both encrypted peers. The raw/direct cases below
      // retain their immediate first-frame retirement timing regression check.
      const hostStats = new MediaStatsSampler(true), clientStats = new MediaStatsSampler();
      hostStats.sample(await sender.getStats()); clientStats.sample(await receiver.getStats());
      await delay(1100);
      const remoteMetrics = hostStats.sample(await sender.getStats()), clientMetrics = clientStats.sample(await receiver.getStats());
      if (![remoteMetrics, clientMetrics].every(metrics => validMediaMetrics(metrics) && metrics.fps > 0 && metrics.bitrateKbps > 0 && metrics.rttMs !== null)) throw new Error('Live WebRTC performance measurements unavailable');
      report.remoteMetrics = remoteMetrics; report.clientMetrics = clientMetrics;
    }
    if (raw) {
      if (rawBytes[(40 * 320 + 80) * 4] !== 48) throw new Error('Raw input pixels were mutated');
      clearInterval(interval); interval = null;
      while (drawing) await delay(5);
      // Verify retirement through actual encoded/decoded video, including a hidden WebGL canvas.
      await painter.clear(); track.requestFrame?.();
      const clearDeadline = performance.now() + 5000;
      let cleared = false;
      while (performance.now() < clearDeadline) {
        if (video.videoWidth === 2 && video.videoHeight === 2) {
          sampling.drawImage(video, 0, 0, 2, 2);
          if (matches(Array.from(sampling.getImageData(0, 0, 1, 1).data), [0, 0, 0])) { cleared = true; break; }
        }
        await delay(50);
      }
      if (!cleared) throw new Error(`Retired ${direct ? 'direct' : 'raw'} native pixels remained in the publication`);
      report.cleared = true;
    }
    const stats = await receiver.getStats(); let framesDecoded = 0;
    for (const entry of stats.values()) if (entry.type === 'inbound-rtp' && entry.kind === 'video') framesDecoded += entry.framesDecoded ?? 0;
    if (framesDecoded < 1) throw new Error('No actual RTP video frames decoded');
    return { mode: direct ? 'direct' : raw ? 'raw' : audit ? 'audited' : 'production', framesDecoded, red, green,
      ...(raw ? { blue, yellow, renderer: direct ? 'videoframe' : painter.gl ? 'webgl' : 'cpu' } : {}), ...report };
  } finally {
    clearInterval(interval); abort.abort(); sender.close(); receiver.close();
    cleanup.forEach(close => close()); stream.getTracks().forEach(item => item.stop());
    painter?.stop();
    video.srcObject = null; canvas.remove(); video.remove(); sample.remove();
  }
}
try {
  const production = await loopback(false), audited = await loopback(true), raw = await loopback(false, true), direct = await loopback(false, true, true), nv12 = await loopback(false, true, true, 'NV12');
  await fetch('./result', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ passed: true, transform: globalThis.RTCRtpScriptTransform ? 'script' : 'legacy', production, audited, raw, direct, nv12 }) });
} catch (error) {
  await fetch('./result', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ passed: false, error: String(error.message ?? error).slice(0, 7000) }) });
}
