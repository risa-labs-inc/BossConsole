/**
 * Sealed (end-to-end encrypted) previews and native APNs through POST /api/push/notify, with
 * `deps.fetch` stubbed so no network is touched.
 *
 * Run: cd supabase/functions/fluck-web && deno test --allow-all
 */

import { assert, assertEquals, assertFalse } from "@std/assert";
import { app, deps } from "../app.ts";
import { resetRateLimits } from "../utils/rate-limit.ts";
import { b64uDecode, b64uEncode } from "../utils/webpush.ts";
import {
  apnsJwt,
  buildApnsPayload,
  MAX_APNS_PAYLOAD_BYTES,
  readApns,
  resetApnsState,
} from "../utils/apns.ts";

const TICKET = "A".repeat(40) + "b-_";
const UA_PUBLIC =
  "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
const UA_AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
const FCM = "https://fcm.googleapis.com/fcm/send/abc";
const APPLE = "https://web.push.apple.com/QAbc";
const SUB_ID = "11111111-1111-4111-8111-111111111111";
const SUB_ID_2 = "22222222-2222-4222-8222-222222222222";
const DEV_ID = "44444444-4444-4444-8444-444444444444";
const DEV_ID_2 = "55555555-5555-4555-8555-555555555555";
const TOKEN = "ab".repeat(32);
const TOKEN_2 = "cd".repeat(32);
const KEY_ID = "ABC123DEFG";
const TEAM_ID = "TEAM123456";
const ENV_VARS = [
  "SUPABASE_URL",
  "SUPABASE_ANON_KEY",
  "FLUCK_WEB_PUBLIC_BASE_URL",
  "FLUCK_WEB_PUBLIC_BASE_PATH",
  "FLUCK_WEB_VAPID_PUBLIC_KEY",
  "FLUCK_WEB_VAPID_PRIVATE_KEY",
  "FLUCK_APNS_KEY_P8",
  "FLUCK_APNS_KEY_ID",
  "FLUCK_APNS_TEAM_ID",
];

type Call = { url: string; init?: RequestInit };

const vapidPair = await crypto.subtle.generateKey(
  { name: "ECDSA", namedCurve: "P-256" },
  true,
  ["sign", "verify"],
);
const VAPID_PUBLIC = b64uEncode(
  new Uint8Array(await crypto.subtle.exportKey("raw", vapidPair.publicKey)),
);
const VAPID_PRIVATE =
  (await crypto.subtle.exportKey("jwk", vapidPair.privateKey)).d!;

const apnsPair = await crypto.subtle.generateKey(
  { name: "ECDSA", namedCurve: "P-256" },
  true,
  ["sign", "verify"],
);
const P8 = `-----BEGIN PRIVATE KEY-----\n${
  btoa(
    String.fromCharCode(
      ...new Uint8Array(
        await crypto.subtle.exportKey("pkcs8", apnsPair.privateKey),
      ),
    ),
  ).replace(/(.{64})/g, "$1\n")
}\n-----END PRIVATE KEY-----\n`;

function withEnv(
  fn: () => Promise<void>,
  opts: { apns?: boolean } = {},
): () => Promise<void> {
  return async () => {
    Deno.env.set("SUPABASE_URL", "https://stack.example");
    Deno.env.set("SUPABASE_ANON_KEY", "anon-key");
    Deno.env.set("FLUCK_WEB_PUBLIC_BASE_URL", "https://fluck.risaboss.com");
    Deno.env.set("FLUCK_WEB_PUBLIC_BASE_PATH", "/");
    Deno.env.set("FLUCK_WEB_VAPID_PUBLIC_KEY", VAPID_PUBLIC);
    Deno.env.set("FLUCK_WEB_VAPID_PRIVATE_KEY", VAPID_PRIVATE);
    if (opts.apns !== false) {
      Deno.env.set("FLUCK_APNS_KEY_P8", P8);
      Deno.env.set("FLUCK_APNS_KEY_ID", KEY_ID);
      Deno.env.set("FLUCK_APNS_TEAM_ID", TEAM_ID);
    }
    resetRateLimits();
    resetApnsState();
    try {
      await fn();
    } finally {
      for (const name of ENV_VARS) Deno.env.delete(name);
      resetApnsState();
    }
  };
}

