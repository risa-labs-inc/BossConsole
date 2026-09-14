/**
 * Tests for the analytics-ingest Edge Function — shape validation, the Amplitude
 * mapping (the three parts of its contract that fail silently against the live API),
 * insert_id pass-through, the rate limiter, the optional shared-key guard, and the
 * upstream-status mapping that decides whether a client keeps or drops a batch. No
 * network: the Hono app is driven in-process via app.request() with globalThis.fetch
 * stubbed for the vendor call.
 *
 * Run: cd supabase/functions/analytics-ingest && deno test --allow-env --config deno.json
 */

import { assert, assertEquals } from "@std/assert"
// Import the app module (not index.ts, which calls Deno.serve) so no listener starts under test.
import {
  allowRequest,
  app,
  clientIp,
  type IngestRequest,
  normalizeId,
  resetRateLimiter,
  toAmplitudePayload,
  validate,
} from "../app.ts"

Deno.env.set("AMPLITUDE_API_KEY", "amp_server_side_key")
Deno.env.delete("ANALYTICS_INGEST_KEY")

const TS_MS = 1_700_000_000_000
const INSERT_ID = "a1b2c3d4e5f60718"

function event(overrides: Record<string, unknown> = {}) {
  return {
    name: "browser.page_viewed",
    timestampMs: TS_MS,
    source: "HOST_EVENT",
    insertId: INSERT_ID,
    properties: { domain: "example.com", pageIndexInVisit: 2 },
    ...overrides,
  }
}

function body(overrides: Record<string, unknown> = {}) {
  return {
    identity: { distinctId: "anon-abcdef", isAnonymous: true },
    events: [event()],
    ...overrides,
  }
}

/** Stubs the vendor call; returns what the function actually forwarded. */
function stubUpstream(status = 200, responseBody = "{}") {
  const original = globalThis.fetch
  const recorded: { url: string; body: unknown; headers: unknown }[] = []
  globalThis.fetch = (input: URL | RequestInfo, init?: RequestInit): Promise<Response> => {
    recorded.push({
      url: input instanceof Request ? input.url : input.toString(),
      body: init?.body ? JSON.parse(init.body as string) : undefined,
      headers: init?.headers,
    })
    return Promise.resolve(new Response(responseBody, { status }))
  }
  return { recorded, restore: () => { globalThis.fetch = original } }
}

async function post(payload: unknown, headers: Record<string, string> = {}) {
  resetRateLimiter()
  return await app.request("/analytics-ingest", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(payload),
  })
}

// ---------------------------------------------------------------- pure helpers

Deno.test("normalizeId pads only ids Amplitude would reject outright", () => {
  assertEquals(normalizeId("anon-abcdef"), "anon-abcdef")
  assertEquals(normalizeId("abcde"), "abcde")
  // Shorter than five characters 400s the WHOLE request, so it is padded, not dropped.
  assertEquals(normalizeId("ab"), "boss-ab")
})

Deno.test("clientIp trusts only edge-set headers", () => {
  assertEquals(clientIp("1.2.3.4", "9.9.9.9"), "1.2.3.4")
  // Rightmost XFF entry: the leftmost is whatever the caller typed.
  assertEquals(clientIp(undefined, "9.9.9.9, 10.0.0.1"), "10.0.0.1")
  assertEquals(clientIp(undefined, undefined), "unknown")
})

Deno.test("the rate limiter bounds a single address", () => {
  resetRateLimiter()
  const now = Date.now()
  let allowed = 0
  for (let i = 0; i < 700; i++) if (allowRequest("1.1.1.1", now)) allowed++
  assertEquals(allowed, 600)
  // A different address has its own budget.
  assert(allowRequest("2.2.2.2", now))
  // And the window slides.
  assert(allowRequest("1.1.1.1", now + 60 * 60 * 1000 + 1))
})

// ---------------------------------------------------------------- validation

Deno.test("a well-formed batch validates", () => {
  const result = validate(body())
  assert(typeof result !== "string", `expected valid, got: ${result}`)
  assertEquals(result.events.length, 1)
})

