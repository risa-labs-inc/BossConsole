import { assertEquals, assertRejects, assertThrows } from "@std/assert"
import { createHandler, type Dependencies } from "../app.ts"
import { mintToken, signingKey } from "../auth.ts"
import {
  CATALOG_CACHE_MAX_STALE_MS,
  CATALOG_CACHE_TTL_MS,
  CatalogCache,
  type CatalogGetOptions,
  USAGE_MAX_AGE_MS,
} from "../catalog_cache.ts"
import { Obj } from "../wire.ts"

const secret = "test-signing-secret-at-least-32-bytes-long"
const ALICE = "00000000-0000-0000-0000-00000000000a"
const BOB = "00000000-0000-0000-0000-00000000000b"

function clock(start = 1_000_000) {
  let t = start
  return { now: () => t, advance: (ms: number) => t += ms }
}

/** The served value alone, for tests that are not about age or staleness. */
const read = (
  cache: CatalogCache,
  user: string,
  load: () => Promise<unknown>,
  options?: CatalogGetOptions,
) => cache.get(user, load, options).then((answer) => answer.value)
const USAGE: CatalogGetOptions = { maxAgeMs: USAGE_MAX_AGE_MS, allowStale: false }

Deno.test("the catalog TTL is five minutes and the usage window thirty seconds", () => {
  assertEquals(CATALOG_CACHE_TTL_MS, 300_000)
  assertEquals(USAGE_MAX_AGE_MS, 30_000)
})

Deno.test("a fresh answer is served without a second load", async () => {
  const c = clock()
  const cache = new CatalogCache({ now: c.now })
  let loads = 0
  const load = () => Promise.resolve([{ id: "m", n: ++loads }])
  assertEquals(await read(cache, ALICE, load), [{ id: "m", n: 1 }])
  c.advance(CATALOG_CACHE_TTL_MS - 1)
  assertEquals(await read(cache, ALICE, load), [{ id: "m", n: 1 }])
  assertEquals(loads, 1)
})

Deno.test("an expired answer is loaded again", async () => {
  const c = clock()
  const cache = new CatalogCache({ now: c.now })
  let loads = 0
  const load = () => Promise.resolve(++loads)
  assertEquals(await read(cache, ALICE, load), 1)
  c.advance(CATALOG_CACHE_TTL_MS)
  assertEquals(await read(cache, ALICE, load), 2)
  assertEquals(await read(cache, ALICE, load), 2)
  assertEquals(loads, 2)
})

Deno.test("an expired answer is served when the load fails, and the next call retries", async () => {
  const c = clock()
  const cache = new CatalogCache({ now: c.now })
  assertEquals(await cache.get(ALICE, () => Promise.resolve("old")), {
    value: "old",
    ageMs: 0,
    stale: false,
  })
  c.advance(CATALOG_CACHE_TTL_MS + 1)
  assertEquals(await cache.get(ALICE, () => Promise.reject(new Error("db down"))), {
    value: "old",
    ageMs: CATALOG_CACHE_TTL_MS + 1,
    stale: true,
    reason: "error",
  })
  assertEquals(await read(cache, ALICE, () => Promise.resolve("new")), "new")
})

Deno.test("an expired answer is served when the load is slow, and the late answer is kept", async () => {
  const c = clock()
  const cache = new CatalogCache({ now: c.now, staleWaitMs: 5 })
  await read(cache, ALICE, () => Promise.resolve("old"))
  c.advance(CATALOG_CACHE_TTL_MS + 1)
  let finish!: (v: string) => void
  const slow = new Promise<string>((resolve) => finish = resolve)
  assertEquals(await cache.get(ALICE, () => slow), {
    value: "old",
    ageMs: CATALOG_CACHE_TTL_MS + 1,
    stale: true,
    reason: "timeout",
  })
  finish("late")
  await slow
  await new Promise((r) => setTimeout(r, 0))
  let loads = 0
  assertEquals(await read(cache, ALICE, () => Promise.resolve(++loads)), "late")
  assertEquals(loads, 0)
})

