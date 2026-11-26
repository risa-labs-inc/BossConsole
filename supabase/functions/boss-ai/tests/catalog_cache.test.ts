import { assertEquals, assertRejects } from "@std/assert"
import { createHandler } from "../app.ts"
import { mintToken, signingKey } from "../auth.ts"
import { CATALOG_CACHE_TTL_MS, CatalogCache } from "../catalog_cache.ts"
import { Obj } from "../wire.ts"

const secret = "test-signing-secret-at-least-32-bytes-long"
const ALICE = "00000000-0000-0000-0000-00000000000a"
const BOB = "00000000-0000-0000-0000-00000000000b"

function clock(start = 1_000_000) {
  let t = start
  return { now: () => t, advance: (ms: number) => t += ms }
}

Deno.test("the catalog TTL is five minutes", () => {
  assertEquals(CATALOG_CACHE_TTL_MS, 300_000)
})

Deno.test("a fresh answer is served without a second load", async () => {
  const c = clock()
  const cache = new CatalogCache({ now: c.now })
  let loads = 0
  const load = () => Promise.resolve([{ id: "m", n: ++loads }])
  assertEquals(await cache.get(ALICE, load), [{ id: "m", n: 1 }])
  c.advance(CATALOG_CACHE_TTL_MS - 1)
  assertEquals(await cache.get(ALICE, load), [{ id: "m", n: 1 }])
  assertEquals(loads, 1)
})

Deno.test("an expired answer is loaded again", async () => {
  const c = clock()
  const cache = new CatalogCache({ now: c.now })
  let loads = 0
  const load = () => Promise.resolve(++loads)
  assertEquals(await cache.get(ALICE, load), 1)
  c.advance(CATALOG_CACHE_TTL_MS)
  assertEquals(await cache.get(ALICE, load), 2)
  assertEquals(await cache.get(ALICE, load), 2)
  assertEquals(loads, 2)
})

Deno.test("an expired answer is served when the load fails, and the next call retries", async () => {
  const c = clock()
  const stale: string[] = []
  const cache = new CatalogCache({ now: c.now, onStale: (r) => stale.push(r) })
  assertEquals(await cache.get(ALICE, () => Promise.resolve("old")), "old")
  c.advance(CATALOG_CACHE_TTL_MS + 1)
  assertEquals(await cache.get(ALICE, () => Promise.reject(new Error("db down"))), "old")
  assertEquals(stale, ["error"])
  assertEquals(await cache.get(ALICE, () => Promise.resolve("new")), "new")
})

Deno.test("an expired answer is served when the load is slow, and the late answer is kept", async () => {
  const c = clock()
  const stale: string[] = []
  const cache = new CatalogCache({ now: c.now, staleWaitMs: 5, onStale: (r) => stale.push(r) })
  await cache.get(ALICE, () => Promise.resolve("old"))
  c.advance(CATALOG_CACHE_TTL_MS + 1)
  let finish!: (v: string) => void
  const slow = new Promise<string>((resolve) => finish = resolve)
  assertEquals(await cache.get(ALICE, () => slow), "old")
  assertEquals(stale, ["timeout"])
  finish("late")
  await slow
  await new Promise((r) => setTimeout(r, 0))
  let loads = 0
  assertEquals(await cache.get(ALICE, () => Promise.resolve(++loads)), "late")
  assertEquals(loads, 0)
})

Deno.test("a failed load with nothing cached, or only a too-old answer, fails", async () => {
  const c = clock()
  const cache = new CatalogCache({ now: c.now, maxStaleMs: CATALOG_CACHE_TTL_MS * 2 })
  await assertRejects(() => cache.get(ALICE, () => Promise.reject(new Error("db down"))))
  await cache.get(ALICE, () => Promise.resolve("old"))
  c.advance(CATALOG_CACHE_TTL_MS * 2)
  await assertRejects(() => cache.get(ALICE, () => Promise.reject(new Error("db down"))))
  // Another user's entry is never the fallback.
  await cache.get(BOB, () => Promise.resolve("bob"))
  await assertRejects(() => cache.get(ALICE, () => Promise.reject(new Error("db down"))))
})

