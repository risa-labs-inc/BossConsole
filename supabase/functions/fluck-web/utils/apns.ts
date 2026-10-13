/**
 * APNs provider API (token auth) with WebCrypto only. The payload carries the agent name and a
 * fixed line; an optional `sealed` blob is ciphertext produced on the user's own Fluck, opaque here.
 * Deno's fetch negotiates HTTP/2, which APNs requires.
 */

import { b64uEncode } from "./webpush.ts";

const enc = new TextEncoder();
export const APNS_TOPIC = "app.fluck.ios";
/** Where the app's start host points; the notification's tap target. */
export const APNS_PORTAL = "https://fluck.risaboss.com";
/** APNs refuses a payload over 4 KB. */
export const MAX_APNS_PAYLOAD_BYTES = 4096;
/** APNs rejects a provider token older than 60 minutes and throttles refreshes faster than every 20. */
const JWT_CACHE_SECONDS = 50 * 60;
const HOSTS = {
  production: "https://api.push.apple.com",
  sandbox: "https://api.sandbox.push.apple.com",
} as const;
const TOKEN_RE = /^[0-9a-f]{64,512}$/;
const KEY_ID_RE = /^[A-Z0-9]{10}$/;

export interface ApnsConfig {
  keyP8: string;
  keyId: string;
  teamId: string;
}

export interface ApnsDevice {
  id: string;
  token: string;
  environment: "sandbox" | "production";
  topic: string;
}

export interface ApnsResult {
  status: number;
  reason?: string;
}

let warnedMissing = false;
let cached: { id: string; jwt: string; at: number } | null = null;

export function resetApnsState() {
  warnedMissing = false;
  cached = null;
}

/** The three secrets, or null (logged once per isolate) unless all are present and well formed. */
export function readApns(): ApnsConfig | null {
  const keyP8 = Deno.env.get("FLUCK_APNS_KEY_P8")?.trim() ?? "";
  const keyId = Deno.env.get("FLUCK_APNS_KEY_ID")?.trim() ?? "";
  const teamId = Deno.env.get("FLUCK_APNS_TEAM_ID")?.trim() ?? "";
  if (!keyP8 || !KEY_ID_RE.test(keyId) || !KEY_ID_RE.test(teamId)) {
    if (!warnedMissing) {
      warnedMissing = true;
      console.warn("apns not configured: skipping native pushes");
    }
    return null;
  }
  return { keyP8, keyId, teamId };
}

export function isApnsDevice(value: unknown): value is ApnsDevice {
  if (!value || typeof value !== "object") return false;
  const v = value as Record<string, unknown>;
  return typeof v.id === "string" && typeof v.token === "string" &&
    TOKEN_RE.test(v.token) &&
    (v.environment === "sandbox" || v.environment === "production") &&
    v.topic === APNS_TOPIC;
}

function pemBody(pem: string): Uint8Array<ArrayBuffer> {
  const b64 = pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

/** ES256 provider token, cached per isolate for 50 minutes. */
export async function apnsJwt(
  cfg: ApnsConfig,
  nowSeconds: number = Math.floor(Date.now() / 1000),
): Promise<string> {
  const id = `${cfg.keyId}:${cfg.teamId}:${cfg.keyP8}`;
  if (
    cached && cached.id === id && nowSeconds - cached.at < JWT_CACHE_SECONDS &&
    nowSeconds >= cached.at
  ) return cached.jwt;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemBody(cfg.keyP8),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  const input = `${
    b64uEncode(enc.encode(JSON.stringify({ alg: "ES256", kid: cfg.keyId })))
  }.${
    b64uEncode(enc.encode(JSON.stringify({ iss: cfg.teamId, iat: nowSeconds })))
  }`;
  const sig = new Uint8Array(
    await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      enc.encode(input),
    ),
  );
  const jwt = `${input}.${b64uEncode(sig)}`;
  cached = { id, jwt, at: nowSeconds };
  return jwt;
}

/** The alert JSON; `sealed` rides along only when the whole payload still fits APNs' 4 KB. */
export function buildApnsPayload(
  agentName: string,
  instanceId: string,
  messageId: string,
  sealed?: string,
): string {
  const base: Record<string, unknown> = {
    aps: {
      alert: { title: agentName, body: "New message" },
      sound: "default",
      "thread-id": `fluck-${instanceId}`,
      "mutable-content": 1,
    },
    message_id: messageId,
    url: `${APNS_PORTAL}/?instance=${encodeURIComponent(instanceId)}`,
  };
  if (sealed) {
    const withSealed = JSON.stringify({ ...base, sealed });
    if (enc.encode(withSealed).length <= MAX_APNS_PAYLOAD_BYTES) {
      return withSealed;
    }
  }
  return JSON.stringify(base);
}

export interface ApnsSendOptions {
  fetch: (input: string, init?: RequestInit) => Promise<Response>;
  timeoutMs?: number;
  nowSeconds?: number;
}

/** One POST; resolves with APNs' status and reason, rejects on a network error or timeout. */
export async function sendApns(
  device: ApnsDevice,
  payload: string,
  cfg: ApnsConfig,
  opts: ApnsSendOptions,
): Promise<ApnsResult> {
  if (!isApnsDevice(device)) throw new Error("device refused");
  const now = opts.nowSeconds ?? Math.floor(Date.now() / 1000);
  const jwt = await apnsJwt(cfg, now);
  const resp = await opts.fetch(
    `${HOSTS[device.environment]}/3/device/${device.token}`,
    {
      method: "POST",
      headers: {
        "apns-topic": device.topic,
        "apns-push-type": "alert",
        "apns-priority": "10",
        "apns-expiration": String(now + 86400),
        "authorization": `bearer ${jwt}`,
        "content-type": "application/json",
      },
      body: payload,
      redirect: "manual",
      signal: AbortSignal.timeout(opts.timeoutMs ?? 8000),
    },
  );
  let reason: string | undefined;
  if (resp.status !== 200) {
    const parsed = await resp.json().catch(() => null) as
      | { reason?: unknown }
      | null;
    if (typeof parsed?.reason === "string") reason = parsed.reason;
  } else {
    await resp.body?.cancel();
  }
  return { status: resp.status, reason };
}

/** The device token is dead for good: unregistered, or a token this app/environment never issued. */
export function isDeadDevice(r: ApnsResult): boolean {
  return r.status === 410 ||
    (r.status === 400 &&
      (r.reason === "BadDeviceToken" || r.reason === "DeviceTokenNotForTopic"));
}
