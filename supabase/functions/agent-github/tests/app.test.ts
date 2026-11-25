import { assert, assertEquals, assertMatch, assertThrows } from "@std/assert";
import { strToU8, zipSync } from "fflate";
import { createApp, EVOLVE_PERMISSIONS } from "../app.ts";
import type { BuildInsert, BuildRow, Connection, Db, RepoRow } from "../db.ts";
import { checkEngineJar, EngineCheckError, WORKFLOW_PATH, WORKFLOW_YAML } from "../engine.ts";
import { appJwt, type GitHubApp, importAppKey, localStack, pkcs1ToPkcs8, type TokenRequest, type WorkflowRun } from "../github.ts";

const ORG = "0a000000-0000-4000-8000-000000000001";
const AGENT = "0b000000-0000-4000-8000-000000000002";
const ADMIN = "0c000000-0000-4000-8000-000000000003";
const EDITOR = "0c000000-0000-4000-8000-000000000004";
const OUTSIDER = "0c000000-0000-4000-8000-000000000005";
const SHA = "a".repeat(40);
const OTHER_SHA = "b".repeat(40);

const PERMS: Record<string, string[]> = {
  [ADMIN]: ["agents.view", "agents.edit", "agents.publish", "agents.evolve", "agents.github"],
  [EDITOR]: ["agents.view", "agents.edit", "agents.evolve"],
  [OUTSIDER]: [],
};

function engineJar(commit = SHA, pluginId = "ai.rever.boss.plugin.dynamic.max", template = true): Uint8Array {
  const files: Record<string, Uint8Array> = {
    "META-INF/boss-plugin/plugin.json": strToU8(JSON.stringify({ pluginId, version: "1.2.33" })),
    "META-INF/max-source.properties": strToU8(`version=1.2.33\ncommit=${commit}\n`),
  };
  if (template) files["META-INF/agent-adapter-template.jar"] = zipSync({ "META-INF/max-agent-capabilities.json": strToU8(JSON.stringify({capabilities:["repo-engine-pin-v1"]})) });
  return zipSync(files);
}

