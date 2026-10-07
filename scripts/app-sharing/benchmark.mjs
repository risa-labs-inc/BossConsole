import { RawVideoFrameTrack, acceptEncryptedVideoOffer, attachEncryption, encryptionSupport, vp8Only, waitForIce, waitForConnected, preferSmoothVideo } from './media.mjs';
import { toBase64 } from './crypto.mjs';
import { benchmarkRpc, productionBenchmarkSource } from './benchmark-production.mjs';

let benchmarkStyle = document.querySelector('link[data-benchmark-style]');
if (!benchmarkStyle?.sheet) {
  const styleReady = new Promise((resolve, reject) => {
    benchmarkStyle ??= document.createElement('link');
    benchmarkStyle.rel = 'stylesheet'; benchmarkStyle.href = new URL('./benchmark.css', import.meta.url).href;
    benchmarkStyle.dataset.benchmarkStyle = '';
    benchmarkStyle.addEventListener('load', resolve, { once: true });
    benchmarkStyle.addEventListener('error', () => reject(new Error('Benchmark layout unavailable')), { once: true });
    if (!benchmarkStyle.isConnected) document.head.append(benchmarkStyle);
  });
  await styleReady;
}
if (!document.querySelector('#start')) {
  document.body.innerHTML = '<button id="start">Run</button><button id="stop">Stop</button><p id="status"></p><div id="surfaces"></div><pre id="result"></pre>';
}
import { viewportMetrics, excessiveMarkerFailures, playbackMetrics, resolvedNativeLatency, configureJitterTarget, receiverTimingMetrics, clockEstimate, nativeDeliveryResponse, nativeReadDelay, nativeStageMetrics, presentationObservation, distribution, markerBits, decodeMarker, rgbaToBgra, rgbaToNv12, lumaPsnr } from './benchmark-metrics.mjs';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const saveReport = async (report, production) => {
  if (production) return benchmarkRpc('benchmarkResult', { report });
  const saved = await fetch('./result', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(report) });
  if (!saved.ok) throw new Error('Aggregate report could not be saved');
};
const status = value => { document.querySelector('#status').textContent = value; };
let running = false, cancelled = false;
async function calibrateClock(url, production = false) {
  let best = null;
  for (let i = 0; i < 12; i++) {
    const before = performance.now();
    const response = production ? null : await fetch(url, { cache: 'no-store' });
    const body = production ? await benchmarkRpc('benchmarkClock') : await response.json(), after = performance.now();
    if ((!production && !response.ok) || !Number.isFinite(body.monotonicMs)) throw new Error('Native benchmark clock unavailable');
    const sample = clockEstimate(before, after, body.monotonicMs);
    if (!best || sample.uncertaintyMs < best.uncertaintyMs) best = sample;
  }
  return best;
}
document.querySelector('#stop').onclick = () => { cancelled = true; };
document.querySelector('#start').onclick = () => window.runBenchmark({
  format: document.querySelector('#format')?.value ?? 'BGRA', fps: Number(document.querySelector('#fps')?.value ?? 60), viewers: Number(document.querySelector('#viewers')?.value ?? 1), markerEvery: Number(document.querySelector('#sampling')?.value ?? 1),
});

