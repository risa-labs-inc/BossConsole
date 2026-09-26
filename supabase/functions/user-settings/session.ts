const enc = new TextEncoder();
export interface Session {
  sub: string;
  csrf: string;
  exp: number;
}
const b64 = (b: Uint8Array) =>
  btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_")
    .replace(/=/g, "");
const bytes = (s: string) =>
  Uint8Array.from(
    atob(s.replace(/-/g, "+").replace(/_/g, "/")),
    (c) => c.charCodeAt(0),
  );
async function key(secret: string) {
  if (secret.length < 32) {
    throw new Error("Settings session secret unavailable");
  }
  return await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
}
export async function signSession(
  session: Session,
  secret: string,
): Promise<string> {
  const body = b64(enc.encode(JSON.stringify(session)));
  return body + "." +
    b64(
      new Uint8Array(
        await crypto.subtle.sign("HMAC", await key(secret), enc.encode(body)),
      ),
    );
}
export async function verifySession(
  value: string,
  secret: string,
  now = Date.now(),
): Promise<Session | null> {
  try {
    if (value.length > 2048) return null;
    const [body, sig, extra] = value.split(".");
    if (
      !body || !sig || extra ||
      !await crypto.subtle.verify(
        "HMAC",
        await key(secret),
        bytes(sig),
        enc.encode(body),
      )
    ) return null;
    const s = JSON.parse(new TextDecoder().decode(bytes(body)));
    if (
      typeof s.sub !== "string" || !/^[0-9a-f-]{36}$/i.test(s.sub) ||
      typeof s.csrf !== "string" ||
      !/^[0-9a-f-]{36}$/i.test(s.csrf) || !Number.isFinite(s.exp) ||
      s.exp <= now / 1000 || s.exp > now / 1000 + 1801
    ) return null;
    return s;
  } catch {
    return null;
  }
}
export function cookies(header: string, name: string): string[] {
  return header.split(";").map((v) => v.trim()).filter((v) =>
    v.startsWith(name + "=")
  ).map((v) => v.slice(name.length + 1));
}
