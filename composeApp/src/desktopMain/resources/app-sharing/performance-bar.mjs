import { monitorMediaStats, ReceiverNetworkSampler } from './stats.mjs';

const STALE_MS = 5000;
function value(number, decimals = 0) { return number === null || number === undefined ? '—' : number.toFixed(decimals); }
export function metricsText(label, metrics) {
  const rate = metrics?.bitrateKbps;
  const network = rate === null || rate === undefined ? '—' : rate >= 1000 ? `${value(rate / 1000, 2)} Mbps` : `${value(rate)} kbps`;
  return `${label} ${value(metrics?.fps)} fps · ${label === 'Client' ? '↓' : '↑'} ${network} · relay ${value(metrics?.rttMs)} ms`;
}

// User-facing indicators, not a link-capacity estimate. Idle video can use very little bandwidth.
export function connectionQuality(metrics) {
  const rtt = metrics?.rttMs, loss = metrics?.packetLossPercent;
  const knownRtt = Number.isFinite(rtt) && rtt >= 0, knownLoss = Number.isFinite(loss) && loss >= 0;
  if ((knownRtt && rtt >= 300) || (knownLoss && loss >= 5)) return { level: 'poor', label: 'Poor' };
  if (metrics?.bandwidthLimited === true || (knownRtt && rtt >= 120) || (knownLoss && loss >= 1)) return { level: 'fair', label: 'Fair' };
  return knownRtt || knownLoss ? { level: 'good', label: 'Good' } : { level: 'unknown', label: 'Unknown' };
}
function renderMetrics(element, label, metrics, description) {
  if (!element) return;
  const quality = connectionQuality(metrics);
  element.textContent = metricsText(label, metrics).replace(`${label} `, `${label} ${quality.label} · `);
  element.setAttribute?.('data-quality', quality.level);
  element.title = `${description} Connection: ${quality.label}. ` +
    `Relay RTT: ${value(metrics?.rttMs)} ms. Packet loss: ${value(metrics?.packetLossPercent, 1)}%. ` +
    'Good: RTT below 120 ms and measured loss below 1%; fair: RTT 120–299 ms, loss 1–4.9%, or reported bandwidth pressure; poor: RTT at least 300 ms or loss at least 5%. Unavailable measurements are not assumed healthy. Bitrate is usage, not connection quality.';
}

/** The native and hosted browser viewers use the same footer and lifecycle. */
export class SharingPerformanceBar {
  constructor(media, video, client, remote, env = globalThis) {
    this.media = media; this.video = video; this.client = client; this.remote = remote; this.env = env;
    this.reset();
  }
  reset() { this.local = null; this.host = null; this.render(); }
  render() {
    const now = this.env.performance.now();
    const local = this.local && now - this.local.receivedAt < STALE_MS ? this.local.metrics : null;
    const host = this.host && now - this.host.receivedAt < STALE_MS ? this.host.metrics : null;
    renderMetrics(this.client, 'Client', local,
      `${local?.fpsSource === 'decoded' ? 'Decoded' : 'Displayed'} video frames per second. Download rate and this device-to-relay connection.`);
    renderMetrics(this.remote, 'Remote', host,
      'BossConsole encoded frames and upload rate. Host-to-relay RTT only; host packet loss and bandwidth pressure are unavailable on this protocol. Older hosts may not report metrics.');
    const resolution = this.env.document?.getElementById('resolution');
    if (resolution) resolution.textContent = local && this.video?.videoWidth > 0 && this.video?.videoHeight > 0
      ? `${this.video.videoWidth}×${this.video.videoHeight}` : '—';
  }

  receiveRemote(metrics) {
    if (!this.running) return;
    this.host = { metrics, receivedAt: this.env.performance.now() }; this.render();
  }
  resume() {
    if (this.running) return;
    this.running = true;
    this.network = new ReceiverNetworkSampler();
    this.stopMonitor = monitorMediaStats(this.media.peer, { video: this.video, signal: this.media.abort?.signal, env: this.env,
      onSample: (metrics, report) => { if (this.running) { this.local = { metrics: { ...metrics, ...this.network.sample(report) }, receivedAt: this.env.performance.now() }; this.render(); } } });
    this.timer = this.env.setInterval(() => this.render(), 1000);
  }
  pause() { this.running = false; this.stopMonitor?.(); this.env.clearInterval(this.timer); this.reset(); }
  stop() { this.pause(); }
}
