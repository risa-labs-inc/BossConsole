// Explicit opt-in smoke test: creates/deletes two synthetic auth users.
// Set ALLOW_CREATE_TEST_USERS=true only on the backend you intend to validate.
// Requires Node >=22 and ENV: SUPABASE_URL, SUPABASE_ANON_KEY,
// SUPABASE_SERVICE_ROLE_KEY, RELAY_WS_URL (wss://debug-host, no room path).
// Never pass credentials as command arguments. This file contains no credentials.
// Tests transport routing with opaque fixtures, not terminal E2E cryptography/rendering.
import { randomBytes, randomUUID } from 'node:crypto';
import { request as httpsRequest } from 'node:https';

let current = 'configuration';
const users = [], sockets = [];
const requireEnv = name => { const value = process.env[name]; if (!value) throw Error('missing environment'); return value; };
const check = condition => { if (!condition) throw Error('case assertion'); };
const pass = label => console.log(`PASS ${label}`);
let base, anon, service, relay;
async function request(path, token, body, method = 'POST', admin = false) {
  const response = await fetch(`${base}${path}`, {
    method, headers: { apikey: admin ? service : anon, Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(15000),
  });
  const data = await response.json().catch(() => null);
  return { ok: response.ok, status: response.status, data };
}
async function rpc(name, token, body = {}, expectedCode) {
  const response = await request(`/rest/v1/rpc/${name}`, token, body, 'POST', token === service);
  if (expectedCode && (response.ok || response.data?.code !== expectedCode)) console.log('RPC mismatch', name, response.status, response.data?.code ?? 'no-code', 'expected', expectedCode);
  if (!expectedCode && !response.ok) console.log('RPC failed', name, response.status, response.data?.code ?? 'no-code');
  if (expectedCode) check(!response.ok && response.data?.code === expectedCode);
  else check(response.ok);
  return response.data;
}
function browserRequest(url, options = {}) {
  return new Promise((resolve, reject) => {
    const req = httpsRequest(url, { method: options.method ?? 'GET', headers: options.headers ?? {} }, res => {
      const chunks = []; let size = 0;
      res.on('data', chunk => { size += chunk.length; if (size > 1024 * 1024) req.destroy(new Error('oversized response')); else chunks.push(chunk); });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, text: Buffer.concat(chunks).toString('utf8') }));
      res.on('error', reject);
    });
    req.setTimeout(15000, () => req.destroy(new Error('request timeout')));
    req.on('error', reject); req.end(options.body);
  });
}

async function createUser() {
  const email = `boss-relay-smoke-${randomUUID()}@example.invalid`;
  const password = randomBytes(32).toString('base64url');
  const created = await request('/auth/v1/admin/users', service, { email, password, email_confirm: true }, 'POST', true);
  check(created.ok && typeof created.data?.id === 'string');
  const user = { id: created.data.id }; users.push(user); // Register cleanup before token grant.
  const login = await request('/auth/v1/token?grant_type=password', anon, { email, password });
  check(login.ok && typeof login.data?.access_token === 'string');
  user.jwt = login.data.access_token;
  return user;
}
async function connection(room, ticket, denied = false) {
  const ws = new WebSocket(`${relay}/v1/rooms/${room}`); sockets.push(ws);
  const queue = []; let wake;
  let closed = null;
  ws.addEventListener('message', event => {
    try { queue.push(JSON.parse(event.data)); } catch { queue.push({ op: 'invalid' }); }
    wake?.();
  });
  ws.addEventListener('close', event => { closed = event.code; wake?.(); });
  ws.addEventListener('error', () => {}); // Suppress endpoint/credential-bearing diagnostics.
  async function wait(predicate) {
    const deadline = Date.now() + 15000;
    for (;;) {
      const value = predicate(); if (value !== undefined) return value;
      if (Date.now() >= deadline) throw Error('socket timeout');
      await new Promise(resolve => { const timer = setTimeout(() => { wake = undefined; resolve(); }, 100); wake = () => { clearTimeout(timer); wake = undefined; resolve(); }; });
    }
  }
  await wait(() => ws.readyState === WebSocket.OPEN ? true : closed !== null ? false : undefined).then(check);
  const send = message => ws.send(JSON.stringify(message));
  const next = (op, predicate = () => true) => wait(() => {
    const index = queue.findIndex(message => message.op === op && predicate(message));
    if (index >= 0) return queue.splice(index, 1)[0];
    if (closed !== null) throw Error('socket closed');
  });
  send({ op: 'hello', v: 1, room, ...(ticket ? { ticket } : {}) });
  const peer = { ws, send, next, closed: () => wait(() => closed ?? undefined) };
  if (!denied) peer.id = (await next('welcome')).peer;
  return peer;
}

