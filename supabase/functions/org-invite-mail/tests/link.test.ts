import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import { createApp, type Db, type Mailer, platformOrigins, Refused, restDb } from "../app.ts";
import type { LinkInviteFacts } from "../mail.ts";
import type { MailMessage } from "../smtp.ts";

const userId = "99999999-8888-4777-8666-555555555555";
const linkId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCde";
const facts: LinkInviteFacts = {
  link_id: linkId,
  token: TOKEN,
  target: "acme",
  target_name: "Acme <Assistant>",
  agent_slug: "acme",
  custom_host: null,
  org_name: "Acme Corp",
  inviter_name: "Ada Admin",
  inviter_email: "ada@acme.test",
  recipient_email: "bob@other.test",
  expires_at: "2026-11-23T10:00:00Z",
};

function harness(
  prepareLink: Db["prepareLink"] = () => Promise.resolve(facts),
  opts: { mailFails?: boolean; baseDomain?: string | null; noMailer?: boolean } = {},
) {
  const sent: MailMessage[] = [];
  const calls: unknown[][] = [];
  const failed: [string, boolean, string][] = [];
  const sentIds: [string, string][] = [];
  const db: Db = {
    user: (jwt) => Promise.resolve(jwt === "good-jwt" ? userId : null),
    prepare: () => Promise.reject(new Error("not an org invite")),
    prepareAgent: () => Promise.reject(new Error("not an agent invite")),
    prepareLink: (...a) => {
      calls.push(a);
      return prepareLink(...a);
    },
    mailFailed: (id, resend, token) => {
      failed.push([id, resend, token]);
      return Promise.resolve();
    },
    mailSent: (id, token) => {
      sentIds.push([id, token]);
      return Promise.resolve();
    },
  };
  const mailer: Mailer = (m) => {
    if (opts.mailFails) return Promise.reject(new Error("smtp 535 bad credentials for user@x"));
    sent.push(m);
    return Promise.resolve();
  };
  const app = createApp({
    db,
    mailer: opts.noMailer ? undefined : mailer,
    consoleUrl: "https://console.test",
    baseDomain: opts.baseDomain === undefined ? "bossconsole.test" : opts.baseDomain,
    origins: platformOrigins("", "http://localhost:9000"),
  });
  return { app, sent, calls, failed, sentIds };
}

const req = (body: unknown, jwt: string | null = "good-jwt", method = "POST") =>
  new Request("https://x.test/functions/v1/org-invite-mail/link", {
    method,
    headers: {
      ...(jwt ? { Authorization: `Bearer ${jwt}` } : {}),
      "Content-Type": "application/json",
    },
    body: method === "POST" ? (typeof body === "string" ? body : JSON.stringify(body)) : undefined,
  });

const body = { target: "acme", email: "Bob@Other.test ", via: "max" };

Deno.test("link: a new invite is mailed with the agent's own site and the token in the path", async () => {
  const { app, sent, calls } = harness();
  const res = await app(req(body));
  assertEquals(res.status, 200);
  assertEquals(await res.json(), {
    sent: true,
    id: linkId,
    recipient: "bob@other.test",
    expires_at: facts.expires_at,
  });
  assertEquals(calls[0], [userId, { target: "acme", email: "bob@other.test", via: "max" }]);
  assertEquals(sent.length, 1);
  assertEquals(sent[0].to, "bob@other.test");
  assertEquals(sent[0].subject, "Ada invited you to Acme <Assistant>");
  assertStringIncludes(sent[0].text, `https://acme.bossconsole.test/i/${TOKEN}`);
  assertStringIncludes(sent[0].html, `href="https://acme.bossconsole.test/i/${TOKEN}"`);
  assertStringIncludes(sent[0].html, "Acme &lt;Assistant&gt;");
  assertStringIncludes(sent[0].text, "expires Mon, 23 Nov 2026 10:00:00 UTC");
  assertStringIncludes(sent[0].text, "If you were not expecting this, ignore this email.");
});

Deno.test("link: a custom domain wins over the base domain", async () => {
  const { app, sent } = harness(() => Promise.resolve({ ...facts, custom_host: "chat.acme.test" }));
  assertEquals((await app(req(body))).status, 200);
  assertStringIncludes(sent[0].text, `https://chat.acme.test/i/${TOKEN}`);
});

