// Injected only by the opt-in isolated Chromium test. All imports/fetches use real production assets.
window.startRawAssetsSmoke = async function startRawAssetsSmoke(format) {
  const state = window.rawAssetsSmoke = { stage: 'connecting', step: 'imports', passed: false };
  const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
  const cleanup = [], abort = new AbortController();
  let source, sender, receiver, video, canvas, sample, failure, retired = false;
  const transport = { acknowledgedFrames: 0, acknowledgedEmpty: 0, maximumFetches: 0, inFlight: 0, formats: [] };
  const errorName = error => ['Error', 'TypeError', 'ReferenceError', 'SyntaxError', 'SecurityError', 'NotSupportedError', 'InvalidStateError', 'OperationError', 'AbortError'].includes(error?.name) ? error.name : 'OtherError';
  const fail = error => { failure = true; state.pipelineError = errorName(error); };
  try {
    if (!['BGRA', 'NV12'].includes(format)) throw new Error('Unsupported case');
    // Native executeJavaScript has no module referrer URL; resolve against the real document explicitly.
    const media = await import(new URL('./media.mjs', location.href).href);
    const { toBase64 } = await import(new URL('./crypto.mjs', location.href).href);
    if (!media.encryptionSupport()) throw new Error('Encoded transforms unavailable');
    state.step = 'key-generation';
    const pair = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
    const config = {
      sessionId: crypto.randomUUID(), generation: crypto.randomUUID(), keyEpoch: crypto.randomUUID(), windowId: crypto.randomUUID(),
      mediaRootKey: toBase64(crypto.getRandomValues(new Uint8Array(32))),
      hostPrivateKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey))),
      hostPublicKey: toBase64(new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey))),
      rawFrameUrl: new URL('./raw-frame', location.href).href, rpcToken: location.pathname.split('/')[1],
    };
    state.step = 'document-setup';
    document.body.replaceChildren();
    canvas = document.createElement('canvas'); canvas.hidden = true;
    video = document.createElement('video'); video.autoplay = true; video.muted = true; video.playsInline = true;
    sample = document.createElement('canvas'); sample.width = 320; sample.height = 160;
    document.body.append(canvas, video, sample);
    state.step = 'raw-environment';
    const env = {
      performance, MediaStream, MediaStreamTrackGenerator, ImageData,
      // Exercise the negotiated BGRA fallback on the same NV12-capable browser.
      VideoFrame: format === 'NV12' ? VideoFrame : class extends VideoFrame {
        constructor(bytes, options) {
          if (options.format === 'NV12') throw new Error('Synthetic BGRA-only capability');
          super(bytes, options);
        }
      },
      fetch: async (url, options) => {
        transport.maximumFetches = Math.max(transport.maximumFetches, ++transport.inFlight);
        try {
          const response = await fetch(url, options);
          state.lastHttpStatus = response.status;
          if ([200, 204].includes(response.status)) {
            if (response.headers.get('X-Boss-App-Wait') !== 'true') throw new Error('Wait negotiation missing');
            if (response.status === 200) {
              transport.acknowledgedFrames++;
              const actual = response.headers.get('X-Boss-App-Pixel-Format');
              if (actual !== format) throw new Error('Unexpected raw format');
              if (!transport.formats.includes(actual)) transport.formats.push(actual);
            } else transport.acknowledgedEmpty++;
          }
          return response;
        } finally { transport.inFlight--; }
      },
    };
    state.step = 'raw-client';
    source = new media.NativeRawFrameCanvas(canvas, config, () => {}, error => {
      if (state.stage === 'cleared' && state.retiring) retired = true;
      else { failure = true; state.rawError = errorName(error); }
      source?.stop();
    }, env);
    if (!source.direct || source.direct.preferredFormat !== format) throw new Error('Direct format unavailable');
    state.step = 'peer-creation';
    sender = new RTCPeerConnection({ iceServers: [], encodedInsertableStreams: true });
    receiver = new RTCPeerConnection({ iceServers: [], encodedInsertableStreams: true });
    const outgoing = sender.addTransceiver('video', { direction: 'sendonly', sendEncodings: [media.desktopVideoEncoding()] }); media.vp8Only(outgoing);
    state.step = 'encrypt-attach';
    cleanup.push(await media.attachEncryption(outgoing.sender, config, 'encrypt', fail));
    await outgoing.sender.replaceTrack(source.track);
    state.step = 'offer';
    await sender.setLocalDescription(await sender.createOffer()); await media.waitForIce(sender, abort.signal);
    state.step = 'decrypt-attach';
    const received = await media.acceptEncryptedVideoOffer(receiver, sender.localDescription, outgoing.mid, config, fail);
    cleanup.push(received.cleanup);
    video.srcObject = new MediaStream([received.track]); video.play().catch(fail);
    state.step = 'answer';
    await receiver.setLocalDescription(await receiver.createAnswer()); await media.waitForIce(receiver, abort.signal);
    await sender.setRemoteDescription(media.desktopStartupDescription(receiver.localDescription, outgoing.mid));
    state.step = 'ice-connect';
    await Promise.all([media.waitForConnected(sender, abort.signal), media.waitForConnected(receiver, abort.signal)]);
    state.step = 'decoded-colors';
    const context = sample.getContext('2d', { willReadFrequently: true });
    const matches = (x, y, rgb) => rgb.every((value, index) => Math.abs(context.getImageData(x, y, 1, 1).data[index] - value) <= 35);
    const until = async predicate => {
      const deadline = performance.now() + 15000;
      while (performance.now() < deadline) {
        if (failure) throw new Error('Pipeline failed');
        if (predicate()) return;
        await delay(25);
      }
      throw new Error('Stage timeout');
    };
    await until(() => {
      if (video.readyState < 2 || video.videoWidth !== 320 || video.videoHeight !== 160) return false;
      context.drawImage(video, 0, 0, 320, 160);
      return matches(80, 40, [230, 40, 48]) && matches(240, 40, [32, 204, 80]) &&
        matches(80, 120, [35, 70, 220]) && matches(240, 120, [220, 200, 30]);
    });
    state.stage = 'colored'; state.step = 'decoded-clear';
    await until(() => {
      if (video.videoWidth !== 2 || video.videoHeight !== 2) return false;
      context.drawImage(video, 0, 0, 2, 2);
      return source.empty && matches(0, 0, [0, 0, 0]);
    });
    state.stage = 'cleared'; state.step = 'retirement';
    await until(() => retired);
    let framesDecoded = 0;
    for (const entry of (await receiver.getStats()).values()) {
      if (entry.type === 'inbound-rtp' && entry.kind === 'video') framesDecoded += entry.framesDecoded ?? 0;
    }
    if (!framesDecoded || !transport.acknowledgedFrames || !transport.acknowledgedEmpty || transport.maximumFetches !== 1) throw new Error('Coverage missing');
    state.report = { format, framesDecoded, cleared: true, retired: true, ...transport };
    state.passed = true; state.stage = 'retired';
  } catch (error) {
    // Only allowlisted fixed categories; never serialize arbitrary messages or capability URLs.
    const known = ['Unsupported case', 'Encoded transforms unavailable', 'Wait negotiation missing', 'Unexpected raw format', 'Direct format unavailable', 'Pipeline failed', 'Stage timeout', 'Coverage missing'];
    state.errorCategory = known.includes(error?.message) ? error.message : errorName(error);
    state.failedStage = state.stage; state.stage = 'failed';
    state.transport = { ...transport };
  } finally {
    source?.stop(); abort.abort(); sender?.close(); receiver?.close(); cleanup.forEach(close => close());
    if (video) video.srcObject = null;
    canvas?.remove(); video?.remove(); sample?.remove();
  }
};
