import { jwtVerify, SignJWT } from "jose"

const issuer = "boss-ai"
const audience = "boss-ai-inference"
const lifetime = 300

export class HttpError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public extra: Record<string, unknown> = {},
  ) {
    super(message)
  }
}

export function signingKey(secret: string | undefined): Uint8Array {
  if (!secret || new TextEncoder().encode(secret).length < 32) {
    throw new HttpError(503, "unavailable", "BOSS AI is temporarily unavailable.")
  }
  return new TextEncoder().encode(secret)
}

export type Scope = "inference" | "voice"
export const LIVE_MAX_SECONDS = 3600
const MAX_VOICE_AGE = LIVE_MAX_SECONDS + 300

/** A call-scoped token: voice routes only, valid for the call plus a five minute grace. */
export async function mintVoiceToken(userId: string, key: Uint8Array, maxSeconds: number) {
  const now = Math.floor(Date.now() / 1000)
  const exp = now + maxSeconds + 300
  return {
    token: await new SignJWT({ scope: "voice" }).setProtectedHeader({ alg: "HS256" })
      .setIssuer(issuer).setAudience(audience).setSubject(userId).setIssuedAt(now)
      .setExpirationTime(exp).setJti(crypto.randomUUID()).sign(key),
    expires_at: new Date(exp * 1000).toISOString(),
  }
}

export async function mintToken(userId: string, key: Uint8Array) {
  const now = Math.floor(Date.now() / 1000)
  return {
    access_token: await new SignJWT({ scope: "inference" }).setProtectedHeader({ alg: "HS256" })
      .setIssuer(issuer).setAudience(audience).setSubject(userId).setIssuedAt(now)
      .setExpirationTime(now + lifetime).setJti(crypto.randomUUID()).sign(key),
    expires_at: new Date((now + lifetime) * 1000).toISOString(),
    refresh_after_seconds: 180,
  }
}

export async function verifyClaims(
  token: string,
  key: Uint8Array,
  scopes: Scope[] = ["inference"],
): Promise<{ user: string; scope: Scope }> {
  try {
    const { payload } = await jwtVerify(token, key, {
      algorithms: ["HS256"],
      issuer,
      audience,
      requiredClaims: ["sub", "exp", "iat", "jti"],
    })
    const scope = payload.scope
    if (!payload.sub || (scope !== "inference" && scope !== "voice") || !scopes.includes(scope)) {
      throw new Error()
    }
    const age = Math.floor(Date.now() / 1000) - payload.iat!
    const limit = scope === "inference"
      ? lifetime
      : Math.min(payload.exp! - payload.iat!, MAX_VOICE_AGE)
    if (age < 0 || age > limit) throw new Error()
    return { user: payload.sub, scope }
  } catch {
    throw new HttpError(401, "unauthorized", "Sign in to BOSS to use AI.")
  }
}

export function bearer(request: Request): string {
  const match = /^Bearer ([^\s]+)$/i.exec(request.headers.get("authorization") ?? "")
  if (!match || match[1].length > 16384) {
    throw new HttpError(401, "unauthorized", "Sign in to BOSS to use AI.")
  }
  return match[1]
}

export async function verifyToken(
  token: string,
  key: Uint8Array,
  scopes: Scope[] = ["inference"],
): Promise<string> {
  return (await verifyClaims(token, key, scopes)).user
}
