import { type InviteFacts, inviteMail } from "./mail.ts";
import { isAddress, type MailMessage, sendMail, type SmtpConfig } from "./smtp.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** Invite tokens are `boss_inv_` + 43 url-safe base64 characters (create_organisation_invite). */
export const TOKEN = /^boss_inv_[A-Za-z0-9_-]{40,64}$/;
const MAX_BODY = 2048;

/** Why org_invite_mail_prepare refused; maps to the HTTP answer. */
export class Refused extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code);
    this.name = "Refused";
  }
}

export interface Db {
  /** The caller's user id from their JWT, or null when the token is not valid. */
  user(jwt: string): Promise<string | null>;
  /**
   * org_invite_mail_prepare as the service role: re-checks that `actor` may invite in the org and that the
   * invite is the org's, pending, email-bound and carries this token; applies the rate limits and records the send.
   */
  prepare(
    actor: string,
    orgId: string,
    inviteId: string,
    token: string,
  ): Promise<InviteFacts & { org_slug: string | null }>;
}

export type Mailer = (msg: MailMessage) => Promise<void>;

function env(name: string): string {
  return (Deno.env.get(name) ?? "").trim();
}

/** The project's auth SMTP settings, as env (the same values as Auth > SMTP). null when not configured. */
export function smtpFromEnv(): SmtpConfig | null {
  const host = env("SMTP_HOST");
  const from = env("SMTP_ADMIN_EMAIL");
  const port = Number(env("SMTP_PORT") || "587");
  if (!host || !isAddress(from) || !Number.isInteger(port) || port < 1 || port > 65535) return null;
  return {
    host,
    port,
    user: env("SMTP_USER") || null,
    pass: env("SMTP_PASS") || null,
    from,
    fromName: env("SMTP_SENDER_NAME") || "BOSS",
    allowPlaintext: env("SMTP_ALLOW_PLAINTEXT") === "true",
  };
}

/** Where the join link points: the agents console, `<CONSOLE_URL>/join/<token>`. https only (http for loopback). */
export function consoleUrlFromEnv(raw = env("CONSOLE_URL")): string | null {
  if (!raw) return null;
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    return null;
  }
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(u.hostname);
  if (u.protocol !== "https:" && !(u.protocol === "http:" && loopback)) return null;
  if (u.search || u.hash || u.username || u.password) return null;
  return u.origin + u.pathname.replace(/\/+$/, "");
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
    async prepare(actor, orgId, inviteId, token) {
      const r = await fetch(`${url}/rest/v1/rpc/org_invite_mail_prepare`, {
        method: "POST",
        headers: {
          apikey: service,
          Authorization: `Bearer ${service}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          p_actor: actor,
          p_org_id: orgId,
          p_invite_id: inviteId,
          p_token: token,
        }),
        signal: timeout(),
      });
      if (!r.ok) {
        const e = await r.json().catch(() => ({})) as { code?: string; message?: string };
        if (e.code === "42501") throw new Refused(403, "permission_denied");
        const m = (e.message ?? "").trim();
        if (m === "invite_not_found") throw new Refused(404, m);
        if (m === "invite_not_pending" || m === "invite_not_email") throw new Refused(409, m);
        if (m === "rate_limited_org" || m === "rate_limited_recipient") throw new Refused(429, m);
        throw new Error(`prepare ${r.status}`);
      }
      return await r.json() as InviteFacts & { org_slug: string | null };
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

/** Log lines carry the invite id and the outcome; never the token, the address or SMTP details. */
function log(inviteId: string, outcome: string) {
  console.info(`org-invite-mail invite=${inviteId} ${outcome}`);
}

/**
 * POST /org-invite-mail {org_id, invite_id, token}: an org administrator (their own JWT) asks for the invite
 * they just made with org_team_invite to be emailed to its address, with a link to the console's /join/<token>.
 * 200 {sent: true, email} | 400 bad body | 401 no/invalid JWT | 403 not allowed to invite in the org |
 * 404 not this org's invite (or the token is not the invite's) | 409 not pending | 429 rate limited |
 * 502 the mail server refused | 503 not configured or the database is unreachable.
 */
export function createApp(
  options: { db?: Db; mailer?: Mailer; consoleUrl?: string | null; configured?: boolean } = {},
) {
  const smtp = options.mailer ? null : smtpFromEnv();
  const mailer: Mailer | null = options.mailer ??
    (smtp ? (msg) => sendMail(smtp, msg) : null);
  const db = options.db ?? restDb();
  const consoleUrl = options.consoleUrl !== undefined ? options.consoleUrl : consoleUrlFromEnv();
  return async (request: Request): Promise<Response> => {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const path = new URL(request.url).pathname.replace(/\/+$/, "");
    if (!path.endsWith("/org-invite-mail")) return json(404, { error: "Not found" });
    if (request.method !== "POST") return json(405, { error: "Method not allowed" });
    const auth = request.headers.get("Authorization") ?? "";
    const jwt = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
    if (!jwt) return json(401, { error: "Unauthorized" });

    let orgId: string, inviteId: string, token: string;
    try {
      const text = await request.text();
      if (text.length > MAX_BODY) throw new Error("body");
      const b = JSON.parse(text);
      if (
        !b || typeof b.org_id !== "string" || !UUID.test(b.org_id) ||
        typeof b.invite_id !== "string" || !UUID.test(b.invite_id) ||
        typeof b.token !== "string" || !TOKEN.test(b.token)
      ) throw new Error("body");
      orgId = b.org_id.toLowerCase();
      inviteId = b.invite_id.toLowerCase();
      token = b.token;
    } catch {
      return json(400, { error: "Invalid request" });
    }
    if (!mailer || !consoleUrl) return json(503, { error: "Not configured" });

    let facts: InviteFacts & { org_slug: string | null };
    try {
      const actor = await db.user(jwt);
      if (!actor) return json(401, { error: "Unauthorized" });
      facts = await db.prepare(actor, orgId, inviteId, token);
    } catch (e) {
      if (e instanceof Refused) {
        log(inviteId, `refused ${e.code}`);
        return json(e.status, { error: e.code });
      }
      return json(503, { error: "Unavailable" });
    }
    if (!isAddress(facts.email)) return json(409, { error: "invite_not_email" });

    const link = `${consoleUrl}/join/${encodeURIComponent(token)}`;
    const mail = inviteMail(facts, link);
    try {
      await mailer({ to: facts.email, ...mail });
    } catch {
      log(inviteId, "smtp failed");
      return json(502, { error: "mail_failed" });
    }
    log(inviteId, "sent");
    return json(200, { sent: true, email: facts.email });
  };
}
