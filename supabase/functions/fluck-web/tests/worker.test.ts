/**
 * The fluck.risaboss.com Worker (infra/cloudflare/fluck-web-alias/worker.js), with the global fetch
 * stubbed. Lives here so the fluck-web CI job runs it; the last tests chain it into the app.
 */

import { assert, assertEquals, assertMatch } from "@std/assert"
import worker, { isOwnCookie, onlyCookies, resetEndpointCache, verifyRoute, withoutCookies } from "../../../../infra/cloudflare/fluck-web-alias/worker.js"
import { app, deps } from "../app.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"
import { routeCookieValue } from "../utils/route.ts"

const SECRET = "s".repeat(64)
const ROUTE_SECRET = "r".repeat(64)
const LOOKUP = "https://api.risaboss.com/functions/v1/fluck-web/internal/endpoint"
const TUNNEL = "https://abc-def.trycloudflare.com"
const USER = "0b6f2c1e-5d4a-4c3b-9a8f-7e6d5c4b3a21"
const OTHER_USER = "9f8e7d6c-5b4a-4321-8fed-cba987654321"

// A throwaway Ed25519 key pair, never the deployed one.
const PAIR = await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]) as CryptoKeyPair
const PKCS8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", PAIR.privateKey))
const PEM = "-----BEGIN PRIVATE KEY-----\n" + (btoa(String.fromCharCode(...PKCS8)).match(/.{1,64}/g) ?? []).join("\n") + "\n-----END PRIVATE KEY-----\n"
const ENV = { FLUCK_WEB_ALIAS_SECRET: SECRET, FLUCK_ROUTE_SECRET: ROUTE_SECRET, FLUCK_IP_SIGN_KEY: PEM }

type Upstream = (req: Request) => Response | Promise<Response>

async function through(
  request: Request,
  upstream: Upstream,
  env: Record<string, string> = ENV,
): Promise<{ seen: Request[]; res: Response }> {
  const original = globalThis.fetch
  const seen: Request[] = []
  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const req = new Request(input, init)
    seen.push(req)
    return await upstream(req)
  }
  try {
    const res = await worker.fetch(request, env)
    return { seen, res }
  } finally {
    globalThis.fetch = original
  }
}

function quiet<T>(fn: () => Promise<T>): Promise<T> {
  const original = console.error
  console.error = () => {}
  return fn().finally(() => (console.error = original))
}

async function routeCookie(instance = "inst-a", now?: number, user = USER): Promise<string> {
  return `__Host-fluck_route=${await routeCookieValue(ROUTE_SECRET, user, instance, now)}`
}

/** Endpoint lookup answers `endpoint`; the tunnel answers `tunnel`. */
function boss(tunnel: Upstream = () => new Response("chat"), endpoint: string | null = TUNNEL): Upstream {
  return (req) => {
    if (req.url.startsWith(LOOKUP)) {
      return endpoint ? Response.json({ endpoint }) : Response.json({ error: "not_found" }, { status: 404 })
    }
    if (req.url.startsWith(TUNNEL)) return tunnel(req)
    return new Response("unexpected", { status: 599 })
  }
}

const NAV = { Accept: "text/html,application/xhtml+xml" }

function b64urlDecode(s: string): Uint8Array<ArrayBuffer> {
  return Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - s.length % 4) % 4)), (c) => c.charCodeAt(0))
}

// ---- /portal ----

