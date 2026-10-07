import { createMediaCipher } from './crypto.mjs';

// Audit instrumentation for REAL encoded frames. Production crypto is used
// unchanged; only the first real received packet is tampered/replayed locally.
self.onrtctransform = async ({ transformer }) => {
  try {
    const { config, direction } = transformer.options;
    const cipher = await createMediaCipher(config, direction);
    let audited = false;
    await transformer.readable.pipeThrough(new TransformStream({
      async transform(frame, controller) {
        if (direction === 'encrypt') {
          const before = frame.data.byteLength;
          frame.data = await cipher.encrypt(frame.data);
          if (!audited) { self.postMessage({ encrypted: frame.data.byteLength === before + 92 }); audited = true; }
        } else if (!audited) {
          const encrypted = frame.data.slice(0);
          const tampered = new Uint8Array(encrypted.slice(0)); tampered[tampered.length - 1] ^= 1;
          let tamperRejected = false, replayRejected = false;
          try { await cipher.decrypt(tampered); } catch (_) { tamperRejected = true; }
          frame.data = await cipher.decrypt(encrypted);
          try { await cipher.decrypt(encrypted); } catch (_) { replayRejected = true; }
          if (!tamperRejected || !replayRejected) throw new Error('Encoded frame authentication/replay guard failed');
          self.postMessage({ decrypted: true, tamperRejected, replayRejected }); audited = true;
        } else frame.data = await cipher.decrypt(frame.data);
        controller.enqueue(frame);
      },
    })).pipeTo(transformer.writable);
  } catch (error) { self.postMessage({ error: String(error.message ?? error).slice(0, 240) }); }
};
