// Fullscreen the viewer root, so its controls remain available alongside the stream.
export function setupViewerFullscreen({ document, root, button, focusStream }) {
  if (!button) return;
  const supported = () => document.fullscreenEnabled !== false && typeof root?.requestFullscreen === 'function';
  let pending = false;
  function update() {
    const active = document.fullscreenElement === root;
    button.disabled = pending || !supported();
    button.textContent = active ? 'Exit fullscreen' : 'Fullscreen';
    button.setAttribute('aria-pressed', String(active));
    button.title = supported() ? (active ? 'Exit viewer fullscreen' : 'View the stream and controls in fullscreen')
      : 'Fullscreen is unavailable in this browser';
  }
  button.addEventListener('click', async () => {
    if (pending || !supported()) return;
    pending = true; update();
    let failed = false;
    try {
      if (document.fullscreenElement === root) await document.exitFullscreen();
      else await root.requestFullscreen();
      focusStream();
    } catch {
      failed = true;
    } finally {
      pending = false;
      update();
      if (failed) button.title = 'Could not change fullscreen. Try again.';
    }
  });
  document.addEventListener('fullscreenchange', update);
  update();
}
