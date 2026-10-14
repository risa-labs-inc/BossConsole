import test from 'node:test';
import assert from 'node:assert/strict';
import { MediaStatsSampler, ReceiverNetworkSampler, monitorMediaStats, validMediaMetrics } from '../../../desktopMain/resources/app-sharing/stats.mjs';
import { SharingPerformanceBar, connectionQuality, metricsText } from '../../../desktopMain/resources/app-sharing/performance-bar.mjs';

function report(timestamp, { host = false, bytes = 0, frames = 0, id = 'video', extra = [] } = {}) {
  const stats = [{ id, type: host ? 'outbound-rtp' : 'inbound-rtp', kind: 'video', timestamp,
    [host ? 'framesEncoded' : 'framesDecoded']: frames, [host ? 'bytesSent' : 'bytesReceived']: bytes,
    codecId: 'vp8', transportId: 'transport' },
  { id: 'vp8', type: 'codec', mimeType: 'video/VP8' },
  { id: 'transport', type: 'transport', selectedCandidatePairId: 'selected' },
  // A nominated but unselected pair must not win over the transport's choice.
  { id: 'other', type: 'candidate-pair', state: 'succeeded', nominated: true, currentRoundTripTime: 9 },
  { id: 'selected', type: 'candidate-pair', currentRoundTripTime: 0.02 }, ...extra];
  return new Map(stats.map(entry => [entry.id, entry]));
}
test('measures actual video rates on each leg and distinguishes unknown from idle', () => {
  for (const host of [true, false]) {
    const sampler = new MediaStatsSampler(host);
    assert.deepEqual(sampler.sample(report(1000, { host })), { fps: null, fpsSource: host ? 'encoded' : 'decoded', bitrateKbps: null, rttMs: 20 });
    assert.deepEqual(sampler.sample(report(2000, { host, frames: 30, bytes: 250000 })), { fps: 30, fpsSource: host ? 'encoded' : 'decoded', bitrateKbps: 2000, rttMs: 20 });
    const idle = sampler.sample(report(3000, { host, frames: 30, bytes: 250000 }));
    assert.equal(idle.fps, 0); assert.equal(idle.bitrateKbps, 0);
  }
});
test('RTP replacements, counter resets, stale reports, RTX and audio cannot inflate metrics', () => {
  const sampler = new MediaStatsSampler(true);
  const noisy = timestamp => [{ id: 'rtx', type: 'outbound-rtp', kind: 'video', timestamp, codecId: 'rtx-codec', bytesSent: timestamp * 900, framesEncoded: timestamp * 9 },
    { id: 'rtx-codec', type: 'codec', mimeType: 'video/rtx' }, { id: 'audio', type: 'outbound-rtp', kind: 'audio', timestamp, bytesSent: timestamp * 100 }];
  sampler.sample(report(1000, { host: true, extra: noisy(1000) }));
  assert.equal(sampler.sample(report(2000, { host: true, frames: 30, bytes: 250000, extra: noisy(2000) })).bitrateKbps, 2000);
  for (const data of [{ timestamp: 3000, frames: 0, bytes: 0 }, { timestamp: 4000, frames: 2000, bytes: 30000000, id: 'new-ssrc' },
    { timestamp: 11000, frames: 3000, bytes: 35000000, id: 'new-ssrc' }]) {
    const { timestamp, ...values } = data;
    const metrics = sampler.sample(report(timestamp, { host: true, ...values }));
    assert.equal(metrics.fps, null); assert.equal(metrics.bitrateKbps, null);
  }
  const missing = sampler.sample(new Map());
  assert.equal(missing.rttMs, null); assert.equal(missing.fps, null);
});
function timers() {
  let clock = 0, next = 0;
  const jobs = new Map();
  return { jobs, now: value => { clock = value; }, env: {
    performance: { now: () => clock }, setTimeout: fn => { jobs.set(++next, fn); return next; }, clearTimeout: id => jobs.delete(id),
    setInterval: fn => { jobs.set(++next, fn); return next; }, clearInterval: id => jobs.delete(id),
  }, async tick() { const [id, fn] = jobs.entries().next().value; jobs.delete(id); await fn(); } };
}
const flush = async () => { await new Promise(resolve => setImmediate(resolve)); };
test('client FPS counts compositor frames instead of claiming all decoded frames were displayed', async () => {
  const t = timers(), samples = []; let callback, canceled = false, frames = 0;
  const video = { requestVideoFrameCallback(fn) { callback = fn; return 1; }, cancelVideoFrameCallback() { canceled = true; } };
  const peer = { getStats: async () => report(t.env.performance.now(), { frames, bytes: frames * 1000 }) };
  const stop = monitorMediaStats(peer, { video, env: t.env, onSample: value => samples.push(value) });
  await flush(); t.now(1000); frames = 30; callback(1000, { presentedFrames: 24 }); await t.tick();
  assert.equal(samples.at(-1).fps, 24); assert.equal(samples.at(-1).fpsSource, 'presented');
  stop(); assert.equal(t.jobs.size, 0); assert.equal(canceled, true);
});
test('sampling serializes work, clears failed counters and drops results after abort', async () => {
  const t = timers(), signal = new AbortController(), seen = []; let resolve, calls = 0;
  const peer = { getStats: () => { calls++; return new Promise(done => { resolve = done; }); } };
  const stop = monitorMediaStats(peer, { host: true, signal: signal.signal, env: t.env, onSample: value => seen.push(value) });
  assert.equal(calls, 1); assert.equal(t.jobs.size, 0);
  signal.abort(); resolve(report(1000, { host: true })); await flush();
  assert.equal(seen.length, 0); assert.equal(t.jobs.size, 0); stop();
  const failures = monitorMediaStats({ getStats: async () => { throw new Error('gone'); } }, { env: t.env, onSample: value => seen.push(value) });
  await flush(); assert.equal(seen[0].fps, null); assert.equal(seen[0].bitrateKbps, null); failures();
});
test('performance bar expires remote telemetry, clears on disconnect and resumes with a fresh baseline', () => {
  const t = timers(), client = {}, remote = {}, bar = new SharingPerformanceBar({ peer: {} }, {}, client, remote, t.env);
  bar.resume(); bar.receiveRemote({ fps: 30, fpsSource: 'encoded', bitrateKbps: 2000, rttMs: 20 });
  assert.equal(remote.textContent, 'Remote Good · 30 fps · ↑ 2.00 Mbps · relay 20 ms');
  assert.match(client.textContent, /Client Unknown · — fps/);
  t.now(5000); bar.render(); assert.match(remote.textContent, /Remote Unknown · — fps/);
  bar.pause(); bar.receiveRemote({ fps: 60 }); assert.match(remote.textContent, /Remote Unknown · — fps/);
  assert.equal(t.jobs.size, 0); bar.resume(); assert.match(remote.textContent, /Remote Unknown · — fps/); bar.stop();
  assert.equal(metricsText('Client', { fps: 0, bitrateKbps: 0, rttMs: null }), 'Client 0 fps · ↓ 0 kbps · relay — ms');
});
test('metrics schema accepts only finite, bounded aggregate fields', () => {
  const metrics = { fps: 30, fpsSource: 'encoded', bitrateKbps: 8000, rttMs: 20 };
  assert.equal(validMediaMetrics(metrics), true);
  for (const invalid of [{ ...metrics, fps: NaN }, { ...metrics, fps: -1 }, { ...metrics, bitrateKbps: Infinity },
    { ...metrics, rttMs: 60001 }, { ...metrics, address: 'private-candidate' }, { ...metrics, fpsSource: 'unknown' }]) assert.equal(validMediaMetrics(invalid), false);
});

