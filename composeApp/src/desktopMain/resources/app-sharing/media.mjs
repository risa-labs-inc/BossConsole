import { createMediaCipher } from './crypto.mjs';
import { SHARING_RECOVERY_MS, transientSharingFailure } from './recovery.mjs';

export function encryptionSupport(env = globalThis) {
  return !!(env.crypto?.subtle && env.RTCPeerConnection &&
    (env.RTCRtpScriptTransform || (env.RTCRtpSender?.prototype?.createEncodedStreams && env.RTCRtpReceiver?.prototype?.createEncodedStreams)));
}

export function vp8Only(transceiver, env = globalThis) {
  const codecs = env.RTCRtpSender.getCapabilities('video')?.codecs?.filter(c => c.mimeType.toLowerCase() === 'video/vp8');
  if (!codecs?.length || !transceiver.setCodecPreferences) throw new Error('Encrypted VP8 is unavailable');
  transceiver.setCodecPreferences(codecs);
}

/** One desktop encoding starts at native scale before the first RTP frame. */
export function desktopVideoEncoding(frameRate = 30) {
  return { maxBitrate: 8_000_000, maxFramerate: frameRate, scaleResolutionDownBy: 1 };
}

/** Chromium's optional initial estimate, not a minimum: congestion control can immediately reduce it.
 * Only the negotiated VP8 media section is touched; ICE, fingerprints and other media stay identical.
 * libwebrtc consumes this codec parameter from the remote description (AppRTC uses the same path).
 */
export function desktopStartupDescription(description, mid) {
  if (description?.type !== 'answer' || typeof description.sdp !== 'string' ||
      description.sdp.length > 1_000_000 || typeof mid !== 'string') return description;
  const newline = description.sdp.includes('\r\n') ? '\r\n' : '\n';
  const lines = description.sdp.split(newline);
  let start = -1, end = lines.length;
  for (let i = 0; i < lines.length;) {
    if (!lines[i].startsWith('m=')) { i++; continue; }
    let next = i + 1;
    while (next < lines.length && !lines[next].startsWith('m=')) next++;
    if (lines[i].startsWith('m=video ') && lines[i].split(/\s+/)[1] !== '0' &&
        lines.slice(i + 1, next).includes(`a=mid:${mid}`)) { start = i; end = next; break; }
    i = next;
  }
  if (start < 0) return description;
  const payloads = new Set(lines[start].split(/\s+/).slice(3));
  let codecLine = -1, payload;
  for (let i = start + 1; i < end; i++) {
    const codec = /^a=rtpmap:(\d+) VP8\/90000$/i.exec(lines[i]);
    if (codec && payloads.has(codec[1])) { codecLine = i; payload = codec[1]; break; }
  }
  if (codecLine < 0) return description;
  const prefix = `a=fmtp:${payload} `;
  const fmtp = lines.findIndex((line, i) => i > start && i < end && line.startsWith(prefix));
  const parameters = fmtp < 0 ? [] : lines[fmtp].slice(prefix.length).split(';').map(value => value.trim());
  if (parameters.some(value => /^x-google-start-bitrate\s*=/i.test(value))) return description;
  const maximum = parameters.map(value => /^x-google-max-bitrate\s*=\s*(\d+)$/i.exec(value)).find(Boolean);
  const initial = maximum ? Math.min(4_000, Number(maximum[1])) : 4_000;
  if (!(initial > 0)) return description;
  parameters.push(`x-google-start-bitrate=${initial}`);
  if (fmtp >= 0) lines[fmtp] = prefix + parameters.join('; ');
  else lines.splice(codecLine + 1, 0, prefix + parameters.join('; '));
  return { type: description.type, sdp: lines.join(newline) };
}

/** Keep resolution adaptive under CPU/network pressure while preferring smooth motion. */
export async function preferSmoothVideo(sender, frameRate = 30) {
  if (!sender.getParameters || !sender.setParameters) return false;
  try {
    const parameters = sender.getParameters();
    parameters.degradationPreference = 'maintain-framerate';
    for (const encoding of parameters.encodings ?? []) {
      encoding.maxFramerate = frameRate;
      // Give desktop text and motion headroom; congestion control still chooses the actual bitrate.
      encoding.maxBitrate ??= 8_000_000;
    }
    await sender.setParameters(parameters);
    return true;
  } catch (_) {
    // Older engines retain the motion hint; optional tuning must not disable encrypted sharing.
    return false;
  }
}