function world() {
  const state = {
    connection: null as Connection | null,
    states: new Map<string, { org_id: string; user_id: string; expires_at: string }>(),
    repo: null as RepoRow | null,
    builds: [] as BuildRow[],
    jars: new Map<string, Uint8Array>(),
    tokens: [] as { installationId: number; req: TokenRequest }[],
    files: new Map<string, string>(),
    protected: [] as string[],
    branches: new Set<string>(),
    runs: new Map<number, WorkflowRun>(),
    onMain: new Set<string>([SHA]),
    artifact: zipSync({ "engine.jar": engineJar(), "engine.json": strToU8("{}") }) as Uint8Array,
    installationType: "Organization",
  };
  const db: Db = {
    user: (jwt) => Promise.resolve(jwt.startsWith("jwt-") ? jwt.slice(4) : null),
    can: (u, o, p) => Promise.resolve(o === ORG && (PERMS[u] ?? []).includes(p)),
    agent: (id) => Promise.resolve(id === AGENT ? { id, org_id: ORG, slug: "acme", name: "Acme" } : null),
    connection: () => Promise.resolve(state.connection),
    upsertConnection: (c) => {
      if (c.installation_id === 666) return Promise.resolve("taken" as const);
      state.connection = { ...c, status: "active" };
      return Promise.resolve("ok" as const);
    },
    setConnectionStatus: (_o, s) => { if (state.connection) state.connection.status = s; return Promise.resolve(); },
    addState: (n, o, u, e) => { state.states.set(n, { org_id: o, user_id: u, expires_at: e }); return Promise.resolve(); },
    takeState: (n) => { const s = state.states.get(n) ?? null; state.states.delete(n); return Promise.resolve(s); },
    repo: () => Promise.resolve(state.repo),
    insertRepo: (r) => {
      if (state.repo) return Promise.resolve("taken" as const);
      state.repo = { ...r, base_version: null, detail: null };
      return Promise.resolve("ok" as const);
    },
    updateRepo: (_a, p) => { Object.assign(state.repo!, p); return Promise.resolve(); },
    builds: () => Promise.resolve(state.builds),
    build: (id) => Promise.resolve(state.builds.find((b) => b.id === id) ?? null),
    buildByRun: (_a, run) => Promise.resolve(state.builds.find((b) => b.run_id === run) ?? null),
    insertBuild: (b: BuildInsert) => {
      const have = state.builds.find((x) => x.jar_sha256 === b.jar_sha256);
      if (have) return Promise.resolve(have);
      const { imported_by: _by, ...rest } = b;
      const row: BuildRow = { ...rest, id: `0d000000-0000-4000-8000-00000000000${state.builds.length + 1}`, imported_at: new Date().toISOString() };
      state.builds.push(row);
      return Promise.resolve(row);
    },
    putJar: (p, j) => { state.jars.set(p, j); return Promise.resolve(); },
    signedJarUrl: (p) => Promise.resolve(`https://sb.test/storage/v1/object/sign/agent-engines/${p}?token=t`),
  };
  const gh: GitHubApp = {
    testMode: false,
    verifyInstaller: (code) => Promise.resolve(code === "valid-code"),
    installUrl: (s) => `https://github.com/apps/boss-agents/installations/new?state=${s}`,
    installation: (id) => Promise.resolve(id === 404 ? null : { id, account: { login: "acme-inc", id: 77, type: state.installationType } }),
    token: (installationId, req) => { state.tokens.push({ installationId, req }); return Promise.resolve({ token: `ghs_${state.tokens.length}`, expiresAt: "2026-11-25T16:00:00Z" }); },
    createRepo: (_t, account, name) => {
      if (name === "taken") return Promise.reject(Object.assign(new Error("x"), { name: "GitHubError" }));
      return Promise.resolve({ id: 4242, name, owner: account.login, private: true, defaultBranch: "main", htmlUrl: `https://github.com/${account.login}/${name}`, cloneUrl: "" });
    },
    repo: () => Promise.resolve(null),
    setDefaultBranch: () => Promise.resolve(),
    branchSha: (_t, _o, _n, b) => Promise.resolve(state.branches.has(b) ? SHA : null),
    putFile: (_t, _o, _n, path, content) => { state.files.set(path, content); return Promise.resolve(); },
    protect: (_t, _o, _n, b) => { state.protected.push(b); return Promise.resolve(); },
    runs: () => Promise.resolve([...state.runs.values()]),
    run: (_t, _o, _n, id) => Promise.resolve(state.runs.get(id) ?? null),
    onBranch: (_t, _o, _n, sha) => Promise.resolve(state.onMain.has(sha)),
    artifacts: (_t, _o, _n, runId) => Promise.resolve(state.runs.has(runId) ? [{ id: 1, name: "boss-engine", sizeInBytes: 100, expired: false, archiveDownloadUrl: "https://api.github.com/repos/acme-inc/acme-max/actions/artifacts/1/zip" }] : []),
    download: () => Promise.resolve(state.artifact),
  };
  let t = Date.parse("2026-11-25T12:00:00Z");
  const app = createApp({ db, github: gh, consoleUrl: "https://app.bossconsole.test", now: () => t, random: () => "N".repeat(43) });
  const post = async (path: string, body: unknown, user: string | null = ADMIN) => {
    const r = await app(new Request(`https://sb.test/functions/v1/agent-github${path}`, {
      method: "POST", headers: { ...(user ? { Authorization: `Bearer jwt-${user}` } : {}), "Content-Type": "application/json" }, body: JSON.stringify(body),
    }));
    return { status: r.status, body: await r.json() as Record<string, unknown> };
  };
  const get = (path: string) => app(new Request(`https://sb.test/functions/v1/agent-github${path}`));
  const run = (o: Partial<WorkflowRun> = {}): WorkflowRun => ({
    id: 900, repoId: 4242, path: WORKFLOW_PATH, event: "push", headBranch: "main", headSha: SHA, status: "completed", conclusion: "success",
    htmlUrl: "https://github.com/acme-inc/acme-max/actions/runs/900", message: "Add a banner", createdAt: "2026-11-25T12:00:00Z", ...o,
  });
  return { state, post, get, run, advance: (ms: number) => { t += ms; } };
}

