import { type Db, DbError, restDb } from "./db.ts";
import { ARTIFACT_NAME, EngineCheckError, engineFromArtifact, engineJson, MAX_ARTIFACT_BYTES, sha256Hex, WORKFLOW_FILE, WORKFLOW_PATH, WORKFLOW_YAML } from "./engine.ts";
import { type GitHubApp, GitHubError, localStack, restGitHub } from "./github.ts";

/**
 * agent-github: the org's GitHub App connection, agent engine repos and their CI builds (design: optimist
 * docs/custom-max-agents.md). Every POST carries the caller's own BOSS/Supabase JWT and is decided here
 * against user_has_org_permission; writes use the service role. GitHub tokens are minted per call and never
 * stored; only /token hands one out, to an agents.evolve holder, for one repository and one hour.
 */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const REPO_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
const SEMVER = /^\d+\.\d+\.\d+$/;
const MAX_BODY = 4096;
const STATE_TTL_MS = 10 * 60_000;
const SIGNED_URL_SECONDS = 600;

/** Max's token: one repository, push branches and open pull requests; read CI. Never workflows or administration. */
export const EVOLVE_PERMISSIONS = { contents: "write", pull_requests: "write", actions: "read", checks: "read", metadata: "read" } as const;
/** The platform's own: create repos, write the workflow, protect branches. Never leaves this function. */
const ADMIN_PERMISSIONS = { administration: "write", contents: "write", workflows: "write", metadata: "read" } as const;
const READ_PERMISSIONS = { actions: "read", contents: "read", metadata: "read" } as const;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
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
  github?: GitHubApp | null;
  /** The console's origin, where the install callback sends the browser back. */
  consoleUrl?: string;
  now?: () => number;
  random?: () => string;
}

function env(k: string): string {
  return Deno.env.get(k) ?? "";
}

/** The App from the function's secrets; test mode only on a local stack. null: not configured. */
export function githubFromEnv(): GitHubApp | null {
  const testToken = env("AGENT_GITHUB_TEST_MODE") === "1" ? env("AGENT_GITHUB_TEST_TOKEN") : "";
  if (testToken) {
    if (!localStack(env("SUPABASE_URL"))) return null;
    return restGitHub({
      appId: "test", appSlug: "test", privateKeyPem: "", testToken, testAccount: env("AGENT_GITHUB_TEST_ACCOUNT"),
      testAccountType: env("AGENT_GITHUB_TEST_ACCOUNT_TYPE") === "User" ? "User" : "Organization",
      callbackUrl: `${env("AGENT_GITHUB_PUBLIC_URL")}/connect/callback`,
    });
  }
  const appId = env("BOSS_AGENTS_GH_APP_ID");
  const appSlug = env("BOSS_AGENTS_GH_APP_SLUG");
  const key = env("BOSS_AGENTS_GH_APP_PRIVATE_KEY");
  if (!appId || !appSlug || !key || !env("BOSS_AGENTS_GH_APP_CLIENT_ID") || !env("BOSS_AGENTS_GH_APP_CLIENT_SECRET")) return null;
  return restGitHub({ appId, appSlug, privateKeyPem: key.replace(/\\n/g, "\n"), clientId: env("BOSS_AGENTS_GH_APP_CLIENT_ID"), clientSecret: env("BOSS_AGENTS_GH_APP_CLIENT_SECRET") });
}