async function configuration() {
  const pair = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
  return {
    sessionId: crypto.randomUUID(), generation: crypto.randomUUID(), keyEpoch: crypto.randomUUID(), windowId: crypto.randomUUID(),
    mediaRootKey: toBase64(crypto.getRandomValues(new Uint8Array(32))),
    hostPrivateKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey))),
    hostPublicKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey))),
  };
}
function surface(width, height) {
  const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height;
  return canvas;
}
function draw(context, sequence, width, height) {
  context.fillStyle = '#161b22'; context.fillRect(0, 0, width, height);
  context.font = '18px monospace'; context.fillStyle = '#eef1f7';
  const lines = ['BossConsole — synthetic remote sharing benchmark', 'const connected = await share.takeControl();', '0123456789 AaBbCcDd [] {} () <> /? +-= _', 'Text remains static while the lower window scrolls.'];
  lines.forEach((line, index) => context.fillText(line, 16, 100 + index * 28));
  for (let row = 0; row < 12; row++) {
    context.fillStyle = ['#77bdfb', '#b5e48c', '#ffd166'][row % 3];
    context.fillText(`row ${String(row).padStart(2, '0')}  terminal output — 0123456789`, 16, 245 + ((row * 31 + sequence * 4) % (height - 250)));
  }
  context.fillStyle = '#e45566'; context.fillRect((sequence * 7) % (width - 90), height - 65, 90, 45);
  markerBits(sequence).forEach((bit, index) => { context.fillStyle = bit ? '#fff' : '#000'; context.fillRect(16 + index * 12, 16, 12, 32); });
}
function newSamples() { return { generated: 0, preparation: [], upload: [], sourceIntervals: [], transport: { http200: 0, http204: 0, rejected: 0 }, viewers: [] }; }
function viewerSamples() { return { frames: 0, compositorFrames: 0, callbacks: 0, missedCallbacks: 0, invalidPresentationCounters: 0, callbackLateness: [], lateCallbacks: 0, markerAttempts: 0, lateMarkerSkips: 0, latency: [], intervals: [], psnr: [], observerCost: [], invalidMarkers: 0, widths: new Set(), heights: new Set(), lastPresentation: null, firstFreshFrameMs: null, pendingLatency: [], missingNativeTimestamps: 0 }; }
function rtpSnapshot(report, kind) {
  const row = [...report.values()].find(entry => entry.type === kind && (entry.kind ?? entry.mediaType) === 'video' && !entry.isRemote);
  if (!row) return {};
  const fields = kind === 'outbound-rtp'
    ? ['framesEncoded', 'framesSent', 'totalEncodeTime', 'bytesSent', 'qualityLimitationReason']
    : ['framesDecoded', 'framesDropped', 'totalDecodeTime', 'jitterBufferDelay', 'jitterBufferEmittedCount', 'jitterBufferTargetDelay', 'jitterBufferMinimumDelay', 'framesRendered', 'totalInterFrameDelay', 'totalSquaredInterFrameDelay', 'freezeCount', 'totalFreezesDuration', 'bytesReceived'];
  return Object.fromEntries(fields.filter(field => row[field] !== undefined).map(field => [field, row[field]]));
}
function delta(end, start, key) { return Number.isFinite(end[key]) && Number.isFinite(start[key]) ? Math.max(0, end[key] - start[key]) : null; }
function rate(total, count) { return total !== null && count > 0 ? total * 1000 / count : null; }
async function snapshots(peers) {
  return Promise.all(peers.map(async peer => ({
    sender: rtpSnapshot(await peer.sender.getStats(), 'outbound-rtp'),
    receiver: rtpSnapshot(await peer.receiver.getStats(), 'inbound-rtp'),
    playback: peer.video.getVideoPlaybackQuality?.() ?? {},
    layout: viewportMetrics(peer.video.getBoundingClientRect(), innerWidth, innerHeight),
  })));
}