Deno.test("a failed load with nothing cached, or only a too-old answer, fails", async () => {
  const c = clock()
  const cache = new CatalogCache({ now: c.now, maxStaleMs: CATALOG_CACHE_TTL_MS * 2 })
  await assertRejects(() => read(cache, ALICE, () => Promise.reject(new Error("db down"))))
  await read(cache, ALICE, () => Promise.resolve("old"))
  c.advance(CATALOG_CACHE_TTL_MS * 2)
  await assertRejects(() => read(cache, ALICE, () => Promise.reject(new Error("db down"))))
  // Another user's entry is never the fallback.
  await read(cache, BOB, () => Promise.resolve("bob"))
  await assertRejects(() => read(cache, ALICE, () => Promise.reject(new Error("db down"))))
})

Deno.test("each user gets only the answer loaded for that user", async () => {
  const cache = new CatalogCache()
  const loaded: string[] = []
  const load = (user: string) => () => {
    loaded.push(user)
    return Promise.resolve([{ id: `model-of-${user}` }])
  }
  assertEquals(await read(cache, ALICE, load(ALICE)), [{ id: `model-of-${ALICE}` }])
  assertEquals(await read(cache, BOB, load(BOB)), [{ id: `model-of-${BOB}` }])
  assertEquals(await read(cache, ALICE, load(ALICE)), [{ id: `model-of-${ALICE}` }])
  assertEquals(await read(cache, BOB, load(BOB)), [{ id: `model-of-${BOB}` }])
  assertEquals(loaded, [ALICE, BOB])
})

Deno.test("concurrent calls for one user share one load; other users load their own", async () => {
  const cache = new CatalogCache()
  const loads: string[] = []
  const gates = new Map<string, (v: string) => void>()
  const load = (user: string) => () => {
    loads.push(user)
    return new Promise<string>((resolve) => gates.set(user, resolve))
  }
  const calls = [
    read(cache, ALICE, load(ALICE)),
    read(cache, ALICE, load(ALICE)),
    read(cache, BOB, load(BOB)),
    read(cache, ALICE, load(ALICE)),
  ]
  assertEquals(loads, [ALICE, BOB])
  gates.get(ALICE)!("a")
  gates.get(BOB)!("b")
  assertEquals(await Promise.all(calls), ["a", "a", "b", "a"])
})

Deno.test("concurrent calls share a failed load, and the failure is not remembered", async () => {
  const cache = new CatalogCache()
  let loads = 0
  const fail = () => {
    loads++
    return Promise.reject(new Error("db down"))
  }
  const results = await Promise.allSettled([read(cache, ALICE, fail), read(cache, ALICE, fail)])
  assertEquals(results.map((r) => r.status), ["rejected", "rejected"])
  assertEquals(loads, 1)
  assertEquals(await read(cache, ALICE, () => Promise.resolve("ok")), "ok")
})

Deno.test("the cache is bounded and drops the least recently used user", async () => {
  const cache = new CatalogCache({ maxEntries: 2 })
  const loads: string[] = []
  const load = (user: string) => () => {
    loads.push(user)
    return Promise.resolve(user)
  }
  await read(cache, "u1", load("u1"))
  await read(cache, "u2", load("u2"))
  await read(cache, "u1", load("u1")) // u1 is now the most recent
  await read(cache, "u3", load("u3")) // drops u2
  assertEquals(cache.size, 2)
  await read(cache, "u1", load("u1"))
  await read(cache, "u2", load("u2"))
  assertEquals(loads, ["u1", "u2", "u3", "u2"])
})

Deno.test("a cached answer reports its age, and a per-call max age forces a live load", async () => {
  const c = clock()
  const cache = new CatalogCache({ now: c.now })
  let loads = 0
  const load = () => Promise.resolve(++loads)
  assertEquals(await cache.get(ALICE, load), { value: 1, ageMs: 0, stale: false })
  c.advance(USAGE_MAX_AGE_MS - 1)
  assertEquals(await cache.get(ALICE, load, USAGE), {
    value: 1,
    ageMs: USAGE_MAX_AGE_MS - 1,
    stale: false,
  })
  c.advance(1)
  // Too old for usage: loaded live, and that load is what the list route now reads.
  assertEquals(await cache.get(ALICE, load, USAGE), { value: 2, ageMs: 0, stale: false })
  c.advance(1_000)
  assertEquals(await cache.get(ALICE, load), { value: 2, ageMs: 1_000, stale: false })
  assertEquals(loads, 2)
})