try {
  check(requireEnv('ALLOW_CREATE_TEST_USERS') === 'true');
  base = requireEnv('SUPABASE_URL').replace(/\/$/, '');
  anon = requireEnv('SUPABASE_ANON_KEY'); service = requireEnv('SUPABASE_SERVICE_ROLE_KEY');
  relay = requireEnv('RELAY_WS_URL').replace(/\/$/, '');
  check(new URL(base).protocol === 'https:' && new URL(relay).protocol === 'wss:');
  check(new URL(base).pathname === '/' && new URL(relay).pathname === '/');
  check(!new URL(base).username && !new URL(relay).username && !new URL(relay).search);
  check(typeof WebSocket === 'function');
  current = 'synthetic users';
  const a = await createUser(), b = await createUser(); pass(current);

  current = 'preferences defaults and owner isolation';
  const defaults = await rpc('get_user_terminal_preferences', a.jwt, { p_expected_user_id: a.id });
  check(defaults.revision === 0 && defaults.unfocused_mode === 'batch' && defaults.unfocused_fps === 4);
  await rpc('get_user_terminal_preferences', a.jwt, { p_expected_user_id: b.id }, '42501');
  await rpc('get_user_terminal_preferences', a.jwt, { p_actor_id: b.id }, '42501'); pass(current);
  current = 'preferences CAS and persistence';
  const saved = await rpc('set_user_terminal_preferences', a.jwt, { p_unfocused_mode: 'preview', p_unfocused_fps: 7, p_revision: 0 });
  check(saved.revision === 1 && saved.unfocused_mode === 'preview' && saved.unfocused_fps === 7);
  await rpc('set_user_terminal_preferences', a.jwt, { p_unfocused_mode: 'batch', p_unfocused_fps: 4, p_revision: 0 }, 'PT409');
  await rpc('set_user_terminal_preferences', a.jwt, { p_unfocused_mode: 'preview', p_unfocused_fps: 7, p_revision: 0, p_actor_id: b.id }, '42501');
  const reread = await rpc('get_user_terminal_preferences', a.jwt);
  check(reread.revision === 1 && reread.unfocused_fps === 7);
  check((await rpc('get_user_terminal_preferences', b.jwt)).revision === 0); pass(current);

  current = 'handoff owner guard and single use';
  await rpc('mint_user_settings_handoff', a.jwt, { p_expected_user_id: b.id }, '42501');
  const handoff = await rpc('mint_user_settings_handoff', a.jwt, { p_expected_user_id: a.id });
  await rpc('consume_user_settings_handoff', b.jwt, { p_token: handoff.token }, '42501');
  check((await rpc('consume_user_settings_handoff', service, { p_token: handoff.token })).user_id === a.id);
  await rpc('consume_user_settings_handoff', service, { p_token: handoff.token }, '42501'); pass(current);

  current = 'settings page handoff, cookie, CSRF and conflict';
  const pageUrl = `${base}/functions/v1/user-settings`;
  const pageToken = (await rpc('mint_user_settings_handoff', a.jwt)).token;
  const navigation = { 'Sec-Fetch-Site': 'none', 'Sec-Fetch-Mode': 'navigate' };
  const handoffPage = await browserRequest(`${pageUrl}?t=${pageToken}`, { headers: navigation });
  check(handoffPage.status === 302);
  const setCookie = handoffPage.headers['set-cookie']?.[0];
  check(setCookie?.includes('HttpOnly') && setCookie.includes('Secure') && setCookie.includes('SameSite=Lax'));
  const cookie = setCookie.split(';')[0];
  check((await browserRequest(`${pageUrl}?t=${pageToken}`, { headers: navigation })).status === 400);
  const page = await browserRequest(pageUrl, { headers: { Cookie: cookie } });
  check(page.status === 200);
  const csrf = page.text.match(/name="csrf" value="([^"]+)"/)?.[1];
  const revision = page.text.match(/name="revision" value="([^"]+)"/)?.[1];
  check(csrf && revision === '1');
  const post = (csrfValue, origin = new URL(base).origin) => browserRequest(pageUrl, {
    method: 'POST', headers: { Cookie: cookie, Origin: origin, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ csrf: csrfValue, revision, mode: 'batch', fps: '5', action: 'save' }).toString(),
  });
  check((await post('invalid')).status === 403);
  check((await post(csrf, 'https://attacker.invalid')).status === 403);
  check((await post(csrf)).status === 303);
  check((await post(csrf)).status === 409);
  const pageSaved = await rpc('get_user_terminal_preferences', a.jwt);
  check(pageSaved.revision === 2 && pageSaved.unfocused_mode === 'batch' && pageSaved.unfocused_fps === 5);
  pass(current);

  const room = randomUUID();
  const mint = role => rpc('mint_terminal_relay_ticket', a.jwt, { p_room_id: room, p_role: role, p_expected_user_id: a.id });
  current = 'ticket owner and expected-account guards';
  await rpc('mint_terminal_relay_ticket', a.jwt, { p_room_id: room, p_role: 'host', p_expected_user_id: b.id }, '42501');
  const hostTicket = await mint('host');
  for (const role of ['host', 'account']) await rpc('mint_terminal_relay_ticket', b.jwt, { p_room_id: room, p_role: role }, '42501');
  await rpc('consume_terminal_relay_ticket', a.jwt, { p_room_id: room, p_token: hostTicket.ticket }, '42501');
  await rpc('consume_terminal_relay_ticket', service, { p_room_id: randomUUID(), p_token: hostTicket.ticket }, '42501'); pass(current);

  current = 'debug Worker host admission and ticket replay';
  const host = await connection(room, hostTicket.ticket); await host.next('peers');
  const replay = await connection(room, hostTicket.ticket, true); check(await replay.closed() === 1008); pass(current);
  current = 'account and guest role classification';
  const viewers = [];
  for (const isAccount of [true, true, false]) {
    const ticket = isAccount ? (await mint('account')).ticket : undefined;
    const viewer = await connection(room, ticket);
    const joined = await host.next('join', message => message.peer === viewer.id);
    check(JSON.parse(joined.payload).account === isAccount);
    viewers.push(viewer);
  }
  pass(current);
  current = 'unapproved guest cannot subscribe';
  const unapproved = await connection(room); await host.next('join', message => message.peer === unapproved.id);
  unapproved.send({ op: 'subscribe', pane: 'p', mode: 'live', fps: 4 });
  check(await unapproved.closed() === 1008); pass(current);
  current = 'three approved viewers receive one published frame';
  for (const viewer of viewers) {
    host.send({ op: 'grant', peer: viewer.id, panes: ['p'] }); await viewer.next('grant');
    viewer.send({ op: 'subscribe', pane: 'p', mode: 'live', fps: 4 });
    await host.next('subscribe', message => message.peer === viewer.id);
    host.send({ op: 'snapshot', peer: viewer.id, pane: 'p', epoch: 'smoke', seq: 0, payload: 'synthetic-snapshot' });
    const snapshot = await viewer.next('frames', frame => frame.messages?.some(message => message.op === 'snapshot'));
    viewer.send({ op: 'ack', through: snapshot.delivery });
  }
  const payload = `synthetic-${randomUUID()}`;
  host.send({ op: 'output', pane: 'p', kind: 'live', epoch: 'smoke', seq: 1, payload }); // Exactly one publish.
  for (const viewer of viewers) {
    const frame = await viewer.next('frames', message => message.messages?.some(item => item.op === 'output'));
    check(frame.messages.length === 1 && frame.messages[0].payload === payload && frame.messages[0].seq === 1);
    viewer.send({ op: 'ack', through: frame.delivery });
  }
  pass(current);
  current = 'host remains active after rejected admissions';
  host.send({ op: 'ping' }); await host.next('pong'); pass(current);
} catch {
  // Never dump Error objects, assertion values, request bodies or URLs.
  console.error(`FAIL ${current}`); process.exitCode = 1;
} finally {
  for (const ws of sockets) { try { ws.close(); } catch {} }
  let clean = true;
  for (const user of users.reverse()) {
    let deleted = false;
    for (let attempt = 0; attempt < 3 && !deleted; attempt++) {
      try { const result = await request(`/auth/v1/admin/users/${user.id}`, service, undefined, 'DELETE', true); deleted = result.ok || result.status === 404; } catch {}
    }
    clean &&= deleted;
  }
  if (users.length) {
    if (clean) pass('synthetic users deleted (owned rows cascade)');
    else { console.error('FAIL synthetic user cleanup requires operator follow-up'); process.exitCode = 1; }
  }
}