/** Hysteresis avoids rate oscillation; idle or reset counters are not congestion. */
export class AdaptiveFrameRate {
  constructor() { this.frameRate = 30; this.previous = null; this.healthy = 0; this.pressure = 0; }
  sample(report) {
    const entry = report && [...report.values()].find(row =>
      row.type === 'outbound-rtp' && (row.kind ?? row.mediaType) === 'video' && !row.isRemote);
    const previous = this.previous; this.previous = entry;
    const elapsed = (entry?.timestamp - previous?.timestamp) / 1000;
    const frames = entry?.framesEncoded - previous?.framesEncoded;
    const encode = entry?.totalEncodeTime - previous?.totalEncodeTime;
    if (entry?.id !== previous?.id || !(elapsed > 0 && elapsed <= 5 && frames > 0 && encode >= 0)) {
      this.healthy = 0; this.pressure = 0; return this.frameRate;
    }
    const encodeMs = encode * 1000 / frames;
    // WebRTC already reduces resolution for bandwidth pressure. Reducing native FPS
    // as well would sacrifice smoothness twice, even when the encoder keeps pace.
    const pressure = entry.qualityLimitationReason === 'cpu' || encodeMs > 1000 / this.frameRate * 0.8;
    const keepingPace = frames / elapsed >= this.frameRate * 0.9;
    const healthy = ['none', 'bandwidth'].includes(entry.qualityLimitationReason) && keepingPace && encodeMs < 10;
    this.pressure = pressure ? this.pressure + 1 : 0;
    this.healthy = healthy ? this.healthy + 1 : 0;
    if (this.pressure >= 2) { this.frameRate = 30; this.healthy = 0; }
    else if (this.healthy >= 10) { this.frameRate = 60; this.pressure = 0; }
    return this.frameRate;
  }
}

export async function attachEncryption(endpoint, config, direction, fail, env = globalThis, observe = null) {
  // Import keys before touching the media sender so an unsupported algorithm is fatal.
  const cipher = await createMediaCipher(config, direction);
  if (env.RTCRtpScriptTransform) {
    const worker = new env.Worker(new URL('./encoded-worker.mjs', import.meta.url), { type: 'module' });
    worker.onmessage = ({ data }) => {
      if (data.type === 'fatal') fail(new Error(data.code));
      else if (data.type === 'diagnostic') observe?.(data);
    };
    worker.onerror = () => fail(new Error('Encrypted media worker failed'));
    endpoint.transform = new env.RTCRtpScriptTransform(worker, { config, direction, diagnostics: !!observe });
    return () => worker.terminate();
  }
  if (!endpoint.createEncodedStreams) throw new Error('Encoded transforms unavailable');
  const { readable, writable } = endpoint.createEncodedStreams();
  const abort = new AbortController();
  let received = 0, processed = 0, dropped = 0;
  readable.pipeThrough(new TransformStream({ async transform(frame, controller) {
    received++;
    try { frame.data = await cipher[direction](frame.data); controller.enqueue(frame); processed++; }
    catch (error) {
      dropped++;
      if (direction === 'encrypt') fail(error);
      observe?.({ type: 'diagnostic', direction, received, processed, dropped, error: String(error.message).slice(0, 120) });
      /* Reject ciphertext, never pass it through. */
    }
    if (received === 1 || received % 60 === 0) observe?.({ type: 'diagnostic', direction, received, processed, dropped });
  } })).pipeTo(writable, { signal: abort.signal }).catch(error => { if (!abort.signal.aborted) fail(error); });
  return () => abort.abort();
}

/**
 * Remote offers create their own receivers: addTransceiver-created receivers
 * are not reused. The peer MUST require encodedInsertableStreams, so incoming
 * frames cannot bypass decryption while the real offered receiver is bound.
 */
export async function acceptEncryptedVideoOffer(peer, description, mid, config, fail, env = globalThis, encrypt = attachEncryption) {
  if (description?.type !== 'offer' || typeof mid !== 'string' || !mid) throw new Error('Invalid SFU subscription offer');
  await peer.setRemoteDescription(description);
  const transceivers = peer.getTransceivers();
  if (transceivers.length !== 1) throw new Error('Unexpected SFU media sections');
  const transceiver = transceivers[0];
  if (transceiver.mid !== mid || transceiver.receiver.track.kind !== 'video') throw new Error('Unexpected SFU media section');
  vp8Only(transceiver, env);
  const cleanup = await encrypt(transceiver.receiver, config, 'decrypt', fail, env);
  return { track: transceiver.receiver.track, cleanup };
}

