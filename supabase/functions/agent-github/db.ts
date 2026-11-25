/** boss-main as agent-github sees it: the caller's identity from their JWT, every decision and write with the service role. */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface AgentRef { id: string; org_id: string; slug: string; name: string }
export interface Connection { org_id: string; installation_id: number; account_login: string; account_id: number; status: "active" | "removed" }
export interface RepoRow {
  agent_id: string; org_id: string; repo_owner: string; repo_name: string; repo_id: number;
  state: "seeding" | "ready" | "error"; base_version: string | null; detail: string | null;
}
export interface BuildRow {
  id: string; agent_id: string; org_id: string; repo: string; commit_sha: string; run_id: number; run_url: string;
  engine_version: string; message: string; jar_sha256: string; jar_size: number; storage_path: string; imported_at: string;
}
export type BuildInsert = Omit<BuildRow, "id" | "imported_at"> & { imported_by: string };

export interface Db {
  user(jwt: string): Promise<string | null>;
  can(userId: string, orgId: string, permission: string): Promise<boolean>;
  agent(id: string): Promise<AgentRef | null>;
  connection(orgId: string): Promise<Connection | null>;
  /** "taken": the installation is already another org's. */
  upsertConnection(c: Omit<Connection, "status">, by: string): Promise<"ok" | "taken">;
  setConnectionStatus(orgId: string, status: Connection["status"]): Promise<void>;
  addState(nonce: string, orgId: string, userId: string, expiresAt: string): Promise<void>;
  /** Deletes and returns the state (single use); null when unknown. */
  takeState(nonce: string): Promise<{ org_id: string; user_id: string; expires_at: string } | null>;
  repo(agentId: string): Promise<RepoRow | null>;
  insertRepo(r: Omit<RepoRow, "detail" | "base_version">, by: string): Promise<"ok" | "taken">;
  updateRepo(agentId: string, patch: Partial<Pick<RepoRow, "state" | "base_version" | "detail">>): Promise<void>;
  builds(agentId: string): Promise<BuildRow[]>;
  build(id: string): Promise<BuildRow | null>;
  buildByRun(agentId: string, runId: number): Promise<BuildRow | null>;
  insertBuild(b: BuildInsert): Promise<BuildRow>;
  putJar(path: string, jar: Uint8Array): Promise<void>;
  signedJarUrl(path: string, seconds: number): Promise<string>;
}

export class DbError extends Error {
  constructor(readonly op: string, readonly status: number) {
    super(`db ${op} answered ${status}`);
  }
}

