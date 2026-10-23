import { type Resolver, resolverFromEnv } from "./dns.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_BODY = 1024;
export const TXT_PREFIX = "_bossconsole-verify.";
export const TXT_VALUE_PREFIX = "bossconsole-verify=";

export interface Domain {
  id: string;
  agent_id: string;
  hostname: string;
  verify_token: string;
}

/** The status write was refused because the actor lacks agents.domains (revoked mid-request). */
export class PermissionDenied extends Error {}

export interface Db {
  /** The caller's user id from their JWT, or null when the token is not valid. */
  user(jwt: string): Promise<string | null>;
  /** The domain as the caller sees it under RLS (null = not theirs or missing). */
  domain(jwt: string, id: string): Promise<Domain | null>;
  /** Whether the caller holds agents.domains on the agent's org (their own JWT). */
  canManage(jwt: string, agentId: string): Promise<boolean>;
  /** Service-role write of the check result; returns the new status. */
  record(id: string, txtOk: boolean, cnameOk: boolean, actor: string): Promise<string>;
}

function env(name: string): string {
  return Deno.env.get(name) ?? "";
}

export function restDb(): Db {
  const url = env("SUPABASE_URL");
  const anon = env("SUPABASE_ANON_KEY");
  const service = env("SUPABASE_SERVICE_ROLE_KEY");
  const timeout = () => AbortSignal.timeout(5000);
  return {
    async user(jwt) {
      const r = await fetch(`${url}/auth/v1/user`, {
        headers: { apikey: anon, Authorization: `Bearer ${jwt}` },
        signal: timeout(),
      });
      if (!r.ok) {
        await r.body?.cancel();
        return null;
      }
      const u = await r.json() as { id?: unknown };
      return typeof u.id === "string" && UUID.test(u.id) ? u.id : null;
    },
    async domain(jwt, id) {
      const r = await fetch(
        `${url}/rest/v1/agent_domains?id=eq.${
          encodeURIComponent(id)
        }&select=id,agent_id,hostname,verify_token`,
        { headers: { apikey: anon, Authorization: `Bearer ${jwt}` }, signal: timeout() },
      );
      if (!r.ok) throw new Error(`domain lookup ${r.status}`);
      const rows = await r.json() as Domain[];
      return rows[0] ?? null;
    },
    async canManage(jwt, agentId) {
      const r = await fetch(`${url}/rest/v1/rpc/has_agent_permission`, {
        method: "POST",
        headers: {
          apikey: anon,
          Authorization: `Bearer ${jwt}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ p_agent_id: agentId, p_permission: "agents.domains" }),
        signal: timeout(),
      });
      if (!r.ok) throw new Error(`permission check ${r.status}`);
      return (await r.json()) === true;
    },
    async record(id, txtOk, cnameOk, actor) {
      const r = await fetch(`${url}/rest/v1/rpc/agent_domain_record_check`, {
        method: "POST",
        headers: {
          apikey: service,
          Authorization: `Bearer ${service}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          p_domain_id: id,
          p_txt_ok: txtOk,
          p_cname_ok: cnameOk,
          p_actor: actor,
        }),
        signal: timeout(),
      });
      if (r.status === 403) {
        await r.body?.cancel();
        throw new PermissionDenied("record");
      }
      if (!r.ok) throw new Error(`record ${r.status}`);
      return await r.json() as string;
    },
  };
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
};

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...CORS },
  });
}

const norm = (name: string) => name.trim().toLowerCase().replace(/\.$/, "");

/**
 * POST /verify {domain_id}: a holder of agents.domains in the agent's org (their own JWT) asks for a
 * DNS check of one of its domains. Anyone who cannot see the row gets 404; a viewer without
 * agents.domains gets 403. TXT `_bossconsole-verify.<host>` = `bossconsole-verify=<token>` proves ownership
 * (verified); a CNAME `<host>` -> `edge.<AGENTS_BASE_DOMAIN>` as well makes it active.
 */
export function createApp(
  options: { db?: Db; resolver?: Resolver; baseDomain?: string } = {},
) {
  const db = options.db ?? restDb();
  const resolve = options.resolver ?? resolverFromEnv();
  const base = norm(options.baseDomain ?? env("AGENTS_BASE_DOMAIN"));
  return async (request: Request): Promise<Response> => {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    if (!new URL(request.url).pathname.endsWith("/verify")) {
      return json(404, { error: "Not found" });
    }
    if (request.method !== "POST") return json(405, { error: "Method not allowed" });
    const auth = request.headers.get("Authorization") ?? "";
    const jwt = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
    if (!jwt) return json(401, { error: "Unauthorized" });

    let domainId: string;
    try {
      const text = await request.text();
      if (text.length > MAX_BODY) throw new Error("body");
      const body = JSON.parse(text);
      if (!body || typeof body.domain_id !== "string" || !UUID.test(body.domain_id)) {
        throw new Error("body");
      }
      domainId = body.domain_id.toLowerCase();
    } catch {
      return json(400, { error: "Invalid request" });
    }
    if (!base) return json(503, { error: "Not configured" });

    let actor: string | null;
    let domain: Domain | null;
    try {
      actor = await db.user(jwt);
      if (!actor) return json(401, { error: "Unauthorized" });
      domain = await db.domain(jwt, domainId);
      if (domain && !(await db.canManage(jwt, domain.agent_id))) {
        return json(403, { error: "Permission denied" });
      }
    } catch {
      return json(503, { error: "Unavailable" });
    }
    if (!domain) return json(404, { error: "Domain not found" });

    const host = norm(domain.hostname);
    const expected = {
      txt_name: `${TXT_PREFIX}${host}`,
      txt_value: `${TXT_VALUE_PREFIX}${domain.verify_token}`,
      cname_target: `edge.${base}`,
    };
    let txtOk: boolean;
    let cnameOk: boolean;
    try {
      const [txt, cname] = await Promise.all([
        resolve(expected.txt_name, "TXT"),
        resolve(host, "CNAME"),
      ]);
      txtOk = txt.some((t) => t.trim() === expected.txt_value);
      cnameOk = cname.some((c) => norm(c) === expected.cname_target);
    } catch {
      // DNS itself failed: say so, and leave the stored status alone.
      return json(503, { error: "DNS lookup failed", expected });
    }
    try {
      const status = await db.record(domain.id, txtOk, cnameOk, actor);
      return json(200, {
        domain_id: domain.id,
        hostname: host,
        status,
        checks: { txt: txtOk, cname: cnameOk },
        expected,
      });
    } catch (e) {
      if (e instanceof PermissionDenied) return json(403, { error: "Permission denied" });
      return json(503, { error: "Unavailable" });
    }
  };
}
