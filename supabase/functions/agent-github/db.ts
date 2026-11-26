/** boss-main as agent-github sees it: the caller's identity from their JWT, every decision and write with the service role. */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const BUCKET = "agent-engines";

export interface AgentRef { id: string; org_id: string; slug: string; name: string }
export interface RepoRow {
  agent_id: string; org_id: string; repo_owner: string; repo_name: string; repo_id: number;
  state: "seeding" | "ready" | "error"; main_protected: boolean; base_version: string | null; detail: string | null;
}
export interface BuildRow {
  id: string; agent_id: string; org_id: string; repo: string; commit_sha: string; source: "max" | "ci";
  run_id: number | null; run_url: string | null; engine_version: string; message: string; jar_sha256: string; jar_size: number;
  storage_path: string; imported_at: string;
}
export type BuildInsert = Omit<BuildRow, "id" | "imported_at" | "run_id" | "run_url"> & { imported_by: string };

export interface Db {
  user(jwt: string): Promise<string | null>;
  can(userId: string, orgId: string, permission: string): Promise<boolean>;
  agent(id: string): Promise<AgentRef | null>;
  repo(agentId: string): Promise<RepoRow | null>;
  /** "taken": another agent already links this GitHub repository. */
  upsertRepo(r: Omit<RepoRow, "detail">, by: string): Promise<"ok" | "taken">;
  builds(agentId: string): Promise<BuildRow[]>;
  build(id: string): Promise<BuildRow | null>;
  buildBySha(agentId: string, sha256: string): Promise<BuildRow | null>;
  insertBuild(b: BuildInsert): Promise<BuildRow>;
  /** A one-time URL Max PUTs the jar to (no service key leaves the function). */
  signedUploadUrl(path: string): Promise<string>;
  /** The uploaded object, or null when absent; refuses (DbError 413) past `maxBytes`. */
  getJar(path: string, maxBytes: number): Promise<Uint8Array | null>;
  putJar(path: string, jar: Uint8Array): Promise<void>;
  removeJar(path: string): Promise<void>;
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
  const pub = () => env("AGENT_GITHUB_PUBLIC_SUPABASE_URL") || url;
  const absolute = (rel: string) => rel.startsWith("http") ? rel : `${pub()}/storage/v1${rel.startsWith("/") ? rel : `/${rel}`}`;

  async function call(op: string, path: string, init: RequestInit = {}, ok = [200]): Promise<Response> {
    const r = await fetch(`${url}${path}`, { ...init, headers: { ...svc, ...init.headers }, signal: init.signal ?? t(), redirect: "error" });
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
    async repo(agentId) {
      return (await rows<RepoRow>("repo", `/rest/v1/agent_engine_repos?agent_id=${eq(agentId)}&select=agent_id,org_id,repo_owner,repo_name,repo_id,state,main_protected,base_version,detail`))[0] ?? null;
    },
    async upsertRepo(r, by) {
      const res = await call("repo upsert", "/rest/v1/agent_engine_repos?on_conflict=agent_id", {
        method: "POST", headers: { Prefer: "resolution=merge-duplicates,return=minimal" },
        body: JSON.stringify({ ...r, detail: null, created_by: by }),
      }, [200, 201, 204, 409]);
      return res.status === 409 ? "taken" : "ok";
    },
    async builds(agentId) {
      return await rows<BuildRow>("builds", `/rest/v1/agent_engine_builds?agent_id=${eq(agentId)}&select=*&order=imported_at.desc&limit=50`);
    },
    async build(id) {
      if (!UUID.test(id)) return null;
      return (await rows<BuildRow>("build", `/rest/v1/agent_engine_builds?id=${eq(id)}&select=*`))[0] ?? null;
    },
    async buildBySha(agentId, sha) {
      return (await rows<BuildRow>("build by sha", `/rest/v1/agent_engine_builds?agent_id=${eq(agentId)}&jar_sha256=${eq(sha)}&select=*`))[0] ?? null;
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
    async signedUploadUrl(path) {
      const r = await call("upload sign", `/storage/v1/object/upload/sign/${BUCKET}/${path}`, { method: "POST", body: "{}" });
      const o = await r.json() as { url?: unknown };
      if (typeof o.url !== "string") throw new DbError("upload sign", 502);
      return absolute(o.url);
    },
    async getJar(path, maxBytes) {
      const r = await fetch(`${url}/storage/v1/object/authenticated/${BUCKET}/${path}`, {
        headers: { apikey: service, Authorization: `Bearer ${service}` }, signal: AbortSignal.timeout(120_000), redirect: "error",
      });
      if (r.status === 400 || r.status === 404) { await r.body?.cancel(); return null; }
      if (!r.ok || !r.body) { await r.body?.cancel(); throw new DbError("jar read", r.status); }
      const declared = Number(r.headers.get("content-length") ?? "0");
      if (declared > maxBytes) { await r.body.cancel(); throw new DbError("jar read", 413); }
      const parts: Uint8Array[] = [];
      let n = 0;
      for await (const chunk of r.body) {
        n += chunk.length;
        if (n > maxBytes) throw new DbError("jar read", 413);
        parts.push(chunk);
      }
      const out = new Uint8Array(n);
      let o = 0;
      for (const p of parts) { out.set(p, o); o += p.length; }
      return out;
    },
    async putJar(path, jar) {
      const r = await fetch(`${url}/storage/v1/object/${BUCKET}/${path}`, {
        method: "POST", headers: { apikey: service, Authorization: `Bearer ${service}`, "Content-Type": "application/java-archive", "x-upsert": "true" },
        body: jar as BodyInit, signal: AbortSignal.timeout(120_000),
      });
      if (!r.ok) { await r.body?.cancel(); throw new DbError("jar upload", r.status); }
      await r.body?.cancel();
    },
    async removeJar(path) {
      const r = await fetch(`${url}/storage/v1/object/${BUCKET}`, {
        method: "DELETE", headers: svc, body: JSON.stringify({ prefixes: [path] }), signal: t(),
      });
      await r.body?.cancel();
    },
    async signedJarUrl(path, seconds) {
      const r = await call("jar sign", `/storage/v1/object/sign/${BUCKET}/${path}`, { method: "POST", body: JSON.stringify({ expiresIn: seconds }) });
      const o = await r.json() as { signedURL?: unknown; signedUrl?: unknown };
      const rel = typeof o.signedURL === "string" ? o.signedURL : typeof o.signedUrl === "string" ? o.signedUrl : null;
      if (!rel) throw new DbError("jar sign", 502);
      return absolute(rel);
    },
  };
}
