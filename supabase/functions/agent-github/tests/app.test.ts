import { assert, assertEquals, assertThrows } from "@std/assert";
import { strToU8, unzipSync, zipSync } from "fflate";
import { createApp } from "../app.ts";
import type { BuildInsert, BuildRow, Db, RepoRow } from "../db.ts";
import { DbError } from "../db.ts";
import { checkEngineJar, EngineCheckError, sha256Hex } from "../engine.ts";

const ORG = "0a000000-0000-4000-8000-000000000001";
const AGENT = "0b000000-0000-4000-8000-000000000002";
const OTHER_AGENT = "0b000000-0000-4000-8000-000000000009";
const ADMIN = "0c000000-0000-4000-8000-000000000003";
const EDITOR = "0c000000-0000-4000-8000-000000000004";
const OUTSIDER = "0c000000-0000-4000-8000-000000000005";
const VIEWER = "0c000000-0000-4000-8000-000000000006";
const UPLOAD = "0e000000-0000-4000-8000-000000000001";
const SHA = "a".repeat(40);
const OTHER_SHA = "b".repeat(40);

const PERMS: Record<string, string[]> = {
  [ADMIN]: ["agents.view", "agents.edit", "agents.publish", "agents.evolve"],
  [EDITOR]: ["agents.view", "agents.edit", "agents.evolve"],
  [VIEWER]: ["agents.view"],
  [OUTSIDER]: [],
};

function engineJar(commit = SHA, pluginId = "ai.rever.boss.plugin.dynamic.max", template = true): Uint8Array {
  const files: Record<string, Uint8Array> = {
    "META-INF/boss-plugin/plugin.json": strToU8(JSON.stringify({ pluginId, version: "1.2.33" })),
    "META-INF/max-source.properties": strToU8(`version=1.2.33\ncommit=${commit}\n`),
  };
  if (template) {
    files["META-INF/agent-adapter-template.jar"] = zipSync({
      "META-INF/max-agent-capabilities.json": strToU8(JSON.stringify({ capabilities: ["repo-engine-pin-v1"] })),
    });
  }
  return zipSync(files);
}

