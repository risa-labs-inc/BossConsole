import { assert, assertEquals } from "@std/assert"
import { decodeJwt } from "jose"
import { createHandler } from "../app.ts"
import { mintToken, mintVoiceToken, signingKey } from "../auth.ts"
import { liveKitClients } from "../livekit.ts"
import { Obj } from "../wire.ts"

const secret = "test-signing-secret-at-least-32-bytes-long"
const user = "11111111-2222-3333-4444-555555555555"
const agentToken = "a".repeat(43)
const month = { limit: 3600, used: 100, remaining: 3500, resets_at: "2026-11-01T00:00:00Z" }
const pool = { month }

async function fixture(options: {
  env?: Record<string, string | undefined>
  usage?: unknown
  failRoom?: boolean
  reserve?: unknown
  claimed?: boolean
  roomExists?: boolean
  failDispatch?: boolean
  failToken?: boolean
  failSettlement?: boolean
  reservationSeconds?: number
} = {}) {
  const calls: { name: string; args: unknown[] }[] = []
  const rpcs: { name: string; params: Obj }[] = []
  const env: Record<string, string | undefined> = {
    BOSS_AI_SIGNING_SECRET: secret,
    LIVEKIT_URL: "wss://demo.livekit.cloud",
    LIVEKIT_API_KEY: "lk-key",
    LIVEKIT_API_SECRET: "lk-secret",
    BOSS_AI_AGENT_ORIGINS: "https://fluck.example",
    SUPABASE_URL: "https://api.example",
    ...options.env,
  }
  const handler = createHandler({
    sessionUser: async () => null,
    secret: (name) => env[name],
    fetch: async () => new Response("{}"),
    audit: () => {},
    async rpc(name, params) {
      rpcs.push({ name, params })
      if (name === "boss_ai_claim_voice_start") return options.claimed ?? true
      if (name === "boss_ai_lookup") {
        return {
          model: { kind: "transcription", max_output_tokens: options.reservationSeconds ?? 60 },
        }
      }
      if (name === "boss_ai_settle" && options.failSettlement) throw new Error("db down")
      if (name === "boss_ai_usage") return "usage" in options ? options.usage : pool
      if (name === "boss_ai_reserve") {
        return "reserve" in options
          ? options.reserve
          : { model: { kind: "transcription", max_output_tokens: 120 } }
      }
      return null
    },
    livekit: (config) => {
      calls.push({ name: "config", args: [config] })
      return {
        async roomExists() {
          return options.roomExists ?? false
        },
        async deleteRoom(room) {
          calls.push({ name: "deleteRoom", args: [room] })
        },
        async createRoom(room) {
          calls.push({ name: "createRoom", args: [room] })
          if (options.failRoom) throw new Error("livekit down")
        },
        async createDispatch(room, agent, metadata) {
          calls.push({ name: "createDispatch", args: [room, agent, metadata] })
          if (options.failDispatch) throw new Error("dispatch down")
        },
        async participantToken(grant) {
          calls.push({ name: "participantToken", args: [grant] })
          if (options.failToken) throw new Error("signing failed")
          return "participant-jwt"
        },
      }
    },
  })
  const inference = (await mintToken(user, signingKey(secret))).access_token
  const voice = (await mintVoiceToken(user, signingKey(secret), 1800)).token
  const live = (body: Obj, token = inference, origin = "https://api.example") =>
    handler(
      new Request(`${origin}/functions/v1/boss-ai/v1/voice/live`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        body: JSON.stringify(body),
      }),
    )
  const get = (path: string, token: string) =>
    handler(
      new Request(`https://api.example/functions/v1/boss-ai/v1/${path}`, {
        headers: { Authorization: `Bearer ${token}` },
      }),
    )
  const post = (path: string, token: string, body: Obj) =>
    handler(
      new Request(`https://api.example/functions/v1/boss-ai/v1/${path}`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        body: JSON.stringify(body),
      }),
    )
  return { calls, rpcs, live, get, post, inference, voice }
}
const good = { agent_url: "https://fluck.example/api/voice/agent", agent_token: agentToken }