Deno.test("a call that refuses stale answers gets the load's failure, not the expired entry", async () => {
  const c = clock()
  const cache = new CatalogCache({ now: c.now, staleWaitMs: 5 })
  await cache.get(ALICE, () => Promise.resolve("old"))
  c.advance(CATALOG_CACHE_TTL_MS + 1)
  await assertRejects(
    () => cache.get(ALICE, () => Promise.reject(new Error("db down")), USAGE),
    Error,
    "db down",
  )
  // A slow load is waited for past the stale wait instead of being replaced by the old answer.
  let finish!: (v: string) => void
  const slow = new Promise<string>((resolve) => finish = resolve)
  const pending = cache.get(ALICE, () => slow, USAGE)
  await new Promise((r) => setTimeout(r, 20))
  finish("live")
  assertEquals(await pending, { value: "live", ageMs: 0, stale: false })
})

Deno.test("a usage call that joins a load in flight gets that live answer", async () => {
  const c = clock()
  const cache = new CatalogCache({ now: c.now })
  await cache.get(ALICE, () => Promise.resolve("old"))
  c.advance(CATALOG_CACHE_TTL_MS + 1)
  let loads = 0
  let finish!: (v: string) => void
  const load = () => {
    loads++
    return new Promise<string>((resolve) => finish = resolve)
  }
  const list = cache.get(ALICE, load)
  const usage = cache.get(ALICE, load, USAGE)
  assertEquals(loads, 1)
  finish("new")
  assertEquals(await usage, { value: "new", ageMs: 0, stale: false })
  assertEquals(await list, { value: "new", ageMs: 0, stale: false })
})

Deno.test("a caller cannot change what the next caller receives", async () => {
  const cache = new CatalogCache()
  const load = () => Promise.resolve([{ id: "m", allowance: { day: { remaining: 5 } } }])
  type Row = { id: string; allowance: { day: { remaining: number } } }
  // The loading caller and a cached caller both hold the stored object: neither may write to it.
  for (let i = 0; i < 2; i++) {
    const rows = await read(cache, ALICE, load) as Row[]
    assertThrows(() => rows[0].allowance.day.remaining = 0, TypeError)
    assertThrows(() => rows[0].id = "other", TypeError)
    assertThrows(() => rows.push({ id: "x", allowance: { day: { remaining: 1 } } }), TypeError)
    assertThrows(() => rows.length = 0, TypeError)
  }
  assertEquals(await read(cache, ALICE, load), [{ id: "m", allowance: { day: { remaining: 5 } } }])
})

type Db = "ok" | "fail" | "hang"