Deno.test("/portal/* maps onto the function with the prefix stripped, and strips visitor-supplied trust headers", async () => {
  const req = new Request("https://fluck.risaboss.com/portal/api/instances?x=1", {
    headers: {
      "CF-Connecting-IP": "198.51.100.7",
      "X-Fluck-Web-Client-Ip": "10.9.9.9",
      "X-Fluck-Web-Alias-Secret": "forged",
      "X-Fluck-Route-Secret": "forged",
      "X-Forwarded-Proto": "http",
      cookie: "a=b",
    },
  })
  const { seen } = await through(req, () => new Response("{}"))
  assertEquals(seen.length, 1)
  assertEquals(seen[0].url, "https://api.risaboss.com/functions/v1/fluck-web/api/instances?x=1")
  assertEquals(seen[0].headers.get("x-fluck-web-client-ip"), "198.51.100.7")
  assertEquals(seen[0].headers.get("x-fluck-web-alias-secret"), SECRET)
  assertEquals(seen[0].headers.get("x-fluck-route-secret"), null)
  assertEquals(seen[0].headers.get("x-fluck-web-alias"), "fluck.risaboss.com")
  assertEquals(seen[0].headers.get("x-forwarded-proto"), "https")
  assertEquals(seen[0].headers.get("x-forwarded-host"), "fluck.risaboss.com")
  assertEquals(seen[0].headers.get("cookie"), null, "a non-portal cookie is not forwarded")
})

Deno.test("/portal forwards only the portal's own cookies: fc_session, the route cookie and others are stripped", async () => {
  const cookie = `fc_session=chat-secret; ${await routeCookie()}; __Secure-boss_fluck_at=jwt; theme=dark; ` +
    `__Secure-boss_fluck_rt=rt; __Host-boss_fluck_csrf=n; __Secure-boss_fluck_pkce=v`
  for (const path of ["/portal/", "/portal/api/instances", "/portal/api/open"]) {
    const { seen } = await through(new Request(`https://fluck.risaboss.com${path}`, { headers: { cookie } }), () => new Response("{}"))
    assertEquals(
      seen[0].headers.get("cookie"),
      "__Secure-boss_fluck_at=jwt; __Secure-boss_fluck_rt=rt; __Host-boss_fluck_csrf=n; __Secure-boss_fluck_pkce=v",
      path,
    )
  }
  const { seen } = await through(new Request("https://fluck.risaboss.com/portal/", { headers: { cookie: "fc_session=x" } }), () => new Response("ok"))
  assertEquals(seen[0].headers.get("cookie"), null)
})

Deno.test("/portal and /portal/ map onto the function root; a POST body and redirect: manual pass through", async () => {
  for (const path of ["/portal", "/portal/", "/portal/?reopen=inst-a"]) {
    const { seen } = await through(new Request(`https://fluck.risaboss.com${path}`, { headers: { host: "fluck.risaboss.com" } }), () => new Response("ok"))
    assertEquals(seen[0].url, "https://api.risaboss.com/functions/v1/fluck-web" + (path.includes("?") ? "?reopen=inst-a" : ""), path)
    assertEquals(seen[0].headers.get("host"), null)
  }
  const { seen, res } = await through(
    new Request("https://fluck.risaboss.com/portal/api/otp", { method: "POST", body: '{"email":"a@b.c"}' }),
    () => new Response(null, { status: 302, headers: { Location: "/portal/" } }),
  )
  assertEquals(await seen[0].text(), '{"email":"a@b.c"}')
  assertEquals(res.status, 302)
  assertEquals(res.headers.get("location"), "/portal/")
})

Deno.test("/portalx is not the portal", async () => {
  const { seen, res } = await through(new Request("https://fluck.risaboss.com/portalx", { headers: NAV }), boss())
  assertEquals(seen.length, 0)
  assertEquals(res.status, 302)
  assertEquals(res.headers.get("location"), "/portal/")
})

Deno.test("the portal without the alias secret forwards no secret (the function then refuses, never loops)", async () => {
  const req = new Request("https://fluck.risaboss.com/portal/", { headers: { "X-Fluck-Web-Alias-Secret": "forged" } })
  const { seen } = await through(req, () => new Response("ok"), {})
  assertEquals(seen[0].headers.get("x-fluck-web-alias-secret"), null)
})

