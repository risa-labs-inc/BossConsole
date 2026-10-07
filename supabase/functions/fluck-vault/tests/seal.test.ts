/**
 * The sealing scheme, sealed and opened.
 *
 * Run: cd supabase/functions/fluck-vault && deno task test
 *
 * The function itself never seals and never opens, so nothing in `app.ts` would fail if the
 * scheme in `seal.ts` were incoherent: the page would encrypt something the DGX cannot read and
 * the only symptom would be a card that silently never arrived. These cases therefore implement
 * BOTH halves against the documented byte layout, so the README's description is executable
 * rather than a claim. `open()` below is the reference the JCA side is written from.
 */
import { assert, assertEquals } from "@std/assert"
import {
  looksSealed,
  SEAL_INFO_PREFIX,
  SEAL_MAX_BYTES,
  SEAL_PUBLIC_KEY_BYTES,
  SEAL_VERSION,
  sealPublicKey,
} from "../seal.ts"

const JTI = "11111111-2222-3333-4444-555555555555"

async function recipient(): Promise<{ publicRaw: Uint8Array; privateKey: CryptoKey }> {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDH", namedCurve: "P-256" },
    true,
    ["deriveBits"],
  ) as CryptoKeyPair
  return {
    publicRaw: new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey)),
    privateKey: pair.privateKey,
  }
}

async function aesKey(shared: ArrayBuffer, jti: string, usage: KeyUsage[]): Promise<CryptoKey> {
  const ikm = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveKey"])
  return await crypto.subtle.deriveKey(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: new Uint8Array(32) as BufferSource,
      info: new TextEncoder().encode(SEAL_INFO_PREFIX + jti) as BufferSource,
    },
    ikm,
    { name: "AES-GCM", length: 256 },
    false,
    usage,
  )
}

/** What the page's inline script does, in the same order and with the same parameters. */
async function seal(publicRaw: Uint8Array, jti: string, plaintext: string): Promise<string> {
  const theirs = await crypto.subtle.importKey(
    "raw",
    publicRaw as BufferSource,
    { name: "ECDH", namedCurve: "P-256" },
    false,
    [],
  )
  const ephemeral = await crypto.subtle.generateKey(
    { name: "ECDH", namedCurve: "P-256" },
    true,
    ["deriveBits"],
  ) as CryptoKeyPair
  const epk = new Uint8Array(await crypto.subtle.exportKey("raw", ephemeral.publicKey))
  const shared = await crypto.subtle.deriveBits(
    { name: "ECDH", public: theirs },
    ephemeral.privateKey,
    256,
  )
  const key = await aesKey(shared, jti, ["encrypt"])
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const ct = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: iv as BufferSource, additionalData: new TextEncoder().encode(jti) },
      key,
      new TextEncoder().encode(plaintext) as BufferSource,
    ),
  )
  const blob = new Uint8Array(1 + epk.length + 12 + ct.length)
  blob[0] = SEAL_VERSION
  blob.set(epk, 1)
  blob.set(iv, 1 + epk.length)
  blob.set(ct, 1 + epk.length + 12)
  return btoa(String.fromCharCode(...blob))
}

/** What the DGX does. The reference for the JCA implementation described in the README. */
async function open(privateKey: CryptoKey, jti: string, blob: string): Promise<string> {
  const bytes = Uint8Array.from(atob(blob), (c) => c.charCodeAt(0))
  assertEquals(bytes[0], SEAL_VERSION)
  const epk = bytes.slice(1, 1 + SEAL_PUBLIC_KEY_BYTES)
  const iv = bytes.slice(1 + SEAL_PUBLIC_KEY_BYTES, 1 + SEAL_PUBLIC_KEY_BYTES + 12)
  const ct = bytes.slice(1 + SEAL_PUBLIC_KEY_BYTES + 12)
  const theirs = await crypto.subtle.importKey(
    "raw",
    epk as BufferSource,
    { name: "ECDH", namedCurve: "P-256" },
    false,
    [],
  )
  const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: theirs }, privateKey, 256)
  const key = await aesKey(shared, jti, ["decrypt"])
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: iv as BufferSource, additionalData: new TextEncoder().encode(jti) },
    key,
    ct as BufferSource,
  )
  return new TextDecoder().decode(plain)
}

Deno.test("a card sealed to the recipient key opens to exactly what was sealed", async () => {
  const { publicRaw, privateKey } = await recipient()
  const card = JSON.stringify({
    kind: "card",
    name: "A Person",
    pan: "4111111111111111",
    exp: "04/29",
    billing: { line1: "1 Street", city: "Town", postal: "12345", country: "US" },
  })
  const blob = await seal(publicRaw, JTI, card)
  assertEquals(await open(privateKey, JTI, blob), card)
})