Deno.test("validation refuses the shapes that would make this an open relay", () => {
  const cases: [string, unknown][] = [
    ["not an object", []],
    ["missing identity", { events: [event()] }],
    ["non-boolean isAnonymous", body({ identity: { distinctId: "x", isAnonymous: "yes" } })],
    ["empty events", body({ events: [] })],
    ["too many events", body({ events: Array(201).fill(event()) })],
    ["uppercase event name", body({ events: [event({ name: "Auth.SignedIn" })] })],
    ["event name with a space", body({ events: [event({ name: "auth signed in" })] })],
    ["non-integer timestamp", body({ events: [event({ timestampMs: 1.5 })] })],
    ["timestamp before BOSS existed", body({ events: [event({ timestampMs: 946_684_800_000 })] })],
    ["timestamp far in the future", body({ events: [event({ timestampMs: TS_MS + 1e12 })] })],
    ["non-hex insertId", body({ events: [event({ insertId: "not-a-digest" })] })],
    ["properties not an object", body({ events: [event({ properties: [1, 2] })] })],
    ["property key with a quote", body({ events: [event({ properties: { 'a"b': 1 } })] })],
    ["over-long string value", body({ events: [event({ properties: { k: "x".repeat(513) } })] })],
    ["too deeply nested value", body({ events: [event({ properties: { k: { a: { b: 1 } } } })] })],
    ["function-ish value", body({ events: [event({ properties: { k: undefined } })] })],
  ]
  for (const [label, payload] of cases) {
    assertEquals(typeof validate(payload), "string", `${label} must be refused`)
  }
})

Deno.test("validation accepts the property shapes the client actually emits", () => {
  const ok = validate(body({
    events: [event({
      properties: {
        domain: "example.com",
        dwellMs: 1234,
        activeMs: 0,
        scrollDepthPercent: 75,
        primary: true,
        missing: null,
        elementPosition: "form>div:2>button:1",
        linkKind: "external",
        tags: ["a", "b"],
      },
    })],
  }))
  assert(typeof ok !== "string", `expected valid, got: ${ok}`)
})

Deno.test("an unknown source label is passed through, not refused", () => {
  // The api documents these enums as open; refusing an unrecognised constant would
  // make a newer client's events vanish at the boundary.
  const ok = validate(body({ events: [event({ source: "SOME_FUTURE_SOURCE" })] }))
  assert(typeof ok !== "string", `expected valid, got: ${ok}`)
})

// ---------------------------------------------------------------- vendor mapping

Deno.test("time is a bare epoch-millis number, not an ISO string", () => {
  const request = validate(body()) as IngestRequest
  const mapped = JSON.parse(toAmplitudePayload("k", request))
  assertEquals(mapped.events[0].time, TS_MS)
  assertEquals(typeof mapped.events[0].time, "number")
})

Deno.test("anonymous identity maps to device_id, signed-in to user_id", () => {
  const anon = validate(body()) as IngestRequest
  const anonMapped = JSON.parse(toAmplitudePayload("k", anon))
  assertEquals(anonMapped.events[0].device_id, "anon-abcdef")
  assert(!("user_id" in anonMapped.events[0]), "anonymous traffic must not create a user profile")

  const signedIn = validate(
    body({ identity: { distinctId: "user-42-uuid", isAnonymous: false } }),
  ) as IngestRequest
  const mapped = JSON.parse(toAmplitudePayload("k", signedIn))
  assertEquals(mapped.events[0].user_id, "user-42-uuid")
  assert(!("device_id" in mapped.events[0]))
})

Deno.test("insert_id is passed through unchanged so a replayed batch de-duplicates", () => {
  const request = validate(body()) as IngestRequest
  const mapped = JSON.parse(toAmplitudePayload("k", request))
  // Re-deriving it here would break de-duplication for a batch the client replayed
  // from its spool: only the client can compute it from the original event content.
  assertEquals(mapped.events[0].insert_id, INSERT_ID)
})

Deno.test("the vendor payload carries the server-side key and the client's properties", () => {
  const request = validate(body()) as IngestRequest
  const mapped = JSON.parse(toAmplitudePayload("amp_server_side_key", request))
  assertEquals(mapped.api_key, "amp_server_side_key")
  const props = mapped.events[0].event_properties
  assertEquals(props.boss_source, "HOST_EVENT")
  assertEquals(props.domain, "example.com")
  assertEquals(props.pageIndexInVisit, 2)
})

// ---------------------------------------------------------------- routes