function stubFetch(
  handler: (call: Call) => Response | Promise<Response>,
): { calls: Call[]; restore: () => void } {
  const calls: Call[] = [];
  const original = deps.fetch;
  deps.fetch = (url: string, init?: RequestInit) => {
    const call = { url, init };
    calls.push(call);
    return Promise.resolve(handler(call));
  };
  return { calls, restore: () => (deps.fetch = original) };
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

function notify(body: unknown): Promise<Response> {
  return Promise.resolve(app.request("/fluck-web/api/push/notify", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "cf-connecting-ip": "203.0.113.9",
    },
    body: JSON.stringify(body),
  }));
}

interface Redeem {
  subs?: { id: string; endpoint: string }[];
  devices?: {
    id: string;
    token: string;
    environment?: string;
    topic?: string;
  }[];
}

function redeemed(r: Redeem) {
  return {
    instance_id: "inst-a",
    agent_name: "Laya",
    subscriptions: (r.subs ?? []).map((s) => ({
      id: s.id,
      subscription: {
        endpoint: s.endpoint,
        keys: { p256dh: UA_PUBLIC, auth: UA_AUTH },
      },
      portal_origin: "https://fluck.risaboss.com",
    })),
    devices: (r.devices ?? []).map((d) => ({
      environment: "production",
      topic: "app.fluck.ios",
      ...d,
    })),
  };
}

const isRedeem = (c: Call) => c.url.endsWith("/rpc/fluck_web_push_redeem");
const isPrune = (c: Call) => c.url.endsWith("/rpc/fluck_web_push_prune");
const blob = (bytes: number) =>
  b64uEncode(crypto.getRandomValues(new Uint8Array(bytes)));

// ---- sealed web push ----

Deno.test(
  "notify forwards a sealed web entry byte for byte and re-encrypts nothing",
  withEnv(async () => {
    const sealedBytes = crypto.getRandomValues(new Uint8Array(300));
    const pushed: Call[] = [];
    const stub = stubFetch((c) => {
      if (isRedeem(c)) {
        return json(
          redeemed({
            subs: [{ id: SUB_ID, endpoint: FCM }, {
              id: SUB_ID_2,
              endpoint: APPLE,
            }],
          }),
        );
      }
      pushed.push(c);
      return new Response(null, { status: 201 });
    });
    try {
      const res = await notify({
        ticket: TICKET,
        message_id: "m1",
        sealed: { [SUB_ID]: b64uEncode(sealedBytes) },
      });
      assertEquals(res.status, 202);
      assertEquals(await res.json(), { sent: 2, pruned: 0 });
      const sealedCall = pushed.find((c) => c.url === FCM)!;
      assertEquals(
        new Uint8Array(sealedCall.init!.body as Uint8Array),
        sealedBytes,
      );
      const h = new Headers(sealedCall.init?.headers);
      assertEquals(h.get("content-encoding"), "aes128gcm");
      assertEquals(h.get("ttl"), "86400");
      assertEquals(h.get("urgency"), "normal");
      assert(h.get("authorization")!.startsWith("vapid t="));
      assertEquals(sealedCall.init?.redirect, "manual");
      const generic = pushed.find((c) => c.url === APPLE)!;
      // No entry for that target: the generic payload, which is real encrypted output (header + keys + tag).
      assert((generic.init!.body as Uint8Array).length > 100);
      assertFalse(
        new Uint8Array(generic.init!.body as Uint8Array).every((b, i) =>
          b === sealedBytes[i]
        ),
      );
    } finally {
      stub.restore();
    }
  }),
);

Deno.test(
  "notify ignores sealed entries for ids that are not the ticket owner's targets",
  withEnv(async () => {
    const stub = stubFetch((c) =>
      isRedeem(c)
        ? json(redeemed({ subs: [{ id: SUB_ID, endpoint: FCM }] }))
        : new Response(null, { status: 201 })
    );
    try {
      const res = await notify({
        ticket: TICKET,
        message_id: "m1",
        sealed: { [DEV_ID]: blob(100) },
      });
      assertEquals(await res.json(), { sent: 1, pruned: 0 });
      assertEquals(stub.calls.filter((c) => c.url === FCM).length, 1);
    } finally {
      stub.restore();
    }
  }),
);

// ---- validation ----

