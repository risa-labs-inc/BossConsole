import { assert, assertEquals, assertFalse } from "@std/assert";
import {
  createApp,
  type Deps,
  MAX_BYTES,
  type PrecheckArgs,
  statusFor,
  type SubmitArgs,
  Unauthorized,
} from "../app.ts";
import { skillBundle } from "./helpers.ts";

const U = "99999999-8888-4777-8666-555555555555";
const VID = "11111111-2222-4333-8444-555555555555";

function harness(over: Partial<Deps> = {}) {
  const log = {
    put: [] as string[],
    precheck: [] as PrecheckArgs[],
    remove: [] as string[],
    submit: [] as SubmitArgs[],
    install: [] as unknown[][],
    sign: [] as string[],
    access: [] as unknown[][],
  };
  const deps: Deps = {
    user: (jwt) => Promise.resolve(jwt === "good" ? U : null),
    flags: () => Promise.resolve({ bundles: true }),
    precheck: (a) => {
      log.precheck.push(a);
      return Promise.resolve({});
    },
    put: (p) => {
      log.put.push(p);
      return Promise.resolve("created");
    },
    remove: (p) => {
      log.remove.push(p);
      return Promise.resolve();
    },
    submit: (a) => {
      log.submit.push(a);
      return Promise.resolve({ slug: "my-skill", version: 1, status: "pending_review" });
    },
    install: (...a) => {
      log.install.push(a);
      return Promise.resolve({
        slug: "my-skill",
        kind: "skill",
        bundle_path: "bundles/" + "a".repeat(64) + ".zip",
        name: "N",
      });
    },
    sign: (p) => {
      log.sign.push(p);
      return Promise.resolve("https://signed.example/x?token=t");
    },
    access: (...a) => {
      log.access.push(a);
      return Promise.resolve("bundles/" + "b".repeat(64) + ".zip");
    },
    ...over,
  };
  const app = createApp(deps);
  const call = (route: string, init: RequestInit & { token?: string | null } = {}) => {
    const { token = "good", ...rest } = init;
    const headers = new Headers(rest.headers);
    if (token) headers.set("authorization", `Bearer ${token}`);
    return app(
      new Request(`http://x/functions/v1/marketplace-bundle/${route}`, {
        method: "POST",
        ...rest,
        headers,
      }),
    );
  };
  return { log, call, app };
}
const zipReq = (body: Uint8Array = skillBundle()) => ({
  body: body as unknown as BodyInit,
  headers: { "content-type": "application/zip" },
});
const json = (o: unknown) => ({
  body: JSON.stringify(o),
  headers: { "content-type": "application/json" },
});
const bodyOf = async (r: Response) => await r.json() as Record<string, unknown>;

Deno.test("routing, CORS and auth", async () => {
  const { call, app } = harness();
  assertEquals(
    (await app(new Request("http://x/marketplace-bundle/upload", { method: "OPTIONS" }))).status,
    204,
  );
  assertEquals((await call("nope", zipReq())).status, 404);
  assertEquals((await app(new Request("http://x/marketplace-bundle/upload"))).status, 404); // GET
  assertEquals((await call("upload", { ...zipReq(), token: null })).status, 401);
  assertEquals((await call("upload", { ...zipReq(), token: "bad" })).status, 401);
  assertEquals((await call("review", { ...json({ version_id: VID }), token: null })).status, 401);
  const r = await call("upload", zipReq());
  assertEquals(r.headers.get("access-control-allow-origin"), "*");
  assertEquals(r.headers.get("content-type"), "application/json");
});

