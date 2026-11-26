import { type Db, DbError, restDb } from "./db.ts";
import { checkEngineJar, EngineCheckError, MAX_ENGINE_BYTES, sha256Hex } from "./engine.ts";

/**
 * agent-github: agent engine repos and their builds (design: optimist docs/custom-max-agents.md).
 *
 * There is no GitHub App and no GitHub credential here. The person's own Max creates and seeds the repo with
 * their own GitHub sign-in (gh), links it, and later builds a commit of main from a clean checkout and uploads
 * the jar. The platform decides every call against user_has_org_permission with the caller's BOSS JWT,
 * verifies each uploaded jar (Max plugin id, the claimed commit packed in its source, a pin-enforcing adapter
 * template) and stores it by SHA-256. Publishing pins that SHA-256; the provisioner and host enforce it.
 */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const OWNER = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/;
const REPO_NAME = /^[A-Za-z0-9._-]{1,100}$/;
const SEMVER = /^\d+\.\d+\.\d+$/;
const SHA1 = /^[0-9a-f]{40}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const MAX_BODY = 4096;
const SIGNED_URL_SECONDS = 600;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
};

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...CORS } });
}

class HttpError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
const fail = (status: number, code: string): never => { throw new HttpError(status, code); };

export interface AppOptions {
  db?: Db;
  random?: () => string;
}

