import test from 'node:test';
import assert from 'node:assert/strict';
import { captureDiagnosticMetrics, viewportMetrics, excessiveMarkerFailures, playbackMetrics, rawFrameMarker, resolvedNativeLatency, configureJitterTarget, receiverTimingMetrics, clockEstimate, nativeDeliveryResponse, nativeReadDelay, nativeStageMetrics, presentationObservation, distribution, markerBits, decodeMarker, rgbaToBgra, rgbaToNv12, lumaPsnr } from './benchmark-metrics.mjs';

test('jitter target experiments feature-test and preserve the default without writes', () => {
  const unsupported = {};
  assert.deepEqual(configureJitterTarget(unsupported, 0), { requestedMs: 0, supported: false, readbackMs: null, status: 'unsupported' });
  assert.equal(Object.hasOwn(unsupported, 'jitterBufferTarget'), false);
  let writes = 0, value = null;
  const endpoint = { get jitterBufferTarget() { return value; }, set jitterBufferTarget(next) { writes++; value = next; } };
  assert.equal(configureJitterTarget(endpoint).status, 'default');
  assert.equal(writes, 0);
  assert.deepEqual(configureJitterTarget(endpoint, 0), { requestedMs: 0, supported: true, readbackMs: 0, status: 'applied' });
  assert.equal(configureJitterTarget(endpoint, 10).readbackMs, 10);
  assert.throws(() => configureJitterTarget(endpoint, 20));
  assert.throws(() => configureJitterTarget(endpoint, '0'));
});
test('jitter target rejection and ignored preferences cannot masquerade as applied', () => {
  const rejected = { get jitterBufferTarget() { return null; }, set jitterBufferTarget(_) { throw new Error('unsupported setter'); } };
  const ignored = { get jitterBufferTarget() { return null; }, set jitterBufferTarget(_) {} };
  assert.equal(configureJitterTarget(rejected, 0).status, 'rejected');
  assert.equal(configureJitterTarget(ignored, 10).status, 'mismatch');
});
test('receiver buffering and cadence use emitted and rendered deltas, not decode counts', () => {
  const before = { jitterBufferEmittedCount: 100, jitterBufferDelay: 1, jitterBufferTargetDelay: 2, jitterBufferMinimumDelay: 0.5, framesRendered: 20, totalInterFrameDelay: 1, totalSquaredInterFrameDelay: 0.1, freezeCount: 2, totalFreezesDuration: 1 };
  const after = { jitterBufferEmittedCount: 110, jitterBufferDelay: 1.13, jitterBufferTargetDelay: 2.1, jitterBufferMinimumDelay: 0.52, framesRendered: 22, totalInterFrameDelay: 1.06, totalSquaredInterFrameDelay: 0.102, framesDecoded: 900, freezeCount: 3, totalFreezesDuration: 1.25 };
  const metrics = receiverTimingMetrics(before, after);
  assert.ok(Math.abs(metrics.averageJitterBufferMs - 13) < 1e-9);
  assert.ok(Math.abs(metrics.averageJitterBufferTargetMs - 10) < 1e-9);
  assert.ok(Math.abs(metrics.averageJitterBufferMinimumMs - 2) < 1e-9);
  assert.ok(Math.abs(metrics.renderedFrameIntervalMeanMs - 30) < 1e-9);
  assert.ok(Math.abs(metrics.renderedFrameIntervalStdDevMs - 10) < 1e-9);
  assert.equal(metrics.receiverFreezes, 1); assert.equal(metrics.receiverFreezeDurationMs, 250);
  assert.ok(Object.values(receiverTimingMetrics({}, {})).every(value => value === null));
  assert.equal(receiverTimingMetrics(after, before).averageJitterBufferMs, null);
  assert.equal(receiverTimingMetrics(before, before).renderedFrameIntervalMeanMs, null);
});