Deno.test("upload: stores bundles/<user>/<sha>.zip and submits the validated manifest", async () => {
  const { call, log } = harness();
  const zip = skillBundle();
  const r = await call("upload", zipReq(zip));
  assertEquals(r.status, 200);
  assertEquals(await bodyOf(r), { slug: "my-skill", version: 1, status: "pending_review" });
  const sha = [
    ...new Uint8Array(await crypto.subtle.digest("SHA-256", zip as unknown as BufferSource)),
  ].map((b) => b.toString(16).padStart(2, "0")).join("");
  assertEquals(log.put, [`bundles/${U}/${sha}.zip`]);
  assertEquals(log.precheck.length, 1);
  assertEquals(log.remove, []);
  assertEquals(log.submit.length, 1);
  assertEquals(log.submit[0].userId, U);
  assertEquals(log.submit[0].path, `bundles/${U}/${sha}.zip`);
  assertEquals(log.submit[0].sha256, sha);
  assertEquals(log.submit[0].size, zip.length);
  assertEquals(log.submit[0].skipReview, false);
  assertEquals((log.submit[0].manifest as { slug: string }).slug, "my-skill");
});

Deno.test("upload: skip_review=1 is passed through", async () => {
  const { call, log } = harness();
  await call("upload?skip_review=1", zipReq());
  assertEquals(log.precheck[0].skipReview, true);
  assertEquals(log.submit[0].skipReview, true);
});

Deno.test("upload: wrong content type, flag off, oversize", async () => {
  const { call, log } = harness();
  assertEquals(
    (await call("upload", {
      body: skillBundle() as unknown as BodyInit,
      headers: { "content-type": "text/plain" },
    })).status,
    415,
  );
  const off = harness({ flags: () => Promise.resolve({ bundles: false }) });
  const r = await off.call("upload", zipReq());
  assertEquals(r.status, 403);
  assertEquals((await bodyOf(r)).error, "bundles_disabled");
  assertEquals(off.log.put.length, 0); // nothing stored while bundles are off
  const big = await call("upload", { ...zipReq(new Uint8Array(MAX_BYTES + 1)) });
  assertEquals(big.status, 413);
  assertEquals((await bodyOf(big)).error, "bundle_too_large");
  const declared = await call("upload", {
    body: skillBundle() as unknown as BodyInit,
    headers: { "content-type": "application/zip", "content-length": String(MAX_BYTES + 1) },
  });
  assertEquals(declared.status, 413);
  assertEquals(log.put.length + log.submit.length, 0);
});

Deno.test("upload: an invalid bundle is a 400 with a code, nothing is stored", async () => {
  const { call, log } = harness();
  const r = await call("upload", zipReq(new TextEncoder().encode("not a zip")));
  assertEquals(r.status, 400);
  const b = await bodyOf(r);
  assertEquals(b.error, "invalid_bundle");
  assert(typeof b.message === "string");
  const m = await call("upload", zipReq(skillBundle({ schema: 2 })));
  assertEquals((await bodyOf(m)).error, "invalid_manifest");
  assertEquals(log.put.length + log.submit.length, 0);
});

Deno.test("upload: database refusals map to statuses", async () => {
  for (
    const [code, status] of [
      ["slug_taken", 409],
      ["kind_mismatch", 409],
      ["listing_suspended", 409],
      ["submission_rate_limited", 429],
      ["review_required", 403],
      ["http_connectors_disabled", 403],
      ["invalid_category", 400],
      ["unknown_agent", 400],
    ] as const
  ) {
    const { call } = harness({ submit: () => Promise.resolve({ error: code }) });
    const r = await call("upload", zipReq());
    assertEquals(r.status, status, code);
    assertEquals((await bodyOf(r)).error, code);
  }
  assertEquals(statusFor("something_new"), 400);
});

Deno.test("upload: a precheck refusal stores nothing and never reaches submit", async () => {
  for (
    const code of [
      "submission_rate_limited",
      "slug_taken",
      "kind_mismatch",
      "listing_suspended",
      "review_required",
      "review_skip_not_allowed",
      "restricted_disabled",
      "agent_restriction_not_allowed",
      "unknown_agent",
      "http_connectors_disabled",
      "script_connectors_disabled",
      "bundles_disabled",
    ]
  ) {
    const { call, log } = harness({ precheck: () => Promise.resolve({ error: code }) });
    const r = await call("upload", zipReq());
    assertEquals((await bodyOf(r)).error, code);
    assertEquals(log.put, [], code);
    assertEquals(log.submit, [], code);
    assertEquals(log.remove, [], code);
  }
});