export function waitForIce(peer, signal, timeoutMs = 10000) {
  if (signal?.aborted) return Promise.reject(new Error('Sharing stopped'));
  if (peer.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve, reject) => {
    const cleanup = () => { clearTimeout(timer); peer.removeEventListener('icegatheringstatechange', changed); signal?.removeEventListener('abort', stopped); };
    const changed = () => { if (peer.iceGatheringState === 'complete') { cleanup(); resolve(); } };
    const stopped = () => { cleanup(); reject(new Error('Sharing stopped')); };
    const timer = setTimeout(() => { cleanup(); reject(new Error('ICE gathering timed out')); }, timeoutMs);
    peer.addEventListener('icegatheringstatechange', changed);
    signal?.addEventListener('abort', stopped, { once: true });
    changed();
  });
}

export function waitForConnected(peer, signal, timeoutMs = 15000) {
  if (signal?.aborted) return Promise.reject(new Error('Sharing stopped'));
  if (peer.connectionState === 'connected') return Promise.resolve();
  return new Promise((resolve, reject) => {
    const cleanup = () => { clearTimeout(timer); peer.removeEventListener('connectionstatechange', changed); signal?.removeEventListener('abort', stopped); };
    const changed = () => {
      if (peer.connectionState === 'connected') { cleanup(); resolve(); }
      else if (['failed', 'closed'].includes(peer.connectionState)) { cleanup(); reject(new Error('Media connection failed')); }
    };
    const stopped = () => { cleanup(); reject(new Error('Sharing stopped')); };
    const timer = setTimeout(() => { cleanup(); reject(new Error('Media connection timed out')); }, timeoutMs);
    peer.addEventListener('connectionstatechange', changed);
    signal?.addEventListener('abort', stopped, { once: true });
    changed();
  });
}

/** One SFU peer, one publication for a selected window, irrespective of viewers. */
function mediaFailureReason(error) {
  if (error?.message === 'Media connection timed out') return 'media_connection_timeout';
  if (error?.message === 'ICE gathering timed out') return 'media_ice_timeout';
  if (error?.message === 'Media connection failed') return 'media_connection_failed';
  return 'media_initialization_failed';
}