test('latency percentiles use observed values and empty samples remain unavailable', () => {
  assert.deepEqual(distribution([]), { samples: 0, p50: null, p95: null, max: null });
  assert.deepEqual(distribution([90, 1, 2, NaN, 3]), { samples: 4, p50: 2, p95: 90, max: 90 });
});
test('native clock mapping retains the full round-trip uncertainty interval', () => {
  const estimate = clockEstimate(100, 104, 1000);
  assert.deepEqual(estimate, { offsetMs: -898, uncertaintyMs: 2 });
  assert.equal(1000 + estimate.offsetMs - estimate.uncertaintyMs, 100);
  assert.equal(1000 + estimate.offsetMs + estimate.uncertaintyMs, 104);
  assert.throws(() => clockEstimate(104, 100, 1000));
  assert.throws(() => clockEstimate(NaN, 104, 1000));
});
test('compositor frame deltas retain presentations missed by JavaScript callbacks', () => {
  assert.deepEqual(presentationObservation(100, { presentedFrames: 103, expectedDisplayTime: 500 }, 495),
    { valid: true, frames: 3, missedCallbacks: 2, latenessMs: 0 });
  assert.deepEqual(presentationObservation(103, { presentedFrames: 104, expectedDisplayTime: 517 }, 530),
    { valid: true, frames: 1, missedCallbacks: 0, latenessMs: 13 });
});
test('compositor counter resets and invalid metadata cannot invent frames', () => {
  for (const metadata of [{ presentedFrames: 3, expectedDisplayTime: 100 }, { presentedFrames: NaN, expectedDisplayTime: 100 }, { presentedFrames: 20, expectedDisplayTime: NaN }]) {
    const observation = presentationObservation(10, metadata, 100);
    assert.equal(observation.valid, false); assert.equal(observation.frames, 0);
  }
  assert.deepEqual(presentationObservation(null, { presentedFrames: 10, expectedDisplayTime: 100 }, 100),
    { valid: true, frames: 1, missedCallbacks: 0, latenessMs: 0 });
});
test('native stage rates use native interval deltas, and GC times are independent aggregates', () => {
  const before = { monotonicMs: 1000, paints: 100, captures: 90, uniqueCapturedPaints: 85, mailboxReads: 100, http200: 80, http204: 20, capturePaintAgeMicros: 600000, captureAgeSamples: 90, httpPaintAgeMicros: 800000, gcAvailable: true, gcCollections: 2, gcCollectionTimeMs: 10, heapUsedBytes: 100, heapMaxBytes: 512000000 };
  const after = { ...before, monotonicMs: 2000, paints: 160, captures: 150, uniqueCapturedPaints: 140, mailboxReads: 158, http200: 124, http204: 34, capturePaintAgeMicros: 1200000, captureAgeSamples: 150, httpPaintAgeMicros: 1680000, gcCollections: 7, gcCollectionTimeMs: 40, heapUsedBytes: 200, secret: 'must-not-escape' };
  const result = nativeStageMetrics(before, after);
  assert.equal(result.paintFps, 60); assert.equal(result.captureDeliveryFps, 60);
  assert.equal(result.uniqueCapturedPaintFps, 55); assert.equal(result.httpFramesPerSecond, 44);
  assert.equal(result.counters.http204, 14); assert.equal(result.capturePaintAgeMeanMs, 10);
  assert.equal(result.httpPaintAgeMeanMs, 20); assert.equal(result.gcCollections, 5);
  assert.equal(result.gcCollectionTimeMs, 30); assert.equal(result.heapUsedBeforeBytes, 100);
  assert.equal(JSON.stringify(result).includes('must-not-escape'), false);
});
test('missing native counters remain unknown and reset counters never produce negative rates', () => {
  assert.equal(nativeStageMetrics(null, null), null);
  const result = nativeStageMetrics({ monotonicMs: 1, paints: 5 }, { monotonicMs: 2, paints: 1 });
  assert.equal(result.paintFps, null); assert.equal(result.gcCollections, null);
  assert.equal(result.capturePaintAgeMeanMs, null);
  assert.throws(() => nativeStageMetrics({ monotonicMs: 1 }, { monotonicMs: 1 }));
});
test('next-frame mode removes pacing only after an explicitly requested acknowledgement', () => {
  const accepted = nativeDeliveryResponse({ status: 200, sequence: '12', waitAccepted: 'true' }, 11, true);
  assert.deepEqual(accepted, { sequence: 12, acknowledgedWait: true });
  assert.equal(nativeReadDelay(60, 6, accepted.acknowledgedWait), 0);
  const unsolicited = nativeDeliveryResponse({ status: 200, sequence: '12', waitAccepted: 'true' }, 11, false);
  assert.equal(unsolicited.acknowledgedWait, false);
  assert.ok(nativeReadDelay(60, 6, unsolicited.acknowledgedWait) > 10);
});
test('empty responses advance the acknowledged sequence and legacy servers retain polling', () => {
  assert.deepEqual(nativeDeliveryResponse({ status: 204, sequence: '13', waitAccepted: 'true' }, 12, true),
    { sequence: 13, acknowledgedWait: true });
  assert.deepEqual(nativeDeliveryResponse({ status: 204, sequence: null, waitAccepted: null }, 12, true),
    { sequence: 12, acknowledgedWait: false });
  assert.deepEqual(nativeDeliveryResponse({ status: 204, sequence: '13', waitAccepted: 'false' }, 12, true),
    { sequence: 13, acknowledgedWait: false });
  assert.equal(nativeReadDelay(30, 100, false), 0);
});
test('malformed next-frame acknowledgements cannot create an empty-response busy loop', () => {
  for (const sequence of [null, '', '-1', 'NaN', '2.5', '9007199254740992']) {
    assert.throws(() => nativeDeliveryResponse({ status: 204, sequence, waitAccepted: 'true' }, 12, true));
  }
  assert.throws(() => nativeDeliveryResponse({ status: 500, sequence: '13', waitAccepted: 'true' }, 12, true));
});
test('decoded pixel markers recover the frame ID and reject corruption', () => {
  for (const sequence of [1, 255, 256, 65535, 1234567, 16777215]) {
    const bits = markerBits(sequence);
    assert.equal(decodeMarker(bits), sequence);
    for (let bit = 0; bit < 40; bit++) {
      const corrupted = [...bits]; corrupted[bit] ^= 1;
      assert.equal(decodeMarker(corrupted), null);
    }
  }
});
test('RGBA conversion preserves source and BGRA channel order', () => {
  const input = Uint8Array.of(10, 20, 30, 255);
  assert.deepEqual(rgbaToBgra(input), Uint8Array.of(30, 20, 10, 255));
  assert.deepEqual(input, Uint8Array.of(10, 20, 30, 255));
});
test('NV12 uses BT709 limited black/white values and one interleaved chroma pair per 2x2 block', () => {
  const black = Uint8Array.from({ length: 16 }, (_, i) => i % 4 === 3 ? 255 : 0);
  assert.deepEqual(rgbaToNv12(black, 2, 2), Uint8Array.of(16, 16, 16, 16, 128, 128));
  assert.deepEqual(rgbaToNv12(new Uint8Array(16).fill(255), 2, 2), Uint8Array.of(235, 235, 235, 235, 128, 128));
  assert.throws(() => rgbaToNv12(black, 1, 4));
});
test('quality metric detects known luma error without emitting infinity into JSON', () => {
  assert.equal(lumaPsnr([0, 0, 0, 255], [0, 0, 0, 255]), 100);
  assert.ok(Math.abs(lumaPsnr([0, 0, 0, 255], [255, 255, 255, 255])) < 1e-10);
  assert.equal(lumaPsnr([], []), null);
});

