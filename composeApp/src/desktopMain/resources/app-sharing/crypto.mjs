// boss-app-share/1. No terminal keys, nonces, or wire formats are reused.
const utf8 = new TextEncoder();
const MAX_FRAME = 16 * 1024 * 1024;
const MAGIC = new Uint8Array([66, 65, 83, 1]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function fromBase64(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value)) throw new Error('Invalid base64url');
  return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
}
export function toBase64(bytes) {
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 16384) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 16384));
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function join(...arrays) {
  const result = new Uint8Array(arrays.reduce((size, a) => size + a.length, 0));
  let at = 0;
  for (const a of arrays) { result.set(a, at); at += a.length; }
  return result;
}
function identity(config, domain, suffix = []) {
  for (const key of ['sessionId', 'generation', 'keyEpoch']) {
    if (!UUID.test(config[key])) throw new Error(`Invalid ${key}`);
  }
  if (typeof config.windowId !== 'string' || !config.windowId || config.windowId.length > 128) throw new Error('Invalid windowId');
  return utf8.encode(JSON.stringify(['boss-app-share/1', domain, config.sessionId, config.generation, config.windowId, config.keyEpoch, ...suffix]));
}
async function derive(secret, context) {
  const bytes = fromBase64(secret);
  if (bytes.length !== 32) throw new Error('Expected 256-bit key');
  const master = await crypto.subtle.importKey('raw', bytes, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: context, info: utf8.encode('AES-256-GCM') }, master, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}
function counterBytes(counter) {
  const bytes = new Uint8Array(8);
  new DataView(bytes.buffer).setBigUint64(0, counter);
  return bytes;
}
function nonce(counter) { return join(new Uint8Array(4), counterBytes(counter)); }

/** Finite reorder window. Authentication happens before commit, so forged high counters cannot lock out real frames. */
export class ReplayWindow {
  #maximum = 0n;
  #seen = new Set();
  accepts(counter) { return counter > 0n && counter > this.#maximum - 1024n && !this.#seen.has(counter); }
  commit(counter) {
    if (!this.accepts(counter)) throw new Error('Replayed frame');
    this.#seen.add(counter);
    if (counter > this.#maximum) this.#maximum = counter;
    for (const old of this.#seen) if (old <= this.#maximum - 1024n) this.#seen.delete(old);
  }
}

/**
 * VP8 only: retain its 3-byte delta / 10-byte key-frame uncompressed header for
 * RTP packetizers. Those bytes are authenticated; all pixel-bearing payload is
 * encrypted. Other codecs must never flow through this transform unchanged.
 * Each (root, generation, window, keyEpoch) has exactly ONE publisher lifetime.
 */
export async function createMediaCipher(config, direction) {
  if (!['encrypt', 'decrypt'].includes(direction)) throw new Error('Invalid cipher direction');
  const context = identity(config, 'media/VP8');
  const key = await derive(config.mediaRootKey, context);
  const signing = direction === 'encrypt'
    ? await crypto.subtle.importKey('pkcs8', fromBase64(config.hostPrivateKey), 'Ed25519', false, ['sign'])
    : await crypto.subtle.importKey('spki', fromBase64(config.hostPublicKey), 'Ed25519', false, ['verify']);
  let counter = 0n;
  const replay = new ReplayWindow();
  return {
    async encrypt(value) {
      if (direction !== 'encrypt') throw new Error('No signing authority');
      const bytes = new Uint8Array(value);
      const clearLength = (bytes[0] & 1) === 0 ? 10 : 3;
      if (bytes.length <= clearLength || bytes.length > MAX_FRAME) throw new Error('Invalid VP8 frame');
      if (++counter > 0xffffffffffffffffn) throw new Error('Media key exhausted');
      const prefix = bytes.slice(0, clearLength);
      const header = join(MAGIC, counterBytes(counter));
      const aad = join(context, prefix, header);
      const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce(counter), additionalData: aad }, key, bytes.subarray(clearLength)));
      const signature = new Uint8Array(await crypto.subtle.sign('Ed25519', signing, join(aad, cipher)));
      return join(prefix, header, cipher, signature).buffer;
    },
    async decrypt(value) {
      if (direction !== 'decrypt') throw new Error('No receive authority');
      const bytes = new Uint8Array(value);
      const clearLength = (bytes[0] & 1) === 0 ? 10 : 3;
      if (bytes.length <= clearLength + 12 + 16 + 64 || bytes.length > MAX_FRAME + 92) throw new Error('Invalid encrypted frame');
      const prefix = bytes.subarray(0, clearLength);
      const header = bytes.subarray(clearLength, clearLength + 12);
      if (!MAGIC.every((v, i) => header[i] === v)) throw new Error('Unencrypted media refused');
      const count = new DataView(header.buffer, header.byteOffset, header.byteLength).getBigUint64(4);
      if (!replay.accepts(count)) throw new Error('Replayed frame');
      const aad = join(context, prefix, header);
      const cipher = bytes.subarray(clearLength + 12, -64);
      if (!await crypto.subtle.verify('Ed25519', signing, bytes.subarray(-64), join(aad, cipher))) throw new Error('Unauthenticated media');
      const plain = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce(count), additionalData: aad }, key, cipher));
      replay.commit(count);
      return join(prefix, plain).buffer;
    },
  };
}

