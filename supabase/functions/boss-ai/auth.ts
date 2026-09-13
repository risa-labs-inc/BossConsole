import { jwtVerify, SignJWT } from "jose"

const issuer = "boss-ai"
const audience = "boss-ai-inference"
const lifetime = 300

export class HttpError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message)
  }
}

export function signingKey(secret: string | undefined): Uint8Array {
  if (!secret || new TextEncoder().encode(secret).length < 32) {
    throw new HttpError(503, "unavailable", "BOSS AI is temporarily unavailable.")
  }
  return new TextEncoder().encode(secret)
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

export async function verifyToken(token: string, key: Uint8Array): Promise<string> {
  try {
    const { payload } = await jwtVerify(token, key, {
      algorithms: ["HS256"],
      issuer,
      audience,
      maxTokenAge: lifetime,
      requiredClaims: ["sub", "exp", "iat", "jti"],
    })
    if (payload.scope !== "inference" || !payload.sub) throw new Error()
    return payload.sub
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
