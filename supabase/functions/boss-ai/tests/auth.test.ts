import { assertEquals, assertRejects, assertThrows } from "@std/assert"
import { SignJWT } from "jose"
import { HttpError, signingKey, verifyToken } from "../auth.ts"

Deno.test("AI credentials require the correct audience issuer scope lifetime and claims", async () => {
  const key = signingKey("test-only-signing-secret-at-least-32-bytes")
  const now = Math.floor(Date.now() / 1000)
  const claims = {
    sub: "user",
    iss: "boss-ai",
    aud: "boss-ai-inference",
    scope: "inference",
    iat: now,
    exp: now + 300,
    jti: "request",
  }
  const signed = (payload: Record<string, unknown>) =>
    new SignJWT(payload).setProtectedHeader({ alg: "HS256" }).sign(key)
  assertEquals(await verifyToken(await signed(claims), key), "user")
  for (
    const changed of [
      { ...claims, aud: "another-service" },
      { ...claims, iss: "another-issuer" },
      { ...claims, scope: "admin" },
      { ...claims, exp: now - 1 },
      { ...claims, iat: now - 301 },
      Object.fromEntries(Object.entries(claims).filter(([k]) => k !== "exp")),
      Object.fromEntries(Object.entries(claims).filter(([k]) => k !== "jti")),
    ]
  ) {
    const token = await signed(changed)
    const error = await assertRejects(() => verifyToken(token, key), HttpError)
    assertEquals(error.status, 401)
  }
})

Deno.test("missing or short AI signing secrets fail closed", () => {
  for (const secret of [undefined, "", "too-short"]) {
    const error = assertThrows(() => signingKey(secret), HttpError)
    assertEquals(error.status, 503)
  }
})