function randomNonce(): string {
  const b = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function createApp(options: AppOptions = {}) {
  const db = options.db ?? restDb(env);
  const gh = options.github !== undefined ? options.github : githubFromEnv();
  const consoleUrl = (options.consoleUrl ?? env("AGENTS_CONSOLE_URL")).replace(/\/$/, "");
  const now = options.now ?? Date.now;
  const nonce = options.random ?? randomNonce;

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

  async function caller(req: Request): Promise<string> {
    const auth = req.headers.get("Authorization") ?? "";
    const jwt = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
    if (!jwt) fail(401, "unauthorized");
    const id = await db.user(jwt);
    return id ?? fail(401, "unauthorized");
  }
  async function need(userId: string, orgId: string, ...perms: string[]) {
    for (const p of perms) if (!(await db.can(userId, orgId, p))) fail(403, "permission_denied");
  }
  /** The agent, with the caller holding `perms` in its org; an agent they cannot see is 404. */
  async function agentFor(userId: string, agentId: string, ...perms: string[]) {
    const a = await db.agent(agentId);
    if (!a || !(await db.can(userId, a.org_id, "agents.view"))) fail(404, "agent_not_found");
    await need(userId, a!.org_id, ...perms);
    return a!;
  }
  const app = () => gh ?? fail(503, "github_not_configured");
  async function activeConnection(orgId: string) {
    const c = await db.connection(orgId);
    if (!c || c.status !== "active") fail(409, "github_not_connected");
    return c!;
  }
  async function adminToken(installationId: number, repoId?: number) {
    return (await app().token(installationId, { permissions: ADMIN_PERMISSIONS, ...(repoId ? { repositoryIds: [repoId] } : {}) })).token;
  }

  const routes: Record<string, (req: Request, user: string) => Promise<Response>> = {
    // An org admin starts the install; the state binds GitHub's redirect back to them and the org.
    async "/connect/start"(req, user) {
      const o = await body(req);
      const orgId = uuidOf(o, "org_id");
      await need(user, orgId, "agents.github");
      app();
      const n = nonce();
      await db.addState(n, orgId, user, new Date(now() + STATE_TTL_MS).toISOString());
      return json(200, { url: app().installUrl(n) });
    },
    async "/connection"(req, user) {
      const o = await body(req);
      const orgId = uuidOf(o, "org_id");
      await need(user, orgId, "agents.view");
      const c = await db.connection(orgId);
      if (!c) return json(200, { connected: false, configured: !!gh, test_mode: gh?.testMode ?? false });
      let reachable: boolean | null = null;
      if (gh && c.status === "active") {
        try { reachable = (await gh.installation(c.installation_id)) !== null; } catch { reachable = null; }
        if (reachable === false) await db.setConnectionStatus(orgId, "removed");
      }
      const status = reachable === false ? "removed" : c.status;
      return json(200, { connected: status === "active", status, account: c.account_login, reachable, configured: !!gh, test_mode: gh?.testMode ?? false });
    },
    async "/connection/remove"(req, user) {
      const o = await body(req);
      const orgId = uuidOf(o, "org_id");
      await need(user, orgId, "agents.github");
      if (await db.connection(orgId)) await db.setConnectionStatus(orgId, "removed");
      return json(200, { connected: false });
    },
    // Creates the agent's private engine repo in the org's account, empty: the person's Max seeds it.
    async "/repos"(req, user) {
      const o = await body(req);
      const a = await agentFor(user, uuidOf(o, "agent_id"), "agents.github", "agents.evolve");
      const name = typeof o.name === "string" && REPO_NAME.test(o.name) && !o.name.endsWith(".git") ? o.name : fail(400, "invalid_name");
      if (await db.repo(a.id)) fail(409, "repo_exists");
      const c = await activeConnection(a.org_id);
      const inst = await app().installation(c.installation_id);
      if (!inst) { await db.setConnectionStatus(a.org_id, "removed"); fail(409, "github_not_connected"); }
      const token = await adminToken(c.installation_id);
      const repo = await app().createRepo(token, inst!.account, name, `${a.name}: a Max engine for the BOSS agent ${a.slug}`);
      const r = await db.insertRepo({ agent_id: a.id, org_id: a.org_id, repo_owner: repo.owner, repo_name: repo.name, repo_id: repo.id, state: "seeding" }, user);
      if (r === "taken") fail(409, "repo_exists");
      return json(201, { repo: `${repo.owner}/${repo.name}`, url: repo.htmlUrl, state: "seeding" });
    },
    // Max's token for one repository: seeding pushes stock and main before protection exists; evolving pushes max/* branches.
    async "/token"(req, user) {
      const o = await body(req);
      const a = await agentFor(user, uuidOf(o, "agent_id"), "agents.evolve");
      const purpose = o.purpose === "seed" || o.purpose === "evolve" ? o.purpose : fail(400, "invalid_purpose");
      const r = await db.repo(a.id) ?? fail(404, "no_repo");
      if (purpose === "seed" && r.state !== "seeding" && r.state !== "error") fail(409, "repo_already_seeded");
      if (purpose === "evolve" && r.state !== "ready") fail(409, "repo_not_ready");
      const c = await activeConnection(a.org_id);
      const t = await app().token(c.installation_id, { repositoryIds: [r.repo_id], permissions: { ...EVOLVE_PERMISSIONS } });
      return json(200, {
        token: t.token, expires_at: t.expiresAt, repo: `${r.repo_owner}/${r.repo_name}`,
        clone_url: `https://github.com/${r.repo_owner}/${r.repo_name}.git`, state: r.state, base_version: r.base_version,
      });
    },
    // After Max pushed stock and main: the platform's workflow and engine.json on main, then branch protection.
    async "/repos/finalize"(req, user) {
      const o = await body(req);
      const a = await agentFor(user, uuidOf(o, "agent_id"), "agents.evolve");
      const base = typeof o.base_version === "string" && SEMVER.test(o.base_version) ? o.base_version : fail(400, "invalid_base_version");
      const r = await db.repo(a.id) ?? fail(404, "no_repo");
      if (r.state === "ready") return json(200, { state: "ready" });
      const c = await activeConnection(a.org_id);
      const token = await adminToken(c.installation_id, r.repo_id);
      const g = app();
      if (!(await g.branchSha(token, r.repo_owner, r.repo_name, "main")) || !(await g.branchSha(token, r.repo_owner, r.repo_name, "stock"))) {
        fail(409, "not_seeded");
      }
      try {
        await g.putFile(token, r.repo_owner, r.repo_name, ".boss/engine.json", engineJson(a.id, base), "main", "BOSS agents platform: engine metadata");
        await g.putFile(token, r.repo_owner, r.repo_name, WORKFLOW_PATH, WORKFLOW_YAML, "main", "BOSS agents platform: engine CI");
        await g.setDefaultBranch(token, r.repo_owner, r.repo_name);
        await g.protect(token, r.repo_owner, r.repo_name, "main", "main");
        await g.protect(token, r.repo_owner, r.repo_name, "stock", "stock");
      } catch (e) {
        await db.updateRepo(a.id, { state: "error", detail: e instanceof GitHubError ? e.message.slice(0, 300) : "finalize failed" });
        throw e;
      }
      await db.updateRepo(a.id, { state: "ready", base_version: base, detail: null });
      return json(200, { state: "ready" });
    },
    // Recent push-to-main runs of the platform workflow, with which are imported.
    async "/builds"(req, user) {
      const o = await body(req);
      const a = await agentFor(user, uuidOf(o, "agent_id"));
      const r = await db.repo(a.id);
      if (!r || r.state !== "ready") return json(200, { runs: [], imported: await db.builds(a.id) });
      const c = await activeConnection(a.org_id);
      const token = (await app().token(c.installation_id, { repositoryIds: [r.repo_id], permissions: { ...READ_PERMISSIONS } })).token;
      const runs = (await app().runs(token, r.repo_owner, r.repo_name, WORKFLOW_FILE, "main")).filter((x) => x.event === "push" && x.repoId === r.repo_id);
      const imported = await db.builds(a.id);
      const byRun = new Map(imported.map((b) => [b.run_id, b.id]));
      return json(200, {
        runs: runs.map((x) => ({
          run_id: x.id, commit: x.headSha, message: x.message, status: x.status, conclusion: x.conclusion, url: x.htmlUrl,
          created_at: x.createdAt, build_id: byRun.get(x.id) ?? null,
        })),
        imported,
      });
    },
    // Verifies one successful push-to-main run and copies its engine jar into the private bucket.
    async "/builds/import"(req, user) {
      const o = await body(req);
      const a = await agentFor(user, uuidOf(o, "agent_id"), "agents.publish");
      const runId = typeof o.run_id === "number" && Number.isSafeInteger(o.run_id) && o.run_id > 0 ? o.run_id : fail(400, "invalid_run_id");
      const r = await db.repo(a.id) ?? fail(404, "no_repo");
      if (r.state !== "ready") fail(409, "repo_not_ready");
      const done = await db.buildByRun(a.id, runId);
      if (done) return json(200, { build: done, imported: false });
      const c = await activeConnection(a.org_id);
      const g = app();
      const token = (await g.token(c.installation_id, { repositoryIds: [r.repo_id], permissions: { ...READ_PERMISSIONS } })).token;
      const run = await g.run(token, r.repo_owner, r.repo_name, runId) ?? fail(404, "run_not_found");
      if (run.repoId !== r.repo_id) fail(404, "run_not_found");
      if (run.path !== WORKFLOW_PATH && !run.path.startsWith(`${WORKFLOW_PATH}@`)) fail(422, "not_the_engine_workflow");
      if (run.event !== "push" || run.headBranch !== "main") fail(422, "not_a_main_build");
      if (run.status !== "completed" || run.conclusion !== "success") fail(422, "build_not_successful");
      if (!(await g.onBranch(token, r.repo_owner, r.repo_name, run.headSha, "main"))) fail(422, "commit_not_on_main");
      const art = (await g.artifacts(token, r.repo_owner, r.repo_name, runId)).find((x) => x.name === ARTIFACT_NAME && !x.expired)
        ?? fail(422, "artifact_missing");
      if (art.sizeInBytes > MAX_ARTIFACT_BYTES) fail(413, "artifact_too_large");
      const zip = await g.download(token, art.archiveDownloadUrl, MAX_ARTIFACT_BYTES);
      let facts;
      try { facts = engineFromArtifact(zip, run.headSha); } catch (e) {
        if (e instanceof EngineCheckError) return json(422, { error: "engine_check_failed", detail: e.message });
        throw e;
      }
      const sha = await sha256Hex(facts.jar);
      const path = `${a.org_id}/${a.id}/${sha}.jar`;
      await db.putJar(path, facts.jar);
      const build = await db.insertBuild({
        agent_id: a.id, org_id: a.org_id, repo: `${r.repo_owner}/${r.repo_name}`, commit_sha: run.headSha, run_id: run.id,
        run_url: run.htmlUrl.startsWith("https://github.com/") ? run.htmlUrl : `https://github.com/${r.repo_owner}/${r.repo_name}/actions/runs/${run.id}`,
        engine_version: facts.version, message: run.message, jar_sha256: sha, jar_size: facts.jar.length, storage_path: path, imported_by: user,
      });
      return json(201, { build, imported: true });
    },
    // A short-lived URL of an imported engine jar, for the console's publish (it bundles the jar into the adapter).
    async "/builds/url"(req, user) {
      const o = await body(req);
      const b = await db.build(uuidOf(o, "build_id")) ?? fail(404, "build_not_found");
      await agentFor(user, b.agent_id, "agents.publish");
      return json(200, { url: await db.signedJarUrl(b.storage_path, SIGNED_URL_SECONDS), sha256: b.jar_sha256, size: b.jar_size });
    },
  };

  /** GitHub sends the browser here after the install (no JWT: the single-use state is the proof). */
  async function callback(url: URL): Promise<Response> {
    const back = (q: string, org?: string) => Response.redirect(`${consoleUrl}${org ? `/orgs/${org}/github` : "/"}?${q}`, 302);
    const state = url.searchParams.get("state") ?? "";
    const instId = Number(url.searchParams.get("installation_id"));
    if (!/^[A-Za-z0-9_-]{32,64}$/.test(state)) return back("github=error&reason=state");
    const s = await db.takeState(state);
    if (!s) return back("github=error&reason=state");
    if (Date.parse(s.expires_at) < now()) return back("github=error&reason=expired", s.org_id);
    if (!Number.isSafeInteger(instId) || instId <= 0) return back("github=error&reason=installation", s.org_id);
    if (!(await db.can(s.user_id, s.org_id, "agents.github"))) return back("github=error&reason=permission", s.org_id);
    if (!(await app().verifyInstaller(url.searchParams.get("code") ?? "", instId))) return back("github=error&reason=installer", s.org_id);
    const inst = await app().installation(instId);
    if (!inst) return back("github=error&reason=installation", s.org_id);
    if (inst.account.type !== "Organization" && !app().testMode) return back("github=error&reason=not_an_organization", s.org_id);
    const r = await db.upsertConnection({ org_id: s.org_id, installation_id: inst.id, account_login: inst.account.login, account_id: inst.account.id }, s.user_id);
    if (r === "taken") return back("github=error&reason=installation_in_use", s.org_id);
    return back("github=connected", s.org_id);
  }

  return async (req: Request): Promise<Response> => {
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const url = new URL(req.url);
    const path = url.pathname.replace(/^.*\/agent-github/, "") || "/";
    try {
      if (path === "/connect/callback" && req.method === "GET") return await callback(url);
      const handler = Object.hasOwn(routes, path) ? routes[path] : undefined;
      if (!handler) return json(404, { error: "not_found" });
      if (req.method !== "POST") return json(405, { error: "method_not_allowed" });
      const user = await caller(req);
      return await handler(req, user);
    } catch (e) {
      if (e instanceof HttpError) return json(e.status, { error: e.code });
      if (e instanceof GitHubError) {
        const status = e.status === 409 ? 409 : e.status === 413 ? 413 : 502;
        return json(status, { error: status === 409 ? "repo_name_taken" : "github_unavailable", detail: e.op });
      }
      if (e instanceof DbError) return json(503, { error: "unavailable" });
      console.error("agent-github", e instanceof Error ? e.name : "error");
      return json(500, { error: "internal" });
    }
  };
}
