/**
 * Link token tests.
 *
 * Run: cd supabase/functions/fluck-vault && deno task test
 *
 * The key pair is generated per run rather than fixtured, because unlike `fluck-oauth`'s HS256
 * state there is no shared secret to pin: the DGX holds the private half and this end holds
 * only the public one. What has to be pinned is the FORMAT, and `mintLink` builds the payload
 * by concatenation so the byte order is a property of the source rather than of a runtime, and
 * the wire format case below asserts the exact claim order the Kotlin minter must reproduce.
 */
import { assert, assertEquals } from "@std/assert"
import {
  decodeBase64,
  encodeBase64Url,
  type LinkClaims,
  MAX_CVV_AGE_SECONDS,
  MAX_VAULT_AGE_SECONDS,
  mintLink,
  publicKeyBytes,
  verifyLink,
} from "../token.ts"

const AUD = "api.risaboss.com"
const NOW = 1_800_000_000

async function keys(): Promise<{ privateKey: CryptoKey; publicRaw: Uint8Array }> {
  const pair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
    "sign",
    "verify",
  ]) as CryptoKeyPair
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey))
  return { privateKey: pair.privateKey, publicRaw: raw }
}

function vaultClaims(overrides: Partial<LinkClaims> = {}): LinkClaims {
  return {
    jti: "11111111-2222-3333-4444-555555555555",
    ws: "ws-abcdefghij",
    purpose: "vault",
    kind: "card",
    alias: "amex",
    aud: AUD,
    iat: NOW - 10,
    exp: NOW + 500,
    ...overrides,
  }
}

function cvvClaims(overrides: Partial<LinkClaims> = {}): LinkClaims {
  return {
    jti: "66666666-7777-8888-9999-000000000000",
    ws: "ws-abcdefghij",
    purpose: "cvv",
    purchase_id: "pur-1",
    aud: AUD,
    iat: NOW - 10,
    exp: NOW + 240,
    ...overrides,
  }
}

Deno.test("a good vault token verifies to its claims", async () => {
  const { privateKey, publicRaw } = await keys()
  const claims = vaultClaims()
  const token = await mintLink(privateKey, claims)
  assertEquals(await verifyLink(publicRaw, token, AUD, NOW), claims)
})

Deno.test("a good cvv token verifies to its claims", async () => {
  const { privateKey, publicRaw } = await keys()
  const claims = cvvClaims()
  const token = await mintLink(privateKey, claims)
  assertEquals(await verifyLink(publicRaw, token, AUD, NOW), claims)
})

Deno.test("the wire format is the one the Kotlin minter has to reproduce", async () => {
  const { privateKey } = await keys()
  const token = await mintLink(privateKey, vaultClaims())
  const [header, payload] = token.split(".")
  assertEquals(new TextDecoder().decode(decodeBase64(header)!), '{"alg":"EdDSA","typ":"JWT"}')
  assertEquals(
    new TextDecoder().decode(decodeBase64(payload)!),
    '{"jti":"11111111-2222-3333-4444-555555555555","ws":"ws-abcdefghij","purpose":"vault"' +
      ',"kind":"card","alias":"amex","aud":"' + AUD + '","iat":1799999990,"exp":1800000500}',
  )
})

Deno.test("an expired token is refused at its own expiry", async () => {
  const { privateKey, publicRaw } = await keys()
  const claims = vaultClaims()
  const token = await mintLink(privateKey, claims)
  assertEquals(await verifyLink(publicRaw, token, AUD, claims.exp), null)
})

Deno.test("a token for another audience is refused", async () => {
  const { privateKey, publicRaw } = await keys()
  const token = await mintLink(
    privateKey,
    vaultClaims({ aud: "pcnwqamqdnsadranufjv.functions.supabase.co" }),
  )
  assertEquals(await verifyLink(publicRaw, token, AUD, NOW), null)
})

