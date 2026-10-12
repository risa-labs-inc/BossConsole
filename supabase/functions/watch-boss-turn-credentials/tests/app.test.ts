import { assertEquals } from "@std/assert";
import { createHandler, TURN_TTL_SECONDS } from "../app.ts";

const ID = "7b7f6638-b35f-4df7-8eaf-45c30df936dc";
const TICKET = "a".repeat(43);
const request = (body: unknown) =>
  new Request(
    "https://api.risaboss.com/functions/v1/watch-boss-turn-credentials",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    },
  );
const provider = () =>
  new Response(
    JSON.stringify({
      iceServers: [
        { urls: ["stun:stun.cloudflare.com:3478"] },
        {
          urls: [
            "turn:turn.cloudflare.com:3478?transport=udp",
            "turns:turn.cloudflare.com:443?transport=tcp",
          ],
          username: "u",
          credential: "c",
        },
      ],
    }),
    { status: 201 },
  );

Deno.test("consumes the bound ticket before minting and returns ISO expiry", async () => {
  const calls: unknown[] = [];
  const response = await createHandler({
    now: () => new Date("2026-10-12T12:00:00.000Z"),
    consume: (ticket, instance, attempt) => {
      calls.push([ticket, instance, attempt]);
      return Promise.resolve(true);
    },
    mint: (ttl) => {
      calls.push(ttl);
      return Promise.resolve(provider());
    },
  })(request({ ticket: TICKET, instance_id: "inst-a", attempt_id: ID }));
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(calls, [[TICKET, "inst-a", ID], TURN_TTL_SECONDS]);
  assertEquals(body.expires_at, "2026-10-13T12:00:00.000Z");
  assertEquals(body.ice_servers.length, 2);
});

Deno.test("refuses an invalid or unbound request before provider dispatch", async () => {
  let minted = false;
  const handler = createHandler({
    now: () => new Date(),
    consume: () => Promise.resolve(false),
    mint: () => {
      minted = true;
      return Promise.resolve(provider());
    },
  });
  assertEquals(
    (await handler(
      request({ ticket: TICKET, instance_id: "inst-a", attempt_id: ID }),
    )).status,
    401,
  );
  assertEquals(
    (await handler(
      request({ ticket: TICKET, instance_id: "bad id", attempt_id: ID }),
    )).status,
    400,
  );
  assertEquals(minted, false);
});

Deno.test("sanitizes provider failures and never returns credential material", async () => {
  const secret = "provider-secret-must-not-escape";
  const response = await createHandler({
    now: () => new Date(),
    consume: () => Promise.resolve(true),
    mint: () => Promise.resolve(new Response(secret, { status: 500 })),
  })(request({ ticket: TICKET, instance_id: "inst-a", attempt_id: ID }));
  assertEquals(response.status, 502);
  assertEquals(await response.text(), '{"error":"upstream_unavailable"}');
});

Deno.test("rejects unexpected ICE hosts and malformed TURN credentials", async () => {
  for (
    const iceServers of [
      [{ urls: ["stun:stun.cloudflare.com:3478"] }],
      [{
        urls: ["turn:evil.example:3478?transport=udp"],
        username: "u",
        credential: "c",
      }],
      [{
        urls: ["turn:turn.cloudflare.com:3478?transport=udp"],
        username: "u",
      }],
    ]
  ) {
    const response = await createHandler({
      now: () => new Date(),
      consume: () => Promise.resolve(true),
      mint: () =>
        Promise.resolve(
          new Response(JSON.stringify({ iceServers }), { status: 201 }),
        ),
    })(request({ ticket: TICKET, instance_id: "inst-a", attempt_id: ID }));
    assertEquals(response.status, 502);
  }
});

Deno.test("cancels chunked request and provider bodies at their byte caps", async () => {
  let requestCancelled = false;
  const oversizedRequest = new Request(
    "https://api.risaboss.com/functions/v1/watch-boss-turn-credentials",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: new ReadableStream({
        pull(controller) {
          controller.enqueue(new Uint8Array(1024));
        },
        cancel() {
          requestCancelled = true;
        },
      }),
      duplex: "half",
    } as RequestInit,
  );
  const handler = createHandler({
    now: () => new Date(),
    consume: () => Promise.resolve(true),
    mint: () => Promise.resolve(provider()),
  });
  assertEquals((await handler(oversizedRequest)).status, 413);
  assertEquals(requestCancelled, true);

  let providerCancelled = false;
  const oversizedProvider = new Response(
    new ReadableStream({
      pull(controller) {
        controller.enqueue(new Uint8Array(8192));
      },
      cancel() {
        providerCancelled = true;
      },
    }),
    { status: 201 },
  );
  const response = await createHandler({
    now: () => new Date(),
    consume: () => Promise.resolve(true),
    mint: () => Promise.resolve(oversizedProvider),
  })(request({ ticket: TICKET, instance_id: "inst-a", attempt_id: ID }));
  assertEquals(response.status, 502);
  assertEquals(providerCancelled, true);
});
