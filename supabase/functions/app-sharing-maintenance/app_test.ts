// Async fixtures implement Promise dependencies.
// deno-lint-ignore-file require-await
import { createMaintenance, type Dependencies } from "./app.ts";
import { validCloseResponse } from "../_shared/app-sharing/close-response.ts";
const assert = (value: unknown) => {
  if (!value) throw Error("assertion failed");
};
const secret = "fixture-maintenance-key-32-characters", timestamp = 1800000000;
const nonce = "11111111-1111-4111-8111-111111111111";
async function request(
  body = JSON.stringify({ nonce, timestamp }),
  keyValue = secret,
) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(keyValue),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = Array.from(
    new Uint8Array(
      await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(body)),
    ),
    (v) => v.toString(16).padStart(2, "0"),
  ).join("");
  return new Request("https://example.test/maintenance", {
    method: "POST",
    body,
    headers: { "X-App-Sharing-Maintenance": signature },
  });
}
Deno.test("scheduler authenticates exact body, bounds timestamp, and redacts failures", async () => {
  let claims = 0;
  const deps: Dependencies = {
    claim: async () => {
      claims++;
      return { jobs: [] };
    },
    ack: async () => true,
    close: async () => {},
  };
  const app = createMaintenance(secret, deps, () => timestamp * 1000);
  assert((await app(await request())).status === 200);
  assert((await app(await request(undefined, "forged-key"))).status === 401);
  assert(
    (await app(
      await request(JSON.stringify({ nonce, timestamp: timestamp - 61 })),
    )).status === 400,
  );
  assert((await app(await request("x".repeat(257)))).status === 400);
  assert(claims === 1);
  const failed = createMaintenance(secret, {
    ...deps,
    claim: () => Promise.reject(Error("SECRET")),
  }, () => timestamp * 1000);
  const response = await failed(await request());
  assert(response.status === 503);
  assert(!(await response.text()).includes("SECRET"));
});
Deno.test("scheduler acknowledges only completed closures, retaining partial failures", async () => {
  const acked: number[] = [];
  const app = createMaintenance(secret, {
    claim: async () => ({
      jobs: [1, 2].map((id) => ({
        id,
        claim: nonce,
        descriptor: { sfu_id: `session${id}`, mid: "0", channel_id: 1 },
      })),
    }),
    ack: async (id) => {
      acked.push(id);
      return true;
    },
    close: async (session, path) => {
      if (session === "session2" && path === "datachannels/close") {
        throw Error("provider");
      }
    },
  }, () => timestamp * 1000);
  const response = await app(await request());
  const value = await response.json();
  assert(value.closed === 1 && value.failed === 1);
  assert(acked.join(",") === "1");
});
Deno.test("close confirmation allows matched absence only and rejects missing or mismatched results", () => {
  const body = { tracks: [{ mid: "0" }] };
  assert(
    validCloseResponse("tracks/close", body, {
      tracks: [{ mid: "0", errorCode: "close_track_error" }],
    }),
  );
  assert(
    !validCloseResponse("tracks/close", body, {
      tracks: [{ mid: "1", errorCode: "close_track_error" }],
    }),
  );
  assert(!validCloseResponse("tracks/close", body, { tracks: [] }));
  assert(
    !validCloseResponse("tracks/close", body, {
      tracks: [{ mid: "0", errorCode: "internal_error" }],
    }),
  );
  assert(
    !validCloseResponse("datachannels/close", {
      dataChannels: [{ id: 1 }, { id: 2 }],
    }, { dataChannels: [{ id: 1 }, { id: 1 }] }),
  );
});
