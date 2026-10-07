import test from 'node:test';
import assert from 'node:assert/strict';
import { NativeRawFrameCanvas, RawVideoFrameTrack } from '../../../desktopMain/resources/app-sharing/media.mjs';

function fixture(response, changed = () => {}) {
  let stream, painted, calls = 0;
  const track = { requestFrame() {}, stop() { this.stopped = true; } };
  const canvas = {
    getContext: type => type === '2d' ? ({ putImageData: image => { painted = image; }, drawImage() {} }) : null,
    captureStream: () => ({ getVideoTracks: () => [track], getTracks: () => [track] }),
  };
  const env = {
    performance, ImageData: class { constructor(bytes, width, height) { Object.assign(this, { bytes, width, height }); } },
    fetch: async (url, options) => {
      calls++;
      assert.equal(url, 'http://127.0.0.1/private/raw-frame');
      assert.equal(options.method, 'POST'); assert.equal(options.headers['X-Boss-App-Token'], 'private-capability');
      assert.equal(options.headers['X-Boss-App-Pixel-Format'], 'BGRA');
      return response;
    },
  };
  let failure;
  stream = new NativeRawFrameCanvas(canvas, { rawFrameUrl: 'http://127.0.0.1/private/raw-frame', rpcToken: 'private-capability' },
    geometry => { changed(geometry); stream.stop(); }, error => { failure = error; stream.stop(); }, env);
  return { stream, track, canvas, result: () => ({ painted, failure, calls }) };
}
function rawResponse(bytes, extra = {}) {
  return { status: 200, headers: new Headers({ 'X-Boss-App-Sequence': '1', 'X-Boss-App-Width': '2',
    'X-Boss-App-Height': '1', 'X-Boss-App-Revision': '7', ...extra }), arrayBuffer: async () => bytes.buffer };
}

test('continuous raw pixels use authenticated binary transport and exact BGRA-to-RGBA colors', async () => {
  const f = fixture(rawResponse(new Uint8Array([10, 20, 30, 255, 40, 50, 60, 255])), geometry => {
    assert.deepEqual(geometry, { width: 2, height: 1, geometryRevision: 7, cursor: 'default' });
  });
  await f.stream.running;
  assert.deepEqual([...f.result().painted.bytes], [30, 20, 10, 255, 60, 50, 40, 255]);
  assert.equal(f.result().calls, 1); assert.equal(f.track.stopped, true);
  assert.throws(() => f.stream.frame({ png: 'unused' }), /PNG frames are disabled/);
});
test('malformed raw byte length fails closed before canvas paint', async () => {
  const f = fixture(rawResponse(new Uint8Array(4)));
  await f.stream.running;
  assert.match(f.result().failure.message, /Invalid native stream pixels/);
  assert.equal(f.result().painted, undefined); assert.equal(f.track.stopped, true);
});
test('oversized raw geometry is rejected before allocating or painting pixels', async () => {
  const f = fixture(rawResponse(new Uint8Array(8), { 'X-Boss-App-Width': '8192', 'X-Boss-App-Height': '8192' }));
  await f.stream.running;
  assert.match(f.result().failure.message, /Invalid native stream geometry/);
  assert.equal(f.result().painted, undefined);
});