Deno.test("each user gets only the answer loaded for that user", async () => {
  const cache = new CatalogCache()
  const loaded: string[] = []
  const load = (user: string) => () => {
    loaded.push(user)
    return Promise.resolve([{ id: `model-of-${user}` }])
  }
  assertEquals(await cache.get(ALICE, load(ALICE)), [{ id: `model-of-${ALICE}` }])
  assertEquals(await cache.get(BOB, load(BOB)), [{ id: `model-of-${BOB}` }])
  assertEquals(await cache.get(ALICE, load(ALICE)), [{ id: `model-of-${ALICE}` }])
  assertEquals(await cache.get(BOB, load(BOB)), [{ id: `model-of-${BOB}` }])
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
    cache.get(ALICE, load(ALICE)),
    cache.get(ALICE, load(ALICE)),
    cache.get(BOB, load(BOB)),
    cache.get(ALICE, load(ALICE)),
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
  const results = await Promise.allSettled([cache.get(ALICE, fail), cache.get(ALICE, fail)])
  assertEquals(results.map((r) => r.status), ["rejected", "rejected"])
  assertEquals(loads, 1)
  assertEquals(await cache.get(ALICE, () => Promise.resolve("ok")), "ok")
})

Deno.test("the cache is bounded and drops the least recently used user", async () => {
  const cache = new CatalogCache({ maxEntries: 2 })
  const loads: string[] = []
  const load = (user: string) => () => {
    loads.push(user)
    return Promise.resolve(user)
  }
  await cache.get("u1", load("u1"))
  await cache.get("u2", load("u2"))
  await cache.get("u1", load("u1")) // u1 is now the most recent
  await cache.get("u3", load("u3")) // drops u2
  assertEquals(cache.size, 2)
  await cache.get("u1", load("u1"))
  await cache.get("u2", load("u2"))
  assertEquals(loads, ["u1", "u2", "u3", "u2"])
})

async function fixture(options: { failAfterFirst?: boolean } = {}) {
  const c = clock(Date.now())
  const calls: { name: string; params: Obj }[] = []
  const logs: Obj[] = []
  const key = signingKey(secret)
  const tokens = {
    [ALICE]: (await mintToken(ALICE, key)).access_token,
    [BOB]: (await mintToken(BOB, key)).access_token,
  }
  let fail = false
  const handler = createHandler({
    now: c.now,
    log: (entry) => logs.push(entry),
    sessionUser: () => Promise.resolve(null),
    secret: (name) => name === "BOSS_AI_SIGNING_SECRET" ? secret : undefined,
    rpc(name, params) {
      calls.push({ name, params })
      if (name !== "boss_ai_catalog") return Promise.resolve(null)
      if (fail) return Promise.reject(new Error("BOSS AI database operation failed"))
      if (options.failAfterFirst) fail = true
      const n = calls.filter((x) => x.name === "boss_ai_catalog").length
      return Promise.resolve([{
        id: `model-of-${params.p_user_id}`,
        allowance: { used: n },
        budget: { load: n },
      }])
    },
    fetch: () => Promise.reject(new Error("no upstream in this test")),
  })
  const get = async (path: string, user: string) => {
    const response = await handler(
      new Request(`https://x.example/functions/v1/boss-ai${path}`, {
        headers: { Authorization: `Bearer ${tokens[user as keyof typeof tokens]}` },
      }),
    )
    return { status: response.status, body: await response.json() }
  }
  const catalogCalls = () => calls.filter((x) => x.name === "boss_ai_catalog")
  return { get, calls, catalogCalls, logs, clock: c }
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
  assertEquals(f.logs, [{ event: "catalog_stale_served", reason: "error" }])
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