test('playback frame deltas distinguish display drops and preserve unavailable or reset counters', () => {
  assert.deepEqual(playbackMetrics({ totalVideoFrames: 100, droppedVideoFrames: 2 }, { totalVideoFrames: 160, droppedVideoFrames: 5 }),
    { playbackTotalFrames: 60, playbackDroppedFrames: 3, playbackDisplayedFrames: 57 });
  assert.deepEqual(playbackMetrics({}, {}), { playbackTotalFrames: null, playbackDroppedFrames: null, playbackDisplayedFrames: null });
  assert.equal(playbackMetrics({ totalVideoFrames: 100 }, { totalVideoFrames: 1 }).playbackTotalFrames, null);
  assert.equal(playbackMetrics({ totalVideoFrames: 0, droppedVideoFrames: 0 }, { totalVideoFrames: 1, droppedVideoFrames: 2 }).playbackDisplayedFrames, null);
});
test('production raw marker observation decodes BGRA and NV12 without mutating frame bytes', () => {
  for (const format of ['BGRA', 'NV12']) {
    const width = 640, height = 360, bytes = new Uint8Array(width * height * (format === 'BGRA' ? 4 : 1.5));
    markerBits(123456).forEach((bit, index) => {
      const offset = (16 * width + 11 + index * 6) * (format === 'BGRA' ? 4 : 1);
      bytes[offset] = bit ? 235 : 16;
    });
    const original = bytes.slice();
    assert.equal(rawFrameMarker(bytes.buffer, width, height, format), 123456);
    assert.deepEqual(bytes, original);
    assert.equal(rawFrameMarker(bytes, width + 1, height, format), null);
    bytes[16 * width * (format === 'BGRA' ? 4 : 1) + 11 * (format === 'BGRA' ? 4 : 1)] ^= 255;
    assert.equal(rawFrameMarker(bytes, width, height, format), null);
  }
});
test('batched native timestamps reject missing, prior-phase and future paint times without persisting identifiers', () => {
  const result = resolvedNativeLatency([
    { id: 1, presentation: 200 }, { id: 2, presentation: 210 },
    { id: 3, presentation: 220 }, { id: 4, presentation: 230 },
  ], { 1: 1000, 2: 900, 3: 1200 }, -850, 100);
  assert.deepEqual(result, { latency: [50], missing: 1, firstFreshFrameMs: 100 });
  assert.ok(!JSON.stringify(result).includes('presentation'));
});

