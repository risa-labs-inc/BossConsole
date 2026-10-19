import { assertEquals } from "@std/assert";
import { createApp } from "../app.ts";

const secret = "test-admission-key-".repeat(3);
const room = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const token = "t".repeat(43);
const encoder = new TextEncoder();
async function signed(body: string, signingKey = secret) {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(signingKey),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = new Uint8Array(
    await crypto.subtle.sign("HMAC", key, encoder.encode(body)),
  );
  return new Request("https://example.test/relay-admission", {
    method: "POST",
    body,
    headers: {
      "X-Relay-Signature": Array.from(
        signature,
        (v) => v.toString(16).padStart(2, "0"),
      ).join(""),
    },
  });
}
const payload = () => JSON.stringify({ p_room_id: room, p_token: token });

Deno.test("only correctly signed requests reach the fixed ticket consumer", async () => {
  let calls = 0;
  const app = createApp({
    secret,
    consume: (r, t) => {
      calls++;
      assertEquals([r, t], [room, token]);
      return Promise.resolve({
        role: "host",
        user_id: "private",
        token: "private",
      });
    },
  });
  assertEquals((await app(new Request("https://example.test"))).status, 405);
  assertEquals(
    (await app(
      new Request("https://example.test", { method: "POST", body: payload() }),
    )).status,
    401,
  );
  assertEquals((await app(await signed(payload(), "wrong-key"))).status, 401);
  assertEquals(calls, 0);
  const result = await app(await signed(payload()));
  assertEquals(result.status, 200);
  assertEquals(await result.json(), { role: "host" });
  assertEquals(result.headers.get("cache-control"), "no-store");
  assertEquals(calls, 1);
});

Deno.test("the signature binds both room and ticket", async () => {
  let calls = 0;
  const app = createApp({
    secret,
    consume: () => {
      calls++;
      return Promise.resolve({ role: "host" });
    },
  });
  const original = await signed(payload());
  const modified = new Request(original.url, {
    method: "POST",
    headers: original.headers,
    body: JSON.stringify({ p_room_id: room, p_token: "z".repeat(43) }),
  });
  assertEquals((await app(modified)).status, 401);
  assertEquals(calls, 0);
});

Deno.test("signed malformed and oversized bodies cannot dispatch a database operation", async () => {
  let calls = 0;
  const app = createApp({
    secret,
    consume: () => {
      calls++;
      return Promise.resolve({ role: "host" });
    },
  });
  for (
    const body of [
      "null",
      "[]",
      "{",
      JSON.stringify({ p_room_id: "bad", p_token: token }),
      JSON.stringify({ p_room_id: room, p_token: "bad" }),
      JSON.stringify({
        p_room_id: room,
        p_token: token,
        function: "arbitrary_rpc",
      }),
      " ".repeat(257),
    ]
  ) assertEquals((await app(await signed(body))).status, 400);
  assertEquals(calls, 0);
});

Deno.test("streaming requests cannot evade the byte limit", async () => {
  const source = await signed(payload());
  const app = createApp({
    secret,
    consume: () => {
      throw new Error("must not run");
    },
  });
  let cancelled = false;
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      controller.enqueue(new Uint8Array(200));
    },
    cancel() {
      cancelled = true;
    },
  });
  const result = await app(
    new Request(source.url, { method: "POST", headers: source.headers, body }),
  );
  assertEquals(result.status, 400);
  assertEquals(cancelled, true);
});

Deno.test("one-use admission and database errors stay fail closed and redacted", async () => {
  let used = false;
  const app = createApp({
    secret,
    consume: () => {
      if (used) throw new Error("private database payload or secret");
      used = true;
      return Promise.resolve({ role: "account" });
    },
  });
  assertEquals(await (await app(await signed(payload()))).json(), {
    role: "account",
  });
  const replay = await app(await signed(payload()));
  assertEquals(replay.status, 403);
  assertEquals(await replay.json(), { error: "Admission refused" });
  for (const result of [null, {}, { role: "service_role" }]) {
    const invalid = createApp({
      secret,
      consume: () => Promise.resolve(result),
    });
    assertEquals((await invalid(await signed(payload()))).status, 403);
  }
});

Deno.test("missing or weak gateway configuration cannot admit peers", async () => {
  for (const key of ["", "short"]) {
    const app = createApp({
      secret: key,
      consume: () => {
        throw new Error("must not run");
      },
    });
    assertEquals((await app(await signed(payload()))).status, 503);
  }
});

Deno.test("an unauthenticated stalled body is cancelled within the read deadline", async () => {
  const headers = (await signed(payload())).headers;
  let cancelled = false;
  const app = createApp({
    secret,
    consume: () => {
      throw new Error("must not consume");
    },
  });
  const body = new ReadableStream<Uint8Array>({
    cancel() {
      cancelled = true;
    },
  });
  const response = await app(
    new Request("https://example.test/relay-admission", {
      method: "POST",
      headers,
      body,
    }),
  );
  assertEquals(response.status, 400);
  assertEquals(cancelled, true);
});

Deno.test("signed raw invalid UTF-8 cannot reach the database", async () => {
  const body = new Uint8Array([0xc3, 0x28]);
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = new Uint8Array(await crypto.subtle.sign("HMAC", key, body));
  const app = createApp({
    secret,
    consume: () => {
      throw new Error("must not consume");
    },
  });
  const request = new Request("https://example.test/relay-admission", {
    method: "POST",
    body,
    headers: {
      "X-Relay-Signature": Array.from(
        signature,
        (v) => v.toString(16).padStart(2, "0"),
      ).join(""),
    },
  });
  assertEquals((await app(request)).status, 400);
});