Deno.test("the portal keeps the function's cookies and drops upstream cookies scoped to another domain", async () => {
  const upstream = new Headers()
  upstream.append("Set-Cookie", "__Secure-boss_fluck_at=t; Path=/portal; HttpOnly; Secure; SameSite=Lax")
  upstream.append("Set-Cookie", "__cf_bm=abc; HttpOnly; SameSite=None; Secure; Path=/; Domain=api.risaboss.com")
  upstream.append("Set-Cookie", "__Host-fluck_route=v; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=2592000")
  upstream.set("Content-Type", "application/json")
  const { res } = await through(
    new Request("https://fluck.risaboss.com/portal/api/open", { method: "POST", body: "{}" }),
    () => new Response("{}", { status: 201, headers: upstream }),
  )
  assertEquals(res.status, 201)
  assertEquals(res.headers.get("content-type"), "application/json")
  const cookies = res.headers.getSetCookie()
  assertEquals(cookies.length, 2)
  assert(cookies[0].startsWith("__Secure-boss_fluck_at="))
  assert(cookies[1].startsWith("__Host-fluck_route="))
})

Deno.test("isOwnCookie", () => {
  assert(isOwnCookie("a=b; Path=/", "fluck.risaboss.com"))
  assert(isOwnCookie("a=b; domain=fluck.risaboss.com", "fluck.risaboss.com"))
  assert(!isOwnCookie("a=b; Domain=risaboss.com", "fluck.risaboss.com"))
  assert(!isOwnCookie("a=b; Domain=api.risaboss.com", "fluck.risaboss.com"))
})

Deno.test("the portal answers 502 when the function fetch throws", () =>
  quiet(async () => {
    const { res } = await through(new Request("https://fluck.risaboss.com/portal/health"), () => {
      throw new Error("boom")
    })
    assertEquals(res.status, 502)
  }))

Deno.test("/portal/leave clears the route cookie and goes to the portal, without touching upstream", async () => {
  const { seen, res } = await through(new Request("https://fluck.risaboss.com/portal/leave", { headers: { cookie: await routeCookie() } }), boss())
  assertEquals(seen.length, 0)
  assertEquals(res.status, 302)
  assertEquals(res.headers.get("location"), "/portal/")
  assertEquals(res.headers.getSetCookie(), ["__Host-fluck_route=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0"])
})

// ---- /internal ----

Deno.test("/internal/* is never forwarded, however it is spelled, top-level or under /portal", async () => {
  const cookie = await routeCookie()
  for (
    const path of [
      "/internal/endpoint?instance=inst-a", "/internal", "/INTERNAL/endpoint", "//internal/endpoint", "/%69nternal/endpoint",
      "/portal/internal/endpoint?instance=inst-a", "/portal//internal/endpoint", "/portal/Internal/endpoint", "/portal/%69nternal/endpoint",
      "/portal/../internal/endpoint",
    ]
  ) {
    const { seen, res } = await through(new Request(`https://fluck.risaboss.com${path}`, { headers: { cookie, "X-Fluck-Route-Secret": ROUTE_SECRET } }), boss())
    assertEquals(res.status, 404, path)
    assertEquals(seen.length, 0, path)
  }
})

// ---- route cookie ----

Deno.test("verifyRoute accepts a minted cookie and rejects tampering, a wrong secret, expiry, and junk", async () => {
  const now = Math.floor(Date.now() / 1000)
  const good = await routeCookieValue(ROUTE_SECRET, USER, "inst-a", now)
  assertEquals(await verifyRoute(good, ROUTE_SECRET), { user: USER, instance: "inst-a" })
  assertEquals(await verifyRoute(good, "x".repeat(64)), null)
  assertEquals(await verifyRoute(good, ""), null)
  const [payload, sig] = good.split(".")
  const forged = btoa(JSON.stringify({ u: OTHER_USER, i: "inst-a", e: now + 999 })).replace(/=+$/, "")
  assertEquals(await verifyRoute(`${forged}.${sig}`, ROUTE_SECRET), null)
  assertEquals(await verifyRoute(`${payload}.${sig.slice(0, -2)}AA`, ROUTE_SECRET), null)
  const expired = await routeCookieValue(ROUTE_SECRET, USER, "inst-a", now - 30 * 24 * 3600 - 1)
  assertEquals(await verifyRoute(expired, ROUTE_SECRET), null)
  assertEquals(await verifyRoute(good, ROUTE_SECRET, now + 30 * 24 * 3600), null, "expiry is exclusive")
  // Correctly signed but without an owner (the pre-binding format), or with a non-uuid owner.
  const sign = async (claims: unknown) => {
    const payload = btoa(JSON.stringify(claims)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_")
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(ROUTE_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"])
    const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload)))
    return `${payload}.${btoa(String.fromCharCode(...mac)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_")}`
  }
  assertEquals(await verifyRoute(await sign({ i: "inst-a", e: now + 999 }), ROUTE_SECRET), null)
  assertEquals(await verifyRoute(await sign({ u: "u1", i: "inst-a", e: now + 999 }), ROUTE_SECRET), null)
  assertEquals(await verifyRoute(await sign({ u: USER, i: "inst-a", e: now + 999 }), ROUTE_SECRET), { user: USER, instance: "inst-a" })
  for (const junk of ["", ".", "a.b.c", `${payload}.`, `.${sig}`, `${payload}.${sig}!`, "x".repeat(2000)]) {
    assertEquals(await verifyRoute(junk, ROUTE_SECRET), null, junk)
  }
})

