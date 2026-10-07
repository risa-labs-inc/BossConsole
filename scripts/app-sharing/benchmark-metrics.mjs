// Pure measurement helpers shared by the browser workload and Node regression tests.
// A paused producer cannot correlate the last in-flight marker with a new source frame.
// Keep its counters visible, but apply the failure threshold only to active phases.
export function excessiveMarkerFailures(phases) {
  return phases.some(phase => !phase.pause && phase.samples.viewers.some(viewer => viewer.invalidMarkers > viewer.markerAttempts * 0.05));
}
export function percentile(values, fraction) {
  const sorted = values.filter(Number.isFinite).sort((a, b) => a - b);
  if (!sorted.length) return null;
  return sorted[Math.max(0, Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1))];
}
export function distribution(values) {
  const clean = values.filter(Number.isFinite);
  return { samples: clean.length, p50: percentile(clean, 0.5), p95: percentile(clean, 0.95), max: clean.length ? Math.max(...clean) : null };
}
export function clockEstimate(before, after, remote) {
  if (![before, after, remote].every(Number.isFinite) || after < before) throw new Error('Invalid clock sample');
  return { offsetMs: (before + after) / 2 - remote, uncertaintyMs: (after - before) / 2 };
}
export function presentationObservation(previous, metadata, now) {
  const count = metadata.presentedFrames;
  if (!Number.isSafeInteger(count) || count < 0 || (previous !== null && (!Number.isSafeInteger(previous) || previous < 0)) || !Number.isFinite(metadata.expectedDisplayTime) || !Number.isFinite(now)) return { valid: false, frames: 0, missedCallbacks: 0, latenessMs: null };
  const frames = previous === null ? 1 : count - previous;
  if (frames < 0) return { valid: false, frames: 0, missedCallbacks: 0, latenessMs: null };
  return { valid: true, frames, missedCallbacks: Math.max(0, frames - 1), latenessMs: Math.max(0, now - metadata.expectedDisplayTime) };
}
// Fixed histograms are aggregate measurements, never per-frame timestamps or stream identifiers.
export function captureDiagnosticMetrics(before, after) {
  if (!before || !after) return null;
  const bounds = after.boundsNanos;
  if (!Array.isArray(bounds) || !bounds.length || bounds.length > 32 || JSON.stringify(bounds) !== JSON.stringify(before.boundsNanos) || bounds.some((n, i) => !Number.isFinite(n) || n < 0 || (i && n <= bounds[i - 1]))) throw new Error('Invalid capture diagnostic histogram');
  const difference = (start, end) => Number.isFinite(start) && Number.isFinite(end) && end >= start ? end - start : null;
  const counters = Object.fromEntries(Object.entries(after.counters).map(([key, value]) => [key, difference(before.counters[key], value)]));
  const timings = Object.fromEntries(Object.entries(after.timings).map(([name, value]) => {
    const previous = before.timings[name], samples = difference(previous?.samples, value.samples), total = difference(previous?.totalNanos, value.totalNanos);
    const buckets = value.buckets.map((count, i) => difference(previous?.buckets?.[i], count));
    if (buckets.length !== bounds.length + 1 || buckets.some(count => count === null)) return [name, null];
    const observed = buckets.reduce((sum, n) => sum + n, 0);
    const quantileUpperMs = fraction => {
      if (!observed) return null;
      let count = 0;
      for (let i = 0; i < buckets.length; i++) {
        count += buckets[i];
        if (count >= Math.ceil(observed * fraction)) return i < bounds.length ? bounds[i] / 1e6 : null;
      }
      return null;
    };
    return [name, { samples, histogramSamples: observed, meanMs: total !== null && samples > 0 ? total / samples / 1e6 : null,
      p50UpperMs: quantileUpperMs(0.5), p95UpperMs: quantileUpperMs(0.95), overflow: buckets.at(-1) }];
  }));
  return { counters, timings, percentileMethod: 'fixed-histogram-upper-bound', snapshotsAtomic: false };
}
export function nativeStageMetrics(before, after) {
  if (!before || !after) return null;
  const elapsedMs = after.monotonicMs - before.monotonicMs;
  if (!(elapsedMs > 0)) throw new Error('Invalid native metric interval');
  const count = key => Number.isFinite(before[key]) && Number.isFinite(after[key]) && after[key] >= before[key] ? after[key] - before[key] : null;
  const frequency = key => { const value = count(key); return value === null ? null : value * 1000 / elapsedMs; };
  const meanAge = (ageKey, sampleKey) => { const age = count(ageKey), samples = count(sampleKey); return age !== null && samples > 0 ? age / samples / 1000 : null; };
  const counters = ['paints', 'captures', 'captureEmpty', 'captureUnknownMarkers', 'uniqueCapturedPaints', 'paintsSkippedByCapture', 'captureSourceReorders', 'mailboxReads', 'http200', 'http204', 'httpUnknownMarkers', 'bytesServed'];
  return {
    captureDiagnostics: captureDiagnosticMetrics(before.captureDiagnostics, after.captureDiagnostics),
    elapsedMs, counters: Object.fromEntries(counters.map(key => [key, count(key)])),
    paintFps: frequency('paints'), captureDeliveryFps: frequency('captures'), uniqueCapturedPaintFps: frequency('uniqueCapturedPaints'),
    mailboxReadsPerSecond: frequency('mailboxReads'), httpFramesPerSecond: frequency('http200'),
    capturePaintAgeMeanMs: meanAge('capturePaintAgeMicros', 'captureAgeSamples'), httpPaintAgeMeanMs: meanAge('httpPaintAgeMicros', 'http200'),
    gcCollections: before.gcAvailable && after.gcAvailable ? count('gcCollections') : null,
    gcCollectionTimeMs: before.gcAvailable && after.gcAvailable ? count('gcCollectionTimeMs') : null,
    heapUsedBeforeBytes: before.heapUsedBytes ?? null, heapUsedAfterBytes: after.heapUsedBytes ?? null,
    heapCommittedAfterBytes: after.heapCommittedBytes ?? null, heapMaxBytes: after.heapMaxBytes ?? null,
  };
}
export function nativeDeliveryResponse({ status, sequence, waitAccepted }, previousSequence, requestedWait) {
  if (status !== 200 && status !== 204) throw new Error('Native frame bridge unavailable');
  const acknowledgedWait = requestedWait && waitAccepted === 'true';
  if (sequence === null || sequence === undefined) {
    if (status === 204 && !acknowledgedWait) return { sequence: previousSequence, acknowledgedWait: false };
    throw new Error('Native frame sequence missing');
  }
  const next = Number(sequence);
  if (!/^\d+$/.test(String(sequence)) || !Number.isSafeInteger(next) || next < 0) throw new Error('Native frame sequence invalid');
  return { sequence: next, acknowledgedWait };
}
export function nativeReadDelay(frameRate, elapsedMs, acknowledgedWait) {
  return acknowledgedWait ? 0 : Math.max(0, 1000 / frameRate - elapsedMs);
}
// A readback acknowledges an application preference, not the actual buffer delay.
export function configureJitterTarget(receiver, requestedMs = null) {
  if (![null, 0, 10].includes(requestedMs)) throw new Error('Invalid jitter target');
  const supported = !!receiver && 'jitterBufferTarget' in receiver;
  const result = { requestedMs, supported, readbackMs: null, status: supported ? 'default' : 'unsupported' };
  if (!supported) return result;
  try {
    if (requestedMs !== null) receiver.jitterBufferTarget = requestedMs;
    const value = receiver.jitterBufferTarget;
    result.readbackMs = Number.isFinite(value) && value >= 0 ? value : null;
    if (requestedMs !== null) result.status = value === requestedMs ? 'applied' : 'mismatch';
  } catch {
    result.status = 'rejected';
  }
  return result;
}
export function receiverTimingMetrics(before, after) {
  const count = key => Number.isFinite(before[key]) && Number.isFinite(after[key]) && after[key] >= before[key] ? after[key] - before[key] : null;
  const emitted = count('jitterBufferEmittedCount'), rendered = count('framesRendered');
  const mean = (key, samples) => { const total = count(key); return total !== null && samples > 0 ? total * 1000 / samples : null; };
  const sum = count('totalInterFrameDelay'), squares = count('totalSquaredInterFrameDelay');
  return {
    averageJitterBufferMs: mean('jitterBufferDelay', emitted),
    averageJitterBufferTargetMs: mean('jitterBufferTargetDelay', emitted),
    averageJitterBufferMinimumMs: mean('jitterBufferMinimumDelay', emitted),
    framesRendered: rendered,
    renderedFrameIntervalMeanMs: mean('totalInterFrameDelay', rendered),
    renderedFrameIntervalStdDevMs: sum !== null && squares !== null && rendered > 0 ? Math.sqrt(Math.max(0, squares / rendered - (sum / rendered) ** 2)) * 1000 : null,
    receiverFreezes: count('freezeCount'), receiverFreezeDurationMs: count('totalFreezesDuration') === null ? null : count('totalFreezesDuration') * 1000,
  };
}
export function viewportMetrics(rect, viewportWidth, viewportHeight) {
  const valid = [rect.left, rect.top, rect.width, rect.height, viewportWidth, viewportHeight].every(Number.isFinite) &&
    rect.width > 0 && rect.height > 0 && viewportWidth > 0 && viewportHeight > 0;
  const visibleWidth = valid ? Math.max(0, Math.min(rect.left + rect.width, viewportWidth) - Math.max(rect.left, 0)) : 0;
  const visibleHeight = valid ? Math.max(0, Math.min(rect.top + rect.height, viewportHeight) - Math.max(rect.top, 0)) : 0;
  return { width: valid ? rect.width : null, height: valid ? rect.height : null,
    viewportWidth, viewportHeight, intersectionFraction: valid ? visibleWidth * visibleHeight / (rect.width * rect.height) : 0 };
}
export function playbackMetrics(before = {}, after = {}) {
  const count = key => Number.isSafeInteger(before[key]) && Number.isSafeInteger(after[key]) && after[key] >= before[key] ? after[key] - before[key] : null;
  const total = count('totalVideoFrames'), dropped = count('droppedVideoFrames');
  return { playbackTotalFrames: total, playbackDroppedFrames: dropped,
    playbackDisplayedFrames: total !== null && dropped !== null && dropped <= total ? total - dropped : null };
}
export function rawFrameMarker(bytes, width, height, format) {
  const pixels = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (!['BGRA', 'NV12'].includes(format) || !Number.isSafeInteger(width) || !Number.isSafeInteger(height) ||
      width < 1 || height < 1 || width * height > 4194304 || pixels.byteLength !== width * height * (format === 'NV12' ? 1.5 : 4)) return null;
  return decodeMarker(Array.from({ length: 40 }, (_, bit) => {
    const x = Math.min(width - 1, Math.floor((22 + bit * 12) * width / 1280));
    const y = Math.min(height - 1, Math.floor(32 * height / 720));
    return pixels[(y * width + x) * (format === 'NV12' ? 1 : 4)] > 128 ? 1 : 0;
  }));
}
export function resolvedNativeLatency(samples, timestamps, offsetMs, phaseStart) {
  let missing = 0, firstFreshFrameMs = null;
  const latency = [];
  for (const sample of samples) {
    const native = timestamps[sample.id], paintedAt = native + offsetMs;
    if (!Number.isFinite(native)) { missing++; continue; }
    if (Number.isFinite(sample.presentation) && paintedAt >= phaseStart && paintedAt <= sample.presentation) {
      latency.push(sample.presentation - paintedAt);
      firstFreshFrameMs ??= sample.presentation - phaseStart;
    }
  }
  return { latency, missing, firstFreshFrameMs };
}
export function checksum(sequence) { return ((sequence >>> 16) ^ (sequence >>> 8) ^ sequence ^ 0xa5) & 255; }
export function markerBits(sequence) {
  const values = [0xd3, sequence >>> 16, sequence >>> 8, sequence, checksum(sequence)];
  return values.flatMap(value => Array.from({ length: 8 }, (_, bit) => (value >>> (7 - bit)) & 1));
}
export function decodeMarker(bits) {
  if (bits.length !== 40 || bits.some(bit => bit !== 0 && bit !== 1)) return null;
  const bytes = Array.from({ length: 5 }, (_, index) => bits.slice(index * 8, index * 8 + 8).reduce((value, bit) => value * 2 + bit, 0));
  const sequence = bytes[1] * 65536 + bytes[2] * 256 + bytes[3];
  return bytes[0] === 0xd3 && bytes[4] === checksum(sequence) ? sequence : null;
}
export function rgbaToBgra(rgba) {
  const bytes = new Uint8Array(rgba.length);
  for (let i = 0; i < bytes.length; i += 4) {
    bytes[i] = rgba[i + 2]; bytes[i + 1] = rgba[i + 1]; bytes[i + 2] = rgba[i]; bytes[i + 3] = 255;
  }
  return bytes;
}
// BT.709 limited-range, 4:2:0. This JavaScript workload conversion is measured
// separately; it does not represent the native SCStream NV12 conversion cost.
export function rgbaToNv12(rgba, width, height) {
  if (width % 2 || height % 2 || rgba.length !== width * height * 4) throw new Error('Even NV12 dimensions required');
  const bytes = new Uint8Array(width * height * 1.5), clamp = value => Math.max(0, Math.min(255, Math.round(value)));
  for (let y = 0; y < height; y += 2) for (let x = 0; x < width; x += 2) {
    let red = 0, green = 0, blue = 0;
    for (let dy = 0; dy < 2; dy++) for (let dx = 0; dx < 2; dx++) {
      const pixel = (y + dy) * width + x + dx, i = pixel * 4;
      const r = rgba[i], g = rgba[i + 1], b = rgba[i + 2];
      bytes[pixel] = clamp(16 + 0.182586 * r + 0.614231 * g + 0.062007 * b);
      red += r / 4; green += g / 4; blue += b / 4;
    }
    const uv = width * height + y / 2 * width + x;
    bytes[uv] = clamp(128 - 0.100644 * red - 0.338572 * green + 0.439216 * blue);
    bytes[uv + 1] = clamp(128 + 0.439216 * red - 0.398942 * green - 0.040274 * blue);
  }
  return bytes;
}
export function lumaPsnr(actual, reference) {
  if (actual.length !== reference.length || actual.length === 0) return null;
  let error = 0;
  for (let i = 0; i < actual.length; i += 4) {
    const delta = (actual[i] - reference[i]) * 0.2126 + (actual[i + 1] - reference[i + 1]) * 0.7152 + (actual[i + 2] - reference[i + 2]) * 0.0722;
    error += delta * delta;
  }
  // A finite ceiling keeps JSON portable; 100 means exact within this metric.
  return error === 0 ? 100 : 10 * Math.log10(255 * 255 / (error / (actual.length / 4)));
}
