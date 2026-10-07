import { createBridge, closeViewerResources, resolveBridgeRequest, rejectBridgeRequest } from './bridge.mjs';
import { WindowMediaPeer } from './media.mjs';
import { AppControlChannel, videoPoint } from './control.mjs';
import { browserViewerConfig } from './browser-bootstrap.mjs';
import { SharingPerformanceBar } from './performance-bar.mjs';
import { acquireControlWithRetry, CONTROL_ATTEMPTS } from './control-retry.mjs';
import { setupViewerFullscreen } from './fullscreen.mjs';
import { StreamPlaceholder } from './placeholder.mjs';
import { remoteCursor } from './cursor.mjs';

const video = document.getElementById('screen');
const status = document.getElementById('status');
const placeholder = new StreamPlaceholder(video, document.getElementById('placeholder'), document.getElementById('placeholder-status'));
const controlButton = document.getElementById('control');
const windowPicker = document.getElementById('window');
const windowLabel = document.getElementById('window-label');
const windowActions = document.getElementById('window-actions');
const windowActionsLabel = document.getElementById('window-actions-label');
const recoveryActions = new Set(['restore', 'exit-fullscreen']);
const recoveryLabels = new Map([
  ['restore', 'Restore window'], ['exit-fullscreen', 'Exit host fullscreen'],
  ['minimize', 'Minimize window'], ['maximize', 'Maximize window'],
  ['unmaximize', 'Restore window size'], ['close', 'Close window'],
]);
function updateWindowActions(owner) {
  if (!windowActions || !windowActionsLabel) return;
  const actions = owner?.geometry?.windowControls;
  const supported = Array.isArray(actions) ? [...recoveryLabels].filter(([id]) => actions.includes(id)) : [];
  windowActionsLabel.hidden = !supported.length;
  windowActions.disabled = !owner?.lease || owner.failed || paused(owner) || owner.inactive ||
    document.hidden || owner.control?.channel?.readyState !== 'open';
  windowActions.replaceChildren();
  const placeholder = document.createElement('option');
  placeholder.value = ''; placeholder.textContent = 'Window actions';
  windowActions.append(placeholder);
  for (const [id, label] of supported) {
    const option = document.createElement('option'); option.value = id; option.textContent = label;
    windowActions.append(option);
  }
  windowActions.value = '';
}
function cancelWindowRecovery(owner) {
  if (!owner) return;
  clearTimeout(owner.windowRecoveryTimer);
  owner.windowRecoveryEpoch = (owner.windowRecoveryEpoch ?? 0) + 1;
}
function recoverWindow() {
  const owner = current, action = windowActions?.value;
  if (windowActions) windowActions.value = '';
  cancelWindowRecovery(owner);
  if (!owner) return;
  const lease = owner.lease, epoch = owner.windowRecoveryEpoch;
  const attempt = number => {
    // AppKit can ignore a toggle while entering fullscreen. Retry only explicit,
    // idempotent recovery commands; each retry is freshly encrypted and authorized.
    if (current !== owner || owner.windowRecoveryEpoch !== epoch || !lease || owner.lease !== lease ||
        owner.failed || paused(owner) || owner.inactive || document.hidden ||
        !recoveryLabels.has(action) || !Array.isArray(owner.geometry?.windowControls) ||
        !owner.geometry.windowControls.includes(action) || owner.control?.channel?.readyState !== 'open') return;
    if (owner.control.send({ type: 'window', action }) && recoveryActions.has(action) && number < 3) {
      owner.windowRecoveryTimer = setTimeout(() => attempt(number + 1), 800);
    }
  };
  attempt(0);
}
let current = null;
const heldKeys = new Map();
let navigation = 0;
setupViewerFullscreen({ document, root: document.documentElement, button: document.getElementById('fullscreen'),
  focusStream: () => { if (current?.lease && !document.hidden) video.focus(); } });
