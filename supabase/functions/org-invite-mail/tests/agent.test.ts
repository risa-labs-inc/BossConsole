import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import {
  agentSiteUrl,
  baseDomainFromEnv,
  createApp,
  type Db,
  type Mailer,
  Refused,
} from "../app.ts";
import type { AgentInviteFacts } from "../mail.ts";
import type { MailMessage } from "../smtp.ts";

const agentId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const userId = "99999999-8888-4777-8666-555555555555";
const facts: AgentInviteFacts = {
  email: "bob@other.test",
  agent_name: "Acme <Assistant>",
  agent_slug: "acme",
  custom_host: null,
  org_name: "Acme Corp",
  inviter_email: "admin@acme.test",
  inviter_name: "Ada Admin",
};

function harness(
  prepareAgent: Db["prepareAgent"] = () => Promise.resolve(facts),
  mailFails = false,
  baseDomain: string | null = "bossconsole.test",
) {
  const sent: MailMessage[] = [];
  const calls: unknown[][] = [];
  const db: Db = {
    user: (jwt) => Promise.resolve(jwt === "good-jwt" ? userId : null),
    prepare: () => Promise.reject(new Error("not an org invite")),
    prepareAgent: (...a) => {
      calls.push(a);
      return prepareAgent(...a);
    },
  };
  const mailer: Mailer = (m) => {
    if (mailFails) return Promise.reject(new Error("smtp 535 bad credentials for user@x"));
    sent.push(m);
    return Promise.resolve();
  };
  return {
    app: createApp({ db, mailer, consoleUrl: "https://console.bossconsole.test", baseDomain }),
    sent,
    calls,
  };
}

const req = (body: unknown, jwt: string | null = "good-jwt", method = "POST") =>
  new Request("https://x.test/functions/v1/org-invite-mail/agent", {
    method,
    headers: {
      ...(jwt ? { Authorization: `Bearer ${jwt}` } : {}),
      "Content-Type": "application/json",
    },
    body: method === "POST" ? (typeof body === "string" ? body : JSON.stringify(body)) : undefined,
  });
const good = { agent_id: agentId, email: " Bob@Other.test " };

async function quietly<T>(fn: () => Promise<T>): Promise<{ value: T; out: string }> {
  const lines: string[] = [];
  const orig = { info: console.info, error: console.error, log: console.log, warn: console.warn };
  console.info =
    console.error =
    console.log =
    console.warn =
      (...a: unknown[]) => void lines.push(a.map(String).join(" "));
  try {
    return { value: await fn(), out: lines.join("\n") };
  } finally {
    Object.assign(console, orig);
  }
}

Deno.test("agent invite: the invitee gets one link to the agent's site; the caller is re-checked with their own id", async () => {
  const { app, sent, calls } = harness();
  const { value: r, out } = await quietly(() => app(req(good)));
  assertEquals(r.status, 200);
  assertEquals(await r.json(), { sent: true, email: "bob@other.test" });
  assertEquals(calls, [[userId, agentId, "bob@other.test"]]);
  assertEquals(sent.length, 1);
  assertEquals(sent[0]!.to, "bob@other.test");
  assertEquals(sent[0]!.subject, "You're invited to Acme <Assistant>");
  assertStringIncludes(sent[0]!.text, "https://acme.bossconsole.test/");
  assertStringIncludes(sent[0]!.html, 'href="https://acme.bossconsole.test/"');
  assertStringIncludes(sent[0]!.html, "Acme &lt;Assistant&gt;");
  assert(!sent[0]!.html.includes("<Assistant>"), "the agent name is escaped");
  // Logs name the agent and the outcome, never the address.
  assert(!out.includes("bob@other.test") && !out.includes("Bob@Other"), out);
  assertStringIncludes(out, `agent=${agentId} sent`);
});

Deno.test("agent invite: an active custom domain is the link", async () => {
  const { app, sent } = harness(() =>
    Promise.resolve({ ...facts, custom_host: "chat.acme.example" })
  );
  await quietly(() => app(req(good)));
  assertStringIncludes(sent[0]!.text, "https://chat.acme.example/");
});

Deno.test("agent invite: refusals map to status codes and send nothing", async () => {
  for (
    const [code, status] of [
      ["permission_denied", 403],
      ["invite_not_found", 404],
      ["agent_not_live", 409],
      ["rate_limited_recipient", 429],
      ["rate_limited_org", 429],
    ] as const
  ) {
    const { app, sent } = harness(() => Promise.reject(new Refused(status, code)));
    const { value: r } = await quietly(() => app(req(good)));
    assertEquals(r.status, status, code);
    assertEquals((await r.json()).error, code);
    assertEquals(sent.length, 0);
  }
});

Deno.test("agent invite: no JWT, a bad JWT, a bad body; GET is not allowed", async () => {
  const { app, calls } = harness();
  assertEquals((await app(req(good, null))).status, 401);
  assertEquals((await quietly(() => app(req(good, "bad-jwt")))).value.status, 401);
  for (
    const bad of ["{", { agent_id: "x", email: "a@b.test" }, { agent_id: agentId, email: "nope" }, {
      agent_id: agentId,
    }, "x".repeat(4000)]
  ) {
    assertEquals((await app(req(bad))).status, 400, JSON.stringify(bad).slice(0, 40));
  }
  assertEquals((await app(req(good, "good-jwt", "GET"))).status, 405);
  assertEquals(calls.length, 0);
});

Deno.test("agent invite: a mail server failure is a 502 with nothing secret in the answer or the log", async () => {
  const { app } = harness(undefined, true);
  const { value: r, out } = await quietly(() => app(req(good)));
  assertEquals(r.status, 502);
  const body = await r.text();
  assert(!body.includes("535") && !out.includes("535") && !out.includes("bob@"), out);
});

Deno.test("agent invite: without AGENTS_BASE_DOMAIN the function refuses", async () => {
  const { app } = harness(undefined, false, null);
  assertEquals((await app(req(good))).status, 503);
});

Deno.test("agentSiteUrl and baseDomainFromEnv refuse what is not a host", () => {
  assertEquals(
    agentSiteUrl({ agent_slug: "acme", custom_host: null }, "bossconsole.ai"),
    "https://acme.bossconsole.ai/",
  );
  assertEquals(
    agentSiteUrl({ agent_slug: "acme", custom_host: "evil.example/x?y" }, "bossconsole.ai"),
    "https://acme.bossconsole.ai/",
  );
  assertEquals(agentSiteUrl({ agent_slug: "../x", custom_host: null }, "bossconsole.ai"), null);
  assertEquals(baseDomainFromEnv(" BossConsole.Localhost. "), "bossconsole.localhost");
  assertEquals(baseDomainFromEnv("https://x"), null);
  assertEquals(baseDomainFromEnv(""), null);
});