function directFixture() {
  const frames = [], writes = [];
  class VideoFrame {
    constructor(bytes, options) {
      this.bytes = bytes instanceof VideoFrame ? bytes.bytes : bytes;
      this.options = options; frames.push(this);
    }
    clone() { return new VideoFrame(this, this.options); }
    close() { this.closed = true; }
  }
  const writer = { ready: Promise.resolve(), write: async frame => { writes.push(frame); }, abort: async () => { writer.aborted = true; } };
  class MediaStreamTrackGenerator {
    writable = { getWriter: () => writer };
    stop() { this.stopped = true; }
  }
  const env = { performance, VideoFrame, MediaStreamTrackGenerator, MediaStream: class {
    constructor(tracks) { this.tracks = tracks; }
    getTracks() { return this.tracks; }
    getVideoTracks() { return this.tracks; }
  } };
  return { env, frames, writes, writer, direct: RawVideoFrameTrack.create(env) };
}
test('direct video frames preserve BGRA bytes, close resources and replace retained pixels with black on empty demand', async () => {
  const h = directFixture(), bytes = new Uint8Array([10, 20, 30, 255, 40, 50, 60, 255]);
  await h.direct.paint(bytes, 2, 1);
  assert.equal(h.writes[0].options.format, 'BGRA'); assert.equal(h.writes[0].closed, true);
  assert.deepEqual([...bytes], [10, 20, 30, 255, 40, 50, 60, 255]);
  const retained = h.direct.retained;
  await h.direct.clear(); assert.equal(retained.closed, true);
  assert.deepEqual([...h.writes[1].bytes], [0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255]);
  await h.direct.redraw();
  assert.ok(h.writes[2].options.timestamp > h.writes[1].options.timestamp);
  h.direct.stop(); assert.equal(h.direct.track.stopped, true); assert.equal(h.writer.aborted, true);
  assert.ok(h.frames.every(frame => frame.closed));
});
test('NV12 keeps limited-range BT709 metadata and avoids allocating a BGRA frame', async () => {
  const h = directFixture(), bytes = new Uint8Array([16, 235, 16, 235, 128, 128]);
  assert.equal(h.direct.supportsFormat('NV12'), true);
  await h.direct.paint(bytes, 2, 2, 'NV12');
  const frame = h.writes[0];
  assert.equal(frame.options.format, 'NV12');
  assert.equal(frame.bytes.byteLength, 6);
  assert.deepEqual(frame.options.colorSpace, { primaries: 'bt709', transfer: 'bt709', matrix: 'bt709', fullRange: false });
  h.direct.stop();
  assert.ok(h.frames.every(value => value.closed));
});
test('binary transport negotiates NV12 and validates its compact plane size', async () => {
  for (const length of [6, 8]) {
    const h = directFixture(); h.direct.stop();
    let stream, failure, geometry;
    h.env.fetch = async (_url, options) => {
      assert.equal(options.headers['X-Boss-App-Pixel-Format'], 'NV12');
      return rawResponse(new Uint8Array(length), { 'X-Boss-App-Pixel-Format': 'NV12', 'X-Boss-App-Height': '2' });
    };
    stream = new NativeRawFrameCanvas({}, { rawFrameUrl: 'synthetic', rpcToken: 'synthetic' },
      value => { geometry = value; stream.stop(); }, error => { failure = error; stream.stop(); }, h.env);
    await stream.running;
    if (length === 6) {
      assert.equal(failure, undefined);
      assert.deepEqual(geometry, { width: 2, height: 2, geometryRevision: 7, cursor: 'default' });
      assert.equal(h.writes[0].options.format, 'NV12');
    } else {
      assert.match(failure.message, /Invalid native stream pixels/);
      assert.equal(h.writes.length, 0);
    }
  }
});
test('unsupported NV12 falls back to BGRA without disabling direct-frame streaming', async () => {
  const h = directFixture(); h.direct.stop();
  const Original = h.env.VideoFrame;
  h.env.VideoFrame = class extends Original {
    constructor(bytes, options) { if (options.format === 'NV12') throw new Error('Unsupported NV12'); super(bytes, options); }
  };
  const direct = RawVideoFrameTrack.create(h.env);
  assert.equal(direct.preferredFormat, 'BGRA');
  assert.equal(direct.supportsFormat('NV12'), false);
  await assert.rejects(direct.paint(new Uint8Array(6), 2, 2, 'NV12'), /Unsupported native pixel format/);
  await direct.paint(new Uint8Array(16), 2, 2);
  direct.stop();
});
test('NV12 response is rejected by a canvas-only client before consuming its bytes', async () => {
  const response = rawResponse(new Uint8Array(6), { 'X-Boss-App-Pixel-Format': 'NV12', 'X-Boss-App-Height': '2' });
  let consumed = false;
  response.arrayBuffer = async () => { consumed = true; return new ArrayBuffer(6); };
  const f = fixture(response); await f.stream.running;
  assert.equal(consumed, false);
  assert.match(f.result().failure.message, /Unsupported native pixel format/);
});
test('stopping a backpressured direct frame does not publish the pending pixels', async () => {
  const h = directFixture(); let resume;
  h.writer.ready = new Promise(resolve => { resume = resolve; });
  const painting = h.direct.paint(new Uint8Array(16), 2, 2);
  h.direct.stop(); resume(); await painting;
  assert.equal(h.writes.length, 0); assert.ok(h.frames.every(frame => frame.closed));
});
test('an engine exposing unsupported direct pixel formats retains the canvas fallback', () => {
  const h = directFixture(); h.direct.stop();
  h.env.VideoFrame = class { constructor() { throw new Error('Unsupported format'); } };
  assert.equal(RawVideoFrameTrack.create(h.env), null);
});
test('empty demand retires real pixels even when their dimensions already equal the blank frame', async () => {
  const h = directFixture(); h.direct.stop();
  let stream, calls = 0, failure;
  h.env.fetch = async () => {
    if (++calls === 1) return rawResponse(new Uint8Array(16).fill(200), { 'X-Boss-App-Height': '2' });
    if (calls === 2) return { status: 204, headers: new Headers({ 'X-Boss-App-Sequence': '2', 'X-Boss-App-Empty': 'true' }) };
    throw new Error('Old pixels were not retired');
  };
  h.writer.write = async frame => { h.writes.push(frame); if (h.writes.length === 2) stream.stop(); };
  stream = new NativeRawFrameCanvas({}, { rawFrameUrl: 'synthetic-protected-url', rpcToken: 'synthetic-capability' },
    () => {}, error => { failure = error; stream.stop(); }, h.env);
  await stream.running;
  assert.equal(failure, undefined); assert.equal(calls, 2); assert.equal(h.writes.length, 2);
  assert.deepEqual([...h.writes[1].bytes], [0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255]);
  assert.ok(h.frames.every(frame => frame.closed));
});


