import { createControlCipher, fromBase64, toBase64 } from './crypto.mjs';
import { monitorMediaStats, validMediaMetrics } from './stats.mjs';
import { SHARING_RECOVERY_MS, transientSharingFailure } from './recovery.mjs';
import { remoteCursor } from './cursor.mjs';

const utf8 = new TextEncoder();
const MAX_BUFFER = 65536;
const LEASE_POLL_MS = 1000;

function waitForControlChannel(channel, signal) {
  return new Promise((resolve, reject) => {
    if (channel.readyState === 'open') { resolve(); return; }
    if (signal?.aborted || ['closed', 'closing'].includes(channel.readyState)) { reject(new Error('Control channel unavailable')); return; }
    const finish = error => {
      clearTimeout(timer); signal?.removeEventListener('abort', canceled);
      channel.removeEventListener('open', opened); channel.removeEventListener('close', canceled); channel.removeEventListener('error', canceled);
      error ? reject(error) : resolve();
    };
    const opened = () => finish(), canceled = () => finish(new Error('Control channel unavailable'));
    const timer = setTimeout(canceled, 10000);
    channel.addEventListener('open', opened, { once: true }); channel.addEventListener('close', canceled, { once: true }); channel.addEventListener('error', canceled, { once: true });
    signal?.addEventListener('abort', canceled, { once: true });
    if (signal?.aborted || channel.readyState === 'closed') canceled();
    else if (channel.readyState === 'open') opened();
  });
}

function normalizeLease(lease, peerId) {
  return lease && { leaseId: lease.lease_id, peerId: lease.peer_id ?? peerId, controlSecret: lease.control_secret, expiresAt: typeof lease.expires_at === 'number' ? lease.expires_at : Date.parse(lease.expires_at) };
}
function geometryPayload(config, geometry, sequence, authorityAvailable) {
  return { protocol: 'boss-app-share/1', type: 'geometry', sessionId: config.sessionId, generation: config.generation, windowId: config.windowId, keyEpoch: config.keyEpoch, sequence, ...geometry, authorityAvailable };
}

