import { assert, assertEquals, assertRejects } from "@std/assert";
import { createApp, type Db, type Domain } from "../app.ts";
import { dohResolver, type RecordType, type Resolver, resolverFromEnv } from "../dns.ts";

const domainId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const userId = "11111111-2222-4333-8444-555555555555";
const token = "0123456789abcdef0123456789abcdef";
const domain: Domain = { id: domainId, hostname: "chat.acme.test", verify_token: token };

type Records = Partial<Record<string, string[]>>;
function fakeResolver(records: Records, seen: string[] = []): Resolver {
  return (name: string, type: RecordType) => {
    seen.push(`${type} ${name}`);
    return Promise.resolve(records[`${type} ${name}`] ?? []);
  };
}

function fakeDb(found: Domain | null = domain) {
  const calls: unknown[][] = [];
  const db: Db = {
    user: (jwt) => Promise.resolve(jwt === "good-jwt" ? userId : null),
    domain: (_jwt, id) => Promise.resolve(found && found.id === id ? found : null),
    record: (id, txt, cname, actor) => {
      calls.push([id, txt, cname, actor]);
      return Promise.resolve(txt && cname ? "active" : txt ? "verified" : "failed");
    },
  };
  return { db, calls };
}

function verify(body: unknown, jwt = "good-jwt") {
  return new Request("https://x.test/functions/v1/agent-domains/verify", {
    method: "POST",
    headers: { Authorization: `Bearer ${jwt}`, "Content-Type": "application/json" },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

const good: Records = {
  "TXT _bossconsole-verify.chat.acme.test": [`bossconsole-verify=${token}`],
  "CNAME chat.acme.test": ["EDGE.bossconsole.test."],
};

Deno.test("method, path, auth and body are checked before any lookup", async () => {
  const { db, calls } = fakeDb();
  const seen: string[] = [];
  const app = createApp({ db, resolver: fakeResolver(good, seen), baseDomain: "bossconsole.test" });
  const pre = await app(new Request("https://x.test/agent-domains/verify", { method: "OPTIONS" }));
  assertEquals(pre.status, 204);
  assertEquals(pre.headers.get("access-control-allow-methods"), "POST, OPTIONS");
  assertEquals((await app(new Request("https://x.test/agent-domains/verify"))).status, 405);
  assertEquals(
    (await app(new Request("https://x.test/agent-domains/other", { method: "POST" }))).status,
    404,
  );
  assertEquals(
    (await app(new Request("https://x.test/agent-domains/verify", { method: "POST", body: "{}" })))
      .status,
    401,
  );
  assertEquals((await app(verify({ domain_id: domainId }, "bad-jwt"))).status, 401);
  assertEquals((await app(verify("not json"))).status, 400);
  assertEquals((await app(verify({ domain_id: "nope" }))).status, 400);
  assertEquals((await app(verify({ domain_id: domainId, pad: "x".repeat(2000) }))).status, 400);
  assertEquals(seen, []);
  assertEquals(calls, []);
});

Deno.test("a domain the caller cannot see is 404 and never resolved or written", async () => {
  const { db, calls } = fakeDb(null);
  const seen: string[] = [];
  const app = createApp({ db, resolver: fakeResolver(good, seen), baseDomain: "bossconsole.test" });
  assertEquals((await app(verify({ domain_id: domainId }))).status, 404);
  assertEquals(seen, []);
  assertEquals(calls, []);
});

Deno.test("TXT and CNAME make the domain active, recorded with the caller as actor", async () => {
  const { db, calls } = fakeDb();
  const seen: string[] = [];
  const app = createApp({ db, resolver: fakeResolver(good, seen), baseDomain: "bossconsole.test" });
  const r = await app(verify({ domain_id: domainId.toUpperCase() }));
  assertEquals(r.status, 200);
  assertEquals(r.headers.get("cache-control"), "no-store");
  assertEquals(await r.json(), {
    domain_id: domainId,
    hostname: "chat.acme.test",
    status: "active",
    checks: { txt: true, cname: true },
    expected: {
      txt_name: "_bossconsole-verify.chat.acme.test",
      txt_value: `bossconsole-verify=${token}`,
      cname_target: "edge.bossconsole.test",
    },
  });
  assertEquals(seen.sort(), ["CNAME chat.acme.test", "TXT _bossconsole-verify.chat.acme.test"]);
  assertEquals(calls, [[domainId, true, true, userId]]);
});

Deno.test("TXT alone verifies; a wrong token or a missing TXT fails", async () => {
  const cases: [Records, string][] = [
    [{ "TXT _bossconsole-verify.chat.acme.test": [`bossconsole-verify=${token}`] }, "verified"],
    [{ ...good, "CNAME chat.acme.test": ["edge.elsewhere.test"] }, "verified"],
    [{
      ...good,
      "TXT _bossconsole-verify.chat.acme.test": ["bossconsole-verify=" + "f".repeat(32)],
    }, "failed"],
    [
      { ...good, "TXT _bossconsole-verify.chat.acme.test": [`bossconsole-verify=${token}x`] },
      "failed",
    ],
    [{ "CNAME chat.acme.test": ["edge.bossconsole.test"] }, "failed"],
    [{
      "TXT chat.acme.test": [`bossconsole-verify=${token}`],
      "CNAME chat.acme.test": ["edge.bossconsole.test"],
    }, "failed"],
  ];
  for (const [records, status] of cases) {
    const { db } = fakeDb();
    const app = createApp({ db, resolver: fakeResolver(records), baseDomain: "bossconsole.test" });
    const r = await app(verify({ domain_id: domainId }));
    assertEquals((await r.json()).status, status, JSON.stringify(records));
  }
});

Deno.test("a DNS outage is 503 and leaves the stored status alone", async () => {
  const { db, calls } = fakeDb();
  const app = createApp({
    db,
    resolver: () => Promise.reject(new Error("timeout")),
    baseDomain: "bossconsole.test",
  });
  const r = await app(verify({ domain_id: domainId }));
  assertEquals(r.status, 503);
  assertEquals((await r.json()).expected.cname_target, "edge.bossconsole.test");
  assertEquals(calls, []);
});

Deno.test("without AGENTS_BASE_DOMAIN the function refuses to judge", async () => {
  const { db, calls } = fakeDb();
  const app = createApp({ db, resolver: fakeResolver(good), baseDomain: "" });
  assertEquals((await app(verify({ domain_id: domainId }))).status, 503);
  assertEquals(calls, []);
});

// A local fake DoH server, the shape AGENT_DOMAINS_DOH_URL points at in tests.
function fakeDoh(
  zone: Record<string, { Status: number; Answer?: { name: string; type: number; data: string }[] }>,
) {
  const server = Deno.serve({ port: 0, hostname: "127.0.0.1", onListen() {} }, (req) => {
    const u = new URL(req.url);
    if (req.headers.get("accept") !== "application/dns-json") {
      return new Response("bad accept", { status: 400 });
    }
    const hit = zone[`${u.searchParams.get("type")} ${u.searchParams.get("name")}`];
    if (hit?.Status === -1) return new Response("down", { status: 502 });
    return Response.json(hit ?? { Status: 3 });
  });
  return { url: `http://127.0.0.1:${server.addr.port}/dns-query`, server };
}

Deno.test("the DoH resolver parses TXT strings and CNAMEs, and tells NXDOMAIN from outages", async () => {
  const { url, server } = fakeDoh({
    "TXT a.test": {
      Status: 0,
      Answer: [{ name: "a.test", type: 16, data: '"bossconsole-" "verify=x"' }, {
        name: "a.test",
        type: 16,
        data: '"other"',
      }],
    },
    "CNAME a.test": { Status: 0, Answer: [{ name: "a.test", type: 5, data: "edge.base.test." }] },
    "TXT servfail.test": { Status: 2 },
    "TXT down.test": { Status: -1 },
  });
  try {
    const resolve = dohResolver(url);
    assertEquals(await resolve("a.test", "TXT"), ["bossconsole-verify=x", "other"]);
    assertEquals(await resolve("a.test", "CNAME"), ["edge.base.test."]);
    assertEquals(await resolve("missing.test", "TXT"), []);
    await assertRejects(() => resolve("servfail.test", "TXT"));
    await assertRejects(() => resolve("down.test", "TXT"));
  } finally {
    await server.shutdown();
  }
});

Deno.test("AGENT_DOMAINS_DOH_URL routes the whole verify flow through the fake resolver", async () => {
  const { url, server } = fakeDoh({
    "TXT _bossconsole-verify.chat.acme.test": {
      Status: 0,
      Answer: [{ name: "x", type: 16, data: `"bossconsole-verify=${token}"` }],
    },
    "CNAME chat.acme.test": {
      Status: 0,
      Answer: [{ name: "x", type: 5, data: "edge.bossconsole.test." }],
    },
  });
  const previous = Deno.env.get("AGENT_DOMAINS_DOH_URL");
  Deno.env.set("AGENT_DOMAINS_DOH_URL", url);
  try {
    const { db, calls } = fakeDb();
    const app = createApp({ db, resolver: resolverFromEnv(), baseDomain: "bossconsole.test" });
    assertEquals((await (await app(verify({ domain_id: domainId }))).json()).status, "active");
    assertEquals(calls, [[domainId, true, true, userId]]);
  } finally {
    if (previous === undefined) Deno.env.delete("AGENT_DOMAINS_DOH_URL");
    else Deno.env.set("AGENT_DOMAINS_DOH_URL", previous);
    await server.shutdown();
  }
});

// Against a throwaway LOCAL stack only: AGENT_DOMAINS_IT_URL=http://127.0.0.1:<port> plus the
// stack's anon and service keys. Exercises the real RLS read, auth lookup and service-role RPC.
const IT_URL = Deno.env.get("AGENT_DOMAINS_IT_URL");
Deno.test({
  name: "integration: org admin verifies a domain on a local stack; a stranger gets 404",
  ignore: !IT_URL || !/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(IT_URL),
  async fn() {
    const anon = Deno.env.get("AGENT_DOMAINS_IT_ANON_KEY")!;
    const service = Deno.env.get("AGENT_DOMAINS_IT_SERVICE_KEY")!;
    Deno.env.set("SUPABASE_URL", IT_URL!);
    Deno.env.set("SUPABASE_ANON_KEY", anon);
    Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", service);
    const svc = {
      apikey: service,
      Authorization: `Bearer ${service}`,
      "Content-Type": "application/json",
    };
    const call = async (path: string, init: RequestInit & { headers: Record<string, string> }) => {
      const r = await fetch(`${IT_URL}${path}`, init);
      const text = await r.text();
      if (!r.ok) throw new Error(`${path} ${r.status} ${text}`);
      return text ? JSON.parse(text) : null;
    };
    const run = crypto.randomUUID().slice(0, 8);
    const session = async (who: string) => {
      const email = `${who}-${run}@agent-domains.test`;
      await call("/auth/v1/admin/users", {
        method: "POST",
        headers: svc,
        body: JSON.stringify({ email, password: `pw-${run}-secret`, email_confirm: true }),
      });
      const s = await call("/auth/v1/token?grant_type=password", {
        method: "POST",
        headers: { apikey: anon, "Content-Type": "application/json" },
        body: JSON.stringify({ email, password: `pw-${run}-secret` }),
      });
      return { jwt: s.access_token as string, id: s.user.id as string };
    };
    const owner = await session("owner");
    const stranger = await session("stranger");
    const slug = `it${run.replace(/[^a-z0-9]/g, "").slice(0, 6)}x`;
    await call("/rest/v1/rpc/create_organisation_internal", {
      method: "POST",
      headers: svc,
      body: JSON.stringify({
        p_slug: slug,
        p_name: "IT Org",
        p_description: null,
        p_owner_id: owner.id,
        p_domain: null,
        p_visibility: "private",
        p_join_policy: "invite_only",
      }),
    });
    const [org] = await call(`/rest/v1/organisations?slug=eq.${slug}&select=id`, { headers: svc });
    const user = (jwt: string) => ({
      apikey: anon,
      Authorization: `Bearer ${jwt}`,
      "Content-Type": "application/json",
      Prefer: "return=representation",
    });
    const [agent] = await call("/rest/v1/agents?select=id", {
      method: "POST",
      headers: user(owner.jwt),
      body: JSON.stringify({
        slug: `a-${slug}`,
        org_id: org.id,
        name: "IT Agent",
        created_by: owner.id,
      }),
    });
    const host = `chat-${run}.it-example.com`;
    const [row] = await call("/rest/v1/agent_domains?select=id,verify_token,status", {
      method: "POST",
      headers: user(owner.jwt),
      body: JSON.stringify({ agent_id: agent.id, hostname: host }),
    });
    assertEquals(row.status, "pending");
    try {
      const app = createApp({
        baseDomain: "bossconsole.test",
        resolver: fakeResolver({
          [`TXT _bossconsole-verify.${host}`]: [`bossconsole-verify=${row.verify_token}`],
          [`CNAME ${host}`]: ["edge.bossconsole.test."],
        }),
      });
      assertEquals((await app(verify({ domain_id: row.id }, stranger.jwt))).status, 404);
      const r = await app(verify({ domain_id: row.id }, owner.jwt));
      assertEquals(r.status, 200);
      assertEquals((await r.json()).status, "active");
      const [stored] = await call(
        `/rest/v1/agent_domains?id=eq.${row.id}&select=status,verified_at`,
        { headers: svc },
      );
      assertEquals(stored.status, "active");
      assert(stored.verified_at);
      const hit = await call("/rest/v1/rpc/agent_for_host", {
        method: "POST",
        headers: {
          apikey: anon,
          Authorization: `Bearer ${anon}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ p_host: host }),
      });
      assertEquals(hit, [], "a draft agent stays hidden from anon even on an active domain");
    } finally {
      await call(`/rest/v1/agents?id=eq.${agent.id}`, { method: "DELETE", headers: svc });
    }
  },
});
