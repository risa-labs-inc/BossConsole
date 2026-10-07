import { createMediaCipher } from './crypto.mjs';

// A worker owns each transform's cipher/counter. Never send a raw frame on failure.
self.onrtctransform = async ({ transformer }) => {
  try {
    const { config, direction, diagnostics } = transformer.options;
    const cipher = await createMediaCipher(config, direction);
    let received = 0, processed = 0, dropped = 0;
    if (diagnostics) self.postMessage({ type: 'diagnostic', direction, ready: true, received, processed, dropped });
    await transformer.readable.pipeThrough(new TransformStream({
      async transform(frame, controller) {
        received++;
        try {
          frame.data = await cipher[direction](frame.data);
          controller.enqueue(frame);
          processed++;
        } catch (error) {
          dropped++;
          // Authentication/replay failures drop the frame without exposing payloads.
          if (direction === 'encrypt') self.postMessage({ type: 'fatal', code: 'media-encryption-failed' });
          if (diagnostics && (dropped === 1 || dropped % 60 === 0)) self.postMessage({ type: 'diagnostic', direction, received, processed, dropped, error: String(error.message).slice(0, 120) });
        }
        if (diagnostics && (received === 1 || received % 60 === 0)) self.postMessage({ type: 'diagnostic', direction, received, processed, dropped });
      },
    })).pipeTo(transformer.writable);
  } catch (_) {
    self.postMessage({ type: 'fatal', code: 'encrypted-media-unavailable' });
  }
};