export async function createControlCipher(config, lease) {
  if (!UUID.test(lease.leaseId) || !UUID.test(lease.peerId)) throw new Error('Invalid control lease');
  const context = identity(config, 'control', [lease.leaseId, lease.peerId]);
  const key = await derive(lease.controlSecret, context);
  let sent = 0n;
  let received = 0n;
  return {
    async encrypt(event, geometryRevision) {
      if (!Number.isSafeInteger(geometryRevision) || geometryRevision < 0) throw new Error('Invalid geometry');
      const sequence = ++sent;
      if (sequence > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('Control key exhausted');
      const message = { protocol: 'boss-app-share/1', sessionId: config.sessionId, generation: config.generation, windowId: config.windowId, geometryRevision, leaseId: lease.leaseId, peerId: lease.peerId, sequence: Number(sequence), event };
      const bytes = utf8.encode(JSON.stringify(message));
      if (bytes.length > 8192 - 16) throw new Error('Control message too large');
      const payload = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce(sequence), additionalData: context }, key, bytes);
      return { sequence: Number(sequence), payload_b64: toBase64(new Uint8Array(payload)) };
    },
    async decrypt(command, geometryRevision, allowCoordinateFreeGeometry = false) {
      if (!Number.isSafeInteger(command.sequence) || command.sequence < 1) throw new Error('Invalid sequence');
      const sequence = BigInt(command.sequence);
      if (sequence <= received) throw new Error('Replayed input');
      const payload = fromBase64(command.payload_b64);
      if (payload.length > 8192) throw new Error('Control message too large');
      const bytes = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: nonce(sequence), additionalData: context }, key, payload);
      const message = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
      // Keys and explicit window recovery have no coordinates. Native dispatch still
      // verifies the current selected window, ownership, modal scope and action.
      const coordinateFree = message.event?.type === 'key' ||
        (message.event?.type === 'window' && ['restore', 'exit-fullscreen'].includes(message.event.action));
      const geometryMatches = message.geometryRevision === geometryRevision ||
        (allowCoordinateFreeGeometry && coordinateFree && Number.isSafeInteger(message.geometryRevision) && message.geometryRevision > 0 && message.geometryRevision <= geometryRevision);
      if (message.protocol !== 'boss-app-share/1' || message.sessionId !== config.sessionId || message.generation !== config.generation || message.windowId !== config.windowId || !geometryMatches || message.leaseId !== lease.leaseId || message.peerId !== lease.peerId || message.sequence !== command.sequence) throw new Error('Stale input context');
      received = sequence;
      return message;
    },
  };
}
