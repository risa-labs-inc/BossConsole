/**
 * fluck.risaboss.com: the Fluck web chat itself, plus its sign-in portal.
 *
 *   /portal, /portal/*  -> https://api.risaboss.com/functions/v1/fluck-web/<rest>  (portal; prefix stripped)
 *   /portal/leave       -> clears the route cookie, 302 /portal/
 *   /internal/*         -> 404 (the function's Worker-only routes are never reachable from here)
 *   everything else     -> the BOSS named by the signed `__Host-fluck_route` cookie, i.e.
 *                          <its tunnel origin><path><search>, WebSockets included
 *
 * The function must be told it is served under /portal (FLUCK_WEB_PUBLIC_BASE_URL=https://fluck.risaboss.com,
 * FLUCK_WEB_PUBLIC_BASE_PATH=/portal), so its cookies carry Path=/portal and the magic-link
 * redirect_to is https://fluck.risaboss.com/portal/auth.
 *
 * Portal proxying:
 *   - Cookie carries ONLY the portal's own cookies (PORTAL_COOKIES): the chat's session (fc_session)
 *     and the route cookie never reach the function.
 *   - Sec-Fetch-Site, Origin, Content-Type, Accept, X-Fluck-Web-CSRF are forwarded untouched.
 *   - Host is dropped so the subrequest carries api.risaboss.com.
 *   - X-Fluck-Web-Alias-Secret and X-Fluck-Web-Client-Ip are stripped from the visitor and set here
 *     only: the secret (Worker secret FLUCK_WEB_ALIAS_SECRET, same value as the function's) is what
 *     makes the function trust the client IP and serve the page.
 *   - X-Forwarded-Proto is https so the function issues __Secure- cookies.
 *   - Set-Cookie: only the function's own (no Domain, or this host); api.risaboss.com's __cf_bm is dropped.
 *
 * Chat routing:
 *   - The route cookie is minted by the function on POST /api/open:
 *     base64url(JSON {"u": user_id, "i": instance_id, "e": expiry}) + "." + base64url(HMAC-SHA256(FLUCK_ROUTE_SECRET, payload)).
 *     u is the owner: instance ids are unique only per account, so the route is (u, i).
 *   - The endpoint comes from the function's GET /internal/endpoint?user=<u>&instance=<i>
 *     (X-Fluck-Route-Secret), cached per isolate by (u, i) for 30 s, and dropped on any upstream failure.
 *   - The route cookie and the portal's own cookies (session, PKCE, CSRF) are removed from the Cookie
 *     header; the chat's other cookies pass through. Origin is forwarded unchanged (the BOSS checks
 *     it on WebSocket upgrades). Visitor-supplied X-Fluck-Client-Ip* headers are dropped and
 *     replaced by CF-Connecting-IP, signed with Ed25519 (FLUCK_IP_SIGN_KEY, PKCS#8 PEM) over
 *     `${ip}|${ts}|${endpointHost}` so the BOSS can trust it without trusting the tunnel.
 *     Public key, for reference (raw 32 bytes, base64url): quQai_sQac5D6568f1e4O1YlWiPgO0z2QME5NeZEb_8
 *   - Upstream responses (including Set-Cookie and 101 upgrades) are returned as-is.
 *
 * Secrets: FLUCK_WEB_ALIAS_SECRET, FLUCK_ROUTE_SECRET, FLUCK_IP_SIGN_KEY (see wrangler.toml).
 */
const ORIGIN = "https://api.risaboss.com";
const BASE = "/functions/v1/fluck-web";
const ROUTE_COOKIE = "__Host-fluck_route";
/** The portal's own cookies (fluck-web/utils/cookies.ts). Never sent to a BOSS. */
const PORTAL_COOKIES = new Set([
  "__Secure-boss_fluck_at", "__Secure-boss_fluck_rt", "__Secure-boss_fluck_pkce", "__Host-boss_fluck_csrf",
  "boss_fluck_at", "boss_fluck_rt", "boss_fluck_pkce", "boss_fluck_csrf",
]);
const INSTANCE_RE = /^[A-Za-z0-9._:-]{1,128}$/;
const USER_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const B64URL_RE = /^[A-Za-z0-9_-]+$/;
const ENDPOINT_TTL_MS = 30_000;
const ENDPOINT_CACHE_MAX = 1000;
const OFFLINE_STATUSES = new Set([502, 503, 504, 530]);
const NO_STORE = { "Cache-Control": "no-store" };

