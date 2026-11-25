/**
 * GitHub for the agents platform: the platform's GitHub App (an App JWT for the installation endpoints, an
 * installation token per call, never stored) and the few REST calls agent-github makes with it.
 *
 * Test mode (AGENT_GITHUB_TEST_MODE=1 with AGENT_GITHUB_TEST_TOKEN, on a local stack only): every
 * installation token is the test token and the installation is AGENT_GITHUB_TEST_ACCOUNT. It exists so the
 * same code paths can run end to end against a throwaway repo before the App is registered; it is refused
 * anywhere SUPABASE_URL is not a local stack (see localStack).
 */

const API = "https://api.github.com";
const ACCEPT = "application/vnd.github+json";
const VERSION = "2022-11-28";

export interface Installation {
  id: number;
  account: { login: string; id: number; type: "Organization" | "User" | string };
}

export interface TokenRequest {
  /** Restricts the token to these repositories (ids). */
  repositoryIds?: number[];
  /** Restricts the token's permissions, e.g. { contents: "write" }. */
  permissions: Record<string, "read" | "write">;
}

export interface MintedToken {
  token: string;
  expiresAt: string;
}

export interface Repo {
  id: number;
  name: string;
  owner: string;
  private: boolean;
  defaultBranch: string;
  htmlUrl: string;
  cloneUrl: string;
}

export interface WorkflowRun {
  id: number;
  repoId: number;
  path: string;
  event: string;
  headBranch: string | null;
  headSha: string;
  status: string;
  conclusion: string | null;
  htmlUrl: string;
  message: string;
  createdAt: string;
}

export interface Artifact {
  id: number;
  name: string;
  sizeInBytes: number;
  expired: boolean;
  archiveDownloadUrl: string;
}

export class GitHubError extends Error {
  constructor(readonly op: string, readonly status: number) {
    super(`github ${op} answered ${status}`);
    this.name = "GitHubError";
  }
}

/** What agent-github needs from GitHub; tests substitute a fake. */
export interface GitHubApp {
  readonly testMode: boolean;
  installUrl(state: string): string;
  /** Exchange the installation OAuth code and prove this user can access that installation. */
  verifyInstaller(code: string, installationId: number): Promise<boolean>;
  installation(id: number): Promise<Installation | null>;
  token(installationId: number, req: TokenRequest): Promise<MintedToken>;
  createRepo(token: string, account: Installation["account"], name: string, description: string): Promise<Repo>;
  repo(token: string, owner: string, name: string): Promise<Repo | null>;
  setDefaultBranch(token: string, owner: string, name: string): Promise<void>;
  branchSha(token: string, owner: string, name: string, branch: string): Promise<string | null>;
  putFile(token: string, owner: string, name: string, path: string, content: string, branch: string, message: string): Promise<void>;
  protect(token: string, owner: string, name: string, branch: string, kind: "main" | "stock"): Promise<void>;
  runs(token: string, owner: string, name: string, workflowFile: string, branch: string): Promise<WorkflowRun[]>;
  run(token: string, owner: string, name: string, runId: number): Promise<WorkflowRun | null>;
  /** Whether `sha` is on `branch` (identical to its head, or behind it). */
  onBranch(token: string, owner: string, name: string, sha: string, branch: string): Promise<boolean>;
  artifacts(token: string, owner: string, name: string, runId: number): Promise<Artifact[]>;
  download(token: string, url: string, maxBytes: number): Promise<Uint8Array>;
}

type Fetch = (input: string, init?: RequestInit) => Promise<Response>;

const b64url = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const b64urlText = (s: string) => b64url(new TextEncoder().encode(s));