async function fixture(
  options: { failAfterFirst?: boolean; catalogCache?: Dependencies["catalogCache"] } = {},
) {
  const c = clock(Date.now())
  const calls: { name: string; params: Obj }[] = []
  const logs: Obj[] = []
  const upstream: Request[] = []
  const key = signingKey(secret)
  const tokens = {
    [ALICE]: (await mintToken(ALICE, key)).access_token,
    [BOB]: (await mintToken(BOB, key)).access_token,
  }
  // What the database does next: tests flip these between requests.
  const state = { db: "ok" as Db, revoked: false }
  const hung: (() => void)[] = []
  const handler = createHandler({
    now: c.now,
    log: (entry) => logs.push(entry),
    audit: () => {},
    catalogCache: options.catalogCache,
    sessionUser: () => Promise.resolve(null),
    secret: (name) => name === "BOSS_AI_SIGNING_SECRET" ? secret : "upstream-secret",
    rpc(name, params) {
      calls.push({ name, params })
      // Live admission, as the database answers it: a revoked or banned caller has no model.
      if (name === "boss_ai_lookup" || name === "boss_ai_reserve") {
        return Promise.resolve(
          state.revoked ? null : {
            model: {
              id: `model-of-${params.p_user_id}`,
              upstream_model: "private-model",
              kind: "chat",
              voice: null,
              context_length: 4096,
              max_output_tokens: 512,
              provider_routing: null,
              capabilities: ["text"],
            },
            connection: {
              base_url: "https://upstream.example/v1",
              api_type: "openai_chat",
              api_key_secret: "BOSS_AI_TEST",
            },
          },
        )
      }
      if (name !== "boss_ai_catalog") return Promise.resolve(null)
      if (state.db === "hang") return new Promise((resolve) => hung.push(() => resolve([])))
      if (state.db === "fail") {
        return Promise.reject(new Error("BOSS AI database operation failed"))
      }
      if (options.failAfterFirst) state.db = "fail"
      const n = calls.filter((x) => x.name === "boss_ai_catalog").length
      return Promise.resolve(
        state.revoked ? [] : [{
          id: `model-of-${params.p_user_id}`,
          allowance: { used: n },
          budget: { load: n },
        }],
      )
    },
    fetch: (url, init) => {
      upstream.push(new Request(url, init))
      return Promise.resolve(
        new Response(JSON.stringify({
          choices: [{ message: { role: "assistant", content: "hi" }, finish_reason: "stop" }],
          usage: { prompt_tokens: 2, completion_tokens: 3 },
        })),
      )
    },
  })
  const url = (path: string) => `https://x.example/functions/v1/boss-ai${path}`
  const auth = (user: string) => ({
    Authorization: `Bearer ${tokens[user as keyof typeof tokens]}`,
  })
  const get = async (path: string, user: string) => {
    const response = await handler(new Request(url(path), { headers: auth(user) }))
    return {
      status: response.status,
      body: await response.json(),
      age: response.headers.get("X-Catalog-Age"),
      requestId: response.headers.get("X-Request-ID"),
    }
  }
  const chat = async (user: string) => {
    const response = await handler(
      new Request(url("/v1/chat/completions"), {
        method: "POST",
        headers: auth(user),
        body: JSON.stringify({
          model: `model-of-${user}`,
          messages: [{ role: "user", content: "hello" }],
        }),
      }),
    )
    return { status: response.status, body: await response.json() }
  }
  const catalogCalls = () => calls.filter((x) => x.name === "boss_ai_catalog")
  /** Lets every hung catalog read finish, so no test leaves a pending promise behind. */
  const release = async () => {
    hung.splice(0).forEach((finish) => finish())
    await new Promise((r) => setTimeout(r, 0))
  }
  return { get, chat, calls, catalogCalls, logs, upstream, state, release, clock: c }
}

Deno.test("/v1/models and /v1/usage share one catalog read per user within the TTL", async () => {
  const f = await fixture()
  const models = await f.get("/v1/models", ALICE)
  assertEquals(models.status, 200)
  assertEquals(models.body, {
    object: "list",
    data: [{ id: `model-of-${ALICE}`, allowance: { used: 1 }, budget: { load: 1 } }],
  })
  const usage = await f.get("/v1/usage", ALICE)
  assertEquals(usage.body, {
    object: "list",
    data: [{ model: `model-of-${ALICE}`, allowance: { used: 1 }, budget: { load: 1 } }],
  })
  assertEquals((await f.get("/v1/models", ALICE)).body, models.body)
  assertEquals(f.catalogCalls().map((x) => x.params), [{ p_user_id: ALICE }])

  f.clock.advance(CATALOG_CACHE_TTL_MS)
  assertEquals((await f.get("/v1/models", ALICE)).body.data[0].allowance, { used: 2 })
  assertEquals(f.catalogCalls().length, 2)
  // The catalog routes make no other database call.
  assertEquals(f.calls.length, 2)
})

Deno.test("one caller never receives another caller's cached catalog", async () => {
  const f = await fixture()
  const alice = await f.get("/v1/models", ALICE)
  const bob = await f.get("/v1/models", BOB)
  assertEquals(alice.body.data[0].id, `model-of-${ALICE}`)
  assertEquals(bob.body.data[0].id, `model-of-${BOB}`)
  assertEquals((await f.get("/v1/usage", BOB)).body.data[0].model, `model-of-${BOB}`)
  assertEquals((await f.get("/v1/usage", ALICE)).body.data[0].model, `model-of-${ALICE}`)
  assertEquals(f.catalogCalls().map((x) => x.params.p_user_id), [ALICE, BOB])
})