function transportFixture(respond, { direct = false, now = 0 } = {}) {
  const h = direct ? directFixture() : null;
  h?.direct.stop();
  const requests = [], delays = [], timers = new Map(), paints = [];
  let nextTimer = 0, stream, failure;
  const track = { requestFrame() {}, stop() {} };
  const canvas = direct ? {} : {
    getContext: type => type === '2d' ? ({ putImageData: image => paints.push(image), drawImage() {} }) : null,
    captureStream: () => ({ getVideoTracks: () => [track], getTracks: () => [track] }),
  };
  const pending = options => new Promise((_, reject) => {
    const abort = () => reject(new Error('Stopped'));
    if (options.signal.aborted) abort();
    else options.signal.addEventListener('abort', abort, { once: true });
  });
  const env = {
    ...(h?.env ?? {}), performance: { now: () => now },
    ImageData: class { constructor(bytes, width, height) { Object.assign(this, { bytes, width, height }); } },
    setTimeout: (callback, delay) => { delays.push(delay); timers.set(++nextTimer, callback); return nextTimer; },
    clearTimeout: timer => timers.delete(timer),
    fetch: async (_url, options) => {
      requests.push(options);
      assert.equal(options.headers['X-Boss-App-Wait'], 'true');
      await Promise.resolve();
      return respond(options, requests.length, () => stream) ?? pending(options);
    },
  };
  stream = new NativeRawFrameCanvas(canvas, { rawFrameUrl: 'synthetic', rpcToken: 'synthetic' }, () => {},
    error => { failure = error; stream.stop(); }, env);
  return { stream, requests, delays, timers, paints, h, failure: () => failure,
    tick: () => { const [id, callback] = timers.entries().next().value; timers.delete(id); callback(); } };
}
const settle = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
const emptyResponse = (sequence = '1', acknowledged = true, extra = {}) => ({
  status: 204, headers: new Headers({ 'X-Boss-App-Sequence': sequence, ...(acknowledged ? { 'X-Boss-App-Wait': 'true' } : {}), ...extra }),
});
const rejectedResponse = (status = 409, acknowledged = true) => ({
  status, headers: new Headers(acknowledged ? { 'X-Boss-App-Wait': 'true' } : {}),
});

test('cursor-only updates announce shapes without repainting or accepting arbitrary CSS', async () => {
  const seen = [];
  const f = transportFixture((_options, call) => {
    if (call === 1) return rawResponse(new Uint8Array(8), { 'X-Boss-App-Wait': 'true' });
    if (call === 2) return emptyResponse('2', true, { 'X-Boss-App-Cursor': 'ew-resize' });
    if (call === 3) return emptyResponse('3', true, { 'X-Boss-App-Cursor': 'ew-resize' });
    if (call === 4) return emptyResponse('4', true, { 'X-Boss-App-Cursor': 'url(https://untrusted/cursor), auto' });
  });
  f.stream.onGeometry = value => seen.push(value);
  await settle();
  assert.deepEqual(seen.map(value => value.cursor), ['default', 'ew-resize', 'default']);
  assert.ok(seen.every(value => value.geometryRevision === 7));
  assert.equal(f.paints.length, 1, 'Cursor feedback must not paint unchanged video');
  assert.equal(f.requests.at(-1).headers['X-Boss-App-After'], '4');
  f.stream.stop(); await f.stream.running; assert.equal(f.failure(), undefined);
});