function world() {
  const state = {
    repo: null as RepoRow | null,
    otherRepoIds: new Set<number>([7777]),
    builds: [] as BuildRow[],
    objects: new Map<string, Uint8Array>(),
    removed: [] as string[],
    signed: [] as string[],
  };
  const db: Db = {
    user: (jwt) => Promise.resolve(jwt.startsWith("jwt-") ? jwt.slice(4) : null),
    can: (u, o, p) => Promise.resolve(o === ORG && (PERMS[u] ?? []).includes(p)),
    agent: (id) => Promise.resolve(id === AGENT || id === OTHER_AGENT ? { id, org_id: ORG, slug: "acme", name: "Acme" } : null),
    repo: (id) => Promise.resolve(id === AGENT ? state.repo : null),
    upsertRepo: (r) => {
      if (state.otherRepoIds.has(r.repo_id)) return Promise.resolve("taken" as const);
      state.repo = { ...r, detail: null };
      return Promise.resolve("ok" as const);
    },
    builds: () => Promise.resolve(state.builds),
    build: (id) => Promise.resolve(state.builds.find((b) => b.id === id) ?? null),
    buildBySha: (_a, sha) => Promise.resolve(state.builds.find((b) => b.jar_sha256 === sha) ?? null),
    insertBuild: (b: BuildInsert) => {
      const { imported_by: _by, ...rest } = b;
      const row: BuildRow = {
        ...rest, run_id: null, run_url: null, id: `0d000000-0000-4000-8000-00000000000${state.builds.length + 1}`,
        imported_at: new Date().toISOString(),
      };
      state.builds.push(row);
      return Promise.resolve(row);
    },
    signedUploadUrl: (p) => {
      state.signed.push(p);
      return Promise.resolve(`https://sb.test/storage/v1/object/upload/sign/agent-engines/${p}?token=t`);
    },
    getJar: (p, max) => {
      const j = state.objects.get(p);
      if (j && j.length > max) return Promise.reject(new DbError("jar read", 413));
      return Promise.resolve(j ?? null);
    },
    putJar: (p, j) => { state.objects.set(p, j); return Promise.resolve(); },
    removeJar: (p) => { state.objects.delete(p); state.removed.push(p); return Promise.resolve(); },
    signedJarUrl: (p) => Promise.resolve(`https://sb.test/storage/v1/object/sign/agent-engines/${p}?token=t`),
  };
  const app = createApp({ db, random: () => UPLOAD });
  const post = async (path: string, body: unknown, user: string | null = ADMIN, method = "POST") => {
    const r = await app(new Request(`https://sb.test/functions/v1/agent-github${path}`, {
      method, headers: { ...(user ? { Authorization: `Bearer jwt-${user}` } : {}), "Content-Type": "application/json" },
      body: method === "GET" ? undefined : JSON.stringify(body),
    }));
    return { status: r.status, body: await r.json() as Record<string, unknown> };
  };
  const link = (o: Record<string, unknown> = {}, user = EDITOR) =>
    post("/repos/link", { agent_id: AGENT, repo: "acme-inc/acme-max", repo_id: 4242, base_version: "1.2.33", main_protected: true, ...o }, user);
  /** What Max does: upload URL, PUT the bytes, import. */
  const upload = async (jar: Uint8Array, o: Record<string, unknown> = {}) => {
    const sha = await sha256Hex(jar);
    const u = await post("/builds/upload", { agent_id: AGENT, sha256: sha, size: jar.length });
    if (u.status !== 200 || !u.body.upload_id) return { upload: u, imported: null };
    state.objects.set(`${ORG}/${AGENT}/incoming/${u.body.upload_id}.jar`, jar);
    const i = await post("/builds/import", { agent_id: AGENT, upload_id: u.body.upload_id, commit: SHA, sha256: sha, message: "Add a banner", ...o });
    return { upload: u, imported: i };
  };
  return { state, post, link, upload };
}

Deno.test("requests without a session, unknown routes and GETs are refused", async () => {
  const w = world();
  assertEquals((await w.post("/repos/link", {}, null)).status, 401);
  assertEquals((await w.post("/nope", {})).status, 404);
  assertEquals((await w.post("/builds", {}, ADMIN, "GET")).status, 405);
  // The GitHub App routes are gone.
  for (const p of ["/connect/start", "/connection", "/token", "/repos/finalize", "/repos"]) assertEquals((await w.post(p, {})).status, 404, p);
});

Deno.test("link: agents.evolve records the repo Max created; outsiders see 404, viewers 403", async () => {
  const w = world();
  assertEquals((await w.link({}, OUTSIDER)).status, 404);
  assertEquals((await w.link({}, VIEWER)).status, 403);
  const r = await w.link();
  assertEquals(r.status, 200);
  assertEquals(r.body, {
    repo: "acme-inc/acme-max", url: "https://github.com/acme-inc/acme-max", state: "ready", main_protected: true, base_version: "1.2.33",
  });
  assertEquals(w.state.repo?.repo_id, 4242);
  // Re-linking the same repository refreshes it.
  assertEquals((await w.link({ main_protected: false })).status, 200);
  assertEquals(w.state.repo?.main_protected, false);
});

Deno.test("link: malformed repos, ids and versions are refused; another agent's repo is refused", async () => {
  const w = world();
  for (const repo of ["acme", "a/b/c", "-x/y", "acme/..", "acme/x.git", "acme/has space", 7]) {
    assertEquals((await w.link({ repo })).status, 400, String(repo));
  }
  assertEquals((await w.link({ repo_id: 0 })).body.error, "invalid_repo_id");
  assertEquals((await w.link({ repo_id: "4242" })).body.error, "invalid_repo_id");
  assertEquals((await w.link({ base_version: "1.2" })).body.error, "invalid_base_version");
  assertEquals((await w.link({ repo_id: 7777 })).body.error, "repo_linked_elsewhere");
});