/** Persistent SFU DataChannel control. HTTP only establishes channels and leases. */
export class AppControlChannel {
  constructor(media, { host = false, onInput = () => {}, onLease = () => {}, onGeometry = () => {}, onMetrics = () => {}, onAuthority = () => {}, onError = () => {} } = {}) {
    this.media = media; this.config = media.config; this.host = host; this.onInput = onInput; this.onLease = onLease; this.onGeometry = onGeometry; this.onError = onError;
    this.closed = false; this.geometry = null; this.geometrySequence = 0; this.lastGeometrySequence = 0; this.queue = []; this.chain = Promise.resolve();
    this.cipherCache = new Map();
    this.onAuthority = onAuthority; this.pollFailures = 0;
    this.onMetrics = onMetrics; this.metricsSequence = 0; this.lastMetricsSequence = 0;
    this.authorityAvailable = true;
  }
  async start() {
    try {
      const established = await this.media.call('dataEstablish');
      await this.media.answer(established.session_description);
      await this.media.waitConnected();
      const response = this.host ? await this.media.call('dataPublish') : await this.media.callWhenPublished('dataSubscribe', { can_reply: false });
      if (!Number.isInteger(response.channel_id) || response.channel_id < 0 || response.channel_id > 65534) throw new Error('Invalid SFU channel');
      if (this.closed) return;
      this.channel = this.media.peer.createDataChannel('controls', { negotiated: true, id: response.channel_id, ordered: true });
      this.signing = await crypto.subtle.importKey(this.host ? 'pkcs8' : 'spki', fromBase64(this.host ? this.config.hostPrivateKey : this.config.hostPublicKey), 'Ed25519', false, [this.host ? 'sign' : 'verify']);
      this.channel.onmessage = ({ data }) => {
        if (typeof data !== 'string' || data.length > 12000 || this.closed || this.queue.length >= 64) return;
        this.queue.push(data);
        if (this.queue.length === 1) this.drain();
      };
      this.channel.onclose = () => { if (!this.closed) { this.clearLease(false, { reason: 'channel' }); this.onError('Remote control disconnected'); } };
      await waitForControlChannel(this.channel, this.media.abort?.signal);
      if (this.closed) return;
      if (this.host) {
        this.pollLease();
        // One publisher sample/broadcast, independent of the number of viewers
        // and of who currently holds the input lease.
        this.stopMetrics = monitorMediaStats(this.media.peer, { host: true, signal: this.media.abort?.signal,
          onSample: async (metrics, report) => { await this.media.updateCaptureRate?.(report); if (!this.closed) this.announceMetrics(metrics); } });
      }
    } catch (error) { this.stop(); throw error; }
  }
  async drain() {
    while (!this.closed && this.queue.length) {
      const data = this.queue[0];
      try {
        const message = JSON.parse(data);
        if (this.host) {
          const lease = this.lease;
          if (!lease || Date.now() >= lease.expiresAt || !this.geometry || !this.cipher) throw new Error('No current control lease');
          const input = await this.cipher.decrypt(message, this.geometry.geometryRevision, true);
          // Decryption yields; a lease can have expired/revoked during that await.
          if (this.lease !== lease || Date.now() >= lease.expiresAt || this.closed) throw new Error('Control lease expired');
          this.onInput(input);
        } else {
          const body = message.body;
          if (message.type === 'metrics') {
            if (!body || body.type !== 'metrics' || body.protocol !== 'boss-app-share/1' || body.sessionId !== this.config.sessionId || body.generation !== this.config.generation || body.windowId !== this.config.windowId || body.keyEpoch !== this.config.keyEpoch || !Number.isSafeInteger(body.sequence) || body.sequence <= this.lastMetricsSequence || !validMediaMetrics(body.metrics) || body.metrics.fpsSource !== 'encoded') throw new Error('Invalid metrics');
            if (!await crypto.subtle.verify('Ed25519', this.signing, fromBase64(message.signature), utf8.encode(JSON.stringify(body)))) throw new Error('Unauthenticated metrics');
            if (this.closed || body.sequence <= this.lastMetricsSequence) throw new Error('Stale metrics');
            this.lastMetricsSequence = body.sequence; this.onMetrics(body.metrics);
            this.queue.shift(); continue;
          }
          if (!body || message.type !== 'geometry' || body.protocol !== 'boss-app-share/1' || body.sessionId !== this.config.sessionId || body.generation !== this.config.generation || body.windowId !== this.config.windowId || body.keyEpoch !== this.config.keyEpoch || !Number.isSafeInteger(body.sequence) || body.sequence <= this.lastGeometrySequence || !Number.isSafeInteger(body.geometryRevision) || body.geometryRevision < 0 || !Number.isInteger(body.width) || !Number.isInteger(body.height) || body.width < 1 || body.height < 1 || body.width > 8192 || body.height > 8192) throw new Error('Invalid geometry');
          if (!await crypto.subtle.verify('Ed25519', this.signing, fromBase64(message.signature), utf8.encode(JSON.stringify(body)))) throw new Error('Unauthenticated geometry');
          if (this.closed || body.sequence <= this.lastGeometrySequence) throw new Error('Stale geometry');
          this.lastGeometrySequence = body.sequence; this.geometry = body; this.onGeometry(body);
        }
      } catch (_) { /* Malformed, unauthorized and stale input is dropped. */ }
      this.queue.shift();
    }
  }
  setGeometry(geometry) {
    const changed = this.geometry?.geometryRevision !== geometry.geometryRevision || remoteCursor(this.geometry?.cursor) !== remoteCursor(geometry.cursor);
    this.geometry = { ...geometry, cursor: remoteCursor(geometry.cursor) };
    // Notify viewers as soon as a popup or resize changes input coordinates.
    if (this.host && changed && !this.closed) {
      this.geometryAnnouncement = (this.geometryAnnouncement ?? Promise.resolve())
        .then(() => this.announceGeometry()).catch(() => {});
    }
  }
  async cipherFor(lease) {
    const id = JSON.stringify([lease.leaseId, lease.peerId, lease.controlSecret]);
    if (!this.cipherCache.has(id)) {
      // Never evict and recreate a cipher in this connection: doing so could
      // reuse a sending nonce or reopen a receiving replay window.
      if (this.cipherCache.size >= 128) throw new Error('Reconnect to renew control authority');
      this.cipherCache.set(id, createControlCipher(this.config, lease));
    }
    return this.cipherCache.get(id);
  }
  async announceGeometry() {
    if (!this.geometry || !this.signing || this.closed || this.channel?.readyState !== 'open' || this.channel.bufferedAmount > MAX_BUFFER) return;
    const body = geometryPayload(this.config, this.geometry, ++this.geometrySequence, this.authorityAvailable);
    const signature = toBase64(new Uint8Array(await crypto.subtle.sign('Ed25519', this.signing, utf8.encode(JSON.stringify(body)))));
    if (!this.closed && this.channel?.readyState === 'open') this.channel.send(JSON.stringify({ type: 'geometry', body, signature }));
  }
  async announceMetrics(metrics) {
    if (!this.host || !validMediaMetrics(metrics) || !this.signing || this.closed || this.channel?.readyState !== 'open' || this.channel.bufferedAmount > MAX_BUFFER) return;
    const body = { protocol: 'boss-app-share/1', type: 'metrics', sessionId: this.config.sessionId, generation: this.config.generation,
      windowId: this.config.windowId, keyEpoch: this.config.keyEpoch, sequence: ++this.metricsSequence, metrics };
    const signature = toBase64(new Uint8Array(await crypto.subtle.sign('Ed25519', this.signing, utf8.encode(JSON.stringify(body)))));
    if (!this.closed && this.channel?.readyState === 'open' && this.channel.bufferedAmount <= MAX_BUFFER) this.channel.send(JSON.stringify({ type: 'metrics', body, signature }));
  }
  async pollLease() {
    if (this.closed) return;
    try {
      const result = await this.media.call('controlPoll', { host_peer_id: this.config.peerId });
      if (this.closed) return;
      clearTimeout(this.authorityDeadline); this.authorityDeadline = null; this.pollFailures = 0;
      this.authorityAvailable = true; this.onAuthority(true);
      const lease = normalizeLease(result.lease);
      if (!lease || !Number.isFinite(lease.expiresAt) || lease.expiresAt <= Date.now()) this.clearLease();
      else if (this.lease?.leaseId !== lease.leaseId) {
        if (this.lease) this.clearLease();
        const cipher = await this.cipherFor(lease);
        if (this.closed) return;
        this.lease = lease; this.cipher = cipher; this.onLease({ leaseId: lease.leaseId, peerId: lease.peerId, expiresAt: lease.expiresAt });
      } else {
        this.lease.expiresAt = lease.expiresAt;
        this.onLease({ leaseId: lease.leaseId, peerId: lease.peerId, expiresAt: lease.expiresAt });
      }
      await this.announceGeometry();
    } catch (error) {
      if (this.closed) return;
      // No input or new window pixels may continue while authority is uncertain.
      this.clearLease(transientSharingFailure(error)); this.authorityAvailable = false; this.onAuthority(false);
      if (!transientSharingFailure(error)) {
        this.failAuthority(); return;
      }
      this.pollFailures++;
      // Keep publication alive while blacking video and disabling input. A fixed
      // deadline, rather than three failures, also bounds a hanging retry.
      if (!this.authorityDeadline) this.authorityDeadline = setTimeout(() => this.failAuthority(), SHARING_RECOVERY_MS);
      await this.announceGeometry();
    }
    if (!this.closed) this.timer = setTimeout(() => this.pollLease(), Math.min(5000, LEASE_POLL_MS * 2 ** Math.min(this.pollFailures, 3)));
  }
  failAuthority() {
    clearTimeout(this.authorityDeadline); clearTimeout(this.timer);
    this.authorityDeadline = null;
    if (!this.closed) { this.stop(); this.onError('Control permissions unavailable'); }
  }
  async takeControl() {
    const epoch = this.controlEpoch ?? 0;
    // A rapid blur/focus must not reacquire a lease whose release is still in flight.
    if (this.releasing) await this.releasing;
    if (this.closed || epoch !== (this.controlEpoch ?? 0)) return;
    if (this.host || this.closed || this.channel?.readyState !== 'open') throw new Error('Control channel unavailable');
    if (this.lease) return;
    if (this.acquiring) return this.acquiring;
    this.acquiring = this.acquireControl(epoch);
    try { await this.acquiring; } finally { this.acquiring = null; }
  }
  async acquireControl(epoch) {
    let lease, installed = false;
    try {
    const response = await this.media.call('controlAcquire');
    lease = normalizeLease(response.lease ?? response, this.config.peerId);
    if (!lease || !Number.isFinite(lease.expiresAt) || lease.expiresAt <= Date.now()) throw new Error('Invalid control lease');
    if (this.closed || epoch !== (this.controlEpoch ?? 0)) return;
    const allocation = await this.media.call('dataSubscribe', { can_reply: true, lease_id: lease.leaseId });
    if (allocation.channel_id !== this.channel.id) throw new Error('Control channel changed');
    const cipher = await this.cipherFor(lease);
    if (this.closed || epoch !== (this.controlEpoch ?? 0)) return;
    this.cipher = cipher; this.lease = lease;
    installed = true;
    this.onLease({ leaseId: lease.leaseId, peerId: lease.peerId, expiresAt: lease.expiresAt });
    this.expiry = setTimeout(() => this.releaseControl({ reason: 'expired' }), Math.max(0, lease.expiresAt - Date.now()));
    this.renewal = setTimeout(() => this.renewLease(lease), 10000);
    } finally { if (lease && !installed) await this.releaseLease(lease); }
  }
  async renewLease(lease, attempt = 0) {
    clearTimeout(this.renewal);
    if (this.closed || this.lease !== lease) return;
    try {
      const result = await this.media.call('controlRenew', { lease_id: lease.leaseId });
      if (this.closed || this.lease !== lease) return;
      const renewed = normalizeLease(result.lease ?? result, this.config.peerId);
      if (!renewed || renewed.leaseId !== lease.leaseId || renewed.peerId !== lease.peerId || renewed.controlSecret !== lease.controlSecret || !Number.isFinite(renewed.expiresAt) || renewed.expiresAt <= Date.now()) throw new Error('Control lease changed');
      lease.expiresAt = renewed.expiresAt;
      clearTimeout(this.expiry);
      this.expiry = setTimeout(() => this.releaseControl({ reason: 'expired' }), Math.max(0, lease.expiresAt - Date.now()));
      this.renewal = setTimeout(() => this.renewLease(lease), 10000);
    } catch (error) {
      if (this.closed || this.lease !== lease) return;
      const retryable = transientSharingFailure(error), delay = (attempt + 1) * 1000;
      if (retryable && attempt < 2 && lease.expiresAt - Date.now() > delay) {
        this.renewal = setTimeout(() => this.renewLease(lease, attempt + 1), delay);
      } else {
        await this.releaseControl({ reason: retryable ? 'renewal' : 'revoked', retryable });
      }
    }
  }
  send(event) {
    const lease = this.lease;
    if (!lease || this.host || this.closed || !this.geometry || Date.now() >= lease.expiresAt || this.channel?.readyState !== 'open') return false;
    // At most one pending pointer move; discrete input has a finite queue. If it
    // fills, drop the lease so a lost key-up cannot leave keys held on the host.
    if (event.type === 'pointer' && event.action === 'move' && this.pendingMove) { this.pendingMove.event = event; return true; }
    if ((this.pendingCount ?? 0) >= 64 || this.channel.bufferedAmount > MAX_BUFFER) { this.releaseControl(); return false; }
    const entry = { event }; if (event.type === 'pointer' && event.action === 'move') this.pendingMove = entry;
    this.pendingCount = (this.pendingCount ?? 0) + 1;
    this.chain = this.chain.then(async () => {
      if (this.pendingMove === entry) this.pendingMove = null;
      if (this.lease !== lease || this.closed || Date.now() >= lease.expiresAt) return;
      const payload = await this.cipher.encrypt(entry.event, this.geometry.geometryRevision);
      if (this.lease === lease && !this.closed && Date.now() < lease.expiresAt && this.channel.readyState === 'open' && this.channel.bufferedAmount <= MAX_BUFFER) this.channel.send(JSON.stringify(payload));
      else this.releaseControl();
    }).catch(() => this.releaseControl()).finally(() => { this.pendingCount--; });
    return true;
  }
  clearLease(suspended = false, details = {}) { clearTimeout(this.expiry); clearTimeout(this.renewal); this.lease = null; this.cipher = null; this.pendingMove = null; this.onLease(null, { suspended, ...details }); }
  releaseControl(details = {}) {
    this.controlEpoch = (this.controlEpoch ?? 0) + 1;
    const lease = this.lease; this.clearLease(false, details);
    const releasing = Promise.allSettled([this.releasing, lease ? this.releaseLease(lease) : undefined, this.acquiring]);
    this.releasing = releasing;
    releasing.then(() => { if (this.releasing === releasing) this.releasing = null; });
    return releasing;
  }
  async releaseLease(lease) {
    try { await this.media.call('controlRelease', { lease_id: lease.leaseId }); } catch (_) { /* Host expiration remains authoritative. */ }
  }
  stop() { this.closed = true; this.stopMetrics?.(); this.controlEpoch = (this.controlEpoch ?? 0) + 1; clearTimeout(this.timer); clearTimeout(this.authorityDeadline); this.clearLease(); this.channel?.close(); this.queue.length = 0; this.cipherCache.clear(); }
}

/** Map letterboxed video to normalized source coordinates; bars never receive input. */
export function videoPoint(clientX, clientY, rect, width, height) {
  if (!(rect.width > 0 && rect.height > 0 && width > 0 && height > 0)) return null;
  const scale = Math.min(rect.width / width, rect.height / height);
  const left = rect.left + (rect.width - width * scale) / 2;
  const top = rect.top + (rect.height - height * scale) / 2;
  const x = (clientX - left) / (width * scale), y = (clientY - top) / (height * scale);
  return Number.isFinite(x) && Number.isFinite(y) && x >= 0 && x <= 1 && y >= 0 && y <= 1 ? { x, y } : null;
}