Deno.test("live call creates the room, dispatches the agent and mints scoped tokens", async () => {
  const f = await fixture()
  const response = await f.live({ ...good, max_seconds: 600 })
  assertEquals(response.status, 200)
  const out = await response.json()
  assertEquals(out.server_url, "wss://demo.livekit.cloud")
  assertEquals(out.participant_token, "participant-jwt")
  assert(/^fluck-call-[0-9a-f-]{36}$/.test(out.room))
  assertEquals(out.quota, { remaining_seconds: 3500, resets_at: "2026-11-01T00:00:00Z" })
  assert(Number.isFinite(Date.parse(out.expires_at)))

  const room = f.calls.find((c) => c.name === "createRoom")!.args[0]
  assertEquals(room, {
    name: out.room,
    emptyTimeout: 60,
    departureTimeout: 120,
    maxParticipants: 2,
  })
  const [dispatchRoom, agent, metadataText] = f.calls.find((c) => c.name === "createDispatch")!
    .args as string[]
  assertEquals([dispatchRoom, agent], [out.room, "max-voice-agent"])
  const metadata = JSON.parse(metadataText)
  assertEquals(metadata.agent_url, good.agent_url)
  assertEquals(metadata.agent_token, agentToken)
  assertEquals(metadata.stt_model, "max-stt")
  assertEquals(metadata.tts_model, "max-tts")
  assertEquals(metadata.max_seconds, 600)
  assertEquals(metadata.user, user)
  assertEquals(metadata.inference.base_url, "https://api.example/functions/v1/boss-ai/v1")
  const claims = decodeJwt(metadata.inference.token)
  assertEquals(claims.scope, "voice")
  assertEquals(claims.sub, user)
  assertEquals(claims.exp! - claims.iat!, 600 + 300)
  assertEquals(out.expires_at, new Date(claims.exp! * 1000).toISOString())

  assertEquals(f.calls.find((c) => c.name === "participantToken")!.args[0], {
    room: out.room,
    identity: "user-11111111",
    name: "You",
    ttlSeconds: 900,
  })
  assertEquals(f.rpcs.find((r) => r.name === "boss_ai_usage")!.params, {
    p_user_id: user,
    p_model_id: "max-stt",
  })
})

Deno.test("live call defaults max_seconds to 1 h; BOSS_AI_PUBLIC_URL overrides the callback base", async () => {
  const f = await fixture({
    env: {
      BOSS_AI_ALLOW_LOCAL_AGENT: "1",
      BOSS_AI_PUBLIC_URL: "https://public.example/functions/v1/boss-ai/",
    },
  })
  const response = await f.live(good, f.inference, "http://kong:8000")
  assertEquals(response.status, 200)
  const metadata = JSON.parse(f.calls.find((c) => c.name === "createDispatch")!.args[2] as string)
  assertEquals(metadata.max_seconds, 3600)
  assertEquals(metadata.inference.base_url, "https://public.example/functions/v1/boss-ai/v1")
})

Deno.test("live call is unavailable without every LiveKit secret", async () => {
  for (const name of ["LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET"]) {
    const f = await fixture({ env: { [name]: undefined } })
    const response = await f.live(good)
    assertEquals(response.status, 503)
    assertEquals((await response.json()).error.code, "voice_unavailable")
    assertEquals(f.calls.length, 0)
  }
})

Deno.test("live call refuses accounts without a voice policy or with under a minute left", async () => {
  const none = await fixture({ usage: null })
  assertEquals((await none.live(good)).status, 403)
  assertEquals(none.calls.length, 0)

  const spent = {
    month: { limit: 3600, used: 3545, remaining: 55, resets_at: "2026-11-01T00:00:00Z" },
  }
  const f = await fixture({ usage: spent })
  const response = await f.live(good)
  assertEquals(response.status, 429)
  const error = (await response.json()).error
  assertEquals(error.code, "allowance_exceeded")
  assertEquals(error.usage, spent)
  assertEquals(f.calls.length, 0)

  const exactly = await fixture({
    usage: { month: { ...month, remaining: 60 } },
  })
  assertEquals((await exactly.live(good)).status, 200)
})