export function restDb(env: (k: string) => string): Db {
  const url = env("SUPABASE_URL");
  const anon = env("SUPABASE_ANON_KEY");
  const service = env("SUPABASE_SERVICE_ROLE_KEY");
  const svc = { apikey: service, Authorization: `Bearer ${service}`, "Content-Type": "application/json" };
  const t = () => AbortSignal.timeout(10_000);

  async function call(op: string, path: string, init: RequestInit = {}, ok = [200]): Promise<Response> {
    const r = await fetch(`${url}${path}`, { ...init, headers: { ...svc, ...init.headers }, signal: t(), redirect: "error" });
    if (!ok.includes(r.status)) {
      await r.body?.cancel();
      throw new DbError(op, r.status);
    }
    return r;
  }
  const rows = async <T>(op: string, path: string) => await (await call(op, path)).json() as T[];
  const eq = (v: string | number) => `eq.${encodeURIComponent(String(v))}`;

  return {
    async user(jwt) {
      const r = await fetch(`${url}/auth/v1/user`, { headers: { apikey: anon, Authorization: `Bearer ${jwt}` }, signal: t() });
      if (!r.ok) { await r.body?.cancel(); return null; }
      const u = await r.json() as { id?: unknown };
      return typeof u.id === "string" && UUID.test(u.id) ? u.id : null;
    },
    async can(userId, orgId, permission) {
      const r = await call("permission", "/rest/v1/rpc/user_has_org_permission", {
        method: "POST", body: JSON.stringify({ p_user_id: userId, p_org_id: orgId, p_permission: permission }),
      });
      return (await r.json()) === true;
    },
    async agent(id) {
      if (!UUID.test(id)) return null;
      return (await rows<AgentRef>("agent", `/rest/v1/agents?id=${eq(id)}&select=id,org_id,slug,name`))[0] ?? null;
    },
    async connection(orgId) {
      return (await rows<Connection>("connection", `/rest/v1/org_github_connections?org_id=${eq(orgId)}&select=org_id,installation_id,account_login,account_id,status`))[0] ?? null;
    },
    async upsertConnection(c, by) {
      const r = await call("connection upsert", "/rest/v1/org_github_connections?on_conflict=org_id", {
        method: "POST", headers: { Prefer: "resolution=merge-duplicates,return=minimal" },
        body: JSON.stringify({ ...c, status: "active", connected_by: by, connected_at: new Date().toISOString() }),
      }, [201, 204, 409]);
      return r.status === 409 ? "taken" : "ok";
    },
    async setConnectionStatus(orgId, status) {
      await call("connection status", `/rest/v1/org_github_connections?org_id=${eq(orgId)}`, {
        method: "PATCH", headers: { Prefer: "return=minimal" }, body: JSON.stringify({ status }),
      }, [200, 204]);
    },
    async addState(nonce, orgId, userId, expiresAt) {
      await call("state add", "/rest/v1/github_connect_states", {
        method: "POST", headers: { Prefer: "return=minimal" },
        body: JSON.stringify({ nonce, org_id: orgId, user_id: userId, expires_at: expiresAt }),
      }, [201]);
    },
    async takeState(nonce) {
      const r = await call("state take", `/rest/v1/github_connect_states?nonce=${eq(nonce)}`, {
        method: "DELETE", headers: { Prefer: "return=representation" },
      }, [200]);
      const got = await r.json() as { org_id: string; user_id: string; expires_at: string }[];
      return got[0] ?? null;
    },
    async repo(agentId) {
      return (await rows<RepoRow>("repo", `/rest/v1/agent_engine_repos?agent_id=${eq(agentId)}&select=agent_id,org_id,repo_owner,repo_name,repo_id,state,base_version,detail`))[0] ?? null;
    },
    async insertRepo(r, by) {
      const res = await call("repo insert", "/rest/v1/agent_engine_repos", {
        method: "POST", headers: { Prefer: "return=minimal" }, body: JSON.stringify({ ...r, created_by: by }),
      }, [201, 409]);
      return res.status === 409 ? "taken" : "ok";
    },
    async updateRepo(agentId, patch) {
      await call("repo update", `/rest/v1/agent_engine_repos?agent_id=${eq(agentId)}`, {
        method: "PATCH", headers: { Prefer: "return=minimal" }, body: JSON.stringify(patch),
      }, [200, 204]);
    },
    async builds(agentId) {
      return await rows<BuildRow>("builds", `/rest/v1/agent_engine_builds?agent_id=${eq(agentId)}&select=*&order=imported_at.desc&limit=50`);
    },
    async build(id) {
      if (!UUID.test(id)) return null;
      return (await rows<BuildRow>("build", `/rest/v1/agent_engine_builds?id=${eq(id)}&select=*`))[0] ?? null;
    },
    async buildByRun(agentId, runId) {
      return (await rows<BuildRow>("build by run", `/rest/v1/agent_engine_builds?agent_id=${eq(agentId)}&run_id=${eq(runId)}&select=*`))[0] ?? null;
    },
    async insertBuild(b) {
      const r = await call("build insert", "/rest/v1/agent_engine_builds?on_conflict=agent_id,jar_sha256", {
        method: "POST", headers: { Prefer: "resolution=ignore-duplicates,return=representation" }, body: JSON.stringify(b),
      }, [200, 201]);
      const got = await r.json() as BuildRow[];
      if (got[0]) return got[0];
      // Already imported (same jar): the existing row.
      const have = (await rows<BuildRow>("build get", `/rest/v1/agent_engine_builds?agent_id=${eq(b.agent_id)}&jar_sha256=${eq(b.jar_sha256)}&select=*`))[0];
      if (!have) throw new DbError("build insert", 409);
      return have;
    },
    async putJar(path, jar) {
      const r = await fetch(`${url}/storage/v1/object/agent-engines/${path}`, {
        method: "POST", headers: { apikey: service, Authorization: `Bearer ${service}`, "Content-Type": "application/java-archive", "x-upsert": "true" },
        body: jar as BodyInit, signal: AbortSignal.timeout(120_000),
      });
      if (!r.ok) { await r.body?.cancel(); throw new DbError("jar upload", r.status); }
      await r.body?.cancel();
    },
    async signedJarUrl(path, seconds) {
      const r = await call("jar sign", `/storage/v1/object/sign/agent-engines/${path}`, { method: "POST", body: JSON.stringify({ expiresIn: seconds }) });
      const o = await r.json() as { signedURL?: unknown; signedUrl?: unknown };
      const rel = typeof o.signedURL === "string" ? o.signedURL : typeof o.signedUrl === "string" ? o.signedUrl : null;
      if (!rel) throw new DbError("jar sign", 502);
      const pub = env("AGENT_GITHUB_PUBLIC_SUPABASE_URL") || url;
      return rel.startsWith("http") ? rel : `${pub}/storage/v1${rel.startsWith("/") ? rel : `/${rel}`}`;
    },
  };
}
