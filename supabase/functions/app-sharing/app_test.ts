// Async dependency fixtures implement production Promise interfaces.
// deno-lint-ignore-file require-await
import { assert, assertEquals } from "@std/assert";
import {
  ApiError,
  createApp,
  type Dependencies,
  productionDependencies,
} from "./app.ts";
const owner = "11111111-1111-4111-8111-111111111111";
const session = "22222222-2222-4222-8222-222222222222";
const generation = "33333333-3333-4333-8333-333333333333";
Deno.test("upstream auth and database outages remain retryable instead of becoming denials", async () => {
  const original = globalThis.fetch;
  try {
    for (const path of ["/auth/v1/user", "/rest/v1/rpc/app_sharing_command"]) {
      for (const status of [401, 403, 408, 429, 500, 503]) {
        globalThis.fetch = async (input) =>
          String(input).endsWith(path)
            ? Response.json({}, { status })
            : Response.json({ id: owner });
        const response = await createApp(productionDependencies())(
          new Request("https://api.example", {
            method: "POST",
            headers: {
              Authorization: "Bearer fixture",
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              action: "controlPoll",
              session_id: session,
              generation,
            }),
          }),
        );
        assertEquals(
          response.status,
          status === 429
            ? 429
            : status >= 500 || status === 408
            ? 503
            : path.includes("auth")
            ? 401
            : 403,
        );
        if (status >= 500 || status === 408 || status === 429) {
          assertEquals(await response.json(), {
            error: "upstream_unavailable",
          });
        }
      }
    }
  } finally {
    globalThis.fetch = original;
  }
});
function harness() {
  const calls: { kind: string; op: string; body: Record<string, unknown> }[] =
    [];
  let publisher = false, leaseAllowed = false, revoked = false;
  let media: Record<string, unknown> | null = {
    sfu_id: "bound-viewer",
    mid: null,
    track_name: null,
    channel_id: null,
    transport_channel_id: null,
  };
  const deps: Dependencies = {
    authenticate: async (jwt) => jwt === "valid" ? owner : null,
    authorizeGrant: async (grant) => grant === "g".repeat(43) ? owner : null,
    bootstrapGrant: async (grant, body) => {
      assertEquals(grant, "g".repeat(43));
      calls.push({ kind: "bootstrap", op: "bootstrap", body });
      return { host_peer_id: owner, viewer_peer_id: session };
    },
    preferences: async (_actor, args) => ({
      auto_admit: true,
      auto_control: true,
      revision: args ? 1 : 0,
    }),
    rpc: async (op, body, actor) => {
      assertEquals(actor, owner);
      calls.push({ kind: "rpc", op, body });
      if (op === "register") return { ...body };
      if (op === "list") {
        return {
          sessions: [
            { viewer_url: "https://bridge.invalid/#k=" + "A".repeat(43) },
            { viewer_url: "https://native.example/#k=" + "B".repeat(43) },
          ],
        };
      }
      if (body.window_id === "unshared") {
        throw new ApiError(403, "unauthorized");
      }
      if (op === "_mediaGet") {
        return {
          publisher,
          media,
          publication: { sfu_id: "bound-host", track_name: "window-selected" },
          control_publication: { sfu_id: "bound-host", channel_id: 3 },
        };
      }
      if (op === "_controlAuthorize" && !leaseAllowed) {
        throw new ApiError(403, "unauthorized");
      }
      if (op === "_mediaTrack" && revoked) {
        throw new ApiError(403, "unauthorized");
      }
      if (op === "_cleanupList") return [];
      return {};
    },
    sfu: async (op, _method, body = {}) => {
      calls.push({ kind: "sfu", op, body });
      if (op === "/sessions/new") return { sessionId: "new-bound-session" };
      if (op.endsWith("/tracks/new")) {
        return {
          sessionDescription: {
            type: publisher ? "answer" : "offer",
            sdp: "v=0",
          },
          tracks: [{ mid: "0" }],
        };
      }
      if (op.endsWith("/datachannels/new")) {
        return { dataChannels: [{ id: 4 }] };
      }
      return {};
    },
  };
  const app = createApp(deps);
  const request = (
    action: string,
    extra: Record<string, unknown> = {},
    token = "valid",
  ) =>
    app(
      new Request("https://api.example/functions/v1/app-sharing", {
        method: "POST",
        headers: {
          Authorization: "Bearer " + token,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          action,
          session_id: session,
          generation,
          peer_id: owner,
          window_id: "selected",
          ...extra,
        }),
      }),
    );
  return {
    request,
    calls,
    deps,
    setPublisher: (value: boolean) => publisher = value,
    setLease: (value: boolean) => leaseAllowed = value,
    setRevoked: (value: boolean) => revoked = value,
    clearMedia: () => media = null,
  };
}
Deno.test("Fluck grant reuses fixed actions without exposing native bearer auth", async () => {
  const h = harness();
  const request = (
    action: string,
    extra: Record<string, unknown> = {},
    both = false,
  ) =>
    h.deps &&
    createApp(h.deps)(
      new Request("https://api.example/functions/v1/app-sharing", {
        method: "POST",
        headers: {
          "X-Fluck-App-Sharing-Grant": "g".repeat(43),
          ...(both ? { Authorization: "Bearer valid" } : {}),
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          action,
          session_id: session,
          generation,
          peer_id: owner,
          window_id: "selected",
          ...extra,
        }),
      }),
    );
  assertEquals((await request("mediaCreate")).status, 409);
  assertEquals((await request("list")).status, 401);
  assertEquals((await request("mediaCreate", {}, true)).status, 401);
  assertEquals(
    (await request("mediaCreate", { session_id: "wrong" })).status,
    400,
  );
  const registered = await request("register", {
    instance_id: "44444444-4444-4444-8444-444444444444",
    device_id: "55555555-5555-4555-8555-555555555555",
    name: "Fluck",
    windows: [{ id: "boss", title: "BOSS" }],
    key_epoch: "66666666-6666-4666-8666-666666666666",
    host_public_key: "A".repeat(43),
  });
  assertEquals(registered.status, 200);
  assertEquals((await registered.json()).viewer_url, null);
  assertEquals(
    h.calls.at(-1)?.body.viewer_url,
    "https://bridge.invalid/#k=" + "A".repeat(43),
  );
  assertEquals(
    (await request("register", {
      viewer_url: "https://native.example/#k=" + "B".repeat(43),
    })).status,
    400,
  );
});
Deno.test("Fluck bootstrap is grant-only and uses one bounded backend call", async () => {
  const h = harness();
  const descriptor = {
    action: "bootstrap",
    session_id: session,
    generation,
    instance_id: "44444444-4444-4444-8444-444444444444",
    device_id: "55555555-5555-4555-8555-555555555555",
    name: "Fluck",
    windows: [{ id: "boss", title: "BOSS" }],
    key_epoch: "66666666-6666-4666-8666-666666666666",
    host_public_key: "A".repeat(43),
    role: "control",
  };
  const grantResponse = await createApp(h.deps)(
    new Request("https://api.example/functions/v1/app-sharing", {
      method: "POST",
      headers: {
        "X-Fluck-App-Sharing-Grant": "g".repeat(43),
        "Content-Type": "application/json",
      },
      body: JSON.stringify(descriptor),
    }),
  );
  assertEquals(grantResponse.status, 200);
  assertEquals(await grantResponse.json(), {
    host_peer_id: owner,
    viewer_peer_id: session,
  });
  assertEquals(h.calls, [{
    kind: "bootstrap",
    op: "bootstrap",
    body: Object.fromEntries(
      Object.entries(descriptor).filter(([key]) => key !== "action"),
    ),
  }]);

  assertEquals(
    (await createApp(h.deps)(
      new Request("https://api.example/functions/v1/app-sharing", {
        method: "POST",
        headers: {
          Authorization: "Bearer valid",
          "Content-Type": "application/json",
        },
        body: JSON.stringify(descriptor),
      }),
    )).status,
    401,
  );
  assertEquals(h.calls.length, 1);

  for (
    const forbidden of [
      "viewer_url",
      "media_root_key",
      "private_key",
      "ticket",
      "peer_id",
      "sfu_id",
    ]
  ) {
    const response = await createApp(h.deps)(
      new Request("https://api.example/functions/v1/app-sharing", {
        method: "POST",
        headers: {
          "X-Fluck-App-Sharing-Grant": "g".repeat(43),
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ ...descriptor, [forbidden]: "secret" }),
      }),
    );
    assertEquals(response.status, 400, forbidden);
    assertEquals(await response.json(), { error: "invalid_request" });
  }
  for (
    const invalid of [
      { ...descriptor, session_id: "not-a-uuid" },
      { ...descriptor, role: "owner" },
      { ...descriptor, windows: [] },
      {
        ...descriptor,
        windows: [{ id: "boss" }, { id: "boss" }],
      },
      {
        ...descriptor,
        windows: [{ id: "boss", private_key: "secret" }],
      },
    ]
  ) {
    const response = await createApp(h.deps)(
      new Request("https://api.example/functions/v1/app-sharing", {
        method: "POST",
        headers: {
          "X-Fluck-App-Sharing-Grant": "g".repeat(43),
          "Content-Type": "application/json",
        },
        body: JSON.stringify(invalid),
      }),
    );
    assertEquals(response.status, 400);
  }
  assertEquals(h.calls.length, 1);
});
Deno.test("native discovery omits keyless bridge sessions", async () => {
  const h = harness();
  const response = await h.request("list");
  assertEquals(response.status, 200);
  assertEquals((await response.json()).sessions, [
    { viewer_url: "https://native.example/#k=" + "B".repeat(43) },
  ]);
});
Deno.test("authentication and fixed action allowlist reject arbitrary SFU proxying", async () => {
  const h = harness();
  assertEquals((await h.request("list", {}, "forged")).status, 401);
  assertEquals(h.calls.length, 0);
  for (
    const action of [
      "_mediaBind",
      "/sessions/victim/tracks/new",
      "http://attacker.invalid",
      "controlSend",
    ]
  ) assertEquals((await h.request(action)).status, 400);
  assertEquals(h.calls.length, 0);
});
Deno.test("window authorization and publisher role enforced before provider mutation", async () => {
  const h = harness();
  assertEquals(
    (await h.request("mediaPublish", {
      mid: "0",
      session_description: { type: "offer", sdp: "v=0" },
    })).status,
    403,
  );
  assertEquals(
    (await h.request("mediaSubscribe", { window_id: "unshared" })).status,
    403,
  );
  assertEquals(h.calls.filter((x) => x.kind === "sfu").length, 0);
});
Deno.test("subscribe ignores hostile locator and binds only stored publication", async () => {
  const h = harness();
  assertEquals(
    (await h.request("mediaSubscribe", {
      sfu_id: "victim",
      sessionId: "victim",
      trackName: "private",
      url: "https://attacker.invalid",
    })).status,
    200,
  );
  const request = h.calls.find((x) => x.kind === "sfu")!;
  assertEquals(request.op, "/sessions/bound-viewer/tracks/new");
  assertEquals(request.body, {
    tracks: [{
      location: "remote",
      sessionId: "bound-host",
      trackName: "window-selected",
    }],
  });
  assert(h.calls.some((x) => x.op === "_mediaUnlock"));
});
Deno.test("view subscriber cannot enable replies without current controller lease", async () => {
  const h = harness();
  assertEquals(
    (await h.request("dataSubscribe", { can_reply: true, lease_id: owner }))
      .status,
    403,
  );
  assertEquals(h.calls.filter((x) => x.kind === "sfu").length, 0);
  assertEquals(
    (await h.request("dataSubscribe", { can_reply: false })).status,
    200,
  );
  const request = h.calls.find((x) => x.kind === "sfu")!;
  assertEquals(
    (request.body.dataChannels as Record<string, unknown>[])[0].canReply,
    false,
  );
});
Deno.test("publication race after revoke closes created track and fails closed", async () => {
  const h = harness();
  h.setPublisher(true);
  h.setRevoked(true);
  assertEquals(
    (await h.request("mediaPublish", {
      mid: "0",
      session_description: { type: "offer", sdp: "v=0" },
    })).status,
    403,
  );
  assert(
    h.calls.some((x) => x.kind === "sfu" && x.op.endsWith("/tracks/close")),
  );
  assert(h.calls.some((x) => x.op === "_mediaUnlock"));
});
Deno.test("configured provider failure is redacted and never exposes credentials", async () => {
  const h = harness();
  h.deps.sfu = async () => {
    throw Error("private-provider-credential");
  };
  const response = await h.request("mediaSubscribe");
  assertEquals(response.status, 503);
  assertEquals(await response.json(), { error: "upstream_unavailable" });
});
Deno.test("streaming request body is bounded before JSON parsing", async () => {
  const h = harness();
  const request = new Request("https://api.example/", {
    method: "POST",
    headers: {
      Authorization: "Bearer valid",
      "Content-Type": "application/json",
    },
    body: "x".repeat(131073),
  });
  assertEquals((await createApp(h.deps)(request)).status, 413);
  assertEquals(h.calls.length, 0);
});
Deno.test("preferences validate booleans and independent revision", async () => {
  const h = harness();
  assertEquals(
    (await h.request("preferencesSet", {
      auto_admit: "true",
      auto_control: true,
      revision: 0,
    })).status,
    400,
  );
  const response = await h.request("preferencesSet", {
    auto_admit: true,
    auto_control: true,
    revision: 0,
  });
  assertEquals(response.status, 200);
  assertEquals((await response.json()).revision, 1);
});

