import { createBridge } from './bridge.mjs';

/** Cookie/CSRF authenticated web entry. Keys are read from the owner's registry,
 * never accepted from a forwarded link as proof of account admission. */
export function sharedWindows(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 16) throw new Error('The shared windows are unavailable.');
  const seen = new Set();
  return value.map(window => {
    if (!window || typeof window.id !== 'string' || !/^[A-Za-z0-9_.:-]{1,128}$/.test(window.id) || seen.has(window.id) || (window.title != null && (typeof window.title !== 'string' || window.title.length > 160))) throw new Error('The shared windows are unavailable.');
    seen.add(window.id);
    return { id: window.id, title: window.title || 'BossConsole window' };
  });
}

export async function browserViewerConfig(location = globalThis.location, fetcher = fetch, selectedWindowId = null) {
  const page = new URL(location.href);
  const sessionId = page.searchParams.get('session');
  const requestedWindow = selectedWindowId ?? page.searchParams.get('window');
  if (!sessionId) throw new Error('Open a BossConsole session from your account.');
  const response = await fetcher(new URL('../api/app-sharing-bootstrap', page), { credentials: 'same-origin', cache: 'no-store', redirect: 'error' });
  if (response.status === 401 || response.status === 403) {
    const error = new Error('Sign in to your BossConsole account, then reopen this link.');
    error.loginUrl = new URL('../', page).href;
    throw error;
  }
  if (!response.ok) throw new Error('Application sharing is unavailable.');
  const bootstrap = await response.json();
  if (typeof bootstrap.csrf !== 'string' || bootstrap.csrf.length < 16) throw new Error('Invalid account session.');
  const auth = { apiUrl: new URL('../api/app-sharing', page).href, csrf: bootstrap.csrf };
  const bridge = createBridge(auth);
  try {
    const listing = await bridge.request({ action: 'list' });
    const session = listing.sessions?.find(value => value.session_id === sessionId);
    if (!session) throw new Error('This BossConsole session is unavailable for your account.');
    const windows = sharedWindows(session.windows);
    const window = windows.find(value => requestedWindow ? value.id === requestedWindow : true);
    if (!window) throw new Error('The shared window is no longer available.');
    const deviceId = crypto.randomUUID();
    const scope = { session_id: session.session_id, generation: session.generation, device_id: deviceId };
    let role = 'control', admitted;
    try { admitted = await bridge.request({ action: 'admit', ...scope, role }); }
    catch (error) {
      if (error.status !== 403) throw error;
      role = 'view'; admitted = await bridge.request({ action: 'admit', ...scope, role });
    }
    const consumed = await bridge.request({ action: 'consume', ...scope, role, ticket: admitted.ticket });
    const registeredLink = new URL(admitted.viewer_url ?? session.viewer_url);
    const mediaRootKey = new URLSearchParams(registeredLink.hash.slice(1)).get('k');
    if (!mediaRootKey) throw new Error('The sharing key is unavailable.');
    return { ...auth, returnUrl: new URL('../', page).href, sessionId: session.session_id, generation: session.generation, peerId: consumed.peer_id, windowId: window.id, windows, canSwitchWindows: true, keyEpoch: admitted.key_epoch ?? session.key_epoch, hostPublicKey: admitted.host_public_key ?? session.host_public_key, mediaRootKey, role };
  } finally { bridge.close(); }
}