export class WindowMediaPeer {
  constructor({ config, request, onState = () => {}, env = globalThis, encrypt = attachEncryption, reconnectGraceMs = SHARING_RECOVERY_MS }) {
    this.config = config; this.request = request; this.onState = onState; this.env = env; this.encrypt = encrypt;
    this.abort = new AbortController(); this.cleanup = []; this.closed = false; this.started = false;
    this.reconnectGraceMs = reconnectGraceMs;
  }
  fields(action, extra = {}) {
    return { action, session_id: this.config.sessionId, generation: this.config.generation, peer_id: this.config.peerId, window_id: this.config.windowId, ...extra };
  }
  async call(action, extra) {
    if (this.closed && action !== 'controlRelease') throw new Error('Sharing stopped');
    const result = await this.request(this.fields(action, extra));
    // A late lease grant must reach the control owner so teardown can release
    // it. This exception never permits starting a new acquisition after stop.
    if (this.closed && action !== 'controlAcquire' && action !== 'controlRelease') throw new Error('Sharing stopped');
    return result;
  }
  async callWhenPublished(action, extra, { timeoutMs = 30000, retryMs = 500 } = {}) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      try { return await this.call(action, extra); }
      catch (error) {
        if (error.code !== 'publication_pending' || Date.now() >= deadline || this.closed) throw error;
        await new Promise((resolve, reject) => {
          const stopped = () => { clearTimeout(timer); reject(new Error('Sharing stopped')); };
          const timer = setTimeout(() => { this.abort.signal.removeEventListener('abort', stopped); resolve(); }, Math.min(retryMs, Math.max(0, deadline - Date.now())));
          this.abort.signal.addEventListener('abort', stopped, { once: true });
          if (this.abort.signal.aborted) stopped();
        });
      }
    }
  }
  async connect() {
    if (this.started) throw new Error('Window already connected');
    this.started = true;
    if (!encryptionSupport(this.env)) throw new Error('This browser cannot receive encrypted app sharing');
    const created = await this.call('mediaCreate');
    this.peer = new this.env.RTCPeerConnection({ iceServers: created.ice_servers ?? [], encodedInsertableStreams: true });
    this.peer.onconnectionstatechange = () => {
      const state = this.peer.connectionState;
      this.onState(state === 'connected' && this.heartbeatRecovering ? 'recovering' : state, state === 'failed' ? 'media_connection_failed' : undefined);
      if (state === 'connected') { clearTimeout(this.disconnectTimer); this.disconnectTimer = null; }
      else if (state === 'disconnected' && !this.disconnectTimer) {
        this.disconnectTimer = setTimeout(() => {
          this.disconnectTimer = null;
          if (this.peer.connectionState !== 'connected') this.fail('media_connection_timeout');
        }, this.reconnectGraceMs);
      }
      if (['failed', 'closed'].includes(state)) this.stop();
    };
    return this.peer;
  }
  async publish(track, onFrameRate) {
    this.track = track;
    try {
      const peer = await this.connect();
      // Transform installation precedes both attaching the native capture and SDP.
      const transceiver = peer.addTransceiver('video', { direction: 'sendonly', sendEncodings: [desktopVideoEncoding()] });
      vp8Only(transceiver, this.env);
      this.cleanup.push(await this.encrypt(transceiver.sender, this.config, 'encrypt', () => this.fail('media_encryption_failed'), this.env));
      if (this.closed) throw new Error('Sharing stopped');
      await transceiver.sender.replaceTrack(track);
      await peer.setLocalDescription(await peer.createOffer());
      await waitForIce(peer, this.abort.signal);
      const response = await this.call('mediaPublish', { mid: transceiver.mid, session_description: peer.localDescription.toJSON?.() ?? peer.localDescription });
      if (response.session_description?.type !== 'answer') throw new Error('Invalid SFU publish answer');
      const startup = desktopStartupDescription(response.session_description, transceiver.mid);
      try { await peer.setRemoteDescription(startup); }
      catch (error) {
        // The hint is optional; older engines can negotiate the original, equally encrypted answer.
        if (startup === response.session_description || this.closed || peer.signalingState !== 'have-local-offer') throw error;
        await peer.setRemoteDescription(response.session_description);
      }
      await this.waitConnected();
      await preferSmoothVideo(transceiver.sender);
      if (onFrameRate) {
        const adaptive = new AdaptiveFrameRate();
        let applied = 30;
        this.updateCaptureRate = async report => {
          if (this.closed) return;
          const next = adaptive.sample(report);
          if (next !== applied && await preferSmoothVideo(transceiver.sender, next) && !this.closed) {
            applied = next; onFrameRate(next);
          }
        };
      }
      if (this.closed) throw new Error('Sharing stopped');
      this.onState('published');
    } catch (error) { this.fail(mediaFailureReason(error)); throw error; }
  }
  async subscribe(onTrack) {
    try {
      const peer = await this.connect();
      const response = await this.callWhenPublished('mediaSubscribe');
      const received = await acceptEncryptedVideoOffer(peer, response.session_description, response.mid, this.config, () => this.fail('media_encryption_failed'), this.env, this.encrypt);
      if (this.closed) { received.cleanup(); throw new Error('Sharing stopped'); }
      this.cleanup.push(received.cleanup);
      this.track = received.track;
      await peer.setLocalDescription(await peer.createAnswer());
      await waitForIce(peer, this.abort.signal);
      await this.call('mediaRenegotiate', { session_description: peer.localDescription.toJSON?.() ?? peer.localDescription });
      onTrack(received.track);
      await this.waitConnected();
      this.onState('subscribed');
      this.heartbeat = setTimeout(() => this.keepAlive(), 60000);
    } catch (error) { this.fail(mediaFailureReason(error)); throw error; }
  }
  async answer(description) {
    if (description?.type !== 'offer') throw new Error('Invalid SFU data offer');
    await this.peer.setRemoteDescription(description);
    await this.peer.setLocalDescription(await this.peer.createAnswer());
    await waitForIce(this.peer, this.abort.signal);
    await this.call('mediaRenegotiate', { session_description: this.peer.localDescription.toJSON?.() ?? this.peer.localDescription });
  }
  waitConnected() { return waitForConnected(this.peer, this.abort.signal); }
  async keepAlive() {
    if (this.closed) return;
    try {
      await this.call('peerHeartbeat');
      if (!this.closed) {
        clearTimeout(this.heartbeatDeadline); this.heartbeatDeadline = null;
        if (this.heartbeatRecovering) { this.heartbeatRecovering = false; if (this.track) this.track.enabled = true; this.onState('connected'); }
        this.heartbeat = setTimeout(() => this.keepAlive(), 60000);
      }
    } catch (error) {
      if (this.closed) return;
      if (!transientSharingFailure(error)) { this.fail('peer_authority_denied'); return; }
      if (!this.heartbeatRecovering) {
        this.heartbeatRecovering = true; if (this.track) this.track.enabled = false;
        this.onState('recovering');
        this.heartbeatDeadline = setTimeout(() => this.fail('peer_authority_timeout'), SHARING_RECOVERY_MS);
      }
      this.heartbeat = setTimeout(() => this.keepAlive(), 2000);
    }
  }
  fail(reason) {
    if (!this.closed) this.onState('failed', reason);
    return this.stop();
  }
  stop() {
    if (this.closed) return this.closeDone;
    this.closed = true;
    // Start cleanup before callbacks can re-enter stop(). The bridge bounds
    // this request to 15s; callers keep it open until the request settles.
    try { this.closeDone = Promise.resolve(this.started ? this.request(this.fields('mediaClose')) : undefined).catch(() => {}); }
    catch (_) { this.closeDone = Promise.resolve(); }
    clearTimeout(this.heartbeat);
    clearTimeout(this.heartbeatDeadline); this.heartbeatDeadline = null;
    clearTimeout(this.disconnectTimer); this.disconnectTimer = null;
    this.abort.abort();
    this.track?.stop();
    for (const cleanup of this.cleanup.splice(0)) cleanup();
    this.peer?.close();
    this.onState('stopped');
    return this.closeDone;
  }
}