Deno.test("no or invalid route cookie: navigations go to the portal, other requests get 401 no_route", async () => {
  const expired = `__Host-fluck_route=${await routeCookieValue(ROUTE_SECRET, USER, "inst-a", 1000)}`
  for (const cookie of [null, "a=b", "__Host-fluck_route=garbage", expired]) {
    const headers: Record<string, string> = cookie ? { cookie } : {}
    const nav = await through(new Request("https://fluck.risaboss.com/", { headers: { ...headers, ...NAV } }), boss())
    assertEquals(nav.res.status, 302, String(cookie))
    assertEquals(nav.res.headers.get("location"), "/portal/")
    assertEquals(nav.seen.length, 0)
    const api = await through(new Request("https://fluck.risaboss.com/api/chat", { method: "POST", body: "{}", headers }), boss())
    assertEquals(api.res.status, 401)
    assertEquals(await api.res.json(), { error: "no_route" })
    const head = await through(new Request("https://fluck.risaboss.com/", { method: "HEAD", headers: { ...headers, ...NAV } }), boss())
    assertEquals(head.res.status, 302)
  }
})

Deno.test("without FLUCK_ROUTE_SECRET even a well-formed cookie is not honoured", () =>
  quiet(async () => {
    const { seen, res } = await through(
      new Request("https://fluck.risaboss.com/", { headers: { cookie: await routeCookie(), ...NAV } }),
      boss(),
      { FLUCK_WEB_ALIAS_SECRET: SECRET },
    )
    assertEquals(res.status, 302)
    assertEquals(seen.length, 0)
  }))

Deno.test("a valid cookie among several of the same name wins", async () => {
  resetEndpointCache()
  const { res } = await through(
    new Request("https://fluck.risaboss.com/", { headers: { cookie: `__Host-fluck_route=bad; ${await routeCookie()}` } }),
    boss(),
  )
  assertEquals(res.status, 200)
})

// ---- proxying ----

Deno.test("a routed request goes to the tunnel with the path and query, secret on the lookup only", async () => {
  resetEndpointCache()
  const { seen, res } = await through(
    new Request("https://fluck.risaboss.com/assets/app.js?v=3", { headers: { cookie: await routeCookie() } }),
    boss(() => new Response("js", { headers: { "Content-Type": "text/javascript" } })),
  )
  assertEquals(res.status, 200)
  assertEquals(await res.text(), "js")
  assertEquals(seen.length, 2)
  assertEquals(seen[0].url, `${LOOKUP}?user=${USER}&instance=inst-a`)
  assertEquals(seen[0].headers.get("x-fluck-route-secret"), ROUTE_SECRET)
  assertEquals(seen[1].url, `${TUNNEL}/assets/app.js?v=3`)
  assertEquals(seen[1].headers.get("x-fluck-route-secret"), null)
  assertEquals(seen[1].headers.get("x-fluck-web-alias-secret"), null)
})