Deno.test("live call validates agent_url, agent_token and max_seconds", async () => {
  const bad = [
    { ...good, agent_url: "http://fluck.example/api/voice/agent" },
    { ...good, agent_url: "http://127.0.0.1:8080/api/voice/agent" },
    { ...good, agent_url: "http://localhost/api/voice/agent" },
    { ...good, agent_url: "ftp://fluck.example/x" },
    { ...good, agent_url: "https://user:pw@fluck.example/x" },
    { ...good, agent_url: "https://fluck.example/" + "a".repeat(2048) },
    { ...good, agent_url: "not a url" },
    { ...good, agent_url: 5 },
    { agent_token: agentToken },
    { ...good, agent_token: "short" },
    { ...good, agent_token: "a".repeat(257) },
    { ...good, agent_token: "has space ".repeat(5) },
    { ...good, max_seconds: 59 },
    { ...good, max_seconds: 3601 },
    { ...good, max_seconds: 60.5 },
    { ...good, max_seconds: "600" },
    { ...good, extra: true },
  ]
  for (const body of bad) {
    const f = await fixture()
    const response = await f.live(body)
    assertEquals(response.status, 400, JSON.stringify(body).slice(0, 80))
    assertEquals(f.calls.length, 0)
    assertEquals(f.rpcs.length, 0)
  }
  for (const agent_url of ["http://127.0.0.1:8080/x", "http://localhost/x"]) {
    const f = await fixture({ env: { BOSS_AI_ALLOW_LOCAL_AGENT: "1" } })
    assertEquals((await f.live({ ...good, agent_url })).status, 200)
  }
  const flagOff = await fixture({ env: { BOSS_AI_ALLOW_LOCAL_AGENT: "0" } })
  assertEquals((await flagOff.live({ ...good, agent_url: "http://localhost/x" })).status, 400)
  const remote = await fixture({ env: { BOSS_AI_ALLOW_LOCAL_AGENT: "1" } })
  assertEquals((await remote.live({ ...good, agent_url: "http://evil.example/x" })).status, 400)
  const bounds = await fixture()
  assertEquals((await bounds.live({ ...good, max_seconds: 60 })).status, 200)
  assertEquals((await bounds.live({ ...good, max_seconds: 3600 })).status, 200)
  assertEquals((await bounds.live({ ...good, max_seconds: 3601 })).status, 400)
})

Deno.test("a LiveKit failure is a generic 503 that leaks nothing", async () => {
  const f = await fixture({ failRoom: true })
  const response = await f.live(good)
  assertEquals(response.status, 503)
  const text = await response.text()
  assert(!text.includes("livekit down") && !text.includes(agentToken))
})

Deno.test("voice tokens work on audio routes only", async () => {
  const f = await fixture()
  const stt = { model: "bad model!" }
  // Audio routes accept both scopes: validation (400) comes after authentication.
  for (const token of [f.inference, f.voice]) {
    assertEquals((await f.post("audio/transcriptions", token, stt)).status, 400)
    assertEquals((await f.post("audio/speech", token, stt)).status, 400)
  }
  assertEquals((await f.post("chat/completions", f.inference, stt)).status, 400)
  // Everything else is inference-only.
  assertEquals((await f.post("chat/completions", f.voice, stt)).status, 401)
  assertEquals((await f.get("models", f.voice)).status, 401)
  assertEquals((await f.get("usage", f.voice)).status, 401)
  assertEquals((await f.get("provider", f.voice)).status, 401)
  assertEquals((await f.live(good, f.voice)).status, 401)
  assertEquals((await f.get("models", f.inference)).status, 200)
  assertEquals(f.calls.length, 0)
})

Deno.test("voice token lifetime is exp - iat, capped at 1 h 5 min; inference stays at 300 s", async () => {
  const key = signingKey(secret)
  const { SignJWT } = await import("jose")
  const sign = (scope: string, iatAgo: number, lifetime: number) => {
    const iat = Math.floor(Date.now() / 1000) - iatAgo
    return new SignJWT({ scope }).setProtectedHeader({ alg: "HS256" }).setIssuer("boss-ai")
      .setAudience("boss-ai-inference").setSubject(user).setIssuedAt(iat)
      .setExpirationTime(iat + lifetime).setJti(crypto.randomUUID()).sign(key)
  }
  const f = await fixture()
  const status = async (token: string) =>
    (await f.post("audio/speech", token, { model: "bad model!" })).status
  assertEquals(await status(await sign("voice", 3000, 3900)), 400)
  assertEquals(await status(await sign("voice", 3800, 40_000)), 400)
  assertEquals(await status(await sign("voice", 4000, 40_000)), 401)
  assertEquals(await status(await sign("inference", 400, 3600)), 401)
  assertEquals(await status(await sign("inference", 100, 3600)), 400)
  assertEquals(await status(await sign("other", 1, 3600)), 401)
  assertEquals(await status(await sign("voice", -3600, 3900)), 401)
  assertEquals(await status(await sign("inference", -3600, 300)), 401)
})