Deno.test(
  "notify validates sealed before redeeming, so a bad body never burns the ticket",
  withEnv(async () => {
    const stub = stubFetch(() => new Response(null, { status: 201 }));
    try {
      const ids = Array.from(
        { length: 21 },
        (_, i) => `00000000-0000-4000-8000-${String(i).padStart(12, "0")}`,
      );
      const bad: Record<string, unknown> = {
        "21 entries": Object.fromEntries(ids.map((id) => [id, blob(10)])),
        "key not a uuid": { "not-a-uuid": blob(10) },
        "value over 5500 chars": { [SUB_ID]: "A".repeat(5501) },
        "value not base64url": { [SUB_ID]: "abc+/=" },
        "value empty": { [SUB_ID]: "" },
        "value not a string": { [SUB_ID]: 5 },
        "decoded over 4096 bytes": { [SUB_ID]: blob(4097) },
        "bad length": { [SUB_ID]: "AAAAA" },
        "an array": [blob(10)],
        "a string": blob(10),
        "null": null,
      };
      for (const [name, sealed] of Object.entries(bad)) {
        const res = await notify({ ticket: TICKET, message_id: "m1", sealed });
        assertEquals(res.status, 400, name);
        await res.body?.cancel();
      }
      assertEquals(
        stub.calls.length,
        0,
        "no upstream call for any refused body",
      );
    } finally {
      stub.restore();
    }
  }),
);

Deno.test(
  "notify accepts the limits: 20 entries, 4096 decoded bytes, 5462 chars",
  withEnv(async () => {
    const stub = stubFetch((c) =>
      isRedeem(c) ? json(redeemed({})) : new Response(null, { status: 201 })
    );
    try {
      const ids = Array.from(
        { length: 20 },
        (_, i) => `00000000-0000-4000-8000-${String(i).padStart(12, "0")}`,
      );
      const res = await notify({
        ticket: TICKET,
        message_id: "m1",
        sealed: Object.fromEntries(ids.map((id) => [id, blob(4096)])),
      });
      assertEquals(res.status, 202);
      await res.body?.cancel();
    } finally {
      stub.restore();
    }
  }),
);

// ---- APNs request shape ----

Deno.test(
  "APNs request: host by environment, headers, ES256 provider token, payload",
  withEnv(async () => {
    const sealed = blob(500);
    const pushed: Call[] = [];
    const stub = stubFetch((c) => {
      if (isRedeem(c)) {
        return json(
          redeemed({
            devices: [{ id: DEV_ID, token: TOKEN }, {
              id: DEV_ID_2,
              token: TOKEN_2,
              environment: "sandbox",
            }],
          }),
        );
      }
      pushed.push(c);
      return new Response(null, { status: 200 });
    });
    try {
      const res = await notify({
        ticket: TICKET,
        message_id: "m-1:2.x",
        sealed: { [DEV_ID]: sealed },
      });
      assertEquals(res.status, 202);
      assertEquals(await res.json(), { sent: 2, pruned: 0 });
      const prod = pushed.find((c) =>
        c.url === `https://api.push.apple.com/3/device/${TOKEN}`
      )!;
      const sandbox = pushed.find((c) =>
        c.url === `https://api.sandbox.push.apple.com/3/device/${TOKEN_2}`
      )!;
      assert(prod && sandbox);
      for (const call of [prod, sandbox]) {
        const h = new Headers(call.init?.headers);
        assertEquals(call.init?.method, "POST");
        assertEquals(h.get("apns-topic"), "app.fluck.ios");
        assertEquals(h.get("apns-push-type"), "alert");
        assertEquals(h.get("apns-priority"), "10");
        const exp = Number(h.get("apns-expiration"));
        assert(Math.abs(exp - (Date.now() / 1000 + 86400)) < 60);
        assert(call.init?.signal, "each APNs POST has a timeout");
        assertEquals(call.init?.redirect, "manual");
        const auth = h.get("authorization")!;
        assert(auth.startsWith("bearer "));
        const [hdr, claims, sig] = auth.slice(7).split(".");
        const dec = (s: string) =>
          JSON.parse(new TextDecoder().decode(b64uDecode(s)));
        assertEquals(dec(hdr), { alg: "ES256", kid: KEY_ID });
        const c = dec(claims);
        assertEquals(Object.keys(c).sort(), ["iat", "iss"]);
        assertEquals(c.iss, TEAM_ID);
        assert(Math.abs(c.iat - Date.now() / 1000) < 60);
        assert(
          await crypto.subtle.verify(
            { name: "ECDSA", hash: "SHA-256" },
            apnsPair.publicKey,
            b64uDecode(sig),
            new TextEncoder().encode(`${hdr}.${claims}`),
          ),
          "the token verifies against the .p8's public key",
        );
      }
      assertEquals(JSON.parse(prod.init!.body as string), {
        aps: {
          alert: { title: "Laya", body: "New message" },
          sound: "default",
          "thread-id": "fluck-inst-a",
          "mutable-content": 1,
        },
        message_id: "m-1:2.x",
        url: "https://fluck.risaboss.com/?instance=inst-a",
        sealed,
      });
      assertFalse(
        "sealed" in JSON.parse(sandbox.init!.body as string),
        "no entry for that device, no sealed field",
      );
    } finally {
      stub.restore();
    }
  }),
);