Deno.test("headers to the tunnel: route and portal cookies dropped, client IP replaced and Ed25519-signed, forwarding set", async () => {
  resetEndpointCache()
  const cookie = `theme=dark; ${await routeCookie()}; __Secure-boss_fluck_at=jwt; __Host-boss_fluck_csrf=n; fluck_session=abc`
  const before = Math.floor(Date.now() / 1000)
  const { seen } = await through(
    new Request("https://fluck.risaboss.com/api/messages", {
      method: "POST",
      body: '{"text":"hi"}',
      headers: {
        cookie,
        host: "fluck.risaboss.com",
        "CF-Connecting-IP": "198.51.100.7",
        "X-Fluck-Client-Ip": "10.0.0.1",
        "X-Fluck-Client-Ip-Ts": "1",
        "X-Fluck-Client-Ip-Sig": "forged",
        "X-Fluck-Client-Ip-Extra": "forged",
        "X-Fluck-Web-Alias-Secret": "forged",
        "X-Forwarded-Proto": "http",
        "Content-Type": "application/json",
      },
    }),
    boss(() => new Response("{}")),
  )
  const up = seen[1]
  assertEquals(await up.text(), '{"text":"hi"}')
  assertEquals(up.method, "POST")
  assertEquals(up.headers.get("cookie"), "theme=dark; fluck_session=abc")
  assertEquals(up.headers.get("content-type"), "application/json")
  assertEquals(up.headers.get("host"), null, "fetch sets Host for the tunnel")
  assertEquals(up.headers.get("x-fluck-client-ip-extra"), null)
  assertEquals(up.headers.get("x-fluck-web-alias-secret"), null)
  assertEquals(up.headers.get("x-forwarded-proto"), "https")
  assertEquals(up.headers.get("x-forwarded-host"), "fluck.risaboss.com")
  assertEquals(up.headers.get("x-fluck-web-alias"), "fluck.risaboss.com")
  const ip = up.headers.get("x-fluck-client-ip")!
  const ts = up.headers.get("x-fluck-client-ip-ts")!
  const sig = up.headers.get("x-fluck-client-ip-sig")!
  assertEquals(ip, "198.51.100.7")
  assert(Number(ts) >= before && Number(ts) <= before + 5)
  assertMatch(sig, /^[A-Za-z0-9_-]{86}$/)
  // Verified with the raw public key, the way the plugin does it.
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", PAIR.publicKey))
  const pub = await crypto.subtle.importKey("raw", raw, { name: "Ed25519" }, false, ["verify"])
  const msg = (s: string) => new TextEncoder().encode(s)
  assert(await crypto.subtle.verify({ name: "Ed25519" }, pub, b64urlDecode(sig), msg(`198.51.100.7|${ts}|abc-def.trycloudflare.com`)))
  assert(!await crypto.subtle.verify({ name: "Ed25519" }, pub, b64urlDecode(sig), msg(`198.51.100.7|${ts}|other.trycloudflare.com`)))
})

Deno.test("the signed host is the tunnel hostname, lowercase and without a port", async () => {
  resetEndpointCache()
  const endpoint = "https://boss.example.com:8443"
  const { seen } = await through(
    new Request("https://fluck.risaboss.com/", { headers: { cookie: await routeCookie(), "CF-Connecting-IP": "2001:db8::1" } }),
    (req) => req.url.startsWith(LOOKUP) ? Response.json({ endpoint }) : new Response("ok"),
  )
  assertEquals(seen[1].url, `${endpoint}/`)
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", PAIR.publicKey))
  const pub = await crypto.subtle.importKey("raw", raw, { name: "Ed25519" }, false, ["verify"])
  const ts = seen[1].headers.get("x-fluck-client-ip-ts")!
  const sig = b64urlDecode(seen[1].headers.get("x-fluck-client-ip-sig")!)
  assert(await crypto.subtle.verify({ name: "Ed25519" }, pub, sig, new TextEncoder().encode(`2001:db8::1|${ts}|boss.example.com`)))
})