async function connected(w: ReturnType<typeof world>) {
  w.state.connection = { org_id: ORG, installation_id: 55, account_login: "acme-inc", account_id: 77, status: "active" };
}
async function readyRepo(w: ReturnType<typeof world>) {
  await connected(w);
  w.state.repo = { agent_id: AGENT, org_id: ORG, repo_owner: "acme-inc", repo_name: "acme-max", repo_id: 4242, state: "ready", base_version: "1.2.33", detail: null };
}

Deno.test("requests without a session are refused", async () => {
  const w = world();
  assertEquals((await w.post("/connect/start", { org_id: ORG }, null)).status, 401);
  assertEquals((await w.post("/nope", {})).status, 404);
});

Deno.test("connect: only agents.github starts an install; the callback consumes the state once", async () => {
  const w = world();
  assertEquals((await w.post("/connect/start", { org_id: ORG }, EDITOR)).status, 403);
  const s = await w.post("/connect/start", { org_id: ORG });
  assertEquals(s.status, 200);
  assertMatch(String(s.body.url), /installations\/new\?state=N{43}$/);
  const r = await w.get(`/connect/callback?code=valid-code&installation_id=55&setup_action=install&state=${"N".repeat(43)}`);
  assertEquals(r.status, 302);
  assertEquals(r.headers.get("location"), `https://app.bossconsole.test/orgs/${ORG}/github?github=connected`);
  assertEquals(w.state.connection?.account_login, "acme-inc");
  const again = await w.get(`/connect/callback?code=valid-code&installation_id=55&state=${"N".repeat(43)}`);
  assertMatch(again.headers.get("location")!, /reason=state/);
});

Deno.test("connect: expired state, a personal account and an installation already in use are refused", async () => {
  let w = world();
  await w.post("/connect/start", { org_id: ORG });
  w.advance(11 * 60_000);
  assertMatch((await w.get(`/connect/callback?code=valid-code&installation_id=55&state=${"N".repeat(43)}`)).headers.get("location")!, /reason=expired/);
  w = world();
  w.state.installationType = "User";
  await w.post("/connect/start", { org_id: ORG });
  assertMatch((await w.get(`/connect/callback?code=valid-code&installation_id=55&state=${"N".repeat(43)}`)).headers.get("location")!, /reason=not_an_organization/);
  assertEquals(w.state.connection, null);
  w = world();
  await w.post("/connect/start", { org_id: ORG });
  assertMatch((await w.get(`/connect/callback?code=valid-code&installation_id=666&state=${"N".repeat(43)}`)).headers.get("location")!, /reason=installation_in_use/);
});

Deno.test("repos: needs github + evolve, a connection, and one repo per agent", async () => {
  const w = world();
  assertEquals((await w.post("/repos", { agent_id: AGENT, name: "acme-max" }, EDITOR)).status, 403);
  assertEquals((await w.post("/repos", { agent_id: AGENT, name: "acme-max" }, OUTSIDER)).status, 404);
  assertEquals((await w.post("/repos", { agent_id: AGENT, name: "acme-max" })).body.error, "github_not_connected");
  await connected(w);
  assertEquals((await w.post("/repos", { agent_id: AGENT, name: "../x" })).status, 400);
  const r = await w.post("/repos", { agent_id: AGENT, name: "acme-max" });
  assertEquals(r.status, 201);
  assertEquals(r.body.repo, "acme-inc/acme-max");
  assertEquals(w.state.repo?.state, "seeding");
  assertEquals(w.state.tokens[0].req.permissions.administration, "write");
  assertEquals((await w.post("/repos", { agent_id: AGENT, name: "acme-max-2" })).body.error, "repo_exists");
});