Deno.test("link: platform origins are fixed per platform, overridable by env, never from the request", async () => {
  const optimist = { ...facts, target: "optimist", target_name: "Optimist", agent_slug: null };
  const h = harness(() => Promise.resolve(optimist));
  assertEquals((await h.app(req({ ...body, target: "optimist" }))).status, 200);
  assertStringIncludes(h.sent[0].text, `https://askoptimist.com/i/${TOKEN}`);
  const max = { ...optimist, target: "max", target_name: "Max" };
  const m = harness(() => Promise.resolve(max));
  assertEquals((await m.app(req({ ...body, target: "max" }))).status, 200);
  assertStringIncludes(m.sent[0].text, `http://localhost:9000/i/${TOKEN}`);
  const evil = harness(() => Promise.resolve(optimist));
  const r = new Request("https://evil.test/functions/v1/org-invite-mail/link", {
    method: "POST",
    headers: { Authorization: "Bearer good-jwt", Origin: "https://evil.test", Host: "evil.test" },
    body: JSON.stringify({ ...body, target: "optimist", origin: "https://evil.test" }),
  });
  assertEquals((await evil.app(r)).status, 200);
  assert(!evil.sent[0].text.includes("evil.test"));
});

Deno.test("link: platformOrigins falls back when the override is not https or loopback", () => {
  assertEquals(platformOrigins("http://example.com", "").optimist, "https://askoptimist.com");
  assertEquals(platformOrigins("", "javascript:alert(1)").max, "https://evolvemax.com");
});

Deno.test("link: a resend takes only the link id", async () => {
  const { app, calls, sent } = harness();
  const res = await app(req({ link_id: linkId.toUpperCase() }));
  assertEquals(res.status, 200);
  assertEquals(calls[0], [userId, { linkId }]);
  assertEquals(sent.length, 1);
});

Deno.test("link: bad bodies", async () => {
  const { app, calls } = harness();
  for (
    const b of [
      "not json",
      "[]",
      {},
      { target: "acme", email: "bob@other.test", via: "api" },
      { target: "acme", email: "bob@other.test" },
      { target: "Bad Slug", email: "bob@other.test", via: "max" },
      { link_id: "nope" },
      { link_id: 7 },
      { target: "acme", email: "bob@other.test", via: "max", pad: "x".repeat(3000) },
    ]
  ) {
    const res = await app(req(b));
    assertEquals(res.status, 400, JSON.stringify(b).slice(0, 60));
    assertEquals((await res.json()).error, "bad_request");
  }
  for (const e of ["", "nope", "a@b", `${"a".repeat(320)}@x.test`, 5]) {
    const res = await app(req({ ...body, email: e }));
    assertEquals(res.status, 400);
  }
  assertEquals((await (await app(req({ ...body, email: "nope" }))).json()).error, "bad_email");
  assertEquals(calls.length, 0);
});

Deno.test("link: auth", async () => {
  const { app, calls } = harness();
  assertEquals((await app(req(body, null))).status, 401);
  assertEquals((await app(req(body, "bad-jwt"))).status, 401);
  assertEquals((await app(req(body, "good-jwt", "GET"))).status, 405);
  assertEquals(calls.length, 0);
});

Deno.test("link: the refusals map to their statuses", async () => {
  const table: [string, number][] = [
    ["bad_email", 400],
    ["not_allowed", 403],
    ["invites_off", 403],
    ["not_found", 404],
    ["not_pending", 409],
    ["resend_in_progress", 409],
    ["too_many_active_links", 409],
    ["rate_limited", 429],
    ["quota_exceeded", 429],
  ];
  for (const [code, status] of table) {
    const { app, sent } = harness(() => Promise.reject(new Refused(status, code)));
    const res = await app(req(body));
    assertEquals(res.status, status, code);
    assertEquals((await res.json()).error, code);
    assertEquals(sent.length, 0);
  }
});

Deno.test("link: an unexpected database failure is mail_unavailable", async () => {
  const { app, sent } = harness(() => Promise.reject(new Error("connect ECONNREFUSED")));
  const res = await app(req(body));
  assertEquals(res.status, 503);
  assertEquals((await res.json()).error, "mail_unavailable");
  assertEquals(sent.length, 0);
});

Deno.test("link: not configured -> 503 before anything is issued", async () => {
  const a = harness(undefined, { noMailer: true });
  assertEquals((await a.app(req(body))).status, 503);
  assertEquals(a.calls.length, 0);
  const b = harness(undefined, { baseDomain: null });
  const res = await b.app(req(body));
  assertEquals(res.status, 503);
  assertEquals((await res.json()).error, "mail_unavailable");
  assertEquals(b.calls.length, 0);
  // a platform needs no base domain
  const c = harness(() => Promise.resolve({ ...facts, target: "max", agent_slug: null }), {
    baseDomain: null,
  });
  assertEquals((await c.app(req({ ...body, target: "max" }))).status, 200);
});

Deno.test("link: an smtp failure is mail_unavailable and leaks nothing", async () => {
  const lines: string[] = [];
  const orig = console.info;
  console.info = (...a: unknown[]) => void lines.push(a.join(" "));
  try {
    const { app } = harness(undefined, { mailFails: true });
    const res = await app(req(body));
    assertEquals(res.status, 503);
    const text = JSON.stringify(await res.json());
    assert(!text.includes("535") && !text.includes("user@x"));
    await harness().app(req(body));
  } finally {
    console.info = orig;
  }
  assert(lines.length >= 2);
  for (const l of lines) {
    assert(!l.includes(TOKEN) && !l.includes("bob@other.test") && !l.includes("535"), l);
  }
});