/** Native snapshots are latest-only; a slow decode never creates an unbounded queue. */
export class NativeFrameCanvas {
  constructor(canvas, onGeometry = () => {}, decode = blob => globalThis.createImageBitmap(blob)) {
    this.canvas = canvas; this.onGeometry = onGeometry; this.decode = decode; this.closed = false;
    this.canvas.width = 2; this.canvas.height = 2;
    this.stream = canvas.captureStream(0); this.track = this.stream.getVideoTracks()[0];
    if (!this.track?.requestFrame) throw new Error('Native capture streaming unsupported');
    this.track.contentHint = 'motion';
    // Cloudflare retires publications after 30s without RTP. Native capture
    // remains demand-driven, so repeat the canvas (initially blank) while idle.
    // requestFrame needs a canvas paint to produce a fresh captured frame.
    this.keepAlive = setInterval(() => {
      if (this.closed) return;
      this.canvas.getContext('2d', { alpha: false }).drawImage(this.canvas, 0, 0);
      this.track.requestFrame();
    }, 5000);
    this.keepAlive.unref?.();
  }
  frame(frame) {
    if (this.closed) return;
    if (!Number.isSafeInteger(frame.width) || !Number.isSafeInteger(frame.height) || frame.width < 1 || frame.height < 1 || frame.width > 8192 || frame.height > 8192 || frame.width * frame.height > 16777216 || !Number.isSafeInteger(frame.geometryRevision) || frame.geometryRevision < 0 || typeof frame.png !== 'string' || frame.png.length > 24 * 1024 * 1024) throw new Error('Invalid native snapshot');
    this.pending = frame;
    if (!this.decoding) this.decoding = this.drain().finally(() => { this.decoding = null; });
    return this.decoding;
  }
  async drain() {
    while (!this.closed && this.pending) {
      const frame = this.pending; this.pending = null;
      const bytes = Uint8Array.from(atob(frame.png), c => c.charCodeAt(0));
      const image = await this.decode(new Blob([bytes], { type: 'image/png' }));
      try {
        if (this.closed) return;
        if (image.width !== frame.width || image.height !== frame.height) throw new Error('Native geometry mismatch');
        this.canvas.width = frame.width; this.canvas.height = frame.height;
        this.canvas.getContext('2d', { alpha: false }).drawImage(image, 0, 0);
        this.onGeometry({ width: frame.width, height: frame.height, geometryRevision: frame.geometryRevision });
        this.track.requestFrame();
      } finally { image.close(); }
    }
  }
  stop() { this.closed = true; clearInterval(this.keepAlive); this.pending = null; this.stream.getTracks().forEach(track => track.stop()); this.canvas.width = 2; this.canvas.height = 2; }
}