test('connection labels use available latency loss and explicit pressure, never low bitrate alone', () => {
  assert.deepEqual(connectionQuality({ bitrateKbps: 0, fps: 0 }), { level: 'unknown', label: 'Unknown' });
  assert.equal(connectionQuality({ bitrateKbps: 0, rttMs: 20 }).level, 'good');
  assert.equal(connectionQuality({ rttMs: 119, packetLossPercent: 0.99 }).level, 'good');
  for (const metrics of [{ rttMs: 120 }, { packetLossPercent: 1 }, { bandwidthLimited: true }])
    assert.equal(connectionQuality(metrics).level, 'fair');
  for (const metrics of [{ rttMs: 300 }, { rttMs: 20, packetLossPercent: 5 }])
    assert.equal(connectionQuality(metrics).level, 'poor');
  assert.equal(connectionQuality({ rttMs: NaN, packetLossPercent: -1 }).level, 'unknown');
});
test('receiver loss uses fresh per-stream packet deltas and resets without changing wire metrics', () => {
  const sampler = new ReceiverNetworkSampler();
  const sample = (timestamp, packetsReceived, packetsLost, id = 'video') => {
    const stats = report(timestamp, { id });
    Object.assign(stats.get(id), { packetsReceived, packetsLost });
    return sampler.sample(stats);
  };
  assert.equal(sample(1000, 100, 0).packetLossPercent, null);
  assert.equal(sample(2000, 195, 5).packetLossPercent, 5);
  assert.equal(sample(3000, 195, 5).packetLossPercent, null); // Idle is not a loss sample.
  assert.equal(sample(4000, 200, 4).packetLossPercent, null); // Late-packet correction.
  assert.equal(sample(5000, 300, 4).packetLossPercent, 0);
  assert.equal(sample(6000, 1000, 50, 'replacement').packetLossPercent, null);
  assert.equal(sample(12000, 2000, 100, 'replacement').packetLossPercent, null);
  assert.equal(sampler.sample(undefined).packetLossPercent, null);
  assert.equal(validMediaMetrics({ fps: 30, fpsSource: 'encoded', bitrateKbps: 20, rttMs: 10, packetLossPercent: 0 }), false);
});
test('footer exposes non-color quality labels and clears resolution and colors when telemetry expires', () => {
  const t = timers(), resolution = {}, attributes = {};
  t.env.document = { getElementById: id => id === 'resolution' ? resolution : null };
  const client = { setAttribute: (key, value) => { attributes[key] = value; } };
  const bar = new SharingPerformanceBar({ peer: {} }, { videoWidth: 1920, videoHeight: 1080 }, client, {}, t.env);
  bar.local = { receivedAt: 0, metrics: { fps: 55, rttMs: 20, bitrateKbps: 0, packetLossPercent: 6 } };
  bar.render();
  assert.equal(attributes['data-quality'], 'poor'); assert.match(client.textContent, /Client Poor/);
  assert.match(client.title, /Packet loss: 6.0%/); assert.equal(resolution.textContent, '1920×1080');
  t.now(5000); bar.render();
  assert.equal(attributes['data-quality'], 'unknown'); assert.match(client.textContent, /Client Unknown/);
  assert.equal(resolution.textContent, '—');
});