Deno.test("token: one repository, no workflows or administration; seed only while seeding, evolve only when ready", async () => {
  const w = world();
  await connected(w);
  await w.post("/repos", { agent_id: AGENT, name: "acme-max" });
  assertEquals((await w.post("/token", { agent_id: AGENT, purpose: "evolve" }, EDITOR)).body.error, "repo_not_ready");
  const seed = await w.post("/token", { agent_id: AGENT, purpose: "seed" }, EDITOR);
  assertEquals(seed.status, 200);
  assertEquals(seed.body.repo, "acme-inc/acme-max");
  const req = w.state.tokens.at(-1)!.req;
  assertEquals(req.repositoryIds, [4242]);
  assertEquals(req.permissions, { ...EVOLVE_PERMISSIONS });
  assert(!("workflows" in req.permissions) && !("administration" in req.permissions));
  assertEquals((await w.post("/token", { agent_id: AGENT, purpose: "seed" }, OUTSIDER)).status, 404);
  w.state.repo!.state = "ready";
  assertEquals((await w.post("/token", { agent_id: AGENT, purpose: "seed" })).body.error, "repo_already_seeded");
  assertEquals((await w.post("/token", { agent_id: AGENT, purpose: "evolve" })).status, 200);
});

Deno.test("finalize: needs both branches, writes the workflow and engine.json, protects main and stock", async () => {
  const w = world();
  await connected(w);
  await w.post("/repos", { agent_id: AGENT, name: "acme-max" });
  assertEquals((await w.post("/repos/finalize", { agent_id: AGENT, base_version: "1.2.33" })).body.error, "not_seeded");
  w.state.branches.add("main");
  w.state.branches.add("stock");
  assertEquals((await w.post("/repos/finalize", { agent_id: AGENT, base_version: "x" })).status, 400);
  const r = await w.post("/repos/finalize", { agent_id: AGENT, base_version: "1.2.33" }, EDITOR);
  assertEquals(r.body.state, "ready");
  assertEquals(w.state.files.get(WORKFLOW_PATH), WORKFLOW_YAML);
  assertEquals(JSON.parse(w.state.files.get(".boss/engine.json")!).agentId, AGENT);
  assertEquals(w.state.protected, ["main", "stock"]);
  assertEquals(w.state.repo?.base_version, "1.2.33");
});

Deno.test("import: only a successful push-to-main run of the platform workflow whose jar is that commit's", async () => {
  const w = world();
  await readyRepo(w);
  const cases: [Partial<WorkflowRun>, string][] = [
    [{ path: ".github/workflows/evil.yml" }, "not_the_engine_workflow"],
    [{ event: "pull_request" }, "not_a_main_build"],
    [{ headBranch: "max/x" }, "not_a_main_build"],
    [{ conclusion: "failure" }, "build_not_successful"],
    [{ headSha: OTHER_SHA }, "commit_not_on_main"],
    [{ repoId: 1 }, "run_not_found"],
  ];
  for (const [o, code] of cases) {
    w.state.runs.set(900, w.run(o));
    assertEquals((await w.post("/builds/import", { agent_id: AGENT, run_id: 900 })).body.error, code, code);
  }
  w.state.runs.set(900, w.run());
  assertEquals((await w.post("/builds/import", { agent_id: AGENT, run_id: 900 }, EDITOR)).status, 403);
  w.state.artifact = zipSync({ "engine.jar": engineJar(OTHER_SHA) });
  const bad = await w.post("/builds/import", { agent_id: AGENT, run_id: 900 });
  assertEquals([bad.status, bad.body.error], [422, "engine_check_failed"]);
  w.state.artifact = zipSync({ "engine.jar": engineJar() });
  const ok = await w.post("/builds/import", { agent_id: AGENT, run_id: 900 });
  assertEquals(ok.status, 201);
  const build = ok.body.build as BuildRow;
  assertEquals([build.commit_sha, build.engine_version, build.message], [SHA, "1.2.33", "Add a banner"]);
  assertMatch(build.storage_path, new RegExp(`^${ORG}/${AGENT}/[0-9a-f]{64}\\.jar$`));
  assert(w.state.jars.has(build.storage_path));
  const again = await w.post("/builds/import", { agent_id: AGENT, run_id: 900 });
  assertEquals([again.status, again.body.imported], [200, false]);
  const url = await w.post("/builds/url", { build_id: build.id });
  assertEquals(url.body.sha256, build.jar_sha256);
  assertEquals((await w.post("/builds/url", { build_id: build.id }, EDITOR)).status, 403);
});