Deno.test("a database failure serves the expired catalog, and 503 when there is none", async () => {
  const f = await fixture({ failAfterFirst: true })
  const first = await f.get("/v1/models", ALICE)
  f.clock.advance(CATALOG_CACHE_TTL_MS + 1)
  const second = await f.get("/v1/models", ALICE)
  assertEquals(second.status, 200)
  assertEquals(second.body, first.body)
  assertEquals(second.age, String(Math.floor((CATALOG_CACHE_TTL_MS + 1) / 1000)))
  // Attributable: which request, whose catalog, which route, why, and how old the answer was.
  assertEquals(f.logs, [{
    event: "catalog_stale_served",
    request_id: second.requestId,
    user: ALICE,
    path: "/v1/models",
    reason: "error",
    age_ms: CATALOG_CACHE_TTL_MS + 1,
  }])
  assertEquals(second.requestId!.length, 36)
  // Bob has nothing cached: the failure reaches him as before, never Alice's catalog.
  const bob = await f.get("/v1/models", BOB)
  assertEquals(bob.status, 503)
  assertEquals(bob.body.error.code, "unavailable")
})

Deno.test("a request without a valid AI token never reaches the cache", async () => {
  const f = await fixture()
  await f.get("/v1/models", ALICE)
  const handlerProbe = await f.get("/v1/models", "nobody")
  assertEquals(handlerProbe.status, 401)
  assertEquals(f.catalogCalls().length, 1)
})

Deno.test("X-Catalog-Age is 0 for a live read and whole seconds for a cached one", async () => {
  const f = await fixture()
  assertEquals((await f.get("/v1/models", ALICE)).age, "0")
  f.clock.advance(12_999)
  assertEquals((await f.get("/v1/models", ALICE)).age, "12")
  assertEquals((await f.get("/v1/usage", ALICE)).age, "12")
  f.clock.advance(USAGE_MAX_AGE_MS)
  assertEquals((await f.get("/v1/models", ALICE)).age, "42")
  assertEquals((await f.get("/v1/usage", ALICE)).age, "0")
  // The usage read refreshed the entry the model list uses.
  assertEquals((await f.get("/v1/models", ALICE)).age, "0")
  assertEquals(f.catalogCalls().length, 2)
  assertEquals(f.logs, [])
})

Deno.test("/v1/usage reuses an answer only inside its short window, then reads live", async () => {
  const f = await fixture()
  assertEquals((await f.get("/v1/models", ALICE)).body.data[0].allowance, { used: 1 })
  f.clock.advance(USAGE_MAX_AGE_MS - 1)
  assertEquals((await f.get("/v1/usage", ALICE)).body.data[0].allowance, { used: 1 })
  f.clock.advance(1)
  assertEquals((await f.get("/v1/usage", ALICE)).body.data[0].allowance, { used: 2 })
  // Still inside the list's five minutes: /v1/models shows what the usage read just loaded.
  assertEquals((await f.get("/v1/models", ALICE)).body.data[0].allowance, { used: 2 })
  assertEquals(f.catalogCalls().length, 2)
})

Deno.test("/v1/usage never answers an expired catalog: a database failure is the 503", async () => {
  const f = await fixture()
  const first = await f.get("/v1/usage", ALICE)
  assertEquals(first.status, 200)
  f.state.db = "fail"
  // Past the usage window (list still fresh), past the TTL, and near the stale limit.
  for (const ms of [USAGE_MAX_AGE_MS, CATALOG_CACHE_TTL_MS, CATALOG_CACHE_MAX_STALE_MS / 2]) {
    f.clock.advance(ms)
    const usage = await f.get("/v1/usage", ALICE)
    assertEquals(usage.status, 503)
    assertEquals(usage.body.error.code, "unavailable")
    assertEquals(usage.age, null)
  }
  assertEquals(f.logs, [])
  // The same outage on the list route does serve the expired answer.
  const models = await f.get("/v1/models", ALICE)
  assertEquals(models.status, 200)
  assertEquals(models.body.data[0].allowance, { used: 1 })
  assertEquals(f.logs.map((entry) => [entry.path, entry.reason]), [["/v1/models", "error"]])
})

Deno.test("/v1/usage waits for a slow database instead of answering the expired catalog", async () => {
  const f = await fixture({ catalogCache: { staleWaitMs: 5 } })
  await f.get("/v1/usage", ALICE)
  f.clock.advance(CATALOG_CACHE_TTL_MS + 1)
  f.state.db = "hang"
  const pending = f.get("/v1/usage", ALICE)
  await new Promise((r) => setTimeout(r, 30))
  await f.release()
  const usage = await pending
  // The (empty) live answer, not the one model cached before.
  assertEquals(usage.body, { object: "list", data: [] })
  assertEquals(usage.age, "0")
  assertEquals(f.logs, [])
})