Deno.test("committed generation retirement returns a failure before SFU mutation", async () => {
  const h = harness();
  h.deps.rpc = async () => ({ generation_revoked: true, must_stop: true });
  for (const action of ["heartbeat", "controlPoll", "mediaPublish"]) {
    const response = await h.request(action);
    assertEquals(response.status, 409);
    assertEquals((await response.json()).error, "session_retired");
  }
  assertEquals(h.calls.length, 0);
});
Deno.test("stored track names support every descriptor character and prefix length", async () => {
  const h = harness(), original = h.deps.rpc;
  h.deps.rpc = async (op, body, actor) => {
    const value = await original(op, body, actor);
    if (op === "_mediaGet") {
      return {
        ...value as Record<string, unknown>,
        publication: {
          sfu_id: "bound-host",
          track_name: "window-" + "a".repeat(126) + ".:",
        },
      };
    }
    return value;
  };
  assertEquals((await h.request("mediaSubscribe")).status, 200);
});
Deno.test("host demand uses authenticated database action and never allocates provider media", async () => {
  const h = harness();
  h.deps.rpc = async (op, _body, actor) => {
    assertEquals(op, "mediaDemand");
    assertEquals(actor, owner);
    return { windows: [{ window_id: "selected", viewers: 0 }] };
  };
  const response = await h.request("mediaDemand");
  assertEquals(response.status, 200);
  assertEquals(await response.json(), {
    windows: [{ window_id: "selected", viewers: 0 }],
  });
  assertEquals(h.calls.length, 0);
});

Deno.test("only authorized viewers receive retryable missing publication state", async () => {
  const h = harness(), original = h.deps.rpc;
  h.deps.rpc = async (op, body, actor) => {
    const value = await original(op, body, actor);
    return op === "_mediaGet"
      ? {
        ...value as Record<string, unknown>,
        publication: null,
        control_publication: null,
      }
      : value;
  };
  for (const action of ["mediaSubscribe", "dataSubscribe"]) {
    const response = await h.request(action);
    assertEquals(response.status, 409);
    assertEquals((await response.json()).error, "publication_pending");
  }
  assertEquals(
    (await h.request("mediaSubscribe", { window_id: "unshared" })).status,
    403,
  );
  h.setPublisher(true);
  assertEquals((await h.request("mediaSubscribe")).status, 403);
  assertEquals(h.calls.filter((c) => c.kind === "sfu").length, 0);
});