Deno.test("the real participant token grants a single room only", async () => {
  const client = liveKitClients({
    url: "wss://demo.livekit.cloud",
    apiKey: "lk-key",
    apiSecret: "lk-secret-that-is-long-enough-for-hs256",
  })
  const claims = decodeJwt(
    await client.participantToken({
      room: "fluck-call-x",
      identity: "user-11111111",
      name: "You",
      ttlSeconds: 900,
    }),
  )
  assertEquals(claims.iss, "lk-key")
  assertEquals(claims.sub, "user-11111111")
  assertEquals(claims.name, "You")
  assertEquals(claims.exp! - claims.nbf!, 900)
  assertEquals((claims as Obj).video, {
    room: "fluck-call-x",
    roomJoin: true,
    canPublish: true,
    canSubscribe: true,
    canPublishData: true,
  })
})

Deno.test("voice usage reserves and settles the reported seconds on max-stt", async () => {
  const f = await fixture()
  const response = await f.post("voice/usage", f.voice, { seconds: 12.2 })
  assertEquals(response.status, 200)
  assertEquals(await response.json(), {
    billed_seconds: 13,
    remaining_seconds: 3500,
    resets_at: "2026-11-01T00:00:00Z",
  })
  assertEquals(f.rpcs.map((r) => r.name), ["boss_ai_reserve", "boss_ai_settle", "boss_ai_usage"])
  const reserve = f.rpcs[0].params
  assertEquals([reserve.p_user_id, reserve.p_model_id], [user, "max-stt"])
  assertEquals(f.rpcs[1].params, { p_request_id: reserve.p_request_id, p_tokens: 13 })
})

Deno.test("voice usage rejects reports exceeding 60 seconds", async () => {
  const f = await fixture()
  const response = await f.post("voice/usage", f.voice, { seconds: 600 })
  assertEquals(response.status, 400)
  assertEquals(f.rpcs.length, 0)
})

Deno.test("voice usage takes voice tokens only", async () => {
  const f = await fixture()
  assertEquals((await f.post("voice/usage", f.inference, { seconds: 5 })).status, 401)
  assertEquals((await f.post("voice/usage", "garbage", { seconds: 5 })).status, 401)
  assertEquals(f.rpcs.length, 0)
})

Deno.test("voice usage rejects bad bodies before touching the ledger", async () => {
  for (
    const body of [{}, { seconds: 0 }, { seconds: -3 }, { seconds: "5" }, { seconds: 5, x: 1 }]
  ) {
    const f = await fixture()
    const response = await f.post("voice/usage", f.voice, body as Obj)
    assertEquals(response.status, 400, JSON.stringify(body))
    assertEquals((await response.json()).error.code, "invalid_seconds")
    assertEquals(f.rpcs.length, 0)
  }
})

Deno.test("voice usage: an exhausted pool is 429 allowance_exceeded and nothing is settled", async () => {
  const usage = { month: { ...month, remaining: 0 } }
  const f = await fixture({ reserve: { error: "allowance_exceeded", usage } })
  const response = await f.post("voice/usage", f.voice, { seconds: 30 })
  assertEquals(response.status, 429)
  const error = (await response.json()).error
  assertEquals(error.code, "allowance_exceeded")
  assertEquals(error.usage, usage)
  assertEquals(f.rpcs.map((r) => r.name), ["boss_ai_reserve"])
})

Deno.test("voice usage without a voice policy is 403", async () => {
  for (const reserve of [null, { error: "forbidden" }]) {
    const f = await fixture({ reserve })
    assertEquals((await f.post("voice/usage", f.voice, { seconds: 5 })).status, 403)
  }
})

Deno.test("live callbacks allow exact operator origins and HTTPS quick tunnels only", async () => {
  for (
    const url of [
      "https://127.0.0.1/x",
      "https://169.254.169.254/x",
      "https://internal/x",
      "https://evil.example/x",
      "https://a.trycloudflare.com.evil.test/x",
      "https://a.b.trycloudflare.com/x",
      "https://a.trycloudflare.com:444/x",
      "https://user@a.trycloudflare.com/x",
      "https://a.trycloudflare.com/x?q=1",
    ]
  ) {
    const f = await fixture()
    assertEquals((await f.live({ ...good, agent_url: url })).status, 400, url)
    assertEquals(f.calls.length, 0)
  }
  for (
    const url of [
      "https://one-two-three.trycloudflare.com/api/voice/agent",
      "https://agent.bossconsole.ai/voice/agent",
    ]
  ) {
    const f = await fixture({ env: { BOSS_AI_AGENT_ORIGINS: "https://agent.bossconsole.ai" } })
    assertEquals((await f.live({ ...good, agent_url: url })).status, 200, url)
  }
})