Deno.test("link: a different repository is refused once builds exist", async () => {
  const w = world();
  await w.link();
  const { imported } = await w.upload(engineJar());
  assertEquals(imported?.status, 201);
  const r = await w.link({ repo: "acme-inc/other", repo_id: 5555 });
  assertEquals(r.status, 409);
  assertEquals(r.body.error, "repo_exists");
});

Deno.test("authorize: needs agents.evolve and a linked repo; returns only the repo", async () => {
  const w = world();
  assertEquals((await w.post("/authorize", { agent_id: AGENT })).body.error, "no_repo");
  await w.link();
  assertEquals((await w.post("/authorize", { agent_id: AGENT }, VIEWER)).status, 403);
  const r = await w.post("/authorize", { agent_id: AGENT }, EDITOR);
  assertEquals(r.status, 200);
  assertEquals(r.body.repo, "acme-inc/acme-max");
  assert(!JSON.stringify(r.body).includes("token"));
  w.state.repo!.state = "error";
  assertEquals((await w.post("/authorize", { agent_id: AGENT })).body.error, "repo_not_ready");
});

Deno.test("import: upload URL, verify checksum and commit, store by sha256, remove the incoming object", async () => {
  const w = world();
  await w.link();
  const jar = engineJar();
  const sha = await sha256Hex(jar);
  const { upload, imported } = await w.upload(jar);
  assertEquals(upload.status, 200);
  assertEquals(w.state.signed, [`${ORG}/${AGENT}/incoming/${UPLOAD}.jar`]);
  assertEquals(imported?.status, 201);
  const b = imported!.body.build as BuildRow;
  assertEquals(b.commit_sha, SHA);
  assertEquals(b.jar_sha256, sha);
  assertEquals(b.source, "max");
  assertEquals(b.repo, "acme-inc/acme-max");
  assertEquals(b.engine_version, "1.2.33");
  assertEquals(b.storage_path, `${ORG}/${AGENT}/${sha}.jar`);
  assert(w.state.objects.has(b.storage_path));
  assert(!w.state.objects.has(`${ORG}/${AGENT}/incoming/${UPLOAD}.jar`));
  // The same jar again: no second upload, the existing build.
  const again = await w.post("/builds/upload", { agent_id: AGENT, sha256: sha, size: jar.length });
  assertEquals(again.body.upload_id, null);
  assertEquals((again.body.build as BuildRow).id, b.id);
  const url = await w.post("/builds/url", { build_id: b.id });
  assertEquals(url.body.sha256, sha);
});

Deno.test("import: a jar whose bytes differ from the claimed sha256 is refused and discarded", async () => {
  const w = world();
  await w.link();
  const jar = engineJar();
  const sha = await sha256Hex(jar);
  await w.post("/builds/upload", { agent_id: AGENT, sha256: sha, size: jar.length });
  const tampered = engineJar(OTHER_SHA);
  w.state.objects.set(`${ORG}/${AGENT}/incoming/${UPLOAD}.jar`, tampered);
  const r = await w.post("/builds/import", { agent_id: AGENT, upload_id: UPLOAD, commit: SHA, sha256: sha });
  assertEquals(r.status, 422);
  assertEquals(r.body.error, "checksum_mismatch");
  assertEquals(w.state.builds.length, 0);
  assertEquals(w.state.objects.size, 0);
});

Deno.test("import: wrong commit, non-Max jar, old adapter and garbage are refused", async () => {
  const cases: [Uint8Array, string][] = [
    [engineJar(OTHER_SHA), "claimed commit"],
    [engineJar(SHA, "ai.rever.boss.plugin.dynamic.agent.x"), "not a Max engine"],
    [engineJar(SHA, undefined, false), "adapter template"],
    [strToU8("not a zip"), "not a jar"],
  ];
  for (const [jar, needle] of cases) {
    const w = world();
    await w.link();
    const { imported } = await w.upload(jar);
    assertEquals(imported?.status, 422, needle);
    assert(String(imported?.body.detail).includes(needle), String(imported?.body.detail));
    assertEquals(w.state.builds.length, 0);
    assertEquals(w.state.objects.size, 0);
  }
});

