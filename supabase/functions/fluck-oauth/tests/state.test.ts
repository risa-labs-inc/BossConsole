/**
 * State token tests.
 *
 * Run: cd supabase/functions/fluck-oauth && deno task test
 *
 * The fixture case is the important one. `fluck-oauth-state.fixture.json` is byte identical to
 * the copy in the fluck-agent-imessage repo at `src/test/resources/`, where the Kotlin minter is
 * asserted against the same token. Ed25519 is deterministic, so both sides must reproduce it
 * exactly from the fixture seed; a change to the prefix or the claim order fails HERE and THERE.
 */
import { assert, assertEquals } from "@std/assert"
import {
  MAX_STATE_AGE_SECONDS,
  mintState,
  parseState,
  STATE_SIGNING_PREFIX,
  type StateClaims,
  statePayload,
  verifyState,
} from "../state.ts"
import { publicKeyBytes } from "../signed.ts"

const fixture = JSON.parse(
  await Deno.readTextFile(new URL("./fluck-oauth-state.fixture.json", import.meta.url)),
) as {
  seedHex: string
  privateKeyPkcs8: string
  publicKey: string
  signingPrefix: string
  payload: string
  claims: StateClaims
  signingInput: string
  token: string
  tamperedToken: string
}

const privateKey = await crypto.subtle.importKey(
  "pkcs8",
  Uint8Array.from(atob(fixture.privateKeyPkcs8), (c) => c.charCodeAt(0)) as BufferSource,
  { name: "Ed25519" },
  true,
  ["sign"],
)
const publicKey = publicKeyBytes(fixture.publicKey)!
const NOW = fixture.claims.exp - 1

async function verify(token: string, now = NOW, key = publicKey): Promise<boolean> {
  const parsed = parseState(token)
  return parsed !== null && await verifyState(key, parsed, now)
}

Deno.test("the fixture key pair is the seed both repos agree on", async () => {
  const pkcs8 = Uint8Array.from(atob(fixture.privateKeyPkcs8), (c) => c.charCodeAt(0))
  assertEquals(
    Array.from(pkcs8.slice(16)).map((b) => b.toString(16).padStart(2, "0")).join(""),
    fixture.seedHex,
  )
  const jwk = await crypto.subtle.exportKey("jwk", privateKey)
  assertEquals(jwk.x, fixture.publicKey)
  assertEquals(STATE_SIGNING_PREFIX, fixture.signingPrefix)
})

Deno.test("the fixture claims serialise to the fixture payload bytes", () => {
  assertEquals(statePayload(fixture.claims), fixture.payload)
  assertEquals(fixture.signingInput, STATE_SIGNING_PREFIX + fixture.token.split(".")[0])
})

Deno.test("minting the fixture claims reproduces the fixture token exactly", async () => {
  assertEquals(await mintState(privateKey, fixture.claims), fixture.token)
})

Deno.test("the fixture token parses and verifies to the fixture claims", async () => {
  const parsed = parseState(fixture.token)
  assert(parsed)
  assertEquals(parsed.claims, fixture.claims)
  assert(await verifyState(publicKey, parsed, NOW))
})

Deno.test("a tampered signature is refused", async () => {
  assertEquals(await verify(fixture.tamperedToken), false)
})

Deno.test("an expired token is refused at its own expiry", async () => {
  assertEquals(await verify(fixture.token, fixture.claims.exp), false)
})

Deno.test("a token signed by another install is refused", async () => {
  const other = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
    "sign",
    "verify",
  ]) as CryptoKeyPair
  const token = await mintState(other.privateKey, fixture.claims)
  assertEquals(await verify(token), false)
})

Deno.test("a signature without the domain prefix is refused", async () => {
  const segment = fixture.token.split(".")[0]
  const bare = await crypto.subtle.sign(
    { name: "Ed25519" },
    privateKey,
    new TextEncoder().encode(segment) as BufferSource,
  )
  const b64 = btoa(String.fromCharCode(...new Uint8Array(bare)))
    .replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
  assertEquals(await verify(segment + "." + b64), false)
})

Deno.test("a token living longer than the policy is refused, from now and from iat", async () => {
  const now = 1_750_000_000
  const far = await mintState(privateKey, {
    ...fixture.claims,
    iat: undefined,
    exp: now + MAX_STATE_AGE_SECONDS + 1,
  })
  assertEquals(await verify(far, now), false)
  const long = await mintState(privateKey, {
    ...fixture.claims,
    iat: now - 10,
    exp: now - 10 + MAX_STATE_AGE_SECONDS + 1,
  })
  assertEquals(await verify(long, now), false)
})

Deno.test("iat and cid are optional; future iat is refused", async () => {
  const now = fixture.claims.iat!
  const bare: StateClaims = { ...fixture.claims, exp: now + 600 }
  delete bare.iat
  delete bare.cid
  assert(await verify(await mintState(privateKey, bare), now))
  const future = await mintState(privateKey, { ...fixture.claims, iat: now + 1 })
  assertEquals(await verify(future, now), false)
})

Deno.test("malformed tokens and claims are refused at parse", async () => {
  assertEquals(parseState("a.b.c"), null)
  assertEquals(parseState(fixture.token + ".x"), null)
  assertEquals(parseState("nodot"), null)
  const sig = fixture.token.split(".")[1]
  const seg = (value: string) =>
    btoa(value).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
  for (
    const payload of [
      "null",
      "[]",
      JSON.stringify({ ...fixture.claims, v: 2 }),
      JSON.stringify({ ...fixture.claims, iid: "short" }),
      JSON.stringify({ ...fixture.claims, uid: "" }),
      JSON.stringify({ ...fixture.claims, nonce: "x".repeat(129) }),
      JSON.stringify({ ...fixture.claims, exp: "1750000600" }),
      JSON.stringify({ ...fixture.claims, cid: 7 }),
    ]
  ) {
    assertEquals(parseState(seg(payload) + "." + sig), null, payload)
  }
  // A well formed payload with a short signature is refused before any key is looked up.
  assertEquals(parseState(fixture.token.split(".")[0] + ".AAAA"), null)
  assertEquals(await verify("a.b"), false)
})