Deno.test("call admission refuses an active room, denied durable claim and insufficient reservation", async () => {
  for (
    const options of [{ roomExists: true }, { claimed: false }, {
      reservationSeconds: 120,
      usage: { month: { ...month, remaining: 90 } },
    }]
  ) {
    const f = await fixture(options)
    assertEquals((await f.live(good)).status, 429)
    assertEquals(
      f.calls.filter((c) => c.name === "createRoom" || c.name === "createDispatch").length,
      0,
    )
  }
})

Deno.test("partial room creation is cleaned up and token failure precedes creation", async () => {
  for (const options of [{ failRoom: true }, { failDispatch: true }]) {
    const f = await fixture(options)
    assertEquals((await f.live(good)).status, 503)
    assertEquals(f.calls.filter((c) => c.name === "deleteRoom").length, 1)
  }
  const f = await fixture({ failToken: true })
  assertEquals((await f.live(good)).status, 503)
  assertEquals(f.calls.filter((c) => c.name === "createRoom").length, 0)
})

Deno.test("invalid callback configuration and null duration fail before room creation", async () => {
  for (
    const env of [
      { BOSS_AI_PUBLIC_URL: "not a URL" },
      { BOSS_AI_PUBLIC_URL: "http://insecure.example" },
      { LIVEKIT_URL: "https://user:secret@live.example" },
      { LIVEKIT_URL: "bad" },
    ]
  ) {
    const f = await fixture({ env })
    assertEquals((await f.live(good)).status, 503)
    assertEquals(f.calls.length, 0)
  }
  const f = await fixture()
  assertEquals((await f.live({ ...good, max_seconds: null })).status, 400)
})

Deno.test("voice bearer cannot select audio models outside the call pool", async () => {
  const f = await fixture()
  for (const route of ["audio/speech", "audio/transcriptions"]) {
    assertEquals((await f.post(route, f.voice, { model: "other-audio" })).status, 403)
  }
  assertEquals(f.rpcs.length, 0)
})

Deno.test("failed usage settlement reports retained reservation without claiming exact billing", async () => {
  const f = await fixture({ failSettlement: true })
  const response = await f.post("voice/usage", f.voice, { seconds: 12.4 })
  assertEquals(response.status, 200)
  const result = await response.json()
  assertEquals(result.billed_seconds, null)
  assertEquals(result.billing_status, "reservation_retained")
  assertEquals(f.rpcs.filter((r) => r.name === "boss_ai_settle").length, 2)
})

Deno.test("real LiveKit SDK sends room and dispatch operations with grants and metadata", async () => {
  const original = globalThis.fetch
  const requests: { url: string; body: any; claims: any }[] = []
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init)
    requests.push({
      url: request.url,
      body: await request.json(),
      claims: decodeJwt(request.headers.get("Authorization")!.replace("Bearer ", "")),
    })
    return Response.json(request.url.endsWith("ListRooms") ? { rooms: [] } : {})
  }
  try {
    const sdk = liveKitClients({
      url: "wss://demo.livekit.cloud",
      apiKey: "key",
      apiSecret: "secret",
    })
    assertEquals(await sdk.roomExists("room"), false)
    await sdk.createRoom({
      name: "room",
      emptyTimeout: 60,
      departureTimeout: 120,
      maxParticipants: 2,
    })
    await sdk.createDispatch("room", "max-voice-agent", "metadata")
    await sdk.deleteRoom("room")
    assertEquals(requests.map((r) => new URL(r.url).pathname.split("/").pop()), [
      "ListRooms",
      "CreateRoom",
      "CreateDispatch",
      "DeleteRoom",
    ])
    assertEquals(requests[2].body, {
      agentName: "max-voice-agent",
      room: "room",
      metadata: "metadata",
    })
    assertEquals(requests[1].claims.video.roomCreate, true)
    assert(requests.every((r) => r.url.startsWith("https://demo.livekit.cloud/")))
  } finally {
    globalThis.fetch = original
  }
})

Deno.test("voice usage reports the capped ledger charge when its model reservation is smaller", async () => {
  const f = await fixture({ reserve: { model: { kind: "transcription", max_output_tokens: 10 } } })
  const response = await f.post("voice/usage", f.voice, { seconds: 12.4 })
  assertEquals((await response.json()).billed_seconds, 10)
})
