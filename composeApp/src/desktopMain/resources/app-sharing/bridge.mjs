let nextNativeRequest = 0;
const nativeBridges = new Set();
// Native responses can arrive after the UI owner was cleared on disconnect.
export function resolveBridgeRequest(id, value) { for (const bridge of nativeBridges) bridge.resolve(id, value); }
export function rejectBridgeRequest(id) { for (const bridge of nativeBridges) bridge.reject(id); }

export function createBridge(config, nativeBridge = globalThis.__bossAppShareBridge) {
  const pending = new Map();
  const abort = new AbortController();
  let rpc;
  const browserSession = !nativeBridge?.request && !!config.csrf;
  if (!nativeBridge?.request) {
    rpc = new URL(browserSession ? config.apiUrl : config.rpcUrl);
    const sameOrigin = rpc.origin === globalThis.location?.origin && !rpc.username && !rpc.password && !rpc.hash;
    if (browserSession) {
      if (!sameOrigin || (rpc.protocol !== 'https:' && !(rpc.protocol === 'http:' && rpc.hostname === '127.0.0.1')) || !rpc.pathname.endsWith('/api/app-sharing')) throw new Error('Untrusted app sharing API origin');
    } else if (!sameOrigin || rpc.protocol !== 'http:' || rpc.hostname !== '127.0.0.1' || !config.rpcToken) throw new Error('Untrusted app sharing RPC origin');
  }
  const bridge = {
    async request(body) {
      if (abort.signal.aborted) throw new Error('Sharing stopped');
      if (nativeBridge?.request) return new Promise((resolve, reject) => {
        const id = String(++nextNativeRequest);
        const timer = setTimeout(() => { pending.delete(id); reject(new Error('Sharing request timed out')); }, 15000);
        pending.set(id, { resolve, reject, timer });
        try { nativeBridge.request(id, JSON.stringify(body)); } catch (_) { clearTimeout(timer); pending.delete(id); reject(new Error('Sharing bridge unavailable')); }
      });
      const timeout = AbortSignal.timeout(15000);
      const headers = { 'Content-Type': 'application/json', [browserSession ? 'X-App-Sharing-CSRF' : 'X-Boss-App-Token']: browserSession ? config.csrf : config.rpcToken };
      // Tiny teardown requests may finish after pagehide. They retain the same
      // origin/auth checks and bounded lifetime as every other RPC.
      const keepalive = body.action === 'mediaClose' || body.action === 'controlRelease';
      const response = await fetch(rpc, { method: 'POST', headers, body: JSON.stringify(body), signal: AbortSignal.any([abort.signal, timeout]), credentials: browserSession ? 'same-origin' : 'omit', redirect: 'error', cache: 'no-store', keepalive });
      if (!response.ok) {
        const error = new Error(`Sharing request failed (${response.status})`);
        error.status = response.status;
        const data = await response.json().catch(() => ({}));
        error.code = typeof data.error === 'string' ? data.error : '';
        throw error;
      }
      return response.json();
    },
    resolve(id, value) {
      const request = pending.get(String(id)); if (!request) return;
      pending.delete(String(id)); clearTimeout(request.timer);
      try { request.resolve(typeof value === 'string' ? JSON.parse(value) : value); } catch (_) { request.reject(new Error('Invalid sharing response')); }
    },
    reject(id) {
      const request = pending.get(String(id)); if (!request) return;
      pending.delete(String(id)); clearTimeout(request.timer); request.reject(new Error('Sharing request refused'));
    },
    state(value) { nativeBridge?.state?.(JSON.stringify(value)); },
    input(value) { nativeBridge?.input?.(JSON.stringify(value)); },
    close() { nativeBridges.delete(bridge); abort.abort(); for (const request of pending.values()) { clearTimeout(request.timer); request.reject(new Error('Sharing stopped')); } pending.clear(); },
  };
  if (nativeBridge?.request) nativeBridges.add(bridge);
  return bridge;
}

export function closeViewerResources(owner) {
  const release = owner.control?.releaseControl();
  owner.control?.stop();
  const mediaClose = owner.media?.stop();
  // Closing the bridge earlier would abort mediaClose and leave a healthy
  // owner's other viewers vulnerable to this peer's eventual expiration.
  return Promise.allSettled([release, mediaClose]).then(() => owner.bridge.close());
}
