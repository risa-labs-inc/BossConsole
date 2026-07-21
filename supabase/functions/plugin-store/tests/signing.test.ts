/**
 * Wire-format tests for store artifact signing.
 *
 * The canonical anchor is hand-duplicated across three implementations
 * (utils/signing.ts here, PluginStoreTrust.versionAnchor in BossConsole, and
 * scripts/backfill-plugin-signatures.ts) — a single divergence silently
 * breaks verification for real installs. These tests pin the exact anchor
 * string and the signature format so the TS side cannot drift unnoticed;
 * the Kotlin side pins the same in PluginSignatureVerifierTest.
 *
 * Run: deno test --allow-env tests/signing.test.ts
 */
import { assertEquals, assert } from "jsr:@std/assert"
import { signVersionAnchor, versionAnchor } from "../utils/signing.ts"

Deno.test("versionAnchor pins the exact canonical format", () => {
  assertEquals(
    versionAnchor(
      "ai.rever.boss.plugin.dynamic.example",
      "1.2.3",
      "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90",
    ),
    // pluginId verbatim | version verbatim | digest lowercased
    "ai.rever.boss.plugin.dynamic.example|1.2.3|a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
  )
})

Deno.test("signVersionAnchor produces a verifiable RSASSA-PKCS1-v1_5/SHA-256 signature over the anchor", async () => {
  // Generate an ephemeral keypair and feed the private half through the same
  // env-var + PEM path production uses.
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]) },
    true,
    ["sign", "verify"],
  )
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey))
  const pem = "-----BEGIN PRIVATE KEY-----\n" +
    btoa(String.fromCharCode(...pkcs8)).replace(/(.{64})/g, "$1\n") +
    "\n-----END PRIVATE KEY-----"
  Deno.env.set("PLUGIN_SIGNING_PRIVATE_KEY", pem)

  const pluginId = "ai.rever.boss.plugin.dynamic.example"
  const version = "1.2.3"
  const sha256 = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

  const signature = await signVersionAnchor(pluginId, version, sha256)
  assert(signature !== null, "expected a signature with the key configured")

  // Base64, decodes to the RSA modulus size (2048 bits = 256 bytes).
  const sigBytes = Uint8Array.from(atob(signature), (c) => c.charCodeAt(0))
  assertEquals(sigBytes.length, 256)

  // Verifies over exactly the canonical anchor bytes — nothing more, nothing less.
  const anchorBytes = new TextEncoder().encode(versionAnchor(pluginId, version, sha256))
  assert(
    await crypto.subtle.verify("RSASSA-PKCS1-v1_5", pair.publicKey, sigBytes, anchorBytes),
    "signature must verify over the canonical anchor",
  )

  // ...and over nothing else: a different version must not verify.
  const wrongAnchor = new TextEncoder().encode(versionAnchor(pluginId, "1.0.0", sha256))
  assertEquals(
    await crypto.subtle.verify("RSASSA-PKCS1-v1_5", pair.publicKey, sigBytes, wrongAnchor),
    false,
  )
})