/** Upload BGRA once; the fragment shader swaps channels without a CPU pixel loop. */
export class RawFramePainter {
  constructor(canvas, env = globalThis) {
    this.canvas = canvas; this.env = env;
    this.gl = canvas.getContext('webgl', { alpha: false, antialias: false, depth: false, stencil: false,
      premultipliedAlpha: false, preserveDrawingBuffer: false, failIfMajorPerformanceCaveat: true });
    if (!this.gl) {
      this.context = canvas.getContext('2d', { alpha: false });
      if (!this.context) throw new Error('Native stream canvas unavailable');
      return;
    }
    const gl = this.gl;
    this.maxTextureSize = gl.getParameter(gl.MAX_TEXTURE_SIZE);
    const shader = (type, source) => {
      const result = gl.createShader(type);
      gl.shaderSource(result, source); gl.compileShader(result);
      if (!gl.getShaderParameter(result, gl.COMPILE_STATUS)) {
        gl.deleteShader(result); throw new Error('Native stream shader unavailable');
      }
      return result;
    };
    let vertex, fragment;
    try {
      vertex = shader(gl.VERTEX_SHADER, 'attribute vec2 position; varying vec2 uv; void main(){ gl_Position=vec4(position,0.0,1.0); uv=vec2((position.x+1.0)*0.5,(1.0-position.y)*0.5); }');
      fragment = shader(gl.FRAGMENT_SHADER, 'precision mediump float; varying vec2 uv; uniform sampler2D pixels; void main(){ gl_FragColor=vec4(texture2D(pixels,uv).bgr,1.0); }');
      this.program = gl.createProgram(); gl.attachShader(this.program, vertex); gl.attachShader(this.program, fragment);
      gl.linkProgram(this.program);
      if (!gl.getProgramParameter(this.program, gl.LINK_STATUS)) throw new Error('Native stream shader unavailable');
      gl.useProgram(this.program);
      this.buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, this.buffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      const position = gl.getAttribLocation(this.program, 'position');
      gl.enableVertexAttribArray(position); gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
      this.texture = gl.createTexture(); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, this.texture);
      gl.uniform1i(gl.getUniformLocation(this.program, 'pixels'), 0);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.disable(gl.DITHER); gl.disable(gl.BLEND); gl.clearColor(0, 0, 0, 1);
    } catch (error) { this.stop(); throw error; }
    finally { if (vertex) gl.deleteShader(vertex); if (fragment) gl.deleteShader(fragment); }
  }
  paint(bytes, width, height) {
    if (this.canvas.width !== width || this.canvas.height !== height) { this.canvas.width = width; this.canvas.height = height; }
    if (this.gl) {
      const gl = this.gl;
      if (gl.isContextLost()) throw new Error('Native stream graphics context lost');
      if (width > this.maxTextureSize || height > this.maxTextureSize) throw new Error('Native stream texture too large');
      const data = new Uint8Array(bytes);
      if (this.width !== width || this.height !== height) {
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, data);
      } else {
        gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, data);
      }
      this.width = width; this.height = height; this.redraw();
    } else {
      const pixels = new Uint32Array(bytes);
      for (let i = 0; i < pixels.length; i++) {
        const value = pixels[i]; pixels[i] = (value & 0xff00ff00) | ((value & 0xff) << 16) | ((value >>> 16) & 0xff);
      }
      this.context.putImageData(new this.env.ImageData(new Uint8ClampedArray(bytes), width, height), 0, 0);
    }
  }
  redraw() {
    if (this.gl) {
      const gl = this.gl;
      if (gl.isContextLost()) throw new Error('Native stream graphics context lost');
      gl.viewport(0, 0, this.canvas.width, this.canvas.height);
      if (this.width) gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); else gl.clear(gl.COLOR_BUFFER_BIT);
      gl.flush();
    } else this.context.drawImage(this.canvas, 0, 0);
  }
  clear() {
    this.canvas.width = 2; this.canvas.height = 2;
    if (this.gl) {
      // Retire the previous window texture as soon as authority/demand clears it.
      this.gl.texImage2D(this.gl.TEXTURE_2D, 0, this.gl.RGBA, 2, 2, 0, this.gl.RGBA, this.gl.UNSIGNED_BYTE, null);
      this.width = this.height = 0;
    }
    this.redraw();
  }
  stop() {
    if (!this.gl) return;
    this.gl.deleteTexture(this.texture); this.gl.deleteBuffer(this.buffer); this.gl.deleteProgram(this.program);
    this.texture = this.buffer = this.program = null;
  }
}