Deno.test("without FLUCK_IP_SIGN_KEY the request is forwarded with no client IP headers at all", () =>
  quiet(async () => {
    resetEndpointCache()
    const { seen, res } = await through(
      new Request("https://fluck.risaboss.com/", { headers: { cookie: await routeCookie(), "CF-Connecting-IP": "198.51.100.7", "X-Fluck-Client-Ip": "10.0.0.1" } }),
      boss(),
      { FLUCK_ROUTE_SECRET: ROUTE_SECRET },
    )
    assertEquals(res.status, 200)
    for (const h of ["x-fluck-client-ip", "x-fluck-client-ip-ts", "x-fluck-client-ip-sig"]) assertEquals(seen[1].headers.get(h), null, h)
  }))

Deno.test("upstream responses come back as-is: status, every Set-Cookie, redirects unfollowed", async () => {
  resetEndpointCache()
  const headers = new Headers({ Location: "/#/chat", "X-Custom": "1" })
  headers.append("Set-Cookie", "fluck_session=a; Path=/; HttpOnly; Secure")
  headers.append("Set-Cookie", "fluck_csrf=b; Path=/; Secure")
  const { res } = await through(
    new Request("https://fluck.risaboss.com/t/redeem", { method: "POST", body: "x", headers: { cookie: await routeCookie() } }),
    boss(() => new Response(null, { status: 303, headers })),
  )
  assertEquals(res.status, 303)
  assertEquals(res.headers.get("location"), "/#/chat")
  assertEquals(res.headers.get("x-custom"), "1")
  assertEquals(res.headers.getSetCookie().length, 2)
  assertEquals(res.headers.get("cache-control"), null, "no caching added")
})

Deno.test("a WebSocket upgrade is forwarded with its headers and the upstream response returned untouched", async () => {
  resetEndpointCache()
  const upgraded = new Response("ws-placeholder", { status: 200, headers: { "X-Upgraded": "yes" } })
  const { seen, res } = await through(
    new Request("https://fluck.risaboss.com/ws", {
      headers: {
        cookie: `${await routeCookie()}; fc_session=s; __Host-boss_fluck_csrf=n`,
        Origin: "https://fluck.risaboss.com",
        Upgrade: "websocket",
        Connection: "Upgrade",
        "Sec-WebSocket-Key": "k",
        "CF-Connecting-IP": "198.51.100.7",
      },
    }),
    boss(() => upgraded),
  )
  assertEquals(seen[1].url, `${TUNNEL}/ws`)
  assertEquals(seen[1].headers.get("upgrade"), "websocket")
  assertEquals(seen[1].headers.get("sec-websocket-key"), "k")
  assertEquals(seen[1].headers.get("origin"), "https://fluck.risaboss.com", "the BOSS checks Origin on the upgrade")
  assert(seen[1].headers.get("x-fluck-client-ip-sig"))
  assertEquals(seen[1].headers.get("cookie"), "fc_session=s", "the chat session rides the upgrade; route and portal CSRF cookies do not")
  assert(res === upgraded, "the same Response object, so a Workers webSocket survives")
})

Deno.test("the same instance id under two accounts: each owner's cookie reaches its own BOSS, cached separately", async () => {
  resetEndpointCache()
  const tunnels: Record<string, string> = { [USER]: "https://mine.trycloudflare.com", [OTHER_USER]: "https://theirs.trycloudflare.com" }
  const upstream: Upstream = (req) => {
    const u = new URL(req.url)
    if (req.url.startsWith(LOOKUP)) {
      assertEquals(u.searchParams.get("instance"), "inst-a")
      return Response.json({ endpoint: tunnels[u.searchParams.get("user")!] })
    }
    return new Response(u.origin)
  }
  for (let round = 0; round < 2; round++) {
    const mine = await through(new Request("https://fluck.risaboss.com/", { headers: { cookie: await routeCookie("inst-a", undefined, USER) } }), upstream)
    assertEquals(await mine.res.text(), "https://mine.trycloudflare.com")
    const theirs = await through(new Request("https://fluck.risaboss.com/", { headers: { cookie: await routeCookie("inst-a", undefined, OTHER_USER) } }), upstream)
    assertEquals(await theirs.res.text(), "https://theirs.trycloudflare.com")
    // Second round is served from the per-(user, instance) cache: no lookups.
    assertEquals(mine.seen.filter((r) => r.url.startsWith(LOOKUP)).length + theirs.seen.filter((r) => r.url.startsWith(LOOKUP)).length, round === 0 ? 2 : 0)
  }
})

