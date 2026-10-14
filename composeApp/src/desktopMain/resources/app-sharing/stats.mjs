// Only these aggregate measurements leave the publisher. Never forward a raw
// RTCStatsReport: it includes candidate addresses and other device information.
const limits = { fps: 240, bitrateKbps: 1000000, rttMs: 60000 };
export function validMediaMetrics(value) {
  return value && typeof value === 'object' && !Array.isArray(value) &&
    Object.keys(value).length === 4 && ['encoded', 'decoded', 'presented'].includes(value.fpsSource) &&
    Object.entries(limits).every(([key, limit]) => value[key] === null ||
      (typeof value[key] === 'number' && Number.isFinite(value[key]) && value[key] >= 0 && value[key] <= limit));
}
function bounded(value, limit) { return Number.isFinite(value) && value >= 0 && value <= limit ? value : null; }
function delta(entry, previous, field) {
  const elapsed = (entry.timestamp - previous?.timestamp) / 1000;
  return elapsed > 0 && elapsed <= 5 && Number.isFinite(entry[field]) && Number.isFinite(previous?.[field]) &&
    entry[field] >= previous[field] ? (entry[field] - previous[field]) / elapsed : null;
}

/** Rates are computed per RTP stream, so a new SSRC or reset cannot create a spike. */
export class MediaStatsSampler {
  constructor(host = false) { this.host = host; this.previous = new Map(); this.playback = null; }
  sample(report, playback) {
    const entries = new Map(Array.from(report.values(), entry => [entry.id, entry]));
    const type = this.host ? 'outbound-rtp' : 'inbound-rtp';
    const streams = [...entries.values()].filter(entry => entry.type === type &&
      (entry.kind ?? entry.mediaType) === 'video' &&
      !/\/(rtx|red|ulpfec|flexfec)(?:$|-)/i.test(entries.get(entry.codecId)?.mimeType ?? ''));
    let fps = null, bitrateKbps = null;
    for (const entry of streams) {
      const previous = this.previous.get(entry.id);
      const frames = delta(entry, previous, this.host ? 'framesEncoded' : 'framesDecoded');
      const bytes = delta(entry, previous, this.host ? 'bytesSent' : 'bytesReceived');
      if (frames !== null) fps = (fps ?? 0) + frames;
      if (bytes !== null) bitrateKbps = (bitrateKbps ?? 0) + bytes * 8 / 1000;
    }
    this.previous = new Map(streams.map(entry => [entry.id, entry]));
    let fpsSource = this.host ? 'encoded' : 'decoded';
    if (!this.host && playback) {
      fps = delta(playback, this.playback, 'frames'); fpsSource = 'presented'; this.playback = playback;
    }
    const transport = entries.get(streams[0]?.transportId);
    const pair = entries.get(transport?.selectedCandidatePairId) ?? [...entries.values()].find(entry =>
      entry.type === 'candidate-pair' && entry.state === 'succeeded' && entry.nominated === true);
    return { fps: bounded(fps, limits.fps), fpsSource, bitrateKbps: bounded(bitrateKbps, limits.bitrateKbps),
      rttMs: bounded(typeof pair?.currentRoundTripTime === 'number' ? pair.currentRoundTripTime * 1000 : null, limits.rttMs) };
  }
}

/** One sample in flight, once a second, with no callback after retirement. */
export function monitorMediaStats(peer, { host = false, video, signal, onSample, intervalMs = 1000, env = globalThis } = {}) {
  if (!peer?.getStats || signal?.aborted) return () => {};
  const sampler = new MediaStatsSampler(host);
  let stopped = false, timer, frameCallback, presentedFrames;
  const frame = (_time, metadata) => {
    if (stopped) return;
    presentedFrames = metadata.presentedFrames;
    frameCallback = video.requestVideoFrameCallback(frame);
  };
  if (video?.requestVideoFrameCallback) frameCallback = video.requestVideoFrameCallback(frame);
  function playback() {
    if (!video || host) return undefined;
    if (video.requestVideoFrameCallback) return { timestamp: env.performance.now(), frames: presentedFrames ?? 0 };
    const quality = video.getVideoPlaybackQuality?.();
    return quality ? { timestamp: env.performance.now(), frames: quality.totalVideoFrames - quality.droppedVideoFrames } : undefined;
  }
  const stop = () => {
    stopped = true; env.clearTimeout(timer); signal?.removeEventListener('abort', stop);
    if (frameCallback !== undefined) video?.cancelVideoFrameCallback?.(frameCallback);
  };
  const poll = async () => {
    try {
      const report = await peer.getStats();
      if (!stopped && !signal?.aborted) await onSample(sampler.sample(report, playback()), report);
    } catch (_) {
      if (!stopped && !signal?.aborted) {
        sampler.previous.clear(); sampler.playback = null;
        // Unavailable is different from a measured idle stream (zero).
        try { await onSample({ fps: null, fpsSource: host ? 'encoded' : 'decoded', bitrateKbps: null, rttMs: null }); } catch (_) {}
      }
    } finally { if (!stopped && !signal?.aborted) timer = env.setTimeout(poll, intervalMs); }
  };
  signal?.addEventListener('abort', stop, { once: true }); poll();
  return stop;
}

/** Receiver-only diagnostics. These fields never enter the signed four-field wire schema. */
export class ReceiverNetworkSampler {
  constructor() { this.previous = new Map(); }
  sample(report) {
    const entries = new Map([...(report?.values?.() ?? [])].map(entry => [entry.id, entry]));
    const streams = [...entries.values()].filter(entry => entry.type === 'inbound-rtp' &&
      (entry.kind ?? entry.mediaType) === 'video' &&
      !/\/(rtx|red|ulpfec|flexfec)(?:$|-)/i.test(entries.get(entry.codecId)?.mimeType ?? ''));
    let received = 0, lost = 0, measured = false;
    for (const entry of streams) {
      const previous = this.previous.get(entry.id);
      const elapsed = entry.timestamp - previous?.timestamp;
      if (!(elapsed > 0 && elapsed <= 5000)) continue;
      const packets = entry.packetsReceived - previous.packetsReceived;
      const losses = entry.packetsLost - previous.packetsLost;
      // Counters can reset or packet-loss estimates can fall after late packets arrive.
      if (![packets, losses].every(value => Number.isFinite(value) && value >= 0)) continue;
      received += packets; lost += losses; measured = true;
    }
    this.previous = new Map(streams.map(entry => [entry.id, entry]));
    return { packetLossPercent: measured && received + lost > 0 ? 100 * lost / (received + lost) : null };
  }
}