Deno.test("APNs payload carries sealed only while the whole JSON is at most 4096 bytes", () => {
  const base =
    new TextEncoder().encode(buildApnsPayload("Laya", "inst-a", "m1")).length;
  const overhead = new TextEncoder().encode(',"sealed":""').length;
  const fits = "A".repeat(MAX_APNS_PAYLOAD_BYTES - base - overhead);
  const at = buildApnsPayload("Laya", "inst-a", "m1", fits);
  assertEquals(new TextEncoder().encode(at).length, MAX_APNS_PAYLOAD_BYTES);
  assertEquals(JSON.parse(at).sealed, fits);
  const over = buildApnsPayload("Laya", "inst-a", "m1", fits + "A");
  assertFalse("sealed" in JSON.parse(over));
  assertEquals(over, buildApnsPayload("Laya", "inst-a", "m1"));
});

Deno.test(
  "an oversize sealed entry still sends the alert through APNs",
  withEnv(async () => {
    const pushed: Call[] = [];
    const stub = stubFetch((c) => {
      if (isRedeem(c)) {
        return json(redeemed({ devices: [{ id: DEV_ID, token: TOKEN }] }));
      }
      pushed.push(c);
      return new Response(null, { status: 200 });
    });
    try {
      // 4096 decoded bytes is valid for the contract but cannot fit inside a 4096-byte APNs payload.
      const res = await notify({
        ticket: TICKET,
        message_id: "m1",
        sealed: { [DEV_ID]: blob(4096) },
      });
      assertEquals(await res.json(), { sent: 1, pruned: 0 });
      assertFalse("sealed" in JSON.parse(pushed[0].init!.body as string));
    } finally {
      stub.restore();
    }
  }),
);

Deno.test(
  "provider token is cached for 50 minutes per isolate, then refreshed",
  withEnv(async () => {
    const cfg = readApns()!;
    const t0 = 1_800_000_000;
    const first = await apnsJwt(cfg, t0);
    assertEquals(await apnsJwt(cfg, t0 + 49 * 60), first);
    const later = await apnsJwt(cfg, t0 + 51 * 60);
    assert(later !== first);
    assertEquals(
      JSON.parse(new TextDecoder().decode(b64uDecode(later.split(".")[1]))).iat,
      t0 + 51 * 60,
    );
  }),
);

// ---- pruning ----

Deno.test(
  "APNs devices are pruned on 410 and on 400 BadDeviceToken / DeviceTokenNotForTopic, nothing else",
  withEnv(async () => {
    const D = (n: number) =>
      `66666666-6666-4666-8666-${String(n).padStart(12, "0")}`;
    const answers: Record<number, [number, string?]> = {
      1: [410, "Unregistered"],
      2: [400, "BadDeviceToken"],
      3: [400, "DeviceTokenNotForTopic"],
      4: [400, "BadTopic"],
      5: [403, "ExpiredProviderToken"],
      6: [500, "InternalServerError"],
      7: [200],
    };
    const tokenOf = (n: number) => String(n).repeat(64);
    const stub = stubFetch((c) => {
      if (isRedeem(c)) {
        return json(
          redeemed({
            devices: Object.keys(answers).map((n) => ({
              id: D(Number(n)),
              token: tokenOf(Number(n)),
            })),
          }),
        );
      }
      if (isPrune(c)) return json(3);
      const n = Number(c.url.split("/3/device/")[1][0]);
      const [status, reason] = answers[n];
      return reason ? json({ reason }, status) : new Response(null, { status });
    });
    try {
      const res = await notify({ ticket: TICKET, message_id: "m1" });
      assertEquals(await res.json(), { sent: 1, pruned: 3 });
      const prune = stub.calls.find(isPrune)!;
      assertEquals(JSON.parse(prune.init!.body as string), {
        p_ticket: TICKET,
        p_ids: [],
        p_device_ids: [D(1), D(2), D(3)],
      });
      assertEquals(
        new Headers(prune.init?.headers).get("Authorization"),
        "Bearer anon-key",
      );
    } finally {
      stub.restore();
    }
  }),
);