Deno.test("a slow database serves /v1/models the expired catalog with reason timeout", async () => {
  const f = await fixture({ catalogCache: { staleWaitMs: 5 } })
  const first = await f.get("/v1/models", ALICE)
  f.clock.advance(CATALOG_CACHE_TTL_MS + 2_000)
  f.state.db = "hang"
  const second = await f.get("/v1/models", ALICE)
  assertEquals(second.status, 200)
  assertEquals(second.body, first.body)
  assertEquals(second.age, "302")
  assertEquals(f.logs, [{
    event: "catalog_stale_served",
    request_id: second.requestId,
    user: ALICE,
    path: "/v1/models",
    reason: "timeout",
    age_ms: CATALOG_CACHE_TTL_MS + 2_000,
  }])
  await f.release()
})

Deno.test("an expired catalog older than the stale limit is not served: the failure is the 503", async () => {
  const f = await fixture()
  await f.get("/v1/models", ALICE)
  f.state.db = "fail"
  f.clock.advance(CATALOG_CACHE_MAX_STALE_MS - 1)
  assertEquals((await f.get("/v1/models", ALICE)).status, 200)
  f.clock.advance(1)
  const expired = await f.get("/v1/models", ALICE)
  assertEquals(expired.status, 503)
  assertEquals(expired.age, null)
  assertEquals(f.logs.length, 1)
})

Deno.test("cache options injected through the handler change what HTTP callers see", async () => {
  const f = await fixture({ catalogCache: { ttlMs: 1_000, maxStaleMs: 2_000, maxEntries: 1 } })
  assertEquals((await f.get("/v1/models", ALICE)).body.data[0].allowance, { used: 1 })
  f.clock.advance(999)
  assertEquals((await f.get("/v1/models", ALICE)).body.data[0].allowance, { used: 1 })
  // ttlMs: one second later the default five minutes would still answer from memory.
  f.clock.advance(1)
  assertEquals((await f.get("/v1/models", ALICE)).body.data[0].allowance, { used: 2 })
  // The per-call usage window cannot outlive a shorter TTL.
  f.clock.advance(1_000)
  assertEquals((await f.get("/v1/usage", ALICE)).body.data[0].allowance, { used: 3 })
  assertEquals(f.catalogCalls().length, 3)
  // maxEntries: Bob's read evicts Alice, so her next request inside the TTL loads again.
  await f.get("/v1/models", BOB)
  assertEquals((await f.get("/v1/models", ALICE)).age, "0")
  assertEquals(f.catalogCalls().length, 5)
  // maxStaleMs: two seconds on, the default thirty minutes would still cover a failure.
  f.state.db = "fail"
  f.clock.advance(2_000)
  assertEquals((await f.get("/v1/models", ALICE)).status, 503)
})

Deno.test("a revoked caller with a cached catalog is refused at inference", async () => {
  const f = await fixture()
  const model = `model-of-${ALICE}`
  assertEquals((await f.get("/v1/models", ALICE)).body.data[0].id, model)
  // While permitted the same fixture admits and dispatches, so the refusal below is the revoke.
  assertEquals((await f.chat(ALICE)).status, 200)
  assertEquals(f.upstream.length, 1)

  f.state.revoked = true
  const before = f.calls.length
  const refused = await f.chat(ALICE)
  assertEquals(refused.status, 403)
  assertEquals(refused.body.error.code, "forbidden")
  // Refused by a live lookup: no catalog read, no reservation, nothing sent upstream.
  assertEquals(f.calls.slice(before).map((x) => x.name), ["boss_ai_lookup"])
  assertEquals(f.upstream.length, 1)
  // The list route still shows the model from memory, which is why inference must not trust it.
  const listed = await f.get("/v1/models", ALICE)
  assertEquals(listed.body.data[0].id, model)
  assertEquals(listed.age, "0")
  assertEquals(f.catalogCalls().length, 1)
  assertEquals((await f.chat(ALICE)).status, 403)
})