Deno.test("builds: lists push runs with their imported build", async () => {
  const w = world();
  await readyRepo(w);
  w.state.runs.set(900, w.run());
  w.state.runs.set(901, w.run({ id: 901, event: "pull_request" }));
  await w.post("/builds/import", { agent_id: AGENT, run_id: 900 });
  const r = await w.post("/builds", { agent_id: AGENT }, EDITOR);
  const runs = r.body.runs as { run_id: number; build_id: string | null }[];
  assertEquals(runs.map((x) => x.run_id), [900]);
  assert(runs[0].build_id);
});

Deno.test("engine jar checks", () => {
  assertEquals(checkEngineJar(engineJar(), SHA).version, "1.2.33");
  assertThrows(() => checkEngineJar(engineJar(SHA, "ai.rever.boss.plugin.dynamic.agent.x"), SHA), EngineCheckError, "not a Max engine");
  assertThrows(() => checkEngineJar(engineJar(SHA, undefined, false), SHA), EngineCheckError, "adapter template");
  assertThrows(() => checkEngineJar(strToU8("nope"), SHA), EngineCheckError);
});

Deno.test("test mode is only for a local stack", () => {
  assert(localStack("http://127.0.0.1:54321"));
  assert(localStack("http://kong:8000"));
  assert(!localStack("https://api.risaboss.com"));
});

Deno.test("App JWT signs with a PKCS#1 key", async () => {
  const pair = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  // PKCS#8 = 26-byte prefix + PKCS#1 for a 2048-bit key; re-wrapping the inner key must give the same bytes.
  const pkcs1 = pkcs8.slice(26);
  assertEquals(pkcs1ToPkcs8(pkcs1), pkcs8);
  const pem = `-----BEGIN RSA PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs1))}\n-----END RSA PRIVATE KEY-----`;
  const jwt = await appJwt("123", await importAppKey(pem), 1_800_000_000);
  const [h, b, s] = jwt.split(".");
  assertEquals(JSON.parse(atob(b.replace(/-/g, "+").replace(/_/g, "/"))), { iat: 1_799_999_940, exp: 1_800_000_540, iss: "123" });
  const sig = Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "==".slice(0, (4 - s.length % 4) % 4)), (c) => c.charCodeAt(0));
  assert(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", pair.publicKey, sig, new TextEncoder().encode(`${h}.${b}`)));
});

Deno.test("connect refuses a spoofed installation id without installer OAuth proof", async () => {
  const w = world();
  await w.post("/connect/start", {org_id: ORG});
  const r = await w.get(`/connect/callback?installation_id=55&state=${"N".repeat(43)}`);
  assertMatch(r.headers.get("location")!, /reason=installer/);
  assertEquals(w.state.connection, null);
});

Deno.test("local build authorization checks Evolve without minting GitHub credentials", async () => {
  const w = world(); await readyRepo(w);
  assertEquals((await w.post("/authorize", {agent_id:AGENT}, OUTSIDER)).status, 404);
  assertEquals((await w.post("/authorize", {agent_id:AGENT}, EDITOR)).status, 200);
  assertEquals(w.state.tokens.length, 0);
});