Deno.test(
  "web and device prunes go in one call",
  withEnv(async () => {
    const stub = stubFetch((c) => {
      if (isRedeem(c)) {
        return json(
          redeemed({
            subs: [{ id: SUB_ID, endpoint: FCM }],
            devices: [{ id: DEV_ID, token: TOKEN }],
          }),
        );
      }
      if (isPrune(c)) return json(2);
      return c.url === FCM
        ? new Response(null, { status: 410 })
        : json({ reason: "BadDeviceToken" }, 400);
    });
    try {
      const res = await notify({ ticket: TICKET, message_id: "m1" });
      assertEquals(await res.json(), { sent: 0, pruned: 2 });
      assertEquals(stub.calls.filter(isPrune).length, 1);
      assertEquals(JSON.parse(stub.calls.find(isPrune)!.init!.body as string), {
        p_ticket: TICKET,
        p_ids: [SUB_ID],
        p_device_ids: [DEV_ID],
      });
    } finally {
      stub.restore();
    }
  }),
);

// ---- configuration and logging ----

Deno.test(
  "APNs is skipped silently when any secret is missing, with one console line per isolate",
  withEnv(async () => {
    const warns: string[] = [];
    const warn = console.warn;
    console.warn = (...a: unknown[]) => void warns.push(a.join(" "));
    const stub = stubFetch((c) =>
      isRedeem(c)
        ? json(
          redeemed({
            subs: [{ id: SUB_ID, endpoint: FCM }],
            devices: [{ id: DEV_ID, token: TOKEN }],
          }),
        )
        : new Response(null, { status: 201 })
    );
    try {
      for (
        const missing of [
          "FLUCK_APNS_KEY_P8",
          "FLUCK_APNS_KEY_ID",
          "FLUCK_APNS_TEAM_ID",
        ]
      ) {
        Deno.env.set("FLUCK_APNS_KEY_P8", P8);
        Deno.env.set("FLUCK_APNS_KEY_ID", KEY_ID);
        Deno.env.set("FLUCK_APNS_TEAM_ID", TEAM_ID);
        Deno.env.delete(missing);
        resetRateLimits();
        stub.calls.length = 0;
        const res = await notify({ ticket: TICKET, message_id: "m1" });
        assertEquals(await res.json(), { sent: 1, pruned: 0 }, missing);
        assertFalse(
          stub.calls.some((c) => c.url.includes("push.apple.com/3/device")),
          missing,
        );
      }
      assertEquals(
        warns.filter((w) => w.includes("apns not configured")).length,
        1,
      );
    } finally {
      console.warn = warn;
      stub.restore();
    }
  }, { apns: false }),
);

Deno.test(
  "device tokens never reach the logs, and malformed devices are not contacted",
  withEnv(async () => {
    const logs: string[] = [];
    const [warn, error] = [console.warn, console.error];
    console.warn = console.error = (...a: unknown[]) =>
      void logs.push(a.map(String).join(" "));
    const stub = stubFetch((c) => {
      if (isRedeem(c)) {
        return json(redeemed({
          devices: [
            { id: DEV_ID, token: TOKEN },
            { id: DEV_ID_2, token: TOKEN_2, topic: "com.other.app" },
            { id: "not-a-uuid", token: TOKEN_2 },
            { id: DEV_ID_2, token: "../../x" },
          ],
        }));
      }
      throw new Error(`socket closed for ${c.url}`);
    });
    try {
      const res = await notify({ ticket: TICKET, message_id: "m1" });
      assertEquals(await res.json(), { sent: 0, pruned: 0 });
      assertEquals(
        stub.calls.filter((c) => c.url.includes("push.apple.com")).length,
        1,
      );
      assert(logs.length > 0);
      assertFalse(logs.some((l) => l.includes(TOKEN) || l.includes(TOKEN_2)));
    } finally {
      [console.warn, console.error] = [warn, error];
      stub.restore();
    }
  }),
);