test('fresh-frame acknowledgement skips pacing and empty demand advances sequence while clearing pixels', async () => {
  const f = transportFixture((_options, call) => {
    if (call === 1) return rawResponse(new Uint8Array(16).fill(200), { 'X-Boss-App-Height': '2', 'X-Boss-App-Wait': 'true' });
    if (call === 2) return emptyResponse('2', true, { 'X-Boss-App-Empty': 'true' });
  }, { direct: true });
  await settle();
  assert.equal(f.requests.length, 3); assert.equal(f.requests[2].headers['X-Boss-App-After'], '2');
  assert.equal(f.h.writes.length, 2); assert.equal(f.stream.empty, true);
  assert.deepEqual(f.delays, []);
  f.stream.stop(); await f.stream.running; assert.equal(f.failure(), undefined);
});
test('unacknowledged legacy response retains frame-period pacing', async () => {
  const f = transportFixture((_options, call) => call === 1 ? emptyResponse('1', false) : undefined);
  await settle(); assert.equal(f.requests.length, 1); assert.deepEqual(f.delays, [1000 / 30]);
  f.tick(); await settle(); assert.equal(f.requests.length, 2);
  assert.equal(f.requests[1].headers['X-Boss-App-After'], '1');
  f.stream.stop(); await f.stream.running;
});
test('missing and malformed success sequence headers fail before consuming or painting pixels', async () => {
  for (const value of [null, '', ' ', '-1', '1e2', '9007199254740992']) {
    const response = rawResponse(new Uint8Array(8));
    if (value === null) response.headers.delete('X-Boss-App-Sequence');
    else response.headers.set('X-Boss-App-Sequence', value);
    response.arrayBuffer = async () => assert.fail('Invalid sequence must not consume pixels');
    const f = transportFixture(() => response); await f.stream.running;
    assert.match(f.failure().message, /Invalid native stream sequence/);
    assert.equal(f.stream.sequence, -1); assert.equal(f.requests.length, 1);
  }
});
test('stopping a pending fresh-frame fetch aborts it without retry or error', async () => {
  const f = transportFixture(() => undefined); await settle();
  f.stream.stop(); await f.stream.running;
  assert.equal(f.requests[0].signal.aborted, true);
  assert.equal(f.requests.length, 1); assert.equal(f.failure(), undefined); assert.deepEqual(f.delays, []);
});
test('stopping during negotiated admission retry cancels and wakes the timer', async () => {
  const f = transportFixture(() => rejectedResponse()); await settle();
  assert.deepEqual(f.delays, [100]); assert.equal(f.stream.sequence, -1);
  f.stream.stop(); await f.stream.running;
  assert.equal(f.timers.size, 0); assert.equal(f.requests.length, 1); assert.equal(f.failure(), undefined);
});
test('negotiated admission pressure retries five times then fails without sequence advancement', async () => {
  const f = transportFixture((_options, call) => rejectedResponse(call % 2 ? 409 : 429));
  for (let i = 0; i < 5; i++) { await settle(); f.tick(); }
  await f.stream.running;
  assert.deepEqual(f.delays, [100, 200, 400, 800, 800]);
  assert.equal(f.requests.length, 6); assert.equal(f.stream.sequence, -1);
  assert.ok(f.requests.every(options => options.headers['X-Boss-App-After'] === '-1'));
  assert.match(f.failure().message, /Native stream unavailable/);
});
test('successful empty responses reset admission retry backoff', async () => {
  const f = transportFixture((_options, call) => {
    if ([1, 2, 4, 5].includes(call)) return rejectedResponse();
    if (call === 3) return emptyResponse('2');
    if (call === 6) return emptyResponse('3');
  });
  for (let i = 0; i < 4; i++) { await settle(); f.tick(); }
  await settle();
  assert.deepEqual(f.delays, [100, 200, 100, 200]); assert.equal(f.requests.length, 7);
  assert.equal(f.requests[3].headers['X-Boss-App-After'], '2');
  assert.equal(f.requests[6].headers['X-Boss-App-After'], '3');
  f.stream.stop(); await f.stream.running; assert.equal(f.failure(), undefined);
});
test('unacknowledged rejection never enters the negotiated retry protocol', async () => {
  for (const status of [409, 429]) {
    const f = transportFixture(() => rejectedResponse(status, false)); await f.stream.running;
    assert.match(f.failure().message, /Native stream unavailable/);
    assert.equal(f.requests.length, 1); assert.deepEqual(f.delays, []);
  }
});
test('acknowledged idle response keeps publication alive and advances sequence', async () => {
  const f = transportFixture((_options, call) => call === 1 ? emptyResponse('3') : undefined, { direct: true, now: 6000 });
  await settle();
  assert.equal(f.h.writes.length, 1); assert.equal(f.stream.sequence, 3); assert.deepEqual(f.delays, []);
  f.stream.stop(); await f.stream.running; assert.equal(f.failure(), undefined);
});
test('fresh-frame polling waits for direct-frame backpressure and stop prevents another fetch', async () => {
  const f = transportFixture(() => rawResponse(new Uint8Array(16), { 'X-Boss-App-Height': '2', 'X-Boss-App-Wait': 'true' }), { direct: true });
  let resume;
  f.h.writer.ready = new Promise(resolve => { resume = resolve; });
  await settle(); assert.equal(f.requests.length, 1); assert.equal(f.h.writes.length, 0);
  f.stream.stop(); resume(); await f.stream.running;
  assert.equal(f.requests.length, 1); assert.equal(f.h.writes.length, 0); assert.equal(f.failure(), undefined);
});
