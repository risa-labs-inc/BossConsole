/**
 * signed.ts is a copy of fluck-vault's scheme, and the copy is the whole authentication of
 * `/refresh`. These cases pin it to the original: every vector runs through both
 * implementations, so a drift in either direction fails here.
 *
 * Run: cd supabase/functions/fluck-oauth && deno task test
 */
import { assert, assertEquals } from "@std/assert"
import {
  bodyDigest,
  decodeBase64,
  publicKeyBytes,
  SIGNATURE_SKEW_SECONDS,
  signingString,
  verifySigned,
} from "../signed.ts"
import * as vaultToken from "../../fluck-vault/token.ts"
import * as vaultSigned from "../../fluck-vault/signed.ts"

const NOW = 1_800_000_000
const PAIR = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
  "sign",
  "verify",
]) as CryptoKeyPair
const RAW = new Uint8Array(await crypto.subtle.exportKey("raw", PAIR.publicKey))
const SPKI = new Uint8Array(await crypto.subtle.exportKey("spki", PAIR.publicKey))

function b64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
}
function b64url(bytes: Uint8Array, padded = false): string {
  const value = b64(bytes).replaceAll("+", "-").replaceAll("/", "_")
  return padded ? value : value.replaceAll("=", "")
}
function pem(der: Uint8Array): string {
  const body = b64(der).match(/.{1,64}/g)!.join("\n")
  return `-----BEGIN PUBLIC KEY-----\n${body}\n-----END PUBLIC KEY-----\n`
}

function vaultKey(value: string): Uint8Array | null {
  try {
    return vaultToken.publicKeyBytes(value)
  } catch {
    return null
  }
}

const KEY_CORPUS: string[] = [
  b64(RAW),
  b64(RAW).replaceAll("=", ""),
  b64url(RAW),
  b64url(RAW, true),
  `  ${b64(RAW)}\n`,
  pem(SPKI),
  pem(SPKI).replaceAll("\n", ""),
  pem(SPKI).replace("PUBLIC KEY", "ED25519 PUBLIC KEY"),
  pem(RAW),
  pem(SPKI.slice(1)),
  b64(RAW.slice(1)),
  b64(new Uint8Array([...RAW, 0])),
  b64(SPKI),
  "",
  "   ",
  "not base64!",
  "====",
  "A",
  "-----BEGIN PUBLIC KEY-----",
]

Deno.test("publicKeyBytes accepts exactly the key forms fluck-vault accepts", () => {
  for (const value of KEY_CORPUS) {
    assertEquals(publicKeyBytes(value), vaultKey(value), JSON.stringify(value))
  }
  for (const value of [b64(RAW), b64url(RAW), pem(SPKI)]) {
    assertEquals(publicKeyBytes(value), RAW, value)
  }
  assertEquals(publicKeyBytes(null), null)
  assertEquals(publicKeyBytes(undefined), null)
})

Deno.test("decodeBase64 agrees with fluck-vault's on every input", () => {
  for (
    const value of [
      "",
      "AA",
      "AA==",
      "AAA",
      "-_8",
      "+/8=",
      "A",
      "AAAAA",
      "a b",
      "AA=A",
      b64(RAW),
      b64url(RAW),
    ]
  ) {
    assertEquals(decodeBase64(value), vaultToken.decodeBase64(value), value)
  }
})

Deno.test("the canonical string and body digest are byte for byte fluck-vault's", async () => {
  for (const body of ["", "{}", '{"refresh_token":"1//x"}', "€\n"]) {
    assertEquals(await bodyDigest(body), await vaultSigned.bodyDigest(body))
    const digest = await bodyDigest(body)
    assertEquals(
      signingString("POST", "/refresh", NOW, digest),
      vaultSigned.signingString("POST", "/refresh", NOW, digest),
    )
  }
  assertEquals(
    await bodyDigest(""),
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  )
  assertEquals(
    signingString("POST", "/refresh", NOW + 0.9, "d"),
    "fluck-vault-signed-v1\nPOST\n/refresh\n1800000000\nd",
  )
  assertEquals(SIGNATURE_SKEW_SECONDS, vaultSigned.SIGNATURE_SKEW_SECONDS)
})

