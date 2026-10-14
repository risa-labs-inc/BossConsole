import test from 'node:test';
import assert from 'node:assert/strict';
import { StreamPlaceholder } from '../../../desktopMain/resources/app-sharing/placeholder.mjs';

function fixture({ callbacks = true } = {}) {
  const video = new EventTarget(), overlay = {}, message = {};
  const pending = new Map(); let id = 0;
  Object.assign(video, { videoWidth: 800, videoHeight: 600, readyState: 2 });
  if (callbacks) {
    video.requestVideoFrameCallback = callback => { pending.set(++id, callback); return id; };
    video.cancelVideoFrameCallback = key => pending.delete(key);
  }
  const placeholder = new StreamPlaceholder(video, overlay, message);
  const track = () => Object.assign(new EventTarget(), { readyState: 'live', muted: false });
  const frame = () => { const callbacks = [...pending.values()]; pending.clear(); callbacks.forEach(callback => callback()); };
  return { video, overlay, message, pending, placeholder, track, frame };
}

test('logo and current connection status remain until a frame is presented, and return on disconnect', () => {
  const f = fixture();
  f.placeholder.text('Connecting securely…');
  assert.equal(f.message.textContent, 'Connecting securely…'); assert.equal(f.overlay.hidden, false);
  f.placeholder.attach(f.track());
  assert.equal(f.overlay.hidden, false);
  f.video.dispatchEvent(new Event('emptied')); // Queued source replacement must retain the new track.
  f.video.dispatchEvent(new Event('loadeddata')); f.frame();
  assert.equal(f.overlay.hidden, true);
  f.placeholder.reset(); f.placeholder.text('Disconnected');
  assert.equal(f.overlay.hidden, false); assert.equal(f.message.textContent, 'Disconnected');
  assert.equal(f.pending.size, 0);
});

test('paused, muted and ended streams hide stale pixels and old tracks cannot affect a replacement', () => {
  const f = fixture(), old = f.track();
  f.placeholder.attach(old); f.frame();
  f.placeholder.unavailable(true); assert.equal(f.overlay.hidden, false);
  f.video.dispatchEvent(new Event('playing')); f.frame(); assert.equal(f.overlay.hidden, false);
  f.placeholder.unavailable(false); f.frame(); assert.equal(f.overlay.hidden, true);
  old.muted = true; old.dispatchEvent(new Event('mute')); assert.equal(f.overlay.hidden, false);
  old.muted = false; old.dispatchEvent(new Event('unmute')); f.frame(); assert.equal(f.overlay.hidden, true);
  const next = f.track(); f.placeholder.attach(next); f.frame();
  old.dispatchEvent(new Event('ended')); assert.equal(f.overlay.hidden, true);
  next.readyState = 'ended'; next.dispatchEvent(new Event('ended')); assert.equal(f.overlay.hidden, false);
});

test('legacy video engines reveal only a loaded frame with nonzero dimensions', () => {
  const f = fixture({ callbacks: false }); f.video.readyState = 0;
  f.placeholder.attach(f.track()); assert.equal(f.overlay.hidden, false);
  f.video.readyState = 2; f.video.videoWidth = 0;
  f.video.dispatchEvent(new Event('loadeddata')); assert.equal(f.overlay.hidden, false);
  f.video.videoWidth = 800;
  f.video.dispatchEvent(new Event('playing')); assert.equal(f.overlay.hidden, true);
});
