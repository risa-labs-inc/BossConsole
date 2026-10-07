import { validCloseResponse } from "../_shared/app-sharing/close-response.ts";
interface Job {
  id: number;
  claim: string;
  descriptor: {
    sfu_id: string;
    mid?: string | null;
    channel_id?: number | null;
    transport_channel_id?: number | null;
  };
}
export interface Dependencies {
  claim: (nonce: string, timestamp: number) => Promise<{ jobs: Job[] }>;
  ack: (id: number, claim: string) => Promise<unknown>;
  close: (
    session: string,
    path: string,
    body: Record<string, unknown>,
  ) => Promise<void>;
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    },
  });
export function createMaintenance(
  secret: string,
  deps: Dependencies,
  now: () => number = Date.now,
) {
  return async (req: Request): Promise<Response> => {
    if (secret.length < 32) return json({ error: "not_configured" }, 503);
    if (req.method !== "POST") {
      return json({ error: "method_not_allowed" }, 405);
    }
    try {
      const signature = req.headers.get("X-App-Sharing-Maintenance");
      if (!signature || !/^[0-9a-f]{64}$/.test(signature)) {
        return json({ error: "unauthorized" }, 401);
      }
      const reader = req.body?.getReader();
      if (!reader) return json({ error: "invalid_request" }, 400);
      let bytes = new Uint8Array(0);
      let timer: ReturnType<typeof setTimeout> | undefined;
      const timeout = new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(Error("timeout")), 2000);
      });
      try {
        for (let i = 0;; i++) {
          const { done, value } = await Promise.race([reader.read(), timeout]);
          if (done) break;
          if (bytes.length + value.length > 256 || i >= 256) {
            return json({ error: "invalid_request" }, 400);
          }
          const next = new Uint8Array(bytes.length + value.length);
          next.set(bytes);
          next.set(value, bytes.length);
          bytes = next;
        }
      } finally {
        clearTimeout(timer);
        void reader.cancel().catch(() => {});
        reader.releaseLock();
      }
      const key = await crypto.subtle.importKey(
        "raw",
        new TextEncoder().encode(secret),
        { name: "HMAC", hash: "SHA-256" },
        false,
        ["verify"],
      );
      const signed = Uint8Array.from(
        signature.match(/../g)!,
        (v) => parseInt(v, 16),
      );
      if (!await crypto.subtle.verify("HMAC", key, signed, bytes)) {
        return json({ error: "unauthorized" }, 401);
      }
      const body = JSON.parse(
        new TextDecoder("utf-8", { fatal: true }).decode(bytes),
      );
      if (
        !body || Object.keys(body).sort().join(",") !== "nonce,timestamp" ||
        !uuid.test(body.nonce) || !Number.isSafeInteger(body.timestamp) ||
        Math.abs(now() / 1000 - body.timestamp) > 60
      ) return json({ error: "invalid_request" }, 400);
      const { jobs } = await deps.claim(body.nonce, body.timestamp);
      if (!Array.isArray(jobs) || jobs.length > 8) throw Error("invalid claim");
      let closed = 0, failed = 0;
      await Promise.all(jobs.map(async (job) => {
        try {
          const m = job.descriptor;
          if (!/^[A-Za-z0-9_-]{1,128}$/.test(m.sfu_id)) {
            throw Error("invalid session");
          }
          if (m.mid) {
            await deps.close(m.sfu_id, "tracks/close", {
              tracks: [{ mid: m.mid }],
              force: true,
            });
          }
          const ids = [m.channel_id, m.transport_channel_id].filter(
            Number.isInteger,
          );
          if (ids.length) {
            await deps.close(m.sfu_id, "datachannels/close", {
              dataChannels: ids.map((id) => ({ id })),
            });
          }
          await deps.ack(job.id, job.claim);
          closed++;
        } catch {
          failed++;
        }
      }));
      return json({ claimed: jobs.length, closed, failed });
    } catch {
      return json({ error: "maintenance_failed" }, 503);
    }
  };
}
export function productionDependencies(): Dependencies {
  const base = Deno.env.get("SUPABASE_URL") ?? "",
    key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const rpc = async (name: string, body: Record<string, unknown>) => {
    const r = await fetch(`${base}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers: {
        apikey: key,
        Authorization: `Bearer ${key}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(10000),
    });
    if (!r.ok) throw Error("database");
    return r.json();
  };
  return {
    claim: (nonce, timestamp) =>
      rpc("claim_app_sharing_cleanup", {
        p_nonce: nonce,
        p_timestamp: timestamp,
        p_limit: 8,
      }),
    ack: (id, claim) =>
      rpc("ack_app_sharing_cleanup", { p_id: id, p_claim: claim }),
    close: async (session, path, body) => {
      const app = Deno.env.get("APP_SHARING_SFU_APP_ID") ?? "",
        secret = Deno.env.get("APP_SHARING_SFU_SECRET") ?? "";
      if (!/^[A-Za-z0-9_-]{1,128}$/.test(app) || !secret) {
        throw Error("provider");
      }
      const r = await fetch(
        `https://rtc.live.cloudflare.com/v1/apps/${app}/sessions/${session}/${path}`,
        {
          method: "PUT",
          headers: {
            Authorization: `Bearer ${secret}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(body),
          signal: AbortSignal.timeout(5000),
        },
      );
      if (!r.ok) throw Error("provider");
      const value = await r.json();
      if (!validCloseResponse(path, body, value)) throw Error("provider");
    },
  };
}