test('intentional pause marker leftovers do not invalidate otherwise correlated active frames', () => {
  const phase = (pause, markerAttempts, invalidMarkers) => ({ pause, samples: { viewers: [{ markerAttempts, invalidMarkers }] } });
  const paused = phase(true, 1, 1);
  assert.equal(excessiveMarkerFailures([phase(false, 548, 1), paused, phase(false, 520, 0)]), false);
  assert.equal(excessiveMarkerFailures([phase(false, 100, 5), paused]), false);
  assert.equal(excessiveMarkerFailures([phase(false, 100, 6), paused]), true);
  assert.equal(excessiveMarkerFailures([phase(false, 1, 1)]), true);
  assert.equal(excessiveMarkerFailures([{ samples: { viewers: [{ markerAttempts: 1, invalidMarkers: 1 }] } }]), true);
});

test('receiver viewport intersection exposes offscreen and clipped layouts without requiring a fixed peer count', () => {
  assert.equal(viewportMetrics({ left: 10, top: 20, width: 400, height: 200 }, 900, 700).intersectionFraction, 1);
  assert.equal(viewportMetrics({ left: 0, top: 720, width: 560, height: 315 }, 900, 700).intersectionFraction, 0);
  assert.equal(viewportMetrics({ left: -200, top: 600, width: 400, height: 200 }, 900, 700).intersectionFraction, 0.25);
  assert.equal(viewportMetrics({ left: 0, top: 0, width: 0, height: 0 }, 900, 700).intersectionFraction, 0);
  assert.equal(viewportMetrics({ left: 0, top: NaN, width: 400, height: 200 }, 900, 700).intersectionFraction, 0);
});

test('capture diagnostic phase deltas preserve histogram bounds and unavailable data', () => {
  assert.equal(captureDiagnosticMetrics(undefined, undefined), null);
  const before = { boundsNanos: [1e6, 4e6], counters: { latest_skipped: 2 }, timings: { latest_age: { samples: 10, totalNanos: 10e6, buckets: [10, 0, 0] } } };
  const after = { boundsNanos: [1e6, 4e6], counters: { latest_skipped: 5 }, timings: { latest_age: { samples: 15, totalNanos: 25e6, buckets: [11, 3, 1] } } };
  const result = captureDiagnosticMetrics(before, after);
  assert.equal(result.counters.latest_skipped, 3);
  assert.deepEqual(result.timings.latest_age, { samples: 5, histogramSamples: 5, meanMs: 3, p50UpperMs: 4, p95UpperMs: null, overflow: 1 });
  assert.equal(captureDiagnosticMetrics(after, before).timings.latest_age, null);
  assert.throws(() => captureDiagnosticMetrics(before, { ...after, boundsNanos: [4e6, 1e6] }));
});
