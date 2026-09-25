/**
 * State token tests.
 *
 * Run: cd supabase/functions/fluck-oauth && deno task test
 *
 * The fixture case is the important one. `fluck-oauth-state.fixture.json` is byte identical to
 * the copy in the fluck-agent-imessage repo at `src/test/resources/`, where the Kotlin minter is
 * asserted against the same token. A change to the header bytes, the claim order or the key
 * derivation therefore fails HERE and THERE, instead of deploying cleanly and refusing every
 * callback in production, which is the one failure mode this format has.
 */
import { assert, assertEquals } from "@std/assert"
import { keyBytes, MAX_STATE_AGE_SECONDS, mintState, verifyState } from "../state.ts"

const fixture = JSON.parse(
  await Deno.readTextFile(new URL("./fluck-oauth-state.fixture.json", import.meta.url)),
) as {
  key: string
  keyBytesHex: string
  token: string
  tamperedToken: string
  claims: { ws: string; uid: string; cid: string; n: string; iat: number; exp: number }
}

const key = keyBytes(fixture.key)

Deno.test("the fixture key decodes to the 32 bytes both repos agree on", () => {
  assertEquals(
    Array.from(key).map((b) => b.toString(16).padStart(2, "0")).join(""),
    fixture.keyBytesHex,
  )
})

Deno.test("minting the fixture claims reproduces the fixture token exactly", async () => {
  assertEquals(await mintState(key, fixture.claims), fixture.token)
})

Deno.test("the fixture token verifies to the fixture claims", async () => {
  const claims = await verifyState(key, fixture.token, fixture.claims.exp - 1)
  assert(claims)
  assertEquals(claims, fixture.claims)
})

Deno.test("a tampered signature is refused", async () => {
  assertEquals(await verifyState(key, fixture.tamperedToken, fixture.claims.exp - 1), null)
})

Deno.test("an expired token is refused at its own expiry", async () => {
  assertEquals(await verifyState(key, fixture.token, fixture.claims.exp), null)
})

Deno.test("a token signed with another key is refused", async () => {
  const other = keyBytes(new Array(43).fill("A").join(""))
  const token = await mintState(other, fixture.claims)
  assertEquals(await verifyState(key, token, fixture.claims.exp - 1), null)
})

Deno.test("a token whose own lifetime exceeds the policy is refused", async () => {
  const iat = 1_750_000_000
  const token = await mintState(key, {
    ...fixture.claims,
    iat,
    exp: iat + MAX_STATE_AGE_SECONDS + 1,
  })
  assertEquals(await verifyState(key, token, iat + 10), null)
})

Deno.test("a token with the wrong number of segments is refused", async () => {
  assertEquals(await verifyState(key, "a.b", 0), null)
  assertEquals(await verifyState(key, fixture.token + ".x", 0), null)
})

Deno.test("future issuance and nonpositive lifetimes are refused", async () => {
  const now = fixture.claims.iat
  for (
    const claims of [
      { ...fixture.claims, iat: now + 1 },
      { ...fixture.claims, iat: now + 10, exp: now + 10 },
      { ...fixture.claims, iat: now + 20, exp: now + 10 },
    ]
  ) {
    assertEquals(await verifyState(key, await mintState(key, claims), now), null)
  }
})

Deno.test("a null JSON header is refused without throwing", async () => {
  const parts = fixture.token.split(".")
  parts[0] = btoa("null").replaceAll("=", "")
  assertEquals(await verifyState(key, parts.join("."), fixture.claims.iat), null)
})

Deno.test("an alg the token chose for itself is refused", async () => {
  const b64 = (value: string) =>
    btoa(value).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
  const forged = b64('{"alg":"none","typ":"JWT"}') + "." +
    fixture.token.split(".")[1] + "."
  assertEquals(await verifyState(key, forged, fixture.claims.exp - 1), null)
})

Deno.test("a missing or short state key is refused rather than defaulted", () => {
  let threw = false
  try {
    keyBytes(undefined)
  } catch {
    threw = true
  }
  assert(threw)
  threw = false
  try {
    keyBytes("short")
  } catch {
    threw = true
  }
  assert(threw)
})

Deno.test("a long passphrase falls back to its utf8 bytes", () => {
  // Not valid base64url (it has a space), so the second branch takes it. Both ends implement
  // the same two branches, which is what keeps an operator's passphrase working on both.
  const passphrase = "a passphrase long enough to be a key, at least thirty two bytes"
  assertEquals(keyBytes(passphrase), new TextEncoder().encode(passphrase))
})