/** One bounded VideoFrame write feeds WebRTC directly, avoiding canvas upload and capture readback. */
export class RawVideoFrameTrack {
  static create(env = globalThis) {
    if (!env.MediaStreamTrackGenerator || !env.VideoFrame || !env.MediaStream) return null;
    let track;
    try {
      // Engines can expose the API while rejecting this pixel format.
      const probe = new env.VideoFrame(new Uint8Array(16), { format: 'BGRA', codedWidth: 2, codedHeight: 2, timestamp: 0 });
      probe.close();
      let preferredFormat = 'BGRA';
      try {
        const yuv = new env.VideoFrame(new Uint8Array(6), { format: 'NV12', codedWidth: 2, codedHeight: 2, timestamp: 0,
          colorSpace: { primaries: 'bt709', transfer: 'bt709', matrix: 'bt709', fullRange: false } });
        yuv.close(); preferredFormat = 'NV12';
      } catch (_) { /* Keep BGRA on engines without NV12 input. */ }
      track = new env.MediaStreamTrackGenerator({ kind: 'video' });
      return new RawVideoFrameTrack(track, env, preferredFormat);
    } catch (_) { track?.stop(); return null; }
  }
  constructor(track, env, preferredFormat = 'BGRA') {
    this.preferredFormat = preferredFormat;
    this.env = env; this.track = track; this.writer = track.writable.getWriter();
    this.stream = new env.MediaStream([track]); this.closed = false; this.width = this.height = 2;
    this.track.contentHint = 'motion'; this.timestamp = -1;
  }
  supportsFormat(format) { return format === 'BGRA' || (format === 'NV12' && this.preferredFormat === 'NV12'); }
  nextTimestamp() { return this.timestamp = Math.max(this.timestamp + 1, Math.floor(this.env.performance.now() * 1000)); }
  async paint(bytes, width, height, format = 'BGRA') {
    if (this.closed) return;
    if (!['BGRA', 'NV12'].includes(format) || (format === 'NV12' &&
        (this.preferredFormat !== 'NV12' || width % 2 || height % 2))) throw new Error('Unsupported native pixel format');
    const frame = new this.env.VideoFrame(bytes, { format, codedWidth: width, codedHeight: height, timestamp: this.nextTimestamp(),
      ...(format === 'NV12' ? { colorSpace: { primaries: 'bt709', transfer: 'bt709', matrix: 'bt709', fullRange: false } } : {}) });
    try {
      await this.writer.ready;
      if (this.closed) return;
      this.retained?.close(); this.retained = frame.clone(); this.width = width; this.height = height;
      await this.writer.write(frame);
    } finally { frame.close(); }
  }
  async clear() {
    this.retained?.close(); this.retained = null;
    this.width = this.height = 2;
    const blank = new Uint8Array(16); for (let i = 3; i < blank.length; i += 4) blank[i] = 255;
    await this.paint(blank, 2, 2);
  }
  async redraw() {
    if (this.closed) return;
    if (!this.retained) return this.clear();
    const frame = new this.env.VideoFrame(this.retained, { timestamp: this.nextTimestamp() });
    try { await this.writer.ready; if (!this.closed) await this.writer.write(frame); }
    finally { frame.close(); }
  }
  stop() {
    if (this.closed) return;
    this.closed = true; this.retained?.close(); this.retained = null; this.track.stop();
    this.writer.abort().catch(() => {});
  }
}