Deno.test("a token signed with another key is refused", async () => {
  const mine = await keys()
  const theirs = await keys()
  const token = await mintLink(theirs.privateKey, vaultClaims())
  assertEquals(await verifyLink(mine.publicRaw, token, AUD, NOW), null)
})

Deno.test("a tampered payload is refused", async () => {
  const { privateKey, publicRaw } = await keys()
  const token = await mintLink(privateKey, vaultClaims())
  const parts = token.split(".")
  const forged = encodeBase64Url(
    new TextEncoder().encode(
      new TextDecoder().decode(decodeBase64(parts[1])!).replace("ws-abcdefghij", "ws-0000000000"),
    ),
  )
  assertEquals(await verifyLink(publicRaw, `${parts[0]}.${forged}.${parts[2]}`, AUD, NOW), null)
})

Deno.test("an alg the token chose for itself is refused", async () => {
  const { privateKey, publicRaw } = await keys()
  const token = await mintLink(privateKey, vaultClaims())
  const header = encodeBase64Url(new TextEncoder().encode('{"alg":"none","typ":"JWT"}'))
  assertEquals(await verifyLink(publicRaw, `${header}.${token.split(".")[1]}.`, AUD, NOW), null)
})

Deno.test("a token with the wrong number of segments is refused", async () => {
  const { publicRaw } = await keys()
  assertEquals(await verifyLink(publicRaw, "a.b", AUD, NOW), null)
})

Deno.test("a vault token whose lifetime exceeds the policy is refused", async () => {
  const { privateKey, publicRaw } = await keys()
  const iat = NOW - 10
  const token = await mintLink(
    privateKey,
    vaultClaims({ iat, exp: iat + MAX_VAULT_AGE_SECONDS + 1 }),
  )
  assertEquals(await verifyLink(publicRaw, token, AUD, NOW), null)
})

Deno.test("a cvv token is held to the shorter five minute policy", async () => {
  const { privateKey, publicRaw } = await keys()
  const iat = NOW - 10
  const tooLong = await mintLink(privateKey, cvvClaims({ iat, exp: iat + MAX_CVV_AGE_SECONDS + 1 }))
  assertEquals(await verifyLink(publicRaw, tooLong, AUD, NOW), null)
  const fine = await mintLink(privateKey, cvvClaims({ iat, exp: iat + MAX_CVV_AGE_SECONDS }))
  assert(await verifyLink(publicRaw, fine, AUD, NOW))
})

Deno.test("a vault token without a kind is refused", async () => {
  const { privateKey, publicRaw } = await keys()
  const token = await mintLink(privateKey, vaultClaims({ kind: undefined }))
  assertEquals(await verifyLink(publicRaw, token, AUD, NOW), null)
})

Deno.test("a cvv token without a purchase is refused", async () => {
  const { privateKey, publicRaw } = await keys()
  const token = await mintLink(privateKey, cvvClaims({ purchase_id: undefined }))
  assertEquals(await verifyLink(publicRaw, token, AUD, NOW), null)
})

Deno.test("the public key decodes from raw base64 and from a PEM block", async () => {
  const { publicRaw } = await keys()
  const base64 = btoa(String.fromCharCode(...publicRaw))
  assertEquals(publicKeyBytes(base64), publicRaw)

  const spki = new Uint8Array(32 + 12)
  spki.set([0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00])
  spki.set(publicRaw, 12)
  const pem = "-----BEGIN PUBLIC KEY-----\n" +
    btoa(String.fromCharCode(...spki)) + "\n-----END PUBLIC KEY-----"
  assertEquals(publicKeyBytes(pem), publicRaw)
})

Deno.test("an unset or wrong length public key throws rather than verifying nothing", () => {
  let threw = 0
  for (const value of [undefined, "", "AAAA"]) {
    try {
      publicKeyBytes(value)
    } catch {
      threw++
    }
  }
  assertEquals(threw, 3)
})
