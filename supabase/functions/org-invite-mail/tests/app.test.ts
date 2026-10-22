import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import { consoleUrlFromEnv, createApp, type Db, type Mailer, Refused } from "../app.ts";
import { inviteMail, roleLabel } from "../mail.ts";
import type { MailMessage } from "../smtp.ts";

const orgId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const inviteId = "11111111-2222-4333-8444-555555555555";
const userId = "99999999-8888-4777-8666-555555555555";
const token = "boss_inv_" + "A".repeat(43);
const facts = {
  email: "invitee@acme.test",
  org_name: "Acme <Corp>",
  org_slug: "acme",
  role_name: "acme_agent_editor",
  expires_at: "2026-10-26T12:00:00Z",
  inviter_email: "admin@acme.test",
  inviter_name: null,
};

function harness(prepare: Db["prepare"] = () => Promise.resolve(facts), mailFails = false) {
  const sent: MailMessage[] = [];
  const calls: unknown[][] = [];
  const db: Db = {
    user: (jwt) => Promise.resolve(jwt === "good-jwt" ? userId : null),
    prepare: (...a) => {
      calls.push(a);
      return prepare(...a);
    },
    prepareAgent: () => Promise.reject(new Error("not an agent invite")),
  };
  const mailer: Mailer = (m) => {
    if (mailFails) return Promise.reject(new Error("smtp 535 bad credentials for user@x"));
    sent.push(m);
    return Promise.resolve();
  };
  const app = createApp({ db, mailer, consoleUrl: "https://console.bossconsole.test" });
  return { app, sent, calls };
}

const req = (
  body: unknown,
  jwt: string | null = "good-jwt",
  method = "POST",
  path = "/functions/v1/org-invite-mail",
) =>
  new Request(`https://x.test${path}`, {
    method,
    headers: {
      ...(jwt ? { Authorization: `Bearer ${jwt}` } : {}),
      "Content-Type": "application/json",
    },
    body: method === "POST" ? (typeof body === "string" ? body : JSON.stringify(body)) : undefined,
  });
const good = { org_id: orgId, invite_id: inviteId, token };

/** Captures console output so a test can assert what is (not) logged. */
async function quietly<T>(fn: () => Promise<T>): Promise<{ value: T; out: string }> {
  const lines: string[] = [];
  const orig = { info: console.info, error: console.error, log: console.log, warn: console.warn };
  console.info =
    console.error =
    console.log =
    console.warn =
      (...a: unknown[]) => lines.push(a.join(" "));
  try {
    return { value: await fn(), out: lines.join("\n") };
  } finally {
    Object.assign(console, orig);
  }
}

Deno.test("auth, method, path and body are checked before anything else", async () => {
  const { app, calls } = harness();
  assertEquals((await app(req(good, null))).status, 401);
  assertEquals((await app(req(good, "bad-jwt"))).status, 401);
  assertEquals((await app(req(good, "good-jwt", "GET"))).status, 405);
  assertEquals(
    (await app(req(good, "good-jwt", "POST", "/functions/v1/org-invite-mail/other"))).status,
    404,
  );
  for (
    const bad of [
      "not json",
      {},
      { ...good, org_id: "x" },
      { ...good, invite_id: 5 },
      { ...good, token: "not-a-token" },
      { ...good, token: "boss_inv_short" },
      "x".repeat(3000),
    ]
  ) {
    assertEquals((await app(req(bad))).status, 400, JSON.stringify(bad).slice(0, 40));
  }
  assertEquals(calls.length, 0);
  assertEquals(
    (await app(new Request("https://x.test/functions/v1/org-invite-mail", { method: "OPTIONS" })))
      .status,
    204,
  );
});

Deno.test("sends the invite to the invite's own address with the console join link", async () => {
  const { app, sent, calls } = harness();
  const { value: r, out } = await quietly(() => app(req(good)));
  assertEquals(r.status, 200);
  assertEquals(await r.json(), { sent: true, email: "invitee@acme.test" });
  assertEquals(calls, [[userId, orgId, inviteId, token]]);
  assertEquals(sent.length, 1);
  assertEquals(sent[0]!.to, "invitee@acme.test");
  const link = `https://console.bossconsole.test/join/${token}`;
  assertStringIncludes(sent[0]!.text, link);
  assertStringIncludes(sent[0]!.html, `href="${link}"`);
  assertStringIncludes(sent[0]!.html, "Acme &lt;Corp&gt;");
  assert(!sent[0]!.html.includes("<Corp>"), "org name is escaped");
  assertStringIncludes(sent[0]!.text, "as agent editor");
  assert(!out.includes(token), "the token is never logged");
  assert(!out.includes("invitee@acme.test"), "the address is never logged");
});

Deno.test("the database's refusals map to 403, 404, 409 and 429 and send nothing", async () => {
  for (
    const [code, status] of [
      ["permission_denied", 403],
      ["invite_not_found", 404],
      ["invite_not_pending", 409],
      ["rate_limited_recipient", 429],
      ["rate_limited_org", 429],
    ] as const
  ) {
    const { app, sent } = harness(() => Promise.reject(new Refused(status, code)));
    const { value: r, out } = await quietly(() => app(req(good)));
    assertEquals(r.status, status, code);
    assertEquals((await r.json()).error, code);
    assertEquals(sent.length, 0);
    assert(!out.includes(token));
  }
});

Deno.test("an unreachable database is 503; a failing mail server is 502 without its text", async () => {
  const down = harness(() => Promise.reject(new Error("fetch failed")));
  assertEquals((await down.app(req(good))).status, 503);
  const smtp = harness(undefined, true);
  const { value: r, out } = await quietly(() => smtp.app(req(good)));
  assertEquals(r.status, 502);
  const body = await r.text();
  assert(!body.includes("535") && !body.includes("user@x"));
  assert(!out.includes("535") && !out.includes(token));
});

Deno.test("without SMTP or a console URL the function answers 503 after validating", async () => {
  const app = createApp({
    db: {
      user: () => Promise.resolve(userId),
      prepare: () => Promise.resolve(facts),
      prepareAgent: () => Promise.reject(new Error("unused")),
    },
    mailer: () => Promise.resolve(),
    consoleUrl: null,
  });
  assertEquals((await app(req(good))).status, 503);
  assertEquals((await app(req({}))).status, 400);
});

Deno.test("CONSOLE_URL: https only (http for loopback), no query, trailing slash dropped", () => {
  assertEquals(
    consoleUrlFromEnv("https://console.bossconsole.ai/"),
    "https://console.bossconsole.ai",
  );
  assertEquals(consoleUrlFromEnv("http://localhost:5173"), "http://localhost:5173");
  assertEquals(consoleUrlFromEnv("http://console.example.com"), null);
  assertEquals(consoleUrlFromEnv("https://c.example.com/?x=1"), null);
  assertEquals(consoleUrlFromEnv("javascript:alert(1)"), null);
  assertEquals(consoleUrlFromEnv(""), null);
});

Deno.test("the mail text: role label without the org prefix, single use, expiry, the address it was sent to", () => {
  assertEquals(roleLabel("acme_agent_manager", "acme"), "agent manager");
  assertEquals(roleLabel(null), null);
  const m = inviteMail(
    { ...facts, role_name: null, inviter_name: "Ada\r\nBcc: x" },
    "https://c.test/join/t",
  );
  assertEquals(m.subject, "You're invited to join Acme <Corp> on BOSS");
  assertStringIncludes(m.text, "Ada Bcc: x invited you");
  assertStringIncludes(m.text, "works once and expires");
  assertStringIncludes(m.text, "sent to invitee@acme.test");
});