/** Continuous native pixels arrive as binary BGRA/NV12, never PNG/Base64 snapshots. */
export class NativeRawFrameCanvas {
  constructor(canvas, config, onGeometry = () => {}, onError = () => {}, env = globalThis) {
    this.canvas = canvas; this.config = config; this.onGeometry = onGeometry; this.onError = onError; this.env = env;
    this.abort = new AbortController(); this.sequence = -1; this.closed = false; this.frameRate = 30;
    this.empty = true; this.retries = 0;
    canvas.width = 2; canvas.height = 2;
    this.direct = RawVideoFrameTrack.create(env);
    if (this.direct) {
      this.stream = this.direct.stream; this.track = this.direct.track;
    } else {
      this.painter = new RawFramePainter(canvas, env);
      this.stream = canvas.captureStream(0); this.track = this.stream.getVideoTracks()[0];
      if (!this.track?.requestFrame) throw new Error('Native capture streaming unsupported');
    }
    this.track.contentHint = 'motion';
    this.lastPaint = 0;
    this.running = this.poll().catch(error => { if (!this.closed) onError(error); });
  }
  async poll() {
    while (!this.closed) {
      const started = this.env.performance.now();
      const response = await this.env.fetch(this.config.rawFrameUrl, {
        method: 'POST', cache: 'no-store', signal: this.abort.signal,
        headers: { 'X-Boss-App-Token': this.config.rpcToken, 'X-Boss-App-After': String(this.sequence), 'X-Boss-App-Frame-Rate': String(this.frameRate),
          'X-Boss-App-Pixel-Format': this.direct?.preferredFormat ?? 'BGRA', 'X-Boss-App-Wait': 'true' },
      });
      if (this.closed) return;
      const waiting = response.headers.get('X-Boss-App-Wait') === 'true';
      if (waiting && [409, 429].includes(response.status)) {
        if (this.retries >= 5) throw new Error('Native stream unavailable');
        await this.wait(Math.min(800, 100 * 2 ** this.retries++));
        continue;
      }
      if (![200, 204].includes(response.status)) throw new Error('Native stream unavailable');
      this.retries = 0;
      const sequenceHeader = response.headers.get('X-Boss-App-Sequence');
      const sequence = Number(sequenceHeader);
      if (sequenceHeader === null || !/^\d+$/.test(sequenceHeader) || !Number.isSafeInteger(sequence) || sequence < 0) throw new Error('Invalid native stream sequence');
      if (response.status === 200) {
        const width = Number(response.headers.get('X-Boss-App-Width'));
        const height = Number(response.headers.get('X-Boss-App-Height'));
        const geometryRevision = Number(response.headers.get('X-Boss-App-Revision'));
        if (!Number.isSafeInteger(width) || !Number.isSafeInteger(height) || width < 1 || height < 1 ||
            width > 8192 || height > 8192 || width * height > 4194304 ||
            !Number.isSafeInteger(geometryRevision) || geometryRevision < 0) throw new Error('Invalid native stream geometry');
        const format = response.headers.get('X-Boss-App-Pixel-Format') ?? 'BGRA';
        if (!['BGRA', 'NV12'].includes(format) || (format === 'NV12' &&
            (this.direct?.preferredFormat !== 'NV12' || width % 2 || height % 2))) throw new Error('Unsupported native pixel format');
        const bytes = await response.arrayBuffer();
        if (this.closed) return;
        if (bytes.byteLength !== width * height * (format === 'NV12' ? 1.5 : 4)) throw new Error('Invalid native stream pixels');
        await this.paint(bytes, width, height, format);
        if (this.closed) return;
        this.empty = false;
        this.onGeometry({ width, height, geometryRevision });
        this.lastPaint = this.env.performance.now();
      } else if (response.status === 204) {
        if (response.headers.get('X-Boss-App-Empty') === 'true' && !this.empty) {
          if (this.direct) await this.direct.clear();
          else { this.painter.clear(); this.track.requestFrame(); }
          this.empty = true; this.lastPaint = this.env.performance.now();
        }
        // Static content and no-viewer demand still need RTP to keep the SFU publication alive.
        if (this.env.performance.now() - this.lastPaint >= 5000) {
          if (this.direct) await this.direct.redraw();
          else { this.painter.redraw(); this.track.requestFrame(); }
          this.lastPaint = this.env.performance.now();
        }
      } else throw new Error('Native stream unavailable');
      if (this.closed) return;
      this.sequence = sequence;
      // New servers wait for a fresh sequence; old servers still need client pacing.
      if (!waiting) await this.wait(Math.max(0, 1000 / this.frameRate - (this.env.performance.now() - started)));
    }
  }
  wait(delay) {
    if (this.closed) return Promise.resolve();
    return new Promise(resolve => {
      this.wake = () => { this.wake = null; this.timer = null; resolve(); };
      this.timer = (this.env.setTimeout ?? setTimeout)(this.wake, delay);
    });
  }
  async paint(bytes, width, height, format = 'BGRA') {
    if (this.direct) await this.direct.paint(bytes, width, height, format);
    else { this.painter.paint(bytes, width, height); this.track.requestFrame(); }
  }
  frame() { throw new Error('PNG frames are disabled for continuous capture'); }
  stop() {
    if (this.closed) return;
    this.closed = true; this.abort.abort(); (this.env.clearTimeout ?? clearTimeout)(this.timer); this.wake?.();
    this.direct?.stop(); this.stream.getTracks().forEach(track => track.stop()); this.painter?.stop(); this.canvas.width = 2; this.canvas.height = 2;
  }
}
