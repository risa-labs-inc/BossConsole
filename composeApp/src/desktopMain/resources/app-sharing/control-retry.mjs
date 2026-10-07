import { transientSharingFailure } from './recovery.mjs';

export const CONTROL_ATTEMPTS = 3;
export function controlFailure(error) {
  if (error?.status === 401 || error?.status === 403) return 'denied';
  if (error?.status === 409 && error?.code !== 'publication_pending') return 'busy';
  if (transientSharingFailure(error) || error?.code === 'publication_pending') return 'temporary';
  return 'unavailable';
}
function pause(ms, signal) {
  return new Promise(resolve => {
    const done = () => { clearTimeout(timer); signal.removeEventListener('abort', done); resolve(); };
    const timer = setTimeout(done, ms);
    signal.addEventListener('abort', done, { once: true });
    if (signal.aborted) done();
  });
}
/** Retry only lease acquisition, never replay initial SFU transport creation. */
export async function acquireControlWithRetry(acquire, { signal, onAttempt = () => {}, wait = pause }) {
  for (let attempt = 1; attempt <= CONTROL_ATTEMPTS && !signal.aborted; attempt++) {
    onAttempt(attempt);
    try {
      await acquire();
      return signal.aborted ? { cancelled: true } : { connected: true };
    } catch (error) {
      if (signal.aborted) return { cancelled: true };
      const kind = controlFailure(error);
      if (!['busy', 'temporary'].includes(kind) || attempt === CONTROL_ATTEMPTS) return { kind, attempts: attempt };
      await wait(attempt * 1000, signal);
    }
  }
  return { cancelled: true };
}