function text(value) { status.textContent = value; placeholder.text(value); }
function paused(owner) { return owner.recovering || owner.hostPaused; }
function updateCursor(owner) {
  video.style.cursor = owner?.lease && owner.pointerInside && !owner.failed && !paused(owner) && !owner.inactive && !document.hidden
    ? remoteCursor(owner.geometry?.cursor) : 'default';
}
function updateViewing(owner) {
  if (current !== owner) return;
  updateCursor(owner);
  updateWindowActions(owner);
  placeholder.unavailable(owner.failed || paused(owner));
  const retry = ['busy', 'temporary', 'expired'].includes(owner.controlFailure);
  controlButton.disabled = owner.failed || paused(owner) || !!owner.controlAttempt || owner.config.role === 'view' ||
    owner.control?.channel?.readyState !== 'open' || ['denied', 'unavailable'].includes(owner.controlFailure);
  controlButton.textContent = owner.lease ? 'Release control' : owner.controlAttempt ? 'Connecting control…' : retry ? 'Retry control' : 'Take control';
  text(owner.failed ? 'Connection lost. Reopen this session to reconnect.' : paused(owner) ? 'Connection interrupted. Reconnecting…'
    : owner.lease ? 'You control this BossConsole window'
    : owner.controlAttempt ? `Connecting control… (${owner.controlAttempt.number}/${CONTROL_ATTEMPTS})`
    : owner.controlFailure === 'busy' ? 'Another viewer has control. Retry control when it is released.'
    : owner.controlFailure === 'temporary' ? 'Could not connect control after three attempts. Select Retry control.'
    : owner.controlFailure === 'expired' ? 'Control expired. Select Retry control.'
    : owner.controlFailure === 'released' ? 'Control was released or is no longer allowed. Select Take control to request it again.'
    : owner.controlFailure === 'denied' ? 'Control is not allowed. Check your sharing settings.'
    : owner.controlFailure === 'unavailable' ? 'Remote control is unavailable. Reopen this session.'
    : owner.inactive && owner.wantsControl ? 'Viewing BossConsole. Control resumes when this viewer is active.' : 'Viewing BossConsole');
}
function restoreControl(owner) {
  if (current === owner && owner.wantsControl && !owner.inactive && !document.hidden && !owner.lease && !owner.controlAttempt &&
      !owner.controlFailure && !paused(owner) && !owner.failed && owner.control?.channel?.readyState === 'open') {
    return connectControl(owner);
  }
}
function suspendControl(owner) {
  cancelWindowRecovery(owner);
  owner.controlAttempt?.abort.abort();
  // Cancels pending acquisition too; a late grant must be released before resuming.
  return owner.control?.releaseControl({ reason: 'suspended' });
}
function stopOwner() {
  const owner = current; current = null;
  updateCursor(null);
  heldKeys.clear();
  placeholder.reset(); placeholder.unavailable(false);
  updateWindowActions(null);
  if (!owner) return;
  cancelWindowRecovery(owner);
  owner.wantsControl = false; owner.controlAttempt?.abort.abort();
  owner.performance?.stop();
  const closing = closeViewerResources(owner); video.srcObject = null;
  controlButton.disabled = true; controlButton.textContent = 'Take control'; text('Disconnected');
  return closing;
}
function stop() { navigation++; return stopOwner(); }
function leaveViewer(owner = current) {
  if (current !== owner) return;
  // Only the cookie-authenticated hosted entry supplies this field. Derive the
  // destination again so a supplied config cannot redirect to another site.
  const returnUrl = owner?.config.returnUrl;
  const destination = new URL('../', globalThis.location.href).href;
  stop();
  // releaseControl/mediaClose start their keepalive requests synchronously.
  // An unhealthy network must not leave this page waiting on their responses.
  if (returnUrl === destination && !globalThis.__bossAppShareBridge) globalThis.location.replace(destination);
}
function configureWindows(config) {
  windowPicker.replaceChildren();
  for (const window of config.windows ?? []) {
    const option = document.createElement('option'); option.value = window.id; option.textContent = window.title;
    windowPicker.append(option);
  }
  windowPicker.value = config.windowId;
  windowPicker.disabled = false;
  windowLabel.hidden = !config.canSwitchWindows || (config.windows?.length ?? 0) < 2;
}
async function start(value) {
  const attempt = ++navigation;
  await stopOwner();
  if (attempt !== navigation) return;
  const config = typeof value === 'string' ? JSON.parse(value) : value;
  const bridge = createBridge(config);
  const owner = { bridge, config, wantsControl: config.role !== 'view' && config.autoTakeControl !== false, inactive: !!document.hidden };
  current = owner; configureWindows(config); text('Connecting securely…');
  try {
    owner.media = new WindowMediaPeer({ config, request: body => bridge.request(body), onState: state => {
      bridge.state({ state });
      if (current !== owner) return;
      if (state === 'connected') {
        owner.recovering = false;
        owner.performance?.resume();
        updateViewing(owner); restoreControl(owner);
      }
      if (['failed', 'disconnected', 'recovering', 'stopped'].includes(state)) {
        owner.recovering = ['disconnected', 'recovering'].includes(state); owner.failed = !owner.recovering;
        owner.performance?.pause();
        if (owner.recovering) suspendControl(owner); else { owner.wantsControl = false; owner.controlAttempt?.abort.abort(); owner.control?.stop(); }
        updateViewing(owner);
        if (owner.failed && owner.config.returnUrl) leaveViewer(owner);
      }
    } });
    await owner.media.subscribe(track => {
      if (current !== owner) return;
      video.srcObject = new MediaStream([track]); placeholder.attach(track);
      video.play().catch(() => text('Select the video to start playback'));
    });
    if (current !== owner) return;
    owner.performance = new SharingPerformanceBar(owner.media, video, document.getElementById('client-metrics'), document.getElementById('remote-metrics'));
    owner.performance.resume();
    owner.control = new AppControlChannel(owner.media, {
      onLease: (lease, details = {}) => {
        if (current !== owner) return;
        const lost = !!owner.lease && !lease;
        if (!lease) heldKeys.clear();
        if (lost) cancelWindowRecovery(owner);
        owner.lease = lease;
        if (lost && details.reason !== 'suspended') {
          if (details.retryable) queueMicrotask(() => restoreControl(owner));
          else {
            owner.wantsControl = false;
            owner.controlFailure = details.reason === 'denied' ? 'denied' : details.reason === 'channel' ? 'unavailable'
              : details.reason === 'expired' ? 'expired' : 'released';
          }
        }
        updateViewing(owner);
      },
      onGeometry: geometry => {
        if (current !== owner) return;
        owner.geometry = geometry;
        const wasPaused = owner.hostPaused; owner.hostPaused = geometry.authorityAvailable === false;
        if (owner.hostPaused && !wasPaused) suspendControl(owner);
        updateViewing(owner); restoreControl(owner);
      },
      onMetrics: metrics => { if (current === owner) owner.performance.receiveRemote(metrics); },
      onError: () => { if (current === owner) { owner.wantsControl = false; owner.controlAttempt?.abort.abort(); owner.controlFailure = 'unavailable'; updateViewing(owner); } },
    });
    try {
      await owner.control.start();
      if (current === owner) {
        updateViewing(owner);
        await restoreControl(owner);
      }
    } catch (_) { if (current === owner) { owner.controlFailure = 'unavailable'; updateViewing(owner); } }
  } catch (_) {
    if (current === owner) { leaveViewer(owner); text('Unable to connect. Reopen the session from BossConsole.'); }
  }
}
async function switchWindow() {
  const owner = current, selected = windowPicker.value;
  if (!owner?.config.canSwitchWindows || !owner.config.windows.some(window => window.id === selected) || selected === owner.config.windowId) return;
  const attempt = ++navigation; windowPicker.disabled = true; text('Switching shared window…');
  try {
    const config = await browserViewerConfig(globalThis.location, fetch, selected);
    if (attempt !== navigation) return;
    // Admission initially grants view only. start() awaits old lease/media
    // cleanup before subscribing; no old control lease follows the new window.
    await start(config);
  } catch (_) {
    if (current === owner && attempt === navigation) { windowPicker.value = owner.config.windowId; windowPicker.disabled = false; text('This shared window is unavailable.'); }
  }
}
async function takeControl() {
  const owner = current;
  if (!owner?.control || owner.failed || paused(owner) || owner.config.role === 'view') return;
  owner.wantsControl = true; owner.controlFailure = null;
  return restoreControl(owner);
}
async function connectControl(owner) {
  const attempt = { abort: new AbortController(), number: 1 };
  owner.controlAttempt = attempt; updateViewing(owner);
  try {
    const result = await acquireControlWithRetry(() => owner.control.takeControl(), {
      signal: attempt.abort.signal,
      onAttempt: number => { attempt.number = number; updateViewing(owner); },
    });
    if (current !== owner || attempt.abort.signal.aborted) return;
    if (result.kind) owner.controlFailure = result.kind;
    if (owner.lease && !owner.inactive && !document.hidden) video.focus();
  } finally {
    if (owner.controlAttempt === attempt) owner.controlAttempt = null;
    updateViewing(owner);
    // Focus/authority can return while cancellation is still retiring a grant.
    if (attempt.abort.signal.aborted) restoreControl(owner);
  }
}
function releaseControl() {
  if (!current) return;
  cancelWindowRecovery(current);
  current.wantsControl = false; current.controlFailure = null; current.controlAttempt?.abort.abort();
  return current.control?.releaseControl({ reason: 'suspended' });
}
function suspendViewer() {
  if (!current) return;
  current.inactive = true; suspendControl(current); updateViewing(current);
}
function resumeViewer() {
  if (!current || document.hidden) return;
  current.inactive = false; updateViewing(current); restoreControl(current);
}
function point(event) {
  const geometry = current?.geometry;
  return geometry && videoPoint(event.clientX, event.clientY, video.getBoundingClientRect(), geometry.width, geometry.height);
}
for (const [eventName, action] of [['pointermove', 'move'], ['pointerdown', 'down'], ['pointerup', 'up']]) {
  video.addEventListener(eventName, event => {
    const coordinates = point(event);
    if (current) { current.pointerInside = !!coordinates; updateCursor(current); }
    if (!current?.lease) return;
    // Releasing outside the image cannot leave a held host button behind.
    if (!coordinates) { if (action === 'up') releaseControl(); return; }
    if (event.button > 2) return;
    event.preventDefault();
    if (action === 'down') { video.focus(); video.setPointerCapture(event.pointerId); }
    current.control.send({ type: 'pointer', action, ...coordinates, button: event.button < 0 ? 0 : event.button });
  });
}
video.addEventListener('pointerleave', () => { if (current) { current.pointerInside = false; updateCursor(current); } });
video.addEventListener('pointercancel', releaseControl);
video.addEventListener('wheel', event => {
  if (!current?.lease) return;
  const coordinates = point(event); if (!coordinates) return;
  event.preventDefault();
  const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? 400 : 1;
  current.control.send({ type: 'wheel', ...coordinates, deltaX: Math.max(-1000, Math.min(1000, event.deltaX * unit)), deltaY: Math.max(-1000, Math.min(1000, event.deltaY * unit)) });
}, { passive: false });
function sendKey(action, event) {
  const owner = current;
  if (!owner?.lease || event.isComposing) return;
  if (action === 'down') heldKeys.set(event.code, { owner, leaseId: owner.lease.leaseId });
  else {
    const held = heldKeys.get(event.code); heldKeys.delete(event.code);
    if (held?.owner !== owner || held.leaseId !== owner.lease.leaseId) return;
  }
  event.preventDefault();
  owner.control.send({ type: 'key', action, code: event.code, key: event.key, alt: event.altKey, ctrl: event.ctrlKey, meta: event.metaKey, shift: event.shiftKey });
}
video.addEventListener('keydown', event => sendKey('down', event));
video.addEventListener('keyup', event => sendKey('up', event));
// A button can take focus while a remote modifier is held. Release only keys that
// began on this stream; ordinary footer keyboard navigation stays in the viewer.
document.addEventListener('keyup', event => sendKey('up', event), true);
video.addEventListener('contextmenu', event => { if (current?.lease) event.preventDefault(); });
video.addEventListener('click', () => video.play().catch(() => {}));
globalThis.addEventListener('blur', suspendViewer);
globalThis.addEventListener('focus', resumeViewer);
document.addEventListener('visibilitychange', () => document.hidden ? suspendViewer() : resumeViewer());
globalThis.addEventListener('pagehide', stop);
controlButton.addEventListener('click', () => current?.lease ? releaseControl() : takeControl());
document.getElementById('disconnect').addEventListener('click', () => leaveViewer());
windowPicker.addEventListener('change', switchWindow);
windowActions?.addEventListener('change', recoverWindow);
globalThis.BossAppShareViewer = { start, stop, takeControl, releaseControl, resolve: resolveBridgeRequest, reject: rejectBridgeRequest };
globalThis.__bossAppShareBridge?.state?.(JSON.stringify({ state: 'ready' }));
if (globalThis.__bossAppShareConfig && globalThis.__bossAppShareConfig.autoStart !== false) start(globalThis.__bossAppShareConfig);
else if (!globalThis.__bossAppShareConfig) browserViewerConfig().then(start).catch(error => {
  text(error.message);
  if (error.loginUrl) {
    const link = document.createElement('a'); link.href = error.loginUrl; link.textContent = 'Sign in';
    status.append(document.createTextNode(' '), link);
  }
});