/** Synthetic source -> production raw VideoFrame -> authenticated VP8 -> real RTP -> video presentation. */
window.runBenchmark = async function runBenchmark({ format = 'BGRA', fps = 60, viewers = 1, width = 1280, height = 720, seconds = 10, warmupSeconds = 20, contentHint = 'motion', markerEvery = 1, viewerPlacement = 'browser-only', sourceRenderer = 'browser-canvas', waitForFrame = false, jitterBufferTargetMs = null, nativeSource = null } = {}) {
  if (running) return;
  running = true; cancelled = false; window.benchmarkReport = null;
  const production = nativeSource?.transport === 'production-assets-native-client';
  const abort = new AbortController(), peers = [], cleanup = [], records = new Map(), phases = [];
  let productionSource, nativeWaitResponses = 0, nativePollingResponses = 0;
  let direct, drawing = false, sourceStopped = false, sourceTimer, sequence = 0, phase, lastSource = null, failure = null, hidden = document.hidden;
  const visibility = () => { hidden ||= document.hidden; };
  document.addEventListener('visibilitychange', visibility);
  const fail = () => { failure = 'Authenticated media pipeline failed'; };
  try {
    if (!['BGRA', 'NV12'].includes(format) || ![30, 60].includes(fps) || ![1, 2, 3].includes(viewers) || width < 640 || width > 1920 || height < 360 || height > 1080 || width % 2 || height % 2 || seconds < 2 || seconds > 30 || warmupSeconds < 3 || warmupSeconds > 60 || !['motion', 'detail'].includes(contentHint) || ![0, 1, 5, 10, 30].includes(markerEvery) || !['browser-only', 'embedded-shared-jvm', 'external-browser'].includes(viewerPlacement) || !['browser-canvas', 'swing', 'compose'].includes(sourceRenderer) || ![null, 0, 10].includes(jitterBufferTargetMs) || typeof waitForFrame !== 'boolean' || (waitForFrame && !nativeSource)) throw new Error('Invalid benchmark options');
    if (!encryptionSupport() || !HTMLVideoElement.prototype.requestVideoFrameCallback) throw new Error('Encoded transforms and video presentation callbacks are required');
    const canvas = surface(width, height), context = canvas.getContext('2d', { alpha: false, willReadFrequently: true });
    const expected = surface(width, height), reference = expected.getContext('2d', { willReadFrequently: true });
    draw(reference, 0, width, height);
    const expectedText = reference.getImageData(16, 78, Math.min(width - 32, 700), 118).data;
    document.querySelector('#surfaces').replaceChildren(canvas);
    if (nativeSource && [nativeSource.rawUrl, nativeSource.clockUrl, nativeSource.metricsUrl].filter(Boolean).some(url => new URL(url, location.href).origin !== location.origin)) throw new Error('Native fixture must use the same loopback origin');
    const clock = nativeSource ? await calibrateClock(nativeSource.clockUrl, production) : null;
    if (production) {
      productionSource = productionBenchmarkSource(canvas, format, fps, (id, began, prepared) => {
        const current = phase;
        if (!current || current.sealed || current.pause) return null;
        if (id !== null && !records.has(id)) {
          records.set(id, { time: null, phase: current });
          if (records.size > 2400) records.delete(records.keys().next().value);
        }
        current.samples.preparation.push(prepared - began);
        if (lastSource !== null) current.samples.sourceIntervals.push(prepared - lastSource);
        lastSource = prepared;
        return ended => { current.samples.upload.push(ended - prepared); current.samples.generated++; };
      }, (code, acknowledged) => {
        if (acknowledged) nativeWaitResponses++; else nativePollingResponses++;
        if (phase && !phase.sealed) phase.samples.transport[code === 200 ? 'http200' : code === 204 ? 'http204' : 'rejected']++;
      }, fail, markerEvery);
      direct = productionSource.source.direct;
    } else direct = RawVideoFrameTrack.create();
    if (!direct) throw new Error('Production direct VideoFrame path is unavailable');
    direct.track.contentHint = contentHint;
    if (format === 'NV12' && !direct.supportsFormat?.('NV12')) throw new Error('This production build does not expose NV12 format support');
    const nativeMetrics = async () => {
      if (production) return benchmarkRpc('benchmarkMetrics');
      if (!nativeSource?.metricsUrl) return null;
      const response = await fetch(nativeSource.metricsUrl, { cache: 'no-store' });
      if (!response.ok) throw new Error('Native fixture metrics unavailable');
      return response.json();
    };
    let nativeSequence = -1;
    const config = await configuration();
    status(`Connecting ${viewers} encrypted local peer${viewers > 1 ? 's' : ''}…`);
    for (let index = 0; index < viewers; index++) {
      const sender = new RTCPeerConnection({ iceServers: [], encodedInsertableStreams: true });
      const receiver = new RTCPeerConnection({ iceServers: [], encodedInsertableStreams: true });
      const video = document.createElement('video'); video.autoplay = true; video.muted = true; video.playsInline = true;
      document.querySelector('#surfaces').append(video);
      const sample = surface(Math.min(width - 32, 700), 118), sampleContext = sample.getContext('2d', { willReadFrequently: true });
      const peer = { sender, receiver, video, sampleContext, lastSequence: null, lastPresentedCount: null, encrypted: 0, decrypted: 0, callback: null };
      peers.push(peer);
      const outgoing = sender.addTransceiver('video', { direction: 'sendonly' }); vp8Only(outgoing);
      cleanup.push(await attachEncryption(outgoing.sender, config, 'encrypt', fail, globalThis, value => { peer.encrypted = value.processed ?? 0; }));
      await outgoing.sender.replaceTrack(direct.track);
      await sender.setLocalDescription(await sender.createOffer()); await waitForIce(sender, abort.signal);
      const receive = await acceptEncryptedVideoOffer(receiver, sender.localDescription, outgoing.mid, config, fail, globalThis,
        (endpoint, settings, direction) => attachEncryption(endpoint, settings, direction, fail, globalThis, value => { peer.decrypted = value.processed ?? 0; }));
      cleanup.push(receive.cleanup);
      peer.jitterTarget = configureJitterTarget(receiver.getReceivers().find(endpoint => endpoint.track === receive.track), jitterBufferTargetMs);
      video.srcObject = new MediaStream([receive.track]); video.play().catch(fail);
      await receiver.setLocalDescription(await receiver.createAnswer()); await waitForIce(receiver, abort.signal);
      await sender.setRemoteDescription(receiver.localDescription);
      await preferSmoothVideo(outgoing.sender, fps); peer.endpoint = outgoing.sender;
      await Promise.all([waitForConnected(sender, abort.signal), waitForConnected(receiver, abort.signal)]);
      const presented = (now, metadata) => {
        peer.callback = video.requestVideoFrameCallback(presented);
        const observed = presentationObservation(peer.lastPresentedCount, metadata, now);
        peer.lastPresentedCount = metadata.presentedFrames;
        if (!phase || phase.sealed || metadata.width < 1 || metadata.height < 1) return;
        const observedAt = performance.now(), entry = phase.samples.viewers[index];
        entry.callbacks++; entry.compositorFrames += observed.frames; entry.missedCallbacks += observed.missedCallbacks;
        if (!observed.valid) entry.invalidPresentationCounters++;
        if (observed.latenessMs !== null) {
          entry.callbackLateness.push(observed.latenessMs);
          if (observed.latenessMs > 0.5) entry.lateCallbacks++;
        }
        entry.widths.add(metadata.width); entry.heights.add(metadata.height);
        try {
          // Readback can perturb the renderer. Sparse and counter-only modes measure that cost.
          if (!markerEvery || entry.callbacks % markerEvery !== 0) return;
          // A callback more than one frame late may no longer sample its associated video pixels.
          if (!observed.valid || observed.latenessMs > 1000 / fps) { entry.lateMarkerSkips++; return; }
          entry.markerAttempts++;
          const sx = video.videoWidth / width, sy = video.videoHeight / height;
          sampleContext.drawImage(video, 16 * sx, 32 * sy, 480 * sx, sy, 0, 0, 480, 1);
          const marker = sampleContext.getImageData(0, 0, 480, 1).data;
          const bits = Array.from({ length: 40 }, (_, i) => marker[(i * 12 + 6) * 4] > 128 ? 1 : 0);
          const id = decodeMarker(bits), record = id === null ? null : records.get(id);
          if (!record) { entry.invalidMarkers++; return; }
          if (production && record.phase !== phase) return;
          if (id === peer.lastSequence) return;
          peer.lastSequence = id;
          // Marker latency is a sampled observation, separate from compositor frame counters.
          const target = record.phase.samples.viewers[index], presentation = metadata.expectedDisplayTime;
          if (!Number.isFinite(presentation) || (!production && presentation < record.time)) { entry.invalidMarkers++; return; }
          target.frames++;
          if (production) {
            if (phase.name !== 'warmup' && target.pendingLatency.length < 2400) target.pendingLatency.push({ id, presentation });
          } else target.latency.push(presentation - record.time);
          if (target.lastPresentation !== null) target.intervals.push(presentation - target.lastPresentation);
          target.lastPresentation = presentation;
          if (!production) target.firstFreshFrameMs ??= Math.max(0, presentation - record.phase.start);
          if (!nativeSource && target.frames % Math.max(1, Math.ceil(30 / markerEvery)) === 0) {
            const textWidth = Math.min(width - 32, 700);
            sampleContext.drawImage(video, 16 * sx, 78 * sy, textWidth * sx, 118 * sy, 0, 0, textWidth, 118);
            target.psnr.push(lumaPsnr(sampleContext.getImageData(0, 0, textWidth, 118).data, expectedText));
          }
        } finally { entry.observerCost.push(performance.now() - observedAt); }
      };
      peer.callback = video.requestVideoFrameCallback(presented);
    }
    const generate = async () => {
      if (sourceStopped || cancelled || abort.signal.aborted || !phase) return;
      const start = performance.now();
      let acknowledgedWait = false;
      if (!phase.pause && !drawing) {
        drawing = true;
        try {
          const current = phase, began = performance.now();
          let id, paintedAt = began, bytes, frameWidth = width, frameHeight = height, frameFormat = format;
          if (nativeSource) {
            const headers = { 'X-Benchmark-After': String(nativeSequence) };
            if (waitForFrame) headers['X-Benchmark-Wait'] = 'true';
            const response = await fetch(nativeSource.rawUrl, { method: 'POST', headers, signal: abort.signal });
            const delivery = nativeDeliveryResponse({ status: response.status, sequence: response.headers.get('X-Benchmark-Sequence'), waitAccepted: response.headers.get('X-Benchmark-Wait-Accepted') }, nativeSequence, waitForFrame);
            nativeSequence = delivery.sequence; acknowledgedWait = delivery.acknowledgedWait;
            if (acknowledgedWait) nativeWaitResponses++; else nativePollingResponses++;
            if (response.status === 204) return;
            id = Number(response.headers.get('X-Benchmark-Frame'));
            paintedAt = Number(response.headers.get('X-Benchmark-Painted-At')) + clock.offsetMs;
            frameWidth = Number(response.headers.get('X-Boss-App-Width')); frameHeight = Number(response.headers.get('X-Boss-App-Height'));
            frameFormat = response.headers.get('X-Boss-App-Pixel-Format');
            bytes = new Uint8Array(await response.arrayBuffer());
            if (!Number.isInteger(id) || id < 1 || !Number.isFinite(paintedAt) || !['BGRA', 'NV12'].includes(frameFormat) || frameWidth * frameHeight > 4194304 || bytes.length !== frameWidth * frameHeight * (frameFormat === 'NV12' ? 1.5 : 4)) throw new Error('Invalid native benchmark frame');
            // Capture can repeat an unchanged source render; do not count it twice.
            if (records.has(id) || paintedAt < current.start) return;
          } else {
            id = ++sequence; draw(context, id, width, height);
            const rgba = context.getImageData(0, 0, width, height).data;
            bytes = format === 'NV12' ? rgbaToNv12(rgba, width, height) : rgbaToBgra(rgba);
          }
          const prepared = performance.now();
          current.samples.preparation.push(prepared - began);
          records.set(id, { time: paintedAt, phase: current });
          if (records.size > 600) records.delete(records.keys().next().value);
          await direct.paint(bytes, frameWidth, frameHeight, frameFormat);
          current.samples.upload.push(performance.now() - prepared); current.samples.generated++;
          if (lastSource !== null) current.samples.sourceIntervals.push(began - lastSource);
          lastSource = began;
        } catch { failure = 'Source frame production failed'; }
        finally {
          drawing = false;
          if (!sourceStopped && !abort.signal.aborted && !cancelled) sourceTimer = setTimeout(generate, nativeReadDelay(fps, performance.now() - start, acknowledgedWait));
        }
      } else if (!sourceStopped && !abort.signal.aborted && !cancelled) {
        sourceTimer = setTimeout(generate, 1000 / fps);
      }
    };
    const plan = [
      { name: 'warmup', duration: warmupSeconds * 1000, bitrate: 8_000_000 },
      { name: 'steady', duration: seconds * 1000, bitrate: 8_000_000 },
      { name: 'sender-bitrate-cap', duration: seconds * 1000, bitrate: 300_000 },
      { name: nativeSource ? 'reader-pause' : 'source-pause', duration: 1000, bitrate: 8_000_000, pause: true },
      { name: 'recovery', duration: seconds * 1000, bitrate: 8_000_000 },
    ];
    for (const scenario of plan) {
      for (const peer of peers) {
        const parameters = peer.endpoint.getParameters();
        for (const encoding of parameters.encodings) encoding.maxBitrate = scenario.bitrate;
        await peer.endpoint.setParameters(parameters);
      }
      const before = await snapshots(peers), samples = newSamples(); samples.viewers = peers.map(viewerSamples);
      const nativeBefore = await nativeMetrics();
      phase = { ...scenario, start: performance.now(), samples, before, nativeBefore }; phases.push(phase);
      lastSource = null; status(`${format} ${width}×${height} @ ${fps} FPS — ${scenario.name}`);
      if (production) productionSource.setPaused(!!scenario.pause);
      else if (!sourceTimer) generate();
      while (performance.now() - phase.start < scenario.duration) {
        if (cancelled) throw new Error('Benchmark cancelled');
        if (failure) throw new Error(failure);
        await sleep(50);
      }
      phase.elapsed = performance.now() - phase.start; phase.after = await snapshots(peers);
      phase.nativeAfter = await nativeMetrics();
      if (production) {
        phase.sealed = true;
        const ids = [...new Set(phase.samples.viewers.flatMap(value => value.pendingLatency.map(sample => sample.id)))];
        const timestamps = ids.length ? await benchmarkRpc('benchmarkTimestamps', { ids }) : {};
        for (const value of phase.samples.viewers) {
          const resolved = resolvedNativeLatency(value.pendingLatency.splice(0), timestamps, clock.offsetMs, phase.start);
          value.latency.push(...resolved.latency); value.missingNativeTimestamps += resolved.missing;
          value.firstFreshFrameMs = resolved.firstFreshFrameMs;
        }
      }
    }
    sourceStopped = true; productionSource?.stop(); clearTimeout(sourceTimer); sourceTimer = null;
    while (drawing) await sleep(5);
    await sleep(500); // Allow final in-flight frames to present before aggregating.
    const invalidReasons = [];
    if (hidden) invalidReasons.push('Page was hidden; browser throttling invalidates comparison');
    if (peers.some(peer => peer.encrypted < 1 || peer.decrypted < 1)) invalidReasons.push('Authenticated transform did not report processed frames');
    const measured = phases.filter(item => item.name !== 'warmup');
    if (measured.some(item => !item.pause && item.samples.viewers.some(value => value.compositorFrames < 10))) invalidReasons.push('Too few authenticated compositor frames');
    if (measured.some(item => item.samples.viewers.some(value => value.invalidPresentationCounters > 0))) invalidReasons.push('Invalid or reset compositor presentation counters');
    if (markerEvery && measured.some(item => !item.pause && item.samples.viewers.some(value => value.latency.length < 1))) invalidReasons.push('No valid frame marker latency observations');
    if (excessiveMarkerFailures(measured)) invalidReasons.push('Over 5% frame marker reads failed');
    if (measured.some(item => item.samples.viewers.some(value => value.missingNativeTimestamps > 0))) invalidReasons.push('Native timestamp lookup was incomplete');
    if (viewers === 1 && measured.some(item => !item.pause && [item.before[0], item.after[0]].some(value => value.layout.intersectionFraction < 0.95))) invalidReasons.push('Single receiver was not fully inside the viewport');
    const report = {
      schema: 'boss-sharing-benchmark/2', valid: invalidReasons.length === 0, invalidReasons,
      environment: { platform: navigator.userAgentData?.platform ?? navigator.platform, browser: navigator.userAgent.match(/(?:Chrome|Chromium|Firefox)\/[\d.]+/)?.[0] ?? 'unknown', hardwareConcurrency: navigator.hardwareConcurrency, transform: globalThis.RTCRtpScriptTransform ? 'worker' : 'legacy' },
      scenario: { layout: 'shared-css-hidden-source-v1', format, width, height, fps, contentHint, markerEvery, viewerPlacement, sourceRenderer, transport: production ? 'production-assets-native-client' : nativeSource ? 'fixture-http' : 'browser-canvas', jitterBufferTargetMs, receiverJitterTargets: peers.map(peer => peer.jitterTarget), requestedFrameDelivery: waitForFrame ? 'wait' : 'polling', frameDelivery: nativeWaitResponses ? (nativePollingResponses ? 'mixed' : 'wait') : 'polling', nativeWaitResponses, nativePollingResponses, warmupSeconds, localPeerCount: viewers, rawBytesPerFrame: nativeSource ? null : width * height * (format === 'NV12' ? 1.5 : 4), nativeClockUncertaintyMs: clock?.uncertaintyMs ?? null },
      coverage: { nativeCapture: !!nativeSource, nativeHttpBridge: production, productionNativeRawFrameCanvas: production, fixtureHttpBridge: !!nativeSource && !production, productionRawVideoFrame: true, productionAuthenticatedVp8: true, actualWebRtc: true, actualVideoPresentation: true, cloudflareSfu: false, networkImpairment: false, fanout: 'independent-local-publishers-not-SFU', latency: nativeSource ? 'native-owned-window-paint-to-requestVideoFrameCallback-with-clock-uncertainty' : 'synthetic-draw-start-to-requestVideoFrameCallback-expectedDisplayTime', quality: nativeSource ? 'not-measured-native-renderer-differs' : 'static-text-region-luma-PSNR-not-OCR', preparation: production ? 'production-client-HTTP-wait-fetch-and-copy' : nativeSource ? 'native-fixture-HTTP-fetch-and-copy' : 'canvas-readback-plus-JavaScript-format-conversion-not-native-capture' },
      phases: measured.map(item => ({
        name: item.name, elapsedMs: item.elapsed, senderBitrateLimit: item.bitrate, clientTransport: production ? item.samples.transport : null, nativeStages: nativeStageMetrics(item.nativeBefore, item.nativeAfter), generatedFrames: item.samples.generated,
        sourceFps: item.samples.generated * 1000 / item.elapsed, preparationMs: distribution(item.samples.preparation), rawUploadMs: distribution(item.samples.upload), sourceFrameIntervalMs: distribution(item.samples.sourceIntervals),
        viewers: item.samples.viewers.map((value, index) => {
          const start = item.before[index], end = item.after[index];
          const encoded = delta(end.sender, start.sender, 'framesEncoded'), decoded = delta(end.receiver, start.receiver, 'framesDecoded');
          return { presentedFrames: value.compositorFrames, presentedFps: value.compositorFrames * 1000 / item.elapsed,
            observedMarkerFrames: value.frames, markerObservationFps: value.frames * 1000 / item.elapsed,
            callbackCount: value.callbacks, missedCallbacks: value.missedCallbacks, lateCallbacks: value.lateCallbacks,
            callbackLatenessMs: distribution(value.callbackLateness), markerAttempts: value.markerAttempts, lateMarkerSkips: value.lateMarkerSkips,
            invalidMarkers: value.invalidMarkers, missingNativeTimestamps: value.missingNativeTimestamps,
            latencyMs: distribution(value.latency), observerCostMs: distribution(value.observerCost), markerObservationIntervalMs: distribution(value.intervals), textLumaPsnrDb: distribution(value.psnr),
            firstFreshFrameMs: value.firstFreshFrameMs, decodedWidths: [...value.widths], decodedHeights: [...value.heights],
            averageEncodeMs: rate(delta(end.sender, start.sender, 'totalEncodeTime'), encoded),
            averageDecodeMs: rate(delta(end.receiver, start.receiver, 'totalDecodeTime'), decoded),
            ...receiverTimingMetrics(start.receiver, end.receiver),
            ...playbackMetrics(start.playback, end.playback),
            viewportBefore: start.layout, viewportAfter: end.layout,
            framesEncoded: encoded, framesDecoded: decoded, receiverDroppedFrames: delta(end.receiver, start.receiver, 'framesDropped'),
            sendKbps: delta(end.sender, start.sender, 'bytesSent') * 8 / item.elapsed,
            qualityLimitationReason: end.sender.qualityLimitationReason ?? null };
        }),
      })),
    };
    window.benchmarkReport = report;
    document.querySelector('#result').textContent = JSON.stringify(report, null, 2);
    await saveReport(report, production);
    status(report.valid ? 'Complete — aggregate report saved' : 'Complete — invalid run; inspect reasons');
    return report;
  } catch (error) {
    status(error.message); window.benchmarkError = error.message;
    if (nativeSource) {
      // Give the native fixture a terminal result instead of waiting for its timeout.
      // Only a fixed failure category crosses into persisted reports, not arbitrary exception text.
      const failed = { schema: 'boss-sharing-benchmark/2', valid: false, invalidReasons: ['Native benchmark failed or was cancelled; inspect fixture status'], environment: {}, scenario: { format, fps, viewerPlacement, sourceRenderer }, phases: [], coverage: { nativeCapture: true } };
      await saveReport(failed, production).catch(() => {});
    }
    return null;
  } finally {
    sourceStopped = true; cancelled = true; productionSource?.stop(); clearTimeout(sourceTimer); abort.abort();
    peers.forEach(peer => { if (peer.callback !== null) peer.video.cancelVideoFrameCallback(peer.callback); peer.sender.close(); peer.receiver.close(); peer.video.srcObject = null; });
    cleanup.forEach(close => close()); direct?.stop();
    document.removeEventListener('visibilitychange', visibility); running = false;
  }
};