Deno.test("the blob layout is the version byte, the ephemeral point, the iv, then the rest", async () => {
  const { publicRaw } = await recipient()
  const blob = Uint8Array.from(
    atob(await seal(publicRaw, JTI, '{"kind":"cvv","cvv":"123"}')),
    (c) => c.charCodeAt(0),
  )
  assertEquals(blob[0], SEAL_VERSION)
  assertEquals(blob[1], 0x04)
  // Version, a 65 byte point, a 12 byte IV, the ciphertext, and a 16 byte tag over 26 bytes.
  assertEquals(blob.length, 1 + 65 + 12 + 26 + 16)
})

Deno.test("a blob sealed under one jti does not open under another", async () => {
  const { publicRaw, privateKey } = await recipient()
  const blob = await seal(publicRaw, JTI, "secret")
  let failed = false
  try {
    await open(privateKey, "99999999-9999-9999-9999-999999999999", blob)
  } catch {
    failed = true
  }
  assert(failed)
})

Deno.test("a blob sealed to one recipient does not open for another", async () => {
  const mine = await recipient()
  const theirs = await recipient()
  const blob = await seal(theirs.publicRaw, JTI, "secret")
  let failed = false
  try {
    await open(mine.privateKey, JTI, blob)
  } catch {
    failed = true
  }
  assert(failed)
})

Deno.test("a real sealed blob passes the structural check", async () => {
  const { publicRaw } = await recipient()
  assert(looksSealed(await seal(publicRaw, JTI, "secret")))
})

Deno.test("plaintext, a wrong version byte and an oversized blob all fail the check", () => {
  assertEquals(looksSealed("4111111111111111"), false)
  assertEquals(looksSealed(""), false)
  assertEquals(looksSealed(42), false)
  const wrongVersion = new Uint8Array(120)
  wrongVersion[0] = 0x02
  wrongVersion[1] = 0x04
  assertEquals(looksSealed(btoa(String.fromCharCode(...wrongVersion))), false)
  const oversized = new Uint8Array(SEAL_MAX_BYTES + 1)
  oversized[0] = SEAL_VERSION
  oversized[1] = 0x04
  assertEquals(looksSealed(btoa(String.fromCharCode(...oversized))), false)
})

Deno.test("a malformed sealing public key is refused rather than served to a page", async () => {
  const { publicRaw } = await recipient()
  assertEquals(sealPublicKey(btoa(String.fromCharCode(...publicRaw))).length, 65)
  for (const bad of [undefined, "", "AAAA", btoa("x".repeat(65))]) {
    let threw = false
    try {
      sealPublicKey(bad)
    } catch {
      threw = true
    }
    assert(threw, `expected a throw for ${String(bad).slice(0, 8)}`)
  }
})

/**
 * The committed cross language vector.
 *
 * `tests/fixtures/seal-vector.json` is opened here and, byte for byte the same file, by
 * `VaultSealVectorTest` in the plugin repo. That is what makes "the Kotlin side implements the
 * same scheme" a fact rather than two readings of the same prose. The key pair in it is a
 * throwaway generated once for this purpose and opens nothing.
 */
Deno.test("the committed vector opens, and is the one the Kotlin side reads", async () => {
  const vector = JSON.parse(
    await Deno.readTextFile(new URL("./fixtures/seal-vector.json", import.meta.url)),
  )
  const priv = await crypto.subtle.importKey(
    "pkcs8",
    decodeVector(vector.recipientPrivateKeyPkcs8Base64) as BufferSource,
    { name: "ECDH", namedCurve: "P-256" },
    false,
    ["deriveBits"],
  )
  const blob = decodeVector(vector.sealedBase64)
  assertEquals(blob[0], SEAL_VERSION)
  const ephemeral = await crypto.subtle.importKey(
    "raw",
    blob.slice(1, 66) as BufferSource,
    { name: "ECDH", namedCurve: "P-256" },
    false,
    [],
  )
  const shared = new Uint8Array(
    await crypto.subtle.deriveBits({ name: "ECDH", public: ephemeral }, priv, 256),
  )
  const hk = await crypto.subtle.importKey("raw", shared as BufferSource, "HKDF", false, [
    "deriveBits",
  ])
  const keyBytes = await crypto.subtle.deriveBits(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: new Uint8Array(32) as BufferSource,
      info: new TextEncoder().encode(SEAL_INFO_PREFIX + vector.jti) as BufferSource,
    },
    hk,
    256,
  )
  const aes = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["decrypt"])
  const plain = await crypto.subtle.decrypt(
    {
      name: "AES-GCM",
      iv: blob.slice(66, 78) as BufferSource,
      additionalData: new TextEncoder().encode(vector.jti) as BufferSource,
      tagLength: 128,
    },
    aes,
    blob.slice(78) as BufferSource,
  )
  assertEquals(new TextDecoder().decode(plain), vector.plaintext)
})

function decodeVector(value: string): Uint8Array {
  const binary = atob(value)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
  return out
}
