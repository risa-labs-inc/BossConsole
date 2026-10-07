import { NativeRawFrameCanvas } from './media.mjs';
import { rawFrameMarker } from './benchmark-metrics.mjs';

// Capabilities stay in memory; callers persist only scalar measurements.
export async function benchmarkRpc(action, extra = {}) {
  const response = await fetch(new URL('./rpc', location.href), {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Boss-App-Token': location.pathname.split('/')[1] },
    body: JSON.stringify({ action, ...extra }),
  });
  if (!response.ok) throw new Error('Synthetic benchmark RPC failed');
  return response.json();
}

export function productionBenchmarkSource(canvas, format, fps, observe, transport, failed, markerEvery = 1) {
  let paused = false, resume = null, latestFetchStart = 0;
  const env = {
    performance, MediaStream, MediaStreamTrackGenerator, ImageData,
    VideoFrame: format === 'NV12' ? VideoFrame : class extends VideoFrame {
      constructor(bytes, options) {
        if (options.format === 'NV12') throw new Error('Synthetic BGRA-only capability');
        super(bytes, options);
      }
    },
    fetch: async (url, options) => {
      if (paused && !options.signal.aborted) await new Promise(resolve => {
        const wake = () => { options.signal.removeEventListener('abort', wake); resume = null; resolve(); };
        resume = wake; options.signal.addEventListener('abort', wake, { once: true });
      });
      if (options.signal.aborted) throw new Error('Synthetic source stopped');
      latestFetchStart = performance.now();
      const response = await fetch(url, options);
      transport(response.status, response.headers.get('X-Boss-App-Wait') === 'true');
      return response;
    },
  };
  const source = new NativeRawFrameCanvas(canvas, {
    rawFrameUrl: new URL('./raw-frame', location.href).href, rpcToken: location.pathname.split('/')[1],
  }, () => {}, failed, env);
  source.frameRate = fps;
  if (!source.direct || source.direct.preferredFormat !== format) {
    source.stop(); throw new Error('Requested production raw format unavailable');
  }
  const paint = source.paint.bind(source);
  source.paint = async (bytes, width, height, pixelFormat) => {
    const prepared = performance.now();
    const id = markerEvery ? rawFrameMarker(bytes, width, height, pixelFormat) : null;
    const complete = observe(id, latestFetchStart, prepared);
    await paint(bytes, width, height, pixelFormat);
    complete?.(performance.now());
  };
  return {
    source,
    setPaused(value) { paused = value; if (!paused) resume?.(); },
    stop() { source.stop(); resume?.(); },
  };
}