/** `${user}\n${instance}` -> { endpoint, until } for this isolate. */
const endpointCache = new Map();

/** Test seam. */
export function resetEndpointCache() {
  endpointCache.clear();
}

/** True for a Set-Cookie line this host can own: no Domain attribute, or Domain = this host. */
export function isOwnCookie(setCookie, host) {
  for (const attr of setCookie.split(";").slice(1)) {
    const eq = attr.indexOf("=");
    if (eq < 0 || attr.slice(0, eq).trim().toLowerCase() !== "domain") continue;
    const domain = attr.slice(eq + 1).trim().replace(/^\./, "").toLowerCase();
    if (domain !== host.toLowerCase()) return false;
  }
  return true;
}

// ---- encoding ----

export function base64UrlEncode(bytes) {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlDecode(text) {
  if (!B64URL_RE.test(text)) return null;
  try {
    const bin = atob(text.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (text.length % 4)) % 4));
    return Uint8Array.from(bin, (c) => c.charCodeAt(0));
  } catch {
    return null;
  }
}

// ---- keys (imported once per isolate and secret value) ----

let hmacKey = { secret: null, key: null };
let signKey = { pem: null, key: null };

async function routeHmacKey(secret) {
  if (hmacKey.secret !== secret) {
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    hmacKey = { secret, key };
  }
  return hmacKey.key;
}

async function ipSignKey(pem) {
  if (signKey.pem !== pem) {
    const der = base64UrlDecode(pem.replace(/-----(BEGIN|END) [A-Z ]+-----/g, "").replace(/\s+/g, "").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""));
    if (!der) throw new Error("FLUCK_IP_SIGN_KEY is not a PEM");
    const key = await crypto.subtle.importKey("pkcs8", der, { name: "Ed25519" }, false, ["sign"]);
    signKey = { pem, key };
  }
  return signKey.key;
}

// ---- route cookie ----

/** Every value the Cookie header carries under `name`. */
function cookieValues(cookieHeader, name) {
  const out = [];
  for (const part of (cookieHeader || "").split(";")) {
    const eq = part.indexOf("=");
    if (eq >= 0 && part.slice(0, eq).trim() === name) out.push(part.slice(eq + 1).trim());
  }
  return out;
}

function filterCookies(cookieHeader, keep) {
  const kept = (cookieHeader || "").split(";").map((p) => p.trim()).filter((p) => {
    if (!p) return false;
    const eq = p.indexOf("=");
    return keep((eq < 0 ? p : p.slice(0, eq)).trim());
  });
  return kept.length ? kept.join("; ") : null;
}

/** The Cookie header minus the named cookies, or null when nothing is left. */
export function withoutCookies(cookieHeader, names) {
  return filterCookies(cookieHeader, (name) => !names.has(name));
}

/** Only the named cookies, or null when none is present. */
export function onlyCookies(cookieHeader, names) {
  return filterCookies(cookieHeader, (name) => names.has(name));
}

function setCookieHeader(headers, value) {
  if (value) headers.set("Cookie", value);
  else headers.delete("Cookie");
}

/**
 * The route { user, instance } of a valid route cookie value, or null. The HMAC is checked with
 * crypto.subtle.verify, which compares in constant time.
 */
export async function verifyRoute(value, secret, nowSeconds = Math.floor(Date.now() / 1000)) {
  if (!secret || typeof value !== "string" || value.length > 1024) return null;
  const parts = value.split(".");
  if (parts.length !== 2) return null;
  const [payload, sigText] = parts;
  const sig = base64UrlDecode(sigText);
  const payloadBytes = base64UrlDecode(payload);
  if (!sig || !payloadBytes) return null;
  const ok = await crypto.subtle.verify("HMAC", await routeHmacKey(secret), sig, new TextEncoder().encode(payload));
  if (!ok) return null;
  let claims;
  try {
    claims = JSON.parse(new TextDecoder().decode(payloadBytes));
  } catch {
    return null;
  }
  if (!claims || typeof claims.u !== "string" || !USER_RE.test(claims.u)) return null;
  if (typeof claims.i !== "string" || !INSTANCE_RE.test(claims.i)) return null;
  if (!Number.isSafeInteger(claims.e) || claims.e <= nowSeconds) return null;
  return { user: claims.u, instance: claims.i };
}