Deno.test("import: permissions, missing uploads, no repo, size limits and malformed input", async () => {
  const w = world();
  const jar = engineJar();
  const sha = await sha256Hex(jar);
  assertEquals((await w.post("/builds/upload", { agent_id: AGENT, sha256: sha, size: 10 })).body.error, "no_repo");
  await w.link();
  // agents.evolve alone cannot import (publishing material); agents.publish can.
  assertEquals((await w.post("/builds/upload", { agent_id: AGENT, sha256: sha, size: 10 }, EDITOR)).status, 403);
  assertEquals((await w.post("/builds/import", { agent_id: AGENT, upload_id: UPLOAD, commit: SHA, sha256: sha }, EDITOR)).status, 403);
  assertEquals((await w.post("/builds/upload", { agent_id: AGENT, sha256: sha, size: 101 * 1024 * 1024 })).body.error, "engine_too_large");
  assertEquals((await w.post("/builds/upload", { agent_id: AGENT, sha256: "x", size: 10 })).body.error, "invalid_sha256");
  assertEquals((await w.post("/builds/upload", { agent_id: AGENT, sha256: sha, size: -1 })).body.error, "invalid_size");
  assertEquals((await w.post("/builds/import", { agent_id: AGENT, upload_id: UPLOAD, commit: SHA, sha256: sha })).body.error, "upload_missing");
  assertEquals((await w.post("/builds/import", { agent_id: AGENT, upload_id: "../x", commit: SHA, sha256: sha })).body.error, "invalid_upload_id");
  assertEquals((await w.post("/builds/import", { agent_id: AGENT, upload_id: UPLOAD, commit: "abc", sha256: sha })).body.error, "invalid_commit");
  w.state.objects.set(`${ORG}/${AGENT}/incoming/${UPLOAD}.jar`, new Uint8Array(101 * 1024 * 1024));
  assertEquals((await w.post("/builds/import", { agent_id: AGENT, upload_id: UPLOAD, commit: SHA, sha256: sha })).status, 413);
  assertEquals((await w.post("/builds/url", { build_id: "0d000000-0000-4000-8000-000000000099" })).body.error, "build_not_found");
});

Deno.test("builds: viewers list the repo and imported builds but cannot fetch jars", async () => {
  const w = world();
  assertEquals((await w.post("/builds", { agent_id: AGENT }, VIEWER)).body, { repo: null, imported: [] });
  await w.link();
  await w.upload(engineJar());
  const r = await w.post("/builds", { agent_id: AGENT }, VIEWER);
  assertEquals((r.body.repo as Record<string, unknown>).repo, "acme-inc/acme-max");
  assertEquals((r.body.imported as unknown[]).length, 1);
  assertEquals((await w.post("/builds/url", { build_id: (r.body.imported as BuildRow[])[0].id }, VIEWER)).status, 403);
});

Deno.test("engine jar checks", () => {
  assertEquals(checkEngineJar(engineJar(), SHA).version, "1.2.33");
  assertThrows(() => checkEngineJar(engineJar(SHA, "ai.rever.boss.plugin.dynamic.agent.x"), SHA), EngineCheckError, "not a Max engine");
  assertThrows(() => checkEngineJar(engineJar(SHA, undefined, false), SHA), EngineCheckError, "adapter template");
  assertThrows(() => checkEngineJar(strToU8("nope"), SHA), EngineCheckError);
});

Deno.test("old engine adapters without pin enforcement cannot be imported", () => {
  const files = unzipSync(engineJar());
  files["META-INF/agent-adapter-template.jar"] = zipSync({
    "META-INF/max-agent-capabilities.json": strToU8(JSON.stringify({ capabilities: [] })),
  });
  const e = assertThrows(() => checkEngineJar(zipSync(files), SHA), EngineCheckError);
  assert(e.message.includes("newer Max"));
});