async function sign(
  body: string,
  ts: number | string,
  options: { path?: string; key?: CryptoKey } = {},
): Promise<Uint8Array> {
  const message = signingString(
    "POST",
    options.path ?? "/refresh",
    Number(ts),
    await bodyDigest(body),
  )
  return new Uint8Array(
    await crypto.subtle.sign(
      { name: "Ed25519" },
      options.key ?? PAIR.privateKey,
      new TextEncoder().encode(message) as BufferSource,
    ),
  )
}

async function both(options: {
  publicKey: string
  body?: string
  timestamp: string | null
  signature: string | null
  path?: string
}): Promise<boolean> {
  const request = {
    method: "POST",
    path: options.path ?? "/refresh",
    body: options.body ?? "{}",
    timestamp: options.timestamp,
    signature: options.signature,
    nowSeconds: NOW,
  }
  const here = await verifySigned({ ...request, publicKey: options.publicKey })
  const there = await vaultSigned.verifySigned({ ...request, publicKey: options.publicKey })
  assertEquals(here, there, JSON.stringify({ ...options, publicKey: "…" }))
  return here
}

Deno.test("a good signature verifies under every accepted key and signature encoding", async () => {
  const signature = await sign("{}", NOW)
  for (const key of [b64(RAW), b64url(RAW), b64url(RAW, true), pem(SPKI)]) {
    for (const encoded of [b64url(signature), b64url(signature, true), b64(signature)]) {
      assert(await both({ publicKey: key, timestamp: String(NOW), signature: encoded }))
    }
  }
})

Deno.test("the skew window is inclusive at 120 seconds and closed at 121", async () => {
  for (const offset of [-SIGNATURE_SKEW_SECONDS, SIGNATURE_SKEW_SECONDS]) {
    const ts = NOW + offset
    const signature = b64url(await sign("{}", ts))
    assert(await both({ publicKey: b64(RAW), timestamp: String(ts), signature }), String(offset))
  }
  for (const offset of [-SIGNATURE_SKEW_SECONDS - 1, SIGNATURE_SKEW_SECONDS + 1]) {
    const ts = NOW + offset
    const signature = b64url(await sign("{}", ts))
    assertEquals(
      await both({ publicKey: b64(RAW), timestamp: String(ts), signature }),
      false,
      String(offset),
    )
  }
})

Deno.test("a timestamp that is not an integer is refused", async () => {
  const signature = b64url(await sign("{}", NOW))
  for (const timestamp of [`${NOW}.5`, "NaN", "Infinity", "-Infinity", "", "1e9x", null]) {
    assertEquals(
      await both({ publicKey: b64(RAW), timestamp, signature }),
      false,
      String(timestamp),
    )
  }
})

Deno.test("a signature of the wrong length, key, path or body is refused", async () => {
  const good = await sign("{}", NOW)
  const other = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
    "sign",
  ]) as CryptoKeyPair
  const cases: { signature: string | null; body?: string; path?: string; publicKey?: string }[] = [
    { signature: b64url(good.slice(0, 63)) },
    { signature: b64url(new Uint8Array([...good, 0])) },
    { signature: b64url(await sign("{}", NOW, { key: other.privateKey })) },
    { signature: b64url(await sign("{}", NOW, { path: "/requests" })) },
    { signature: b64url(good), body: "{ }" },
    { signature: b64url(good), path: "/inbox/claim" },
    { signature: b64url(good), publicKey: "" },
    { signature: "" },
    { signature: null },
    { signature: "!!" },
  ]
  for (const c of cases) {
    assertEquals(
      await both({
        publicKey: c.publicKey ?? b64(RAW),
        timestamp: String(NOW),
        signature: c.signature,
        body: c.body,
        path: c.path,
      }),
      false,
      JSON.stringify(c),
    )
  }
})