async function routeOf(request, secret) {
  for (const value of cookieValues(request.headers.get("Cookie"), ROUTE_COOKIE)) {
    const route = await verifyRoute(value, secret);
    if (route) return route;
  }
  return null;
}

// ---- endpoint lookup ----

/** `value` if it is a bare https origin, else null. */
function bareHttpsOrigin(value) {
  if (typeof value !== "string") return null;
  try {
    const u = new URL(value);
    return u.protocol === "https:" && u.origin === value ? value : null;
  } catch {
    return null;
  }
}

const cacheKey = (route) => `${route.user}\n${route.instance}`;

async function resolveEndpoint(route, secret) {
  const now = Date.now();
  const key = cacheKey(route);
  const hit = endpointCache.get(key);
  if (hit && hit.until > now) return hit.endpoint;
  endpointCache.delete(key);
  let res;
  try {
    const query = `user=${encodeURIComponent(route.user)}&instance=${encodeURIComponent(route.instance)}`;
    res = await fetch(`${ORIGIN}${BASE}/internal/endpoint?${query}`, {
      headers: { "X-Fluck-Route-Secret": secret, Accept: "application/json" },
      redirect: "manual",
    });
  } catch (e) {
    console.error("fluck endpoint lookup failed", e);
    return null;
  }
  if (res.status !== 200) {
    await res.body?.cancel();
    if (res.status !== 404) console.error("fluck endpoint lookup status", res.status);
    return null;
  }
  const body = await res.json().catch(() => null);
  const endpoint = bareHttpsOrigin(body?.endpoint);
  if (!endpoint) return null;
  if (endpointCache.size >= ENDPOINT_CACHE_MAX) endpointCache.delete(endpointCache.keys().next().value);
  endpointCache.set(key, { endpoint, until: now + ENDPOINT_TTL_MS });
  return endpoint;
}

// ---- responses ----

function json(body, status, extra = {}) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...NO_STORE, ...extra } });
}

function redirect(location, extra = {}) {
  return new Response(null, { status: 302, headers: { Location: location, ...NO_STORE, ...extra } });
}

function isNavigation(request) {
  return (request.method === "GET" || request.method === "HEAD") && (request.headers.get("Accept") || "").includes("text/html");
}

/** True for /internal and /internal/*, however the path is spelled. */
function isInternal(path) {
  let p = path;
  try {
    p = decodeURIComponent(path);
  } catch { /* keep it raw */ }
  return /^\/+internal(\/|$)/i.test(p.replace(/\/{2,}/g, "/"));
}

function requestInit(request, headers) {
  const init = { method: request.method, headers, redirect: "manual" };
  if (request.method !== "GET" && request.method !== "HEAD") {
    init.body = request.body;
    init.duplex = "half"; // required by the Fetch standard for a streamed body
  }
  return init;
}

// ---- handlers ----

async function portal(request, env, url) {
  const rest = url.pathname.slice("/portal".length);
  if (isInternal(rest)) return json({ error: "not_found" }, 404);
  const target = ORIGIN + BASE + (rest === "" || rest === "/" ? "" : rest) + url.search;
  const headers = new Headers(request.headers);
  headers.delete("Host");
  setCookieHeader(headers, onlyCookies(request.headers.get("Cookie"), PORTAL_COOKIES));
  headers.delete("X-Fluck-Web-Alias-Secret");
  headers.delete("X-Fluck-Web-Client-Ip");
  headers.delete("X-Fluck-Route-Secret");
  headers.set("X-Forwarded-Proto", "https");
  headers.set("X-Forwarded-Host", url.host);
  // Claims the alias; the function only believes it alongside the secret, and refuses (rather
  // than redirecting back here) when the secret is missing or wrong.
  headers.set("X-Fluck-Web-Alias", url.host);
  const secret = env?.FLUCK_WEB_ALIAS_SECRET;
  if (secret) headers.set("X-Fluck-Web-Alias-Secret", secret);
  // Cloudflare sets CF-Connecting-IP on our inbound request but not on the subrequest.
  const ip = request.headers.get("CF-Connecting-IP");
  if (ip) headers.set("X-Fluck-Web-Client-Ip", ip);
  let upstream;
  try {
    upstream = await fetch(target, requestInit(request, headers));
  } catch (e) {
    console.error("fluck-web upstream fetch failed", e);
    return json({ error: "upstream" }, 502);
  }
  const out = new Headers(upstream.headers);
  out.delete("Set-Cookie");
  for (const c of upstream.headers.getSetCookie()) if (isOwnCookie(c, url.hostname)) out.append("Set-Cookie", c);
  return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: out });
}