function pemBody(pem: string): Uint8Array {
  const b64 = pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function derLength(n: number): number[] {
  if (n < 0x80) return [n];
  const bytes: number[] = [];
  for (let v = n; v > 0; v >>= 8) bytes.unshift(v & 0xff);
  return [0x80 | bytes.length, ...bytes];
}

/**
 * GitHub hands out PKCS#1 keys ("BEGIN RSA PRIVATE KEY"); WebCrypto imports PKCS#8. Wrapping is a fixed
 * prefix: SEQUENCE { INTEGER 0, AlgorithmIdentifier rsaEncryption, OCTET STRING <pkcs1> }.
 */
export function pkcs1ToPkcs8(pkcs1: Uint8Array): Uint8Array {
  const algId = [0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00];
  const octet = [0x04, ...derLength(pkcs1.length)];
  const inner = [0x02, 0x01, 0x00, ...algId, ...octet];
  const total = inner.length + pkcs1.length;
  const out = new Uint8Array([0x30, ...derLength(total), ...inner, ...pkcs1]);
  return out;
}

export async function importAppKey(pem: string): Promise<CryptoKey> {
  const der = pem.includes("BEGIN RSA PRIVATE KEY") ? pkcs1ToPkcs8(pemBody(pem)) : pemBody(pem);
  return await crypto.subtle.importKey("pkcs8", der as BufferSource, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
}

/** An App JWT: valid 9 minutes, issued a minute in the past for clock drift (GitHub's own advice). */
export async function appJwt(appId: string, key: CryptoKey, nowSec = Math.floor(Date.now() / 1000)): Promise<string> {
  const head = b64urlText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const body = b64urlText(JSON.stringify({ iat: nowSec - 60, exp: nowSec + 540, iss: appId }));
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${body}`)));
  return `${head}.${body}.${b64url(sig)}`;
}

/** Hosts a local Supabase stack's edge runtime sees as SUPABASE_URL. */
export function localStack(supabaseUrl: string): boolean {
  try {
    const h = new URL(supabaseUrl).hostname;
    return ["127.0.0.1", "localhost", "kong", "host.docker.internal"].includes(h);
  } catch {
    return false;
  }
}

const OWNER = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/;
const NAME = /^[A-Za-z0-9._-]{1,100}$/;
const SHA = /^[0-9a-f]{40}$/;
const seg = (s: string, re: RegExp) => {
  if (!re.test(s) || s === "." || s === "..") throw new Error("invalid path segment");
  return encodeURIComponent(s);
};

function repoOf(o: Record<string, unknown>): Repo {
  const owner = (o.owner as { login?: unknown } | undefined)?.login;
  if (typeof o.id !== "number" || typeof o.name !== "string" || typeof owner !== "string") throw new GitHubError("repo shape", 502);
  return {
    id: o.id, name: o.name, owner, private: o.private === true,
    defaultBranch: typeof o.default_branch === "string" ? o.default_branch : "main",
    htmlUrl: String(o.html_url ?? ""), cloneUrl: String(o.clone_url ?? ""),
  };
}

function runOf(o: Record<string, unknown>): WorkflowRun {
  const repo = o.repository as { id?: unknown } | undefined;
  const commit = o.head_commit as { message?: unknown } | undefined;
  return {
    id: Number(o.id), repoId: Number(repo?.id), path: String(o.path ?? ""), event: String(o.event ?? ""),
    headBranch: typeof o.head_branch === "string" ? o.head_branch : null, headSha: String(o.head_sha ?? ""),
    status: String(o.status ?? ""), conclusion: typeof o.conclusion === "string" ? o.conclusion : null,
    htmlUrl: String(o.html_url ?? ""), message: typeof commit?.message === "string" ? commit.message.split("\n")[0].slice(0, 200) : "",
    createdAt: String(o.created_at ?? ""),
  };
}

export interface AppConfig {
  appId: string;
  appSlug: string;
  privateKeyPem: string;
  clientId?: string;
  clientSecret?: string;
  /** Test mode only. */
  testToken?: string;
  testAccount?: string;
  testAccountType?: "Organization" | "User";
  /** Where a test-mode "install" lands: the callback itself, with installation_id=1. */
  callbackUrl?: string;
}

export function restGitHub(cfg: AppConfig, fetchImpl: Fetch = fetch): GitHubApp {
  const testMode = !!cfg.testToken;
  let keyP: Promise<CryptoKey> | null = null;
  const jwt = async () => {
    if (!keyP) keyP = importAppKey(cfg.privateKeyPem);
    return await appJwt(cfg.appId, await keyP);
  };

  async function call(op: string, token: string, path: string, init: RequestInit = {}, ok = [200]): Promise<Response> {
    const r = await fetchImpl(path.startsWith("https://") ? path : `${API}${path}`, {
      ...init,
      headers: { Accept: ACCEPT, "X-GitHub-Api-Version": VERSION, Authorization: `Bearer ${token}`, "User-Agent": "boss-agents", ...init.headers },
      redirect: "manual",
      signal: AbortSignal.timeout(30_000),
    });
    if (!ok.includes(r.status)) {
      await r.body?.cancel();
      throw new GitHubError(op, r.status);
    }
    return r;
  }

  return {
    testMode,
    installUrl(state) {
      if (testMode) return `${cfg.callbackUrl}?installation_id=1&setup_action=install&code=local-test&state=${encodeURIComponent(state)}`;
      return `https://github.com/apps/${encodeURIComponent(cfg.appSlug)}/installations/new?state=${encodeURIComponent(state)}`;
    },
    async verifyInstaller(code, installationId) {
      if (testMode) return code === "local-test";
      if (!cfg.clientId || !cfg.clientSecret || !code) return false;
      const response = await fetchImpl("https://github.com/login/oauth/access_token", {
        method: "POST", headers: { Accept: "application/json", "Content-Type": "application/json" },
        body: JSON.stringify({ client_id: cfg.clientId, client_secret: cfg.clientSecret, code }),
        redirect: "error", signal: AbortSignal.timeout(30000),
      });
      if (!response.ok) { await response.body?.cancel(); return false; }
      const answer = await response.json() as { access_token?: string };
      if (!answer.access_token) return false;
      // Tokens are used once here, never persisted or returned. Do not trust installation_id from the browser.
      for (let page = 1; page <= 10; page++) {
        const r = await call("user installations", answer.access_token, `/user/installations?per_page=100&page=${page}`);
        const o = await r.json() as { installations?: { id: number; account?: {login?: string; type?: string} }[] };
        const installation = o.installations?.find(i => i.id === installationId);
        if (installation) {
          const org = installation.account?.login;
          if (installation.account?.type !== "Organization" || !org || !OWNER.test(org)) return false;
          const user = await (await call("installer", answer.access_token, "/user")).json() as {login?: string};
          if (!user.login || !OWNER.test(user.login)) return false;
          const membership = await call("installer membership", answer.access_token, `/orgs/${seg(org, OWNER)}/memberships/${seg(user.login, OWNER)}`, {}, [200,404]);
          if (membership.status !== 200) { await membership.body?.cancel(); return false; }
          const m = await membership.json() as {role?: string; state?: string};
          return m.role === "admin" && m.state === "active";
        }
        if (!o.installations || o.installations.length < 100) return false;
      }
      return false;
    },
    async installation(id) {
      if (testMode) return { id, account: { login: cfg.testAccount ?? "", id: 1, type: cfg.testAccountType ?? "Organization" } };
      const r = await call("installation", await jwt(), `/app/installations/${id}`, {}, [200, 404]);
      if (r.status === 404) { await r.body?.cancel(); return null; }
      const o = await r.json() as { id?: unknown; account?: { login?: unknown; id?: unknown; type?: unknown } };
      if (typeof o.id !== "number" || typeof o.account?.login !== "string" || typeof o.account?.id !== "number") throw new GitHubError("installation shape", 502);
      return { id: o.id, account: { login: o.account.login, id: o.account.id, type: String(o.account.type) } };
    },
    async token(installationId, req) {
      if (testMode) return { token: cfg.testToken!, expiresAt: new Date(Date.now() + 3600_000).toISOString() };
      const body: Record<string, unknown> = { permissions: req.permissions };
      if (req.repositoryIds) body.repository_ids = req.repositoryIds;
      const r = await call("token", await jwt(), `/app/installations/${installationId}/access_tokens`, {
        method: "POST", body: JSON.stringify(body), headers: { "Content-Type": "application/json" },
      }, [201]);
      const o = await r.json() as { token?: unknown; expires_at?: unknown };
      if (typeof o.token !== "string" || typeof o.expires_at !== "string") throw new GitHubError("token shape", 502);
      return { token: o.token, expiresAt: o.expires_at };
    },
    async createRepo(token, account, name, description) {
      seg(name, NAME);
      // An App installation creates org repos; the user endpoint exists for test mode on a personal account only.
      const path = account.type === "Organization" ? `/orgs/${seg(account.login, OWNER)}/repos` : testMode ? "/user/repos" : null;
      if (!path) throw new GitHubError("create repo: not an organisation", 400);
      const r = await call("create repo", token, path, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, description, private: true, auto_init: false, has_wiki: false, has_projects: false, delete_branch_on_merge: true }),
      }, [201, 422]);
      if (r.status === 422) { await r.body?.cancel(); throw new GitHubError("create repo: name taken", 409); }
      return repoOf(await r.json());
    },
    async repo(token, owner, name) {
      const r = await call("repo", token, `/repos/${seg(owner, OWNER)}/${seg(name, NAME)}`, {}, [200, 404]);
      if (r.status === 404) { await r.body?.cancel(); return null; }
      return repoOf(await r.json());
    },
    async setDefaultBranch(token, owner, name) {
      const r = await call("default branch", token, `/repos/${seg(owner, OWNER)}/${seg(name, NAME)}`, {
        method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({default_branch: "main"}),
      });
      await r.body?.cancel();
    },
    async branchSha(token, owner, name, branch) {
      const r = await call("branch", token, `/repos/${seg(owner, OWNER)}/${seg(name, NAME)}/git/ref/heads/${encodeURIComponent(branch)}`, {}, [200, 404, 409]);
      if (r.status !== 200) { await r.body?.cancel(); return null; }
      const o = await r.json() as { object?: { sha?: unknown } };
      return typeof o.object?.sha === "string" ? o.object.sha : null;
    },
    async putFile(token, owner, name, path, content, branch, message) {
      const base = `/repos/${seg(owner, OWNER)}/${seg(name, NAME)}/contents/${path.split("/").map(encodeURIComponent).join("/")}`;
      const existing = await call("file get", token, `${base}?ref=${encodeURIComponent(branch)}`, {}, [200, 404]);
      let sha: string | undefined;
      if (existing.status === 200) {
        const o = await existing.json() as { sha?: unknown; content?: unknown };
        sha = typeof o.sha === "string" ? o.sha : undefined;
        if (typeof o.content === "string" && new TextDecoder().decode(Uint8Array.from(atob(o.content.replace(/\s/g, "")), c => c.charCodeAt(0))) === content) return;
      } else await existing.body?.cancel();
      const bytes = new TextEncoder().encode(content);
      let bin = "";
      for (const b of bytes) bin += String.fromCharCode(b);
      const r = await call("file put", token, base, {
        method: "PUT", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message, content: btoa(bin), branch, ...(sha ? { sha } : {}) }),
      }, [200, 201]);
      await r.body?.cancel();
    },
    async protect(token, owner, name, branch, kind) {
      const body = kind === "main"
        ? {
          required_status_checks: { strict: false, contexts: ["engine-build"] },
          enforce_admins: true,
          required_pull_request_reviews: { required_approving_review_count: 1, dismiss_stale_reviews: true },
          restrictions: null, allow_force_pushes: false, allow_deletions: false, required_conversation_resolution: true,
        }
        : { required_status_checks: null, enforce_admins: true, required_pull_request_reviews: null, restrictions: null, allow_force_pushes: false, allow_deletions: false, lock_branch: true };
      const r = await call("protect", token, `/repos/${seg(owner, OWNER)}/${seg(name, NAME)}/branches/${encodeURIComponent(branch)}/protection`, {
        method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
      }, [200]);
      await r.body?.cancel();
    },
    async runs(token, owner, name, workflowFile, branch) {
      const r = await call("runs", token, `/repos/${seg(owner, OWNER)}/${seg(name, NAME)}/actions/workflows/${encodeURIComponent(workflowFile)}/runs?branch=${encodeURIComponent(branch)}&event=push&per_page=20`, {}, [200, 404]);
      if (r.status === 404) { await r.body?.cancel(); return []; }
      const o = await r.json() as { workflow_runs?: Record<string, unknown>[] };
      return (o.workflow_runs ?? []).map(runOf);
    },
    async run(token, owner, name, runId) {
      const r = await call("run", token, `/repos/${seg(owner, OWNER)}/${seg(name, NAME)}/actions/runs/${runId}`, {}, [200, 404]);
      if (r.status === 404) { await r.body?.cancel(); return null; }
      return runOf(await r.json());
    },
    async onBranch(token, owner, name, sha, branch) {
      if (!SHA.test(sha)) return false;
      const r = await call("compare", token, `/repos/${seg(owner, OWNER)}/${seg(name, NAME)}/compare/${sha}...${encodeURIComponent(branch)}`, {}, [200, 404]);
      if (r.status === 404) { await r.body?.cancel(); return false; }
      const o = await r.json() as { status?: unknown };
      return o.status === "identical" || o.status === "ahead";
    },
    async artifacts(token, owner, name, runId) {
      const r = await call("artifacts", token, `/repos/${seg(owner, OWNER)}/${seg(name, NAME)}/actions/runs/${runId}/artifacts`, {}, [200]);
      const o = await r.json() as { artifacts?: Record<string, unknown>[] };
      return (o.artifacts ?? []).map((a) => ({
        id: Number(a.id), name: String(a.name ?? ""), sizeInBytes: Number(a.size_in_bytes ?? 0), expired: a.expired === true,
        archiveDownloadUrl: String(a.archive_download_url ?? ""),
      }));
    },
    async download(token, url, maxBytes) {
      if (!url.startsWith(`${API}/repos/`)) throw new GitHubError("download url", 400);
      // The API answers 302 to a short-lived storage URL: follow it without the token.
      const first = await call("artifact", token, url, {}, [302]);
      const loc = first.headers.get("location");
      await first.body?.cancel();
      if (!loc || !loc.startsWith("https://")) throw new GitHubError("artifact redirect", 502);
      const r = await fetchImpl(loc, { redirect: "follow", signal: AbortSignal.timeout(120_000) });
      if (!r.ok || !r.body) { await r.body?.cancel(); throw new GitHubError("artifact download", r.status); }
      const declared = Number(r.headers.get("content-length") ?? "0");
      if (declared > maxBytes) { await r.body.cancel(); throw new GitHubError("artifact too large", 413); }
      const chunks: Uint8Array[] = [];
      let total = 0;
      for await (const c of r.body) {
        total += c.length;
        if (total > maxBytes) throw new GitHubError("artifact too large", 413);
        chunks.push(c);
      }
      const out = new Uint8Array(total);
      let off = 0;
      for (const c of chunks) { out.set(c, off); off += c.length; }
      return out;
    },
  };
}