Deno.test("a valid batch is forwarded and acknowledged", async () => {
  const upstream = stubUpstream(200)
  try {
    const res = await post(body())
    assertEquals(res.status, 200)
    assertEquals((await res.json()).accepted, 1)
    assertEquals(upstream.recorded.length, 1)
    assertEquals(upstream.recorded[0].url, "https://api2.amplitude.com/2/httpapi")
    // The key reached the vendor and came from the server, not the caller.
    assertEquals((upstream.recorded[0].body as { api_key: string }).api_key, "amp_server_side_key")
  } finally {
    upstream.restore()
  }
})

Deno.test("a malformed batch is refused before the vendor is contacted", async () => {
  const upstream = stubUpstream(200)
  try {
    const res = await post(body({ events: [event({ name: "NOPE" })] }))
    assertEquals(res.status, 400)
    assertEquals(upstream.recorded.length, 0, "junk must not cost an upstream call")
  } finally {
    upstream.restore()
  }
})

Deno.test("an upstream refusal becomes 503 so the client keeps the batch spooled", async () => {
  // Deliberately not proxied: a 400 from Amplitude means OUR key or OUR mapping is
  // wrong, and a 4xx would tell the client to discard the batch permanently, losing
  // a deployment's telemetry over a server-side fault it cannot see.
  for (const upstreamStatus of [400, 401, 413, 429, 500, 503]) {
    const upstream = stubUpstream(upstreamStatus, "rejected")
    try {
      const res = await post(body())
      assertEquals(res.status, 503, `upstream ${upstreamStatus} must come back as 503`)
    } finally {
      upstream.restore()
    }
  }
})

Deno.test("an unreachable backend is also 503", async () => {
  const original = globalThis.fetch
  globalThis.fetch = () => Promise.reject(new Error("connection reset"))
  try {
    assertEquals((await post(body())).status, 503)
  } finally {
    globalThis.fetch = original
  }
})

Deno.test("the shared-key guard is off unless the server configures one", async () => {
  const upstream = stubUpstream(200)
  try {
    // No ANALYTICS_INGEST_KEY: an honest client that cannot read the app's anon key
    // still gets through.
    assertEquals((await post(body())).status, 200)

    Deno.env.set("ANALYTICS_INGEST_KEY", "shared-secret")
    try {
      assertEquals((await post(body())).status, 401)
      assertEquals((await post(body(), { apikey: "wrong" })).status, 401)
      assertEquals((await post(body(), { apikey: "shared-secret" })).status, 200)
      assertEquals(
        (await post(body(), { authorization: "Bearer shared-secret" })).status,
        200,
      )
    } finally {
      Deno.env.delete("ANALYTICS_INGEST_KEY")
    }
  } finally {
    upstream.restore()
  }
})

Deno.test("no vendor key configured is a 503, not a crash", async () => {
  Deno.env.delete("AMPLITUDE_API_KEY")
  try {
    assertEquals((await post(body())).status, 503)
  } finally {
    Deno.env.set("AMPLITUDE_API_KEY", "amp_server_side_key")
  }
})

Deno.test("the POST route actually enforces the limit, not just the helper", async () => {
  const upstream = stubUpstream(200)
  try {
    resetRateLimiter()
    // No forwarding headers, so clientIp() buckets under "unknown". Exhaust that
    // budget directly, then check the route refuses rather than forwarding.
    const now = Date.now()
    while (allowRequest("unknown", now)) { /* drain */ }

    const res = await app.request("/analytics-ingest", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body()),
    })
    assertEquals(res.status, 429)
    assertEquals(upstream.recorded.length, 0, "a throttled batch must not reach the vendor")
  } finally {
    upstream.restore()
    resetRateLimiter()
  }
})

Deno.test("health check responds", async () => {
  const res = await app.request("/analytics-ingest/health")
  assertEquals(res.status, 200)
  assertEquals((await res.json()).status, "healthy")
})

Deno.test("no CORS headers are emitted", async () => {
  const upstream = stubUpstream(200)
  try {
    const res = await post(body())
    // The caller is a desktop HTTP client; CORS headers would only enable the
    // cross-origin browser requests this endpoint has no use for.
    assertEquals(res.headers.get("access-control-allow-origin"), null)
  } finally {
    upstream.restore()
  }
})