function leave() {
  return redirect("/portal/", { "Set-Cookie": `${ROUTE_COOKIE}=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0` });
}

async function signedIpHeaders(request, env, endpointHost) {
  const ip = request.headers.get("CF-Connecting-IP");
  const pem = env?.FLUCK_IP_SIGN_KEY;
  if (!ip || !pem) {
    if (ip) console.error("FLUCK_IP_SIGN_KEY is not set; forwarding without a client IP");
    return null;
  }
  const ts = String(Math.floor(Date.now() / 1000));
  try {
    const sig = await crypto.subtle.sign({ name: "Ed25519" }, await ipSignKey(pem), new TextEncoder().encode(`${ip}|${ts}|${endpointHost}`));
    return { ip, ts, sig: base64UrlEncode(new Uint8Array(sig)) };
  } catch (e) {
    console.error("client IP signing failed; forwarding without a client IP", e);
    return null;
  }
}

async function chat(request, env, url) {
  const secret = env?.FLUCK_ROUTE_SECRET;
  if (!secret) console.error("FLUCK_ROUTE_SECRET is not set; every chat request goes to the portal");
  const route = secret ? await routeOf(request, secret) : null;
  if (!route) return isNavigation(request) ? redirect("/portal/") : json({ error: "no_route" }, 401);

  const offline = () => {
    endpointCache.delete(cacheKey(route));
    return isNavigation(request)
      ? redirect(`/portal/?reopen=${encodeURIComponent(route.instance)}`)
      : json({ error: "boss_offline" }, 502);
  };
  const endpoint = await resolveEndpoint(route, secret);
  if (!endpoint) return offline();
  const endpointHost = new URL(endpoint).hostname.toLowerCase();

  const headers = new Headers(request.headers);
  headers.delete("Host");
  for (const name of [...headers.keys()]) if (name.toLowerCase().startsWith("x-fluck-client-ip")) headers.delete(name);
  headers.delete("X-Fluck-Web-Alias-Secret");
  headers.delete("X-Fluck-Route-Secret");
  setCookieHeader(headers, withoutCookies(request.headers.get("Cookie"), new Set([ROUTE_COOKIE, ...PORTAL_COOKIES])));
  const signed = await signedIpHeaders(request, env, endpointHost);
  if (signed) {
    headers.set("X-Fluck-Client-Ip", signed.ip);
    headers.set("X-Fluck-Client-Ip-Ts", signed.ts);
    headers.set("X-Fluck-Client-Ip-Sig", signed.sig);
  }
  headers.set("X-Forwarded-Proto", "https");
  headers.set("X-Forwarded-Host", url.host);
  headers.set("X-Fluck-Web-Alias", url.host);

  let upstream;
  try {
    upstream = await fetch(endpoint + url.pathname + url.search, requestInit(request, headers));
  } catch (e) {
    console.error("fluck upstream fetch failed", e);
    return offline();
  }
  if (OFFLINE_STATUSES.has(upstream.status)) {
    await upstream.body?.cancel();
    return offline();
  }
  // As-is: keeps every Set-Cookie and, for an Upgrade, the webSocket.
  return upstream;
}

export default {
  fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    if (path === "/portal/leave") return leave();
    if (path === "/portal" || path.startsWith("/portal/")) return portal(request, env, url);
    if (isInternal(path)) return json({ error: "not_found" }, 404);
    return chat(request, env, url);
  },
};
