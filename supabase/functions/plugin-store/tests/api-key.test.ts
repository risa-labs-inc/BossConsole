import { assert, assertEquals, assertMatch } from "@std/assert"
import {
  areValidScopes,
  generateApiKey,
  getKeyPrefix,
  hashApiKey,
  isValidApiKeyFormat,
} from "../utils/api-key.ts"

Deno.test("generated keys carry the documented shape and stay unique", () => {
  const seen = new Set<string>()
  for (let i = 0; i < 500; i++) {
    const key = generateApiKey()
    assert(isValidApiKeyFormat(key), `generated key must pass format validation: ${i}`)
    assertEquals(key.length, 40, "keys are boss_pk_ plus 32 random chars")
    assertMatch(key, /^boss_pk_[A-Za-z0-9]{32}$/)
    assertEquals(seen.has(key), false, "every generated key must be fresh")
    seen.add(key)
  }
  assertEquals(seen.size, 500, "no duplicates across 500 draws")
})

Deno.test("key generation covers the full charset (no folded-away indices)", () => {
  // Rejection sampling maps bytes below 248 onto indices via % 62, which covers
  // every index exactly 4 times. A run of 500 keys (16000 random chars) missing
  // any charset entry would mean the mapping is broken.
  const used = new Set<string>()
  for (let i = 0; i < 500; i++) {
    for (const c of generateApiKey().substring("boss_pk_".length)) used.add(c)
  }
  assertEquals(used.size, 62, "all 62 charset indices must be reachable")
})

Deno.test("hashApiKey matches the database's pgcrypto digest bit for bit", async () => {
  // 20260923172000 pins key_hash to the pgcrypto digest extensions.digest(...,
  // 'sha256') encoded as lowercase hex, and its pgTAP suite seeds rows with
  // digests computed the same way. The edge function must produce the
  // identical digest for the same presented key, or a minted key can never
  // match its stored digest.
  assertEquals(
    await hashApiKey("boss_pk_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"),
    "4349a95d0392fccd07d0b88fd040ec4aa14d78df6830129ac31e0c4a465a6ab1",
    "sha256 of the documented example key, lowercase hex",
  )
  assertEquals(
    await hashApiKey("boss_pk_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef"),
    "6c8d96fc6e16730e4d68f6339efed2b66496022635d49b44fd80fb1057c89c46",
    "sha256 of a second fixed key, lowercase hex",
  )
  assertMatch(await hashApiKey(generateApiKey()), /^[0-9a-f]{64}$/, "always 64 lowercase hex chars")
})

Deno.test("getKeyPrefix exposes only the 16-char display mask, never full material", () => {
  const key = "boss_pk_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
  assertEquals(getKeyPrefix(key), "boss_pk_a1b2c3d4", "first 16 chars total")
  assert(!getKeyPrefix(key).includes("m3n4o5p6"), "no material beyond the 8 display chars")
  assertEquals(getKeyPrefix(key).length, 16)
})

Deno.test("scopes validation accepts only the known set, never empty", () => {
  assert(areValidScopes(["publish"]))
  assert(areValidScopes(["publish", "version", "finalize"]))
  assert(!areValidScopes([]))
  assert(!areValidScopes(["publish", "delete"]))
})
