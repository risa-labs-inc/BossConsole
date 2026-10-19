// Keep a useful connection state in the stage until this stream has a presented frame.
export class StreamPlaceholder {
  constructor(video, overlay, message) {
    this.video = video; this.overlay = overlay; this.message = message;
    this.blocked = false; this.epoch = 0;
    for (const name of ['emptied', 'ended', 'error']) video.addEventListener(name, () => this.hideFrame());
    for (const name of ['loadeddata', 'playing']) video.addEventListener(name, () => this.watch());
    this.reset();
  }
  text(value) { if (this.message) this.message.textContent = value; }
  cancel() {
    this.epoch++;
    if (this.frame !== undefined) this.video.cancelVideoFrameCallback?.(this.frame);
    this.frame = undefined;
  }
  reset() {
    this.trackAbort?.abort(); this.track = null;
    this.hideFrame();
  }
  hideFrame() {
    this.cancel();
    if (this.overlay) this.overlay.hidden = false;
  }
  attach(track) {
    this.reset(); this.track = track;
    this.trackAbort = new AbortController();
    const options = { signal: this.trackAbort.signal };
    for (const name of ['ended', 'mute']) track.addEventListener(name, () => {
      if (this.track === track) this.hideFrame();
    }, options);
    track.addEventListener('unmute', () => { if (this.track === track) this.watch(); }, options);
    this.watch();
  }
  unavailable(value) {
    if (this.blocked === value) return;
    this.blocked = value;
    if (value) this.hideFrame();
    else this.watch();
  }
  watch() {
    if (!this.overlay || this.overlay.hidden || this.blocked || !this.track || this.track.readyState === 'ended' || this.track.muted || this.frame !== undefined) return;
    const epoch = this.epoch;
    const reveal = () => {
      if (epoch !== this.epoch) return;
      this.frame = undefined;
      if (!this.blocked && this.track?.readyState !== 'ended' && !this.track?.muted && this.video.videoWidth > 0 && this.video.videoHeight > 0) this.overlay.hidden = true;
    };
    if (this.video.requestVideoFrameCallback) this.frame = this.video.requestVideoFrameCallback(reveal);
    else if (this.video.readyState >= 2) reveal();
  }
}