// ---- failure and cache ----

Deno.test("upstream 530/502/503/504 or a throw: navigations to the portal with ?reopen, others 502 boss_offline", () =>
  quiet(async () => {
    const cookie = await routeCookie()
    const failures: Upstream[] = [530, 502, 503, 504].map((s) => () => new Response("down", { status: s }))
    failures.push(() => {
      throw new Error("tunnel gone")
    })
    for (const tunnel of failures) {
      resetEndpointCache()
      const nav = await through(new Request("https://fluck.risaboss.com/", { headers: { cookie, ...NAV } }), boss(tunnel))
      assertEquals(nav.res.status, 302)
      assertEquals(nav.res.headers.get("location"), "/portal/?reopen=inst-a")
      const api = await through(new Request("https://fluck.risaboss.com/api/x", { headers: { cookie } }), boss(tunnel))
      assertEquals(api.res.status, 502)
      assertEquals(await api.res.json(), { error: "boss_offline" })
    }
    resetEndpointCache()
    const notFound = await through(new Request("https://fluck.risaboss.com/api/x", { headers: { cookie } }), boss(() => new Response("nope", { status: 404 })))
    assertEquals(notFound.res.status, 404, "an ordinary app 404 is not an outage")
  }))

Deno.test("endpoint lookup 404 (or a lookup failure) is offline", () =>
  quiet(async () => {
    const cookie = await routeCookie("inst-z")
    resetEndpointCache()
    const nav = await through(new Request("https://fluck.risaboss.com/", { headers: { cookie, ...NAV } }), boss(undefined, null))
    assertEquals(nav.res.headers.get("location"), "/portal/?reopen=inst-z")
    assertEquals(nav.seen.length, 1)
    const thrown = await through(new Request("https://fluck.risaboss.com/x", { headers: { cookie } }), () => {
      throw new Error("function down")
    })
    assertEquals(thrown.res.status, 502)
    const bad = await through(new Request("https://fluck.risaboss.com/x", { headers: { cookie } }), (req) =>
      req.url.startsWith(LOOKUP) ? Response.json({ endpoint: "http://plain.example" }) : new Response("never"))
    assertEquals(bad.res.status, 502, "a non-https endpoint is refused")
    assertEquals(bad.seen.length, 1)
  }))

Deno.test("the endpoint is cached for 30 s per instance, and dropped on an upstream failure", () =>
  quiet(async () => {
    resetEndpointCache()
    const cookie = await routeCookie()
    const lookups = (seen: Request[]) => seen.filter((r) => r.url.startsWith(LOOKUP)).length
    const first = await through(new Request("https://fluck.risaboss.com/a", { headers: { cookie } }), boss())
    const second = await through(new Request("https://fluck.risaboss.com/b", { headers: { cookie } }), boss())
    assertEquals(lookups(first.seen), 1)
    assertEquals(lookups(second.seen), 0, "served from cache")

    const realNow = Date.now
    try {
      const start = realNow()
      Date.now = () => start + 31_000
      const later = await through(new Request("https://fluck.risaboss.com/c", { headers: { cookie } }), boss())
      assertEquals(lookups(later.seen), 1, "expired after 30 s")
    } finally {
      Date.now = realNow
    }

    resetEndpointCache()
    await through(new Request("https://fluck.risaboss.com/a", { headers: { cookie } }), boss())
    const down = await through(new Request("https://fluck.risaboss.com/a", { headers: { cookie } }), boss(() => new Response("", { status: 530 })))
    assertEquals(lookups(down.seen), 0)
    assertEquals(down.res.status, 502)
    const after = await through(new Request("https://fluck.risaboss.com/a", { headers: { cookie } }), boss())
    assertEquals(lookups(after.seen), 1, "the failure dropped the cached endpoint")
  }))