Deno.test("upload: a refused submit removes the object this request created, and only that", async () => {
  const refuse = () => Promise.resolve({ error: "submission_rate_limited" });
  const mine = harness({ submit: refuse });
  assertEquals((await mine.call("upload", zipReq())).status, 429);
  assertEquals(mine.log.remove, mine.log.put);
  assertEquals(mine.log.remove.length, 1);
  // The object was already there (an earlier upload of the same bytes): keep it.
  const kept = harness({ submit: refuse, put: () => Promise.resolve("exists") });
  assertEquals((await kept.call("upload", zipReq())).status, 429);
  assertEquals(kept.log.remove, []);
  // A failed cleanup does not change the answer.
  const broken = harness({
    submit: refuse,
    remove: () => Promise.reject(new Error("storage down")),
  });
  assertEquals((await broken.call("upload", zipReq())).status, 429);
  // An accepted submit keeps its object.
  const ok = harness();
  await ok.call("upload", zipReq());
  assertEquals(ok.log.remove, []);
});

Deno.test("upload: a backend failure is a 502 that leaks nothing", async () => {
  const { call } = harness({
    put: () => Promise.reject(new Error("secret stack detail /etc/passwd")),
  });
  const r = await call("upload", zipReq());
  assertEquals(r.status, 502);
  const text = JSON.stringify(await bodyOf(r));
  assertFalse(text.includes("secret"));
  assertFalse(text.includes("passwd"));
});

Deno.test("download: passes the user's JWT, adds a signed url, maps refusals", async () => {
  const { call, log } = harness();
  const r = await call("download", json({ slug: "my-skill", agent: "com.acme.agent" }));
  assertEquals(r.status, 200);
  const b = await bodyOf(r);
  assertEquals(b.url, "https://signed.example/x?token=t");
  assertEquals(b.slug, "my-skill");
  assertEquals(log.install, [["good", "my-skill", "com.acme.agent"]]);
  assertEquals(log.sign, ["bundles/" + "a".repeat(64) + ".zip"]);

  for (
    const [code, status] of [["agent_not_allowed", 403], ["not_found", 404], [
      "bundles_disabled",
      403,
    ], ["script_connectors_disabled", 403]] as const
  ) {
    const h = harness({ install: () => Promise.resolve({ error: code }) });
    const x = await h.call("download", json({ slug: "my-skill", agent: "a" }));
    assertEquals(x.status, status, code);
    assertEquals((await bodyOf(x)).error, code);
    assertEquals(h.log.sign.length, 0);
  }
});

Deno.test("download: no bundle path means no url; bad input; unauthorized from the database", async () => {
  const legacy = harness({
    install: () => Promise.resolve({ slug: "x-skill", kind: "skill", content: { markdown: "m" } }),
  });
  const b = await bodyOf(await legacy.call("download", json({ slug: "x-skill", agent: null })));
  assertFalse("url" in b);
  const { call } = harness();
  assertEquals((await call("download", json({ slug: "Bad Slug", agent: null }))).status, 400);
  assertEquals((await call("download", json({ agent: null }))).status, 400);
  assertEquals((await call("download", { body: "{nope" })).status, 400);
  assertEquals((await call("download", json({ slug: "ok-slug", agent: 5 }))).status, 400);
  const gone = harness({ install: () => Promise.reject(new Unauthorized()) });
  assertEquals((await gone.call("download", json({ slug: "ok-slug", agent: null }))).status, 401);
});

Deno.test("review: a signed url for the creator or a platform reader, else 403", async () => {
  const { call, log } = harness();
  const r = await call("review", json({ version_id: VID }));
  assertEquals(r.status, 200);
  assertEquals((await bodyOf(r)).url, "https://signed.example/x?token=t");
  assertEquals(log.access, [[U, VID]]);
  const denied = harness({ access: () => Promise.resolve(null) });
  const d = await denied.call("review", json({ version_id: VID }));
  assertEquals(d.status, 403);
  assertEquals(denied.log.sign.length, 0);
  assertEquals((await call("review", json({ version_id: "nope" }))).status, 400);
});
