import test from 'node:test';
import assert from 'node:assert/strict';
import { desktopStartupDescription } from '../../../desktopMain/resources/app-sharing/media.mjs';

const video = ['m=video 9 UDP/TLS/RTP/SAVPF 96 97', 'a=mid:screen', 'a=recvonly',
  'a=rtpmap:96 VP8/90000', 'a=rtpmap:97 rtx/90000', 'a=fmtp:97 apt=96',
  'a=ice-ufrag:synthetic', 'a=fingerprint:sha-256 synthetic'];
const audio = ['m=audio 9 UDP/TLS/RTP/SAVPF 96', 'a=mid:audio', 'a=rtpmap:96 opus/48000/2'];
const answer = lines => ({ type: 'answer', sdp: lines.join('\r\n') + '\r\n' });

test('startup hint targets only negotiated VP8 and preserves transport, audio, RTX and newline framing', () => {
  const original = answer(['v=0', ...audio, ...video]);
  const tuned = desktopStartupDescription(original, 'screen');
  assert.equal(tuned.sdp.replace('a=fmtp:96 x-google-start-bitrate=4000\r\n', ''), original.sdp);
  assert.equal(original.sdp.includes('start-bitrate'), false);
  assert.equal(tuned.sdp.includes('x-google-min-bitrate'), false);
  assert.equal(tuned.sdp.includes('x-google-max-bitrate'), false);
  assert.strictEqual(desktopStartupDescription(tuned, 'screen'), tuned);
});

test('startup preserves existing codec parameters and respects an explicit lower maximum or start hint', () => {
  const original = answer([...video, 'a=fmtp:96 max-fs=12288; x-google-max-bitrate=1200']);
  assert.match(desktopStartupDescription(original, 'screen').sdp,
    /a=fmtp:96 max-fs=12288; x-google-max-bitrate=1200; x-google-start-bitrate=1200\r\n/);
  const configured = answer([...video, 'a=fmtp:96 x-google-start-bitrate=800; max-fs=12288']);
  assert.strictEqual(desktopStartupDescription(configured, 'screen'), configured);
});

test('unrelated, rejected or unsupported sections remain byte-for-byte unchanged', () => {
  for (const value of [answer(audio), answer(video.map(line => line.replace('m=video 9 ', 'm=video 0 '))),
    answer(video.map(line => line.replace('VP8', 'H264'))), { type: 'offer', sdp: answer(video).sdp },
    answer(video.filter(line => line !== 'a=mid:screen')), { type: 'answer', sdp: 'synthetic-answer' }]) {
    assert.strictEqual(desktopStartupDescription(value, 'screen'), value);
  }
  const lf = { type: 'answer', sdp: video.join('\n') };
  const tuned = desktopStartupDescription(lf, 'screen');
  assert.equal(tuned.sdp.includes('\r'), false);
  assert.equal(tuned.sdp.replace('a=fmtp:96 x-google-start-bitrate=4000\n', ''), lf.sdp);
});