Deno.test("link: facts that cannot be mailed are refused, not sent", async () => {
  for (
    const f of [
      { ...facts, token: "short" },
      { ...facts, recipient_email: "not an address" },
      { ...facts, agent_slug: "Bad Slug" },
    ]
  ) {
    const { app, sent } = harness(() => Promise.resolve(f));
    assertEquals((await app(req(body))).status, 503);
    assertEquals(sent.length, 0);
  }
});

Deno.test("link: the mail has one button and no stray content; no inviter name falls back to Someone", async () => {
  const { app, sent } = harness(() => Promise.resolve({ ...facts, inviter_name: null }));
  await app(req(body));
  assertEquals(sent[0].subject, "Someone invited you to Acme <Assistant>");
  assertEquals((sent[0].html.match(/<a /g) ?? []).length, 2); // the button and the "Or open" fallback
  assert(!sent[0].text.includes("Acme Corp"));
  assert(!sent[0].text.includes("ada@acme.test"));
});

Deno.test("link: an smtp failure on a new invite asks the database to drop the link", async () => {
  const { app, failed } = harness(undefined, { mailFails: true });
  assertEquals((await app(req(body))).status, 503);
  assertEquals(failed, [[linkId, false, TOKEN]]);
});

Deno.test("link: an smtp failure on a resend asks for the earlier state back", async () => {
  const { app, failed } = harness(undefined, { mailFails: true });
  assertEquals((await app(req({ link_id: linkId }))).status, 503);
  assertEquals(failed, [[linkId, true, TOKEN]]);
});

Deno.test("link: a send that went out, or was refused, never calls mailFailed", async () => {
  const ok = harness();
  await ok.app(req(body));
  const refused = harness(() => Promise.reject(new Refused(429, "rate_limited")), {
    mailFails: true,
  });
  await refused.app(req(body));
  assertEquals(ok.failed.length + refused.failed.length, 0);
});

Deno.test("link: facts that cannot be mailed also release the link", async () => {
  const { app, failed } = harness(() => Promise.resolve({ ...facts, token: "short" }));
  assertEquals((await app(req(body))).status, 503);
  assertEquals(failed, [[linkId, false, "short"]]);
});

Deno.test("link: a failing mailFailed does not change the answer", async () => {
  const sent: MailMessage[] = [];
  const app = createApp({
    db: {
      user: () => Promise.resolve(userId),
      prepare: () => Promise.reject(new Error("x")),
      prepareAgent: () => Promise.reject(new Error("x")),
      prepareLink: () => Promise.resolve(facts),
      mailFailed: () => Promise.reject(new Error("db down")),
      mailSent: () => Promise.reject(new Error("db down")),
    },
    mailer: () => Promise.reject(new Error("smtp")),
    baseDomain: "bossconsole.test",
    origins: platformOrigins("", ""),
  });
  const res = await app(req(body));
  assertEquals(res.status, 503);
  assertEquals((await res.json()).error, "mail_unavailable");
  assertEquals(sent.length, 0);
});

Deno.test("link: a sent mail tells the database; a failed one does not", async () => {
  const ok = harness();
  assertEquals((await ok.app(req({ link_id: linkId }))).status, 200);
  assertEquals(ok.sentIds, [[linkId, TOKEN]]);
  const bad = harness(undefined, { mailFails: true });
  await bad.app(req({ link_id: linkId }));
  assertEquals(bad.sentIds, []);
});

Deno.test("restDb: resend_in_progress is a 409, and the outcome calls carry the issued token", async () => {
  const real = globalThis.fetch;
  const bodies: Record<string, unknown>[] = [];
  Deno.env.set("SUPABASE_URL", "http://db.test");
  Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "svc");
  globalThis.fetch = ((input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    if (init?.body) bodies.push(JSON.parse(String(init.body)));
    const out = url.endsWith("invite_link_mail_prepare")
      ? { success: false, error: "resend_in_progress" }
      : null;
    return Promise.resolve(new Response(JSON.stringify(out), { status: 200 }));
  }) as typeof fetch;
  try {
    const db = restDb();
    const err = await db.prepareLink(userId, { linkId }).catch((e) => e);
    assert(err instanceof Refused);
    assertEquals([err.status, err.code], [409, "resend_in_progress"]);
    await db.mailFailed(linkId, true, TOKEN);
    await db.mailSent(linkId, TOKEN);
    assertEquals(bodies.slice(-2), [
      { p_link_id: linkId, p_resend: true, p_token: TOKEN },
      { p_link_id: linkId, p_token: TOKEN },
    ]);
  } finally {
    globalThis.fetch = real;
  }
});