export function createApp(options: AppOptions = {}) {
  const db = options.db ?? restDb((k) => Deno.env.get(k) ?? "");
  const uploadId = options.random ?? (() => crypto.randomUUID());

  async function body(req: Request): Promise<Record<string, unknown>> {
    const text = await req.text();
    if (text.length > MAX_BODY) fail(413, "too_large");
    try {
      const o = JSON.parse(text || "{}");
      if (!o || typeof o !== "object" || Array.isArray(o)) fail(400, "invalid_request");
      return o;
    } catch (e) {
      if (e instanceof HttpError) throw e;
      return fail(400, "invalid_request");
    }
  }
  const uuidOf = (o: Record<string, unknown>, k: string) => {
    const v = o[k];
    return typeof v === "string" && UUID.test(v) ? v.toLowerCase() : fail(400, `invalid_${k}`);
  };
  const match = (o: Record<string, unknown>, k: string, re: RegExp) => {
    const v = o[k];
    return typeof v === "string" && re.test(v) ? v : fail(400, `invalid_${k}`);
  };

  async function caller(req: Request): Promise<string> {
    const auth = req.headers.get("Authorization") ?? "";
    const jwt = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
    if (!jwt) fail(401, "unauthorized");
    const id = await db.user(jwt);
    return id ?? fail(401, "unauthorized");
  }
  /** The agent, with the caller holding `perms` in its org; an agent they cannot see is 404. */
  async function agentFor(userId: string, agentId: string, ...perms: string[]) {
    const a = await db.agent(agentId);
    if (!a || !(await db.can(userId, a.org_id, "agents.view"))) fail(404, "agent_not_found");
    for (const p of perms) if (!(await db.can(userId, a!.org_id, p))) fail(403, "permission_denied");
    return a!;
  }
  async function readyRepo(agentId: string) {
    const r = await db.repo(agentId) ?? fail(404, "no_repo");
    if (r.state !== "ready") fail(409, "repo_not_ready");
    return r;
  }
  const incoming = (orgId: string, agentId: string, id: string) => `${orgId}/${agentId}/incoming/${id}.jar`;
  const repoView = (r: { repo_owner: string; repo_name: string; state: string; main_protected: boolean; base_version: string | null }) => ({
    repo: `${r.repo_owner}/${r.repo_name}`, url: `https://github.com/${r.repo_owner}/${r.repo_name}`, state: r.state,
    main_protected: r.main_protected, base_version: r.base_version,
  });

  const routes: Record<string, (req: Request, user: string) => Promise<Response>> = {
    // After the person's Max created the repo with their own gh and pushed stock and main.
    async "/repos/link"(req, user) {
      const o = await body(req);
      const a = await agentFor(user, uuidOf(o, "agent_id"), "agents.evolve");
      // Max asks first, before it creates anything on GitHub.
      if (o.check === true) {
        const have = await db.repo(a.id);
        if (have && (await db.builds(a.id)).length > 0) fail(409, "repo_exists");
        return json(200, { ok: true, repo: have ? repoView(have) : null });
      }
      const [owner, name] = match(o, "repo", /^[^/]+\/[^/]+$/).split("/");
      if (!OWNER.test(owner) || !REPO_NAME.test(name) || name === "." || name === ".." || name.endsWith(".git")) fail(400, "invalid_repo");
      const repoId = typeof o.repo_id === "number" && Number.isSafeInteger(o.repo_id) && o.repo_id > 0 ? o.repo_id : fail(400, "invalid_repo_id");
      const base = match(o, "base_version", SEMVER);
      const mainProtected = o.main_protected === true;
      const have = await db.repo(a.id);
      // One repo per agent: re-linking the same repository refreshes it, a different one is refused once builds exist.
      if (have && have.repo_id !== repoId && (await db.builds(a.id)).length > 0) fail(409, "repo_exists");
      const r = {
        agent_id: a.id, org_id: a.org_id, repo_owner: owner, repo_name: name, repo_id: repoId, state: "ready" as const,
        main_protected: mainProtected, base_version: base,
      };
      if (await db.upsertRepo(r, user) === "taken") fail(409, "repo_linked_elsewhere");
      return json(200, repoView(r));
    },
    // Fresh authorization before each of Max's repo operations: the repo it may work on, nothing else.
    async "/authorize"(req, user) {
      const o = await body(req);
      const a = await agentFor(user, uuidOf(o, "agent_id"), "agents.evolve");
      return json(200, repoView(await readyRepo(a.id)));
    },
    async "/builds"(req, user) {
      const o = await body(req);
      const a = await agentFor(user, uuidOf(o, "agent_id"));
      const r = await db.repo(a.id);
      return json(200, { repo: r ? repoView(r) : null, imported: await db.builds(a.id) });
    },
    // Step 1 of an import: a one-time upload URL for the jar Max built from a commit on main.
    async "/builds/upload"(req, user) {
      const o = await body(req);
      const a = await agentFor(user, uuidOf(o, "agent_id"), "agents.publish");
      await readyRepo(a.id);
      const sha = match(o, "sha256", SHA256);
      const size = typeof o.size === "number" && Number.isSafeInteger(o.size) && o.size > 0 ? o.size : fail(400, "invalid_size");
      if (size > MAX_ENGINE_BYTES) fail(413, "engine_too_large");
      const done = await db.buildBySha(a.id, sha);
      if (done) return json(200, { build: done, upload_id: null, url: null });
      const id = uploadId();
      return json(200, { upload_id: id, url: await db.signedUploadUrl(incoming(a.org_id, a.id, id)) });
    },
    // Step 2: verify what arrived and record the build. Idempotent per jar SHA-256.
    async "/builds/import"(req, user) {
      const o = await body(req);
      const a = await agentFor(user, uuidOf(o, "agent_id"), "agents.publish");
      const r = await readyRepo(a.id);
      const id = uuidOf(o, "upload_id");
      const commit = match(o, "commit", SHA1);
      const claimed = match(o, "sha256", SHA256);
      const message = typeof o.message === "string" ? o.message.replace(/\p{Cc}/gu, " ").slice(0, 200) : "";
      const done = await db.buildBySha(a.id, claimed);
      if (done) return json(200, { build: done, imported: false });
      const path = incoming(a.org_id, a.id, id);
      const jar = await db.getJar(path, MAX_ENGINE_BYTES) ?? fail(404, "upload_missing");
      try {
        const sha = await sha256Hex(jar);
        if (sha !== claimed) return json(422, { error: "checksum_mismatch" });
        let facts;
        try {
          facts = checkEngineJar(jar, commit);
        } catch (e) {
          if (e instanceof EngineCheckError) return json(422, { error: "engine_check_failed", detail: e.message });
          throw e;
        }
        const final = `${a.org_id}/${a.id}/${sha}.jar`;
        await db.putJar(final, jar);
        const build = await db.insertBuild({
          agent_id: a.id, org_id: a.org_id, repo: `${r.repo_owner}/${r.repo_name}`, commit_sha: commit, source: "max",
          engine_version: facts.version, message, jar_sha256: sha, jar_size: jar.length, storage_path: final, imported_by: user,
        });
        return json(201, { build, imported: true });
      } finally {
        await db.removeJar(path);
      }
    },
    // A short-lived URL of an imported engine jar, for the console's publish (it bundles the jar into the adapter).
    async "/builds/url"(req, user) {
      const o = await body(req);
      const b = await db.build(uuidOf(o, "build_id")) ?? fail(404, "build_not_found");
      await agentFor(user, b.agent_id, "agents.publish");
      return json(200, { url: await db.signedJarUrl(b.storage_path, SIGNED_URL_SECONDS), sha256: b.jar_sha256, size: b.jar_size });
    },
  };

  return async (req: Request): Promise<Response> => {
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const url = new URL(req.url);
    const path = url.pathname.replace(/^.*\/agent-github/, "") || "/";
    try {
      const handler = Object.hasOwn(routes, path) ? routes[path] : undefined;
      if (!handler) return json(404, { error: "not_found" });
      if (req.method !== "POST") return json(405, { error: "method_not_allowed" });
      const user = await caller(req);
      return await handler(req, user);
    } catch (e) {
      if (e instanceof HttpError) return json(e.status, { error: e.code });
      if (e instanceof DbError) return json(e.status === 413 ? 413 : 503, { error: e.status === 413 ? "engine_too_large" : "unavailable" });
      console.error("agent-github", e instanceof Error ? e.name : "error");
      return json(500, { error: "internal" });
    }
  };
}
