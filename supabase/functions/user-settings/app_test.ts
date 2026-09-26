import { assertEquals, assertMatch } from "@std/assert";
import { createApp, type Rpc } from "./app.ts";
import { signSession, verifySession } from "./session.ts";
const origin = "https://api.example.com";
const base = origin + "/functions/v1/user-settings";
const secret = "test-secret-".repeat(4);
const sub = "11111111-1111-4111-8111-111111111111";
const csrf = "22222222-2222-4222-8222-222222222222";
const config = { publicUrl: base, sessionSecret: secret };
const prefs = { unfocused_mode: "batch", unfocused_fps: 4, revision: 0 };
async function cookie() {
  return "__Secure-boss_user_settings=" +
    await signSession(
      { sub, csrf, exp: Math.floor(Date.now() / 1000) + 1800 },
      secret,
    );
}
Deno.test("handoff redirects away from bearer token and sets scoped secure cookie", async () => {
  let calls = 0;
  const app = createApp(config, (name, args) => {
    assertEquals(name, "consume_user_settings_handoff");
    assertEquals(args, { p_token: "a".repeat(43) });
    calls++;
    return Promise.resolve({ user_id: sub });
  });
  const r = await app(new Request(base + "?t=" + "a".repeat(43)));
  assertEquals(r.status, 302);
  assertEquals(calls, 1);
  assertEquals(r.headers.get("location"), "/functions/v1/user-settings");
  assertMatch(
    r.headers.get("set-cookie")!,
    /Path=\/functions\/v1\/user-settings; HttpOnly; SameSite=Lax; Max-Age=1800; Secure/,
  );
  assertEquals(r.headers.get("referrer-policy"), "no-referrer");
});
Deno.test("unauthenticated and cross-site requests do not invoke privileged RPC", async () => {
  const app = createApp(config, () => {
    throw new Error("Unexpected privileged RPC");
  });
  assertEquals((await app(new Request(base))).status, 401);
  assertEquals(
    (await app(
      new Request(base + "?t=" + "a".repeat(43), {
        headers: { "sec-fetch-site": "cross-site" },
      }),
    )).status,
    403,
  );
});
Deno.test("GET renders account mode selector", async () => {
  const app = createApp(config, (name, args) => {
    assertEquals(name, "get_user_terminal_preferences");
    assertEquals(args, { p_actor_id: sub });
    return Promise.resolve(prefs);
  });
  const r = await app(
    new Request(base, { headers: { cookie: await cookie() } }),
  );
  assertEquals(r.status, 200);
  assertMatch(await r.text(), /Screen preview/);
});
Deno.test("POST verifies origin and CSRF, rejects invalid rate and saves as cookie owner", async () => {
  const calls: unknown[] = [];
  const rpc: Rpc = (name, args) => {
    calls.push({ name, args });
    return Promise.resolve(prefs);
  };
  const app = createApp(config, rpc);
  const session = await cookie();
  const post = (body: string, requestOrigin = origin) =>
    app(
      new Request(base, {
        method: "POST",
        headers: {
          cookie: session,
          origin: requestOrigin,
          "content-type": "application/x-www-form-urlencoded",
        },
        body,
      }),
    );
  const body =
    `csrf=${csrf}&action=save&mode=preview&fps=10&revision=0&p_actor_id=attacker`;
  assertEquals((await post(body, "https://attacker.example")).status, 403);
  assertEquals((await post(body.replace(csrf, "wrong"))).status, 403);
  assertEquals((await post(body.replace("fps=10", "fps=31"))).status, 400);
  assertEquals(calls.length, 0);
  assertEquals((await post(body)).status, 303);
  assertEquals(calls, [{
    name: "set_user_terminal_preferences",
    args: {
      p_actor_id: sub,
      p_unfocused_mode: "preview",
      p_unfocused_fps: 10,
      p_revision: 0,
    },
  }]);
});
Deno.test("stale writes return conflict; reset uses server defaults", async () => {
  const app = createApp(config, (_name, args) => {
    assertEquals(args.p_unfocused_mode, "batch");
    assertEquals(args.p_unfocused_fps, 4);
    return Promise.reject(new Error("conflict"));
  });
  const r = await app(
    new Request(base, {
      method: "POST",
      headers: {
        cookie: await cookie(),
        origin,
        "content-type": "application/x-www-form-urlencoded",
      },
      body: `csrf=${csrf}&action=reset&revision=1`,
    }),
  );
  assertEquals(r.status, 409);
});
Deno.test("session rejects tampering and expiration", async () => {
  const session = { sub, csrf, exp: Math.floor(Date.now() / 1000) + 60 };
  const token = await signSession(session, secret);
  assertEquals(await verifySession(token, secret), session);
  assertEquals(await verifySession(token, secret + "wrong"), null);
  assertEquals(await verifySession(token, secret, Date.now() + 61000), null);
});
