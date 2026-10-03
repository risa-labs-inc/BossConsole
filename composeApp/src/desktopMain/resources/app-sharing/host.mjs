import { createBridge, resolveBridgeRequest, rejectBridgeRequest } from './bridge.mjs';
import { WindowMediaPeer, NativeFrameCanvas, NativeRawFrameCanvas } from './media.mjs';
import { AppControlChannel } from './control.mjs';

let current = null;
const usedEpochs = new Set();
function parse(value) { return typeof value === 'string' ? JSON.parse(value) : value; }
function state(value) { globalThis.__bossAppShareBridge?.state?.(JSON.stringify(value)); }

async function start(value) {
  stop();
  const config = parse(value);
  const epoch = JSON.stringify([config.sessionId, config.generation, config.windowId, config.keyEpoch]);
  // A fresh epoch/root must be issued by the host before reconnecting a publisher.
  if (usedEpochs.has(epoch)) throw new Error('Restart sharing with a fresh media key epoch');
  usedEpochs.add(epoch);
  // The Java bridge exposes only state/input. Read optional RPC members from a
  // plain JS adapter: JxBrowser throws when a missing Java member is accessed.
  const bridge = createBridge(config, {
    state: value => globalThis.__bossAppShareBridge?.state(value),
    input: value => globalThis.__bossAppShareBridge?.input(value),
  });
  const owner = { bridge }; current = owner;
  try {
    owner.media = new WindowMediaPeer({ config, request: body => bridge.request(body), onState: (status, reason) => {
      bridge.state({ state: status, ...(status === 'failed' ? { reason: reason ?? 'media_connection_failed' } : {}) });
      if (status === 'stopped' && current === owner) stop();
    } });
    const geometryChanged = geometry => {
      const scoped = { ...geometry, ...(config.windowControls ? { windowControls: config.windowControls } : {}) };
      owner.geometry = scoped; owner.control?.setGeometry(scoped);
    };
    owner.canvas = config.rawFrameUrl
      ? new NativeRawFrameCanvas(document.getElementById('capture'), config, geometryChanged, () => {
        if (current === owner) { bridge.state({ state: 'failed', reason: 'native_stream_failed' }); stop(); }
      })
      : new NativeFrameCanvas(document.getElementById('capture'), geometryChanged);
    // Only native, exactly selected window frames feed this canvas. There is no
    // display picker or title-matching capture fallback here.
    await owner.media.publish(owner.canvas.track, config.rawFrameUrl ? rate => { owner.canvas.frameRate = rate; } : undefined);
    if (current !== owner) return;
    owner.control = new AppControlChannel(owner.media, {
      host: true,
      onInput: input => { if (current === owner) bridge.input(input); },
      onLease: (lease, status) => {
        if (current === owner) bridge.state(status?.suspended ? { state: 'control-suspended' } : { state: 'lease', lease });
      },
      onAuthority: available => {
        if (current === owner) {
          owner.canvas.track.enabled = available;
          if (owner.authorityAvailable !== available) {
            owner.authorityAvailable = available;
            bridge.state({ state: available ? 'authority-restored' : 'authority-suspended' });
          }
        }
      },
      onError: () => {
        // Losing authoritative lease/session health cannot leave an old media
        // key publishing indefinitely to a revoked or expired subscription.
        if (current === owner) { bridge.state({ state: 'failed', reason: 'authority_unavailable', message: 'Application sharing authority could not be verified' }); stop(); }
      },
    });
    if (owner.geometry) owner.control.setGeometry(owner.geometry);
    try { await owner.control.start(); }
    catch (_) { if (current === owner) bridge.state({ state: 'control-unavailable' }); }
    if (current === owner) bridge.state({ state: 'sharing' });
  } catch (error) {
    if (current === owner) { bridge.state({ state: 'failed', reason: 'media_initialization_failed', message: 'Encrypted application sharing could not start' }); stop(); }
    throw error;
  }
}
function frameFailure(error) {
  if (error?.message === 'Invalid native snapshot') return 'native_snapshot_invalid';
  if (error?.message === 'Native geometry mismatch') return 'native_geometry_mismatch';
  if (error?.name === 'InvalidStateError') return 'native_decode_failed';
  return 'native_frame_failed';
}
function frame(value) {
  const owner = current;
  if (!owner?.canvas) return false;
  try {
    const promise = owner.canvas.frame(parse(value));
    promise?.catch(error => { if (current === owner) { owner.bridge.state({ state: 'failed', reason: frameFailure(error), message: 'Native window capture failed' }); stop(); } });
    return true;
  } catch (error) { owner.bridge.state({ state: 'failed', reason: frameFailure(error), message: 'Native window capture failed' }); stop(); return false; }
}
function stop() {
  const owner = current; current = null;
  if (!owner) return;
  owner.control?.stop(); owner.canvas?.stop();
  const closing = Promise.resolve(owner.media?.stop()).finally(() => owner.bridge.close());
  state({ state: 'stopped' });
  return closing;
}
globalThis.BossAppShareHost = { start, frame, stop, resolve: resolveBridgeRequest, reject: rejectBridgeRequest };
globalThis.addEventListener('pagehide', stop);
state({ state: 'ready' });
if (globalThis.__bossAppShareConfig && globalThis.__bossAppShareConfig.autoStart !== false) start(globalThis.__bossAppShareConfig).catch(() => {});