Deno.test("onlyCookies keeps just the named cookies", () => {
  assertEquals(onlyCookies("fc_session=1; boss_fluck_at=t; x=2", new Set(["boss_fluck_at"])), "boss_fluck_at=t")
  assertEquals(onlyCookies("fc_session=1", new Set(["boss_fluck_at"])), null)
  assertEquals(onlyCookies(null, new Set(["x"])), null)
})

Deno.test("withoutCookies keeps everything else and returns null when nothing is left", () => {
  assertEquals(withoutCookies("a=1; __Host-fluck_route=x; b=2", new Set(["__Host-fluck_route"])), "a=1; b=2")
  assertEquals(withoutCookies("__Host-fluck_route=x", new Set(["__Host-fluck_route"])), null)
  assertEquals(withoutCookies(null, new Set(["x"])), null)
})

// ---- end to end ----

const E2E_ENV = ["SUPABASE_URL", "SUPABASE_ANON_KEY", "SUPABASE_SERVICE_ROLE_KEY", "FLUCK_WEB_PUBLIC_BASE_URL", "FLUCK_WEB_PUBLIC_BASE_PATH", "FLUCK_WEB_ALIAS_SECRET", "FLUCK_ROUTE_SECRET"]

function setE2eEnv() {
  Deno.env.set("SUPABASE_URL", "https://stack.example")
  Deno.env.set("SUPABASE_ANON_KEY", "anon-key")
  Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "service-key")
  Deno.env.set("FLUCK_WEB_PUBLIC_BASE_URL", "https://fluck.risaboss.com")
  Deno.env.set("FLUCK_WEB_PUBLIC_BASE_PATH", "/portal")
  Deno.env.set("FLUCK_WEB_ALIAS_SECRET", SECRET)
  Deno.env.set("FLUCK_ROUTE_SECRET", ROUTE_SECRET)
  resetRateLimits()
  resetEndpointCache()
}

/** The edge gateway strips /functions/v1; the function sees the API host. */
function toApp(req: Request): Response | Promise<Response> {
  const u = new URL(req.url)
  const headers = new Headers(req.headers)
  headers.set("host", u.host)
  return app.request(u.pathname.replace("/functions/v1", "") + u.search, { method: req.method, headers })
}

Deno.test("worker -> function end to end: /portal serves the page via the alias, and a forged direct marker is not", async () => {
  setE2eEnv()
  try {
    const { res } = await through(new Request("https://fluck.risaboss.com/portal/?instance=inst-a", { headers: { "CF-Connecting-IP": "198.51.100.7" } }), toApp)
    assertEquals(res.status, 200)
    assert((await res.text()).includes('"basePath":"/portal"'))
    const forged = await app.request("/fluck-web/", { headers: { host: "api.risaboss.com", "x-fluck-web-alias": "fluck.risaboss.com" } })
    assertEquals(forged.status, 503)
    await forged.body?.cancel()
  } finally {
    for (const n of E2E_ENV) Deno.env.delete(n)
  }
})

Deno.test("worker -> function end to end: a route cookie resolves through /internal/endpoint to the tunnel", async () => {
  setE2eEnv()
  const original = deps.fetch
  deps.fetch = () => Promise.resolve(Response.json([{ endpoint_url: TUNNEL, last_seen_at: new Date().toISOString() }]))
  try {
    const { seen, res } = await through(
      new Request("https://fluck.risaboss.com/", { headers: { cookie: await routeCookie(), ...NAV } }),
      (req) => req.url.startsWith(TUNNEL) ? new Response("chat") : toApp(req),
    )
    assertEquals(res.status, 200)
    assertEquals(await res.text(), "chat")
    assertEquals(seen.map((r) => new URL(r.url).origin), ["https://api.risaboss.com", TUNNEL])
  } finally {
    deps.fetch = original
    for (const n of E2E_ENV) Deno.env.delete(n)
  }
})
