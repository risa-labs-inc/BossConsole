import {
  type AgentInviteFacts,
  agentInviteMail,
  type InviteFacts,
  inviteMail,
  type LinkInviteFacts,
  linkInviteMail,
} from "./mail.ts";
import { isAddress, type MailMessage, sendMail, type SmtpConfig, smtpFailure } from "./smtp.ts";

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

/**
 * How long invite_link_mail_prepare may run before its work is thrown away. The budget travels with
 * the request (X-Invite-Budget-Ms) and the database compares it with how long the statement has run
 * on its own clock, then rolls the call back when it is over. It is a duration, not a time of day,
 * so this host's clock and the database's are never compared. A call this function has stopped
 * waiting for is therefore not left behind as a link (short of the margin below being used up).
 */
export const PREPARE_BUDGET_MS = 5000;
/**
 * How long this function waits for that call: the budget plus a margin for what the database does
 * not measure (the wait for a connection before the statement starts, the commit, the answer).
 */
export const PREPARE_WAIT_MS = 8000;
/** The request header that carries the budget; invite_link_mail_budget_ms reads it. */
export const BUDGET_HEADER = "X-Invite-Budget-Ms";
/** How long after a failed cleanup the one background retry runs. */
export const CLEANUP_RETRY_DELAY_MS = 2000;

// deno-lint-ignore no-control-regex
const CONTROL = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]+/g;

/** Why a database or auth call failed, for the log: "timeout", or the error's own short text. Never an address or a token. */
export function dbFailure(e: unknown): string {
  if (!(e instanceof Error)) return "error";
  if (e.name === "TimeoutError" || e.name === "AbortError") return "timeout";
  return e.message.replace(CONTROL, " ").replace(/\s+/g, " ").replace(
    /[^\s@]+@[^\s@]+/g,
    "<address>",
  )
    .slice(0, 120);
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
  /**
   * agent_invite_mail_prepare as the service role: re-checks that `actor` holds agents.access on the agent's org, that the
   * invite exists and the agent is live; applies the rate limits and records the send.
   */
  prepareAgent(actor: string, agentId: string, email: string): Promise<AgentInviteFacts>;
  /**
   * invite_link_mail_prepare as the service role: a new email invite for `email` on `target` (policy, quotas and mail limits
   * as create_invite_link plus the invite mail limits), or with `linkId` a resend that rotates the token. Throws Refused
   * for the refusals the function returns. The call carries a budget (PREPARE_BUDGET_MS): over it the database rolls the
   * call back, so a timeout here means no link was made and no token was rotated. Logs once per call when the answer
   * does not confirm the budget (a database without the migration, or a header that did not arrive).
   */
  prepareLink(
    actor: string,
    req: { target: string; email: string; via: string } | { linkId: string },
  ): Promise<LinkInviteFacts>;
  /**
   * invite_link_mail_failed as the service role, when the mail did not go out: a new invite's link is deleted, a resend gets
   * its earlier token and last_sent_at back, and that send's mail-log rows are removed. `token` is the one this send issued;
   * the database ignores the report when it is no longer the link's current token. Best effort and never throws: one
   * attempt is awaited; if it fails, one more runs in the background after CLEANUP_RETRY_DELAY_MS so the caller's
   * answer is not held up. Failures are logged (link id only).
   */
  mailFailed(linkId: string, resend: boolean, token: string): Promise<void>;
  /** invite_link_mail_sent as the service role: the mail went out, so the saved previous token is dropped (if `token` is current). Best effort. */
  mailSent(linkId: string, token: string): Promise<void>;
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

/** Keeps `work` alive after the response has gone out, where the runtime offers that (EdgeRuntime.waitUntil). */
function afterResponse(work: Promise<unknown>): void {
  const rt = (globalThis as { EdgeRuntime?: { waitUntil?: (p: Promise<unknown>) => void } })
    .EdgeRuntime;
  try {
    rt?.waitUntil?.(work);
  } catch { /* not available here: the promise still runs while the worker lives */ }
}

/** The JSON body of a refused call, {} when it is not JSON. The body is always consumed or cancelled. */
async function errorBody(r: Response): Promise<{ code?: string; message?: string }> {
  try {
    const parsed: unknown = JSON.parse(await r.text());
    return parsed && typeof parsed === "object"
      ? parsed as { code?: string; message?: string }
      : {};
  } catch {
    await r.body?.cancel().catch(() => {});
    return {};
  }
}

export interface RestDbOptions {
  /** Delay before the background cleanup retry. */
  retryDelayMs?: number;
  /** Runs work that may outlive the response; tests collect it to await it. */
  background?: (work: Promise<unknown>) => void;
}

export function restDb(options: RestDbOptions = {}): Db {
  const url = env("SUPABASE_URL");
  const anon = env("SUPABASE_ANON_KEY");
  const service = env("SUPABASE_SERVICE_ROLE_KEY");
  const timeout = () => AbortSignal.timeout(5000);
  const retryDelayMs = options.retryDelayMs ?? CLEANUP_RETRY_DELAY_MS;
  const background = options.background ?? afterResponse;
  /** One invite_link_mail_failed call: null when it landed, otherwise why not. Never throws. */
  const cleanup = async (
    linkId: string,
    resend: boolean,
    token: string,
  ): Promise<string | null> => {
    try {
      const r = await fetch(`${url}/rest/v1/rpc/invite_link_mail_failed`, {
        method: "POST",
        headers: {
          apikey: service,
          Authorization: `Bearer ${service}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ p_link_id: linkId, p_resend: resend, p_token: token }),
        signal: timeout(),
      });
      await r.body?.cancel().catch(() => {});
      return r.ok ? null : `http ${r.status}`;
    } catch (e) {
      return dbFailure(e);
    }
  };
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
        const e = await errorBody(r);
        if (e.code === "42501") throw new Refused(403, "permission_denied");
        const m = (e.message ?? "").trim();
        if (m === "invite_not_found") throw new Refused(404, m);
        if (m === "invite_not_pending" || m === "invite_not_email") throw new Refused(409, m);
        if (m === "rate_limited_org" || m === "rate_limited_recipient") throw new Refused(429, m);
        throw new Error(`prepare ${r.status}`);
      }
      return await r.json() as InviteFacts & { org_slug: string | null };
    },
    async prepareAgent(actor, agentId, email) {
      const r = await fetch(`${url}/rest/v1/rpc/agent_invite_mail_prepare`, {
        method: "POST",
        headers: {
          apikey: service,
          Authorization: `Bearer ${service}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ p_actor: actor, p_agent_id: agentId, p_email: email }),
        signal: timeout(),
      });
      if (!r.ok) {
        const e = await errorBody(r);
        if (e.code === "42501") throw new Refused(403, "permission_denied");
        const m = (e.message ?? "").trim();
        if (m === "invite_not_found") throw new Refused(404, m);
        if (m === "agent_not_live") throw new Refused(409, m);
        if (m === "rate_limited_org" || m === "rate_limited_recipient") throw new Refused(429, m);
        throw new Error(`prepare ${r.status}`);
      }
      return await r.json() as AgentInviteFacts;
    },
    async prepareLink(actor, req) {
      const r = await fetch(`${url}/rest/v1/rpc/invite_link_mail_prepare`, {
        method: "POST",
        headers: {
          apikey: service,
          Authorization: `Bearer ${service}`,
          "Content-Type": "application/json",
          [BUDGET_HEADER]: String(PREPARE_BUDGET_MS),
        },
        body: JSON.stringify(
          "linkId" in req
            ? { p_actor: actor, p_target: null, p_email: null, p_link_id: req.linkId }
            : { p_actor: actor, p_target: req.target, p_email: req.email, p_via: req.via },
        ),
        signal: AbortSignal.timeout(PREPARE_WAIT_MS),
      });
      if (!r.ok) {
        const e = await errorBody(r);
        // 504 budget_exceeded: the database gave the work up (see PREPARE_BUDGET_MS).
        throw new Error(
          e.message === "budget_exceeded" ? "prepare budget_exceeded" : `prepare ${r.status}`,
        );
      }
      const out = await r.json() as
        & { success?: boolean; error?: string; budget_ms?: unknown }
        & Partial<LinkInviteFacts>;
      if (out.success !== true) {
        const code = String(out.error ?? "");
        throw new Refused(LINK_REFUSALS[code] ?? 503, code in LINK_REFUSALS ? code : "unavailable");
      }
      // The database echoes the budget it applied. Anything else means this call ran unprotected:
      // the migration is not applied there, or the header did not reach it.
      if (out.budget_ms !== PREPARE_BUDGET_MS) {
        console.warn(
          `org-invite-mail link=${
            UUID.test(String(out.link_id)) ? out.link_id : "?"
          } prepare ran without a budget (database answered budget_ms=${
            typeof out.budget_ms === "number" ? out.budget_ms : "none"
          }, sent ${PREPARE_BUDGET_MS})`,
        );
      }
      return out as LinkInviteFacts;
    },
    async mailFailed(linkId, resend, token) {
      // This is what keeps a link whose mail never left from counting against the sender's cap.
      const why = await cleanup(linkId, resend, token);
      if (why === null) return;
      console.error(
        `org-invite-mail link=${linkId} cleanup failed (${why}) attempt=1/2: retrying in the background`,
      );
      // Not awaited: the caller already has a failure to report, and an immediate second try
      // against a database that just failed is the one least likely to work.
      background((async () => {
        await new Promise((done) => setTimeout(done, retryDelayMs));
        const again = await cleanup(linkId, resend, token);
        if (again !== null) {
          console.error(
            `org-invite-mail link=${linkId} cleanup failed (${again}) attempt=2/2: the link may still be active`,
          );
        }
      })());
    },
    async mailSent(linkId, token) {
      try {
        const r = await fetch(`${url}/rest/v1/rpc/invite_link_mail_sent`, {
          method: "POST",
          headers: {
            apikey: service,
            Authorization: `Bearer ${service}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ p_link_id: linkId, p_token: token }),
          signal: timeout(),
        });
        await r.body?.cancel().catch(() => {});
      } catch { /* the mail went out; the saved token is harmless until the next resend */ }
    },
  };
}

/** The RPC's refusals as HTTP answers. */
const LINK_REFUSALS: Record<string, number> = {
  bad_request: 400,
  bad_email: 400,
  not_allowed: 403,
  invites_off: 403,
  not_found: 404,
  not_pending: 409,
  resend_in_progress: 409,
  too_many_active_links: 409,
  rate_limited: 429,
  quota_exceeded: 429,
};

const HOST =
  /^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/;
const SLUG = /^[a-z][a-z0-9-]{1,30}[a-z0-9]$/;

/** The agents' base domain (AGENTS_BASE_DOMAIN): an agent's site is https://<slug>.<base>/. null when unset or malformed. */
export function baseDomainFromEnv(raw = env("AGENTS_BASE_DOMAIN")): string | null {
  const base = raw.trim().toLowerCase().replace(/^\.+|\.+$/g, "");
  return HOST.test(base) ? base : null;
}

/** Where an agent invite sends people: the agent's active custom domain, else its own host under the base. */
export function agentSiteUrl(
  facts: Pick<AgentInviteFacts, "agent_slug" | "custom_host">,
  base: string,
): string | null {
  const custom = (facts.custom_host ?? "").toLowerCase();
  if (custom && HOST.test(custom)) return `https://${custom}/`;
  return SLUG.test(facts.agent_slug) ? `https://${facts.agent_slug}.${base}/` : null;
}

/** Where a link's /i/<token> page lives: https only (http for loopback), a bare origin. null when unset or malformed. */
export function originFromEnv(raw: string): string | null {
  return consoleUrlFromEnv(raw);
}

/** Origins of the platforms' own sites; overridable for local runs (INVITE_ORIGIN_OPTIMIST, INVITE_ORIGIN_MAX). */
export function platformOrigins(
  optimist = env("INVITE_ORIGIN_OPTIMIST"),
  max = env("INVITE_ORIGIN_MAX"),
): Record<string, string> {
  return {
    optimist: originFromEnv(optimist) ?? "https://askoptimist.com",
    max: originFromEnv(max) ?? "https://evolvemax.com",
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

/**
 * Log lines carry the invite (or agent) id and the outcome; never the token, the address or a credential.
 * A failed send adds why (smtpFailure: stage, port, reply code and text with addresses masked).
 */
function log(inviteId: string, outcome: string) {
  console.info(`org-invite-mail invite=${inviteId} ${outcome}`);
}
function logAgent(agentId: string, outcome: string) {
  console.info(`org-invite-mail agent=${agentId} ${outcome}`);
}

/** Link ids and outcomes only; never the token or the address. */
function logLink(ref: string, outcome: string) {
  console.info(`org-invite-mail link=${ref} ${outcome}`);
}
const TOKEN_43 = /^[A-Za-z0-9_-]{43}$/;

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * POST /org-invite-mail {org_id, invite_id, token}: an org administrator (their own JWT) asks for the invite
 * they just made with org_team_invite to be emailed to its address, with a link to the console's /join/<token>.
 * 200 {sent: true, email} | 400 bad body | 401 no/invalid JWT | 403 not allowed to invite in the org |
 * 404 not this org's invite (or the token is not the invite's) | 409 not pending | 429 rate limited |
 * 503 mail_failed (the mail server did not take the mail) | 503 not configured or the database is unreachable.
 * On all three routes a mail failure is a 503 with error mail_failed, never a 502: the gateway in front of the project
 * answers a function's 502 itself, without the CORS headers, so the browser reports a failed fetch and the code is lost.
 */
export function createApp(
  options: {
    db?: Db;
    mailer?: Mailer;
    consoleUrl?: string | null;
    baseDomain?: string | null;
    configured?: boolean;
    origins?: Record<string, string>;
  } = {},
) {
  const smtp = options.mailer ? null : smtpFromEnv();
  const mailer: Mailer | null = options.mailer ??
    (smtp ? (msg) => sendMail(smtp, msg) : null);
  const db = options.db ?? restDb();
  const consoleUrl = options.consoleUrl !== undefined ? options.consoleUrl : consoleUrlFromEnv();
  const baseDomain = options.baseDomain !== undefined ? options.baseDomain : baseDomainFromEnv();
  const origins = options.origins ?? platformOrigins();

  /**
   * POST /org-invite-mail/agent {agent_id, email}: someone with agents.access on the agent's org (their own JWT) asks for an
   * agent invite (already in agent_invites) to be emailed, with a link to the agent's site. Same answers as the classic
   * route (see createApp), plus 409 agent_not_live.
   */
  const agentInvite = async (request: Request): Promise<Response> => {
    const auth = request.headers.get("Authorization") ?? "";
    const jwt = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
    if (!jwt) return json(401, { error: "Unauthorized" });
    let agentId: string, email: string;
    try {
      const text = await request.text();
      if (text.length > MAX_BODY) throw new Error("body");
      const b = JSON.parse(text);
      if (
        !b || typeof b.agent_id !== "string" || !UUID.test(b.agent_id) ||
        typeof b.email !== "string" || b.email.length > 320 || !EMAIL.test(b.email.trim())
      ) throw new Error("body");
      agentId = b.agent_id.toLowerCase();
      email = b.email.trim().toLowerCase();
    } catch {
      return json(400, { error: "Invalid request" });
    }
    if (!mailer || !baseDomain) return json(503, { error: "Not configured" });
    let facts: AgentInviteFacts;
    try {
      const actor = await db.user(jwt);
      if (!actor) return json(401, { error: "Unauthorized" });
      facts = await db.prepareAgent(actor, agentId, email);
    } catch (e) {
      if (e instanceof Refused) {
        logAgent(agentId, `refused ${e.code}`);
        return json(e.status, { error: e.code });
      }
      logAgent(agentId, `unavailable ${dbFailure(e)}`);
      return json(503, { error: "Unavailable" });
    }
    const link = agentSiteUrl(facts, baseDomain);
    if (!isAddress(facts.email) || !link) return json(409, { error: "invite_not_email" });
    try {
      await mailer({ to: facts.email, ...agentInviteMail(facts, link) });
    } catch (e) {
      logAgent(agentId, `smtp failed ${smtpFailure(e)}`);
      return json(503, { error: "mail_failed" });
    }
    logAgent(agentId, "sent");
    return json(200, { sent: true, email: facts.email });
  };

  /**
   * POST /org-invite-mail/link {target, email, via: "max"|"console"} or {link_id}: someone who may invite to the target (their
   * own JWT) emails a single-use invite link bound to one address, or re-sends one (the token rotates, the earlier mail stops
   * working). The link origin is built here from the target, never from the request.
   * 200 {sent, id, recipient, expires_at} | 400 bad_request, bad_email | 401 | 403 not_allowed, invites_off | 404 not_found |
   * 409 not_pending, resend_in_progress, too_many_active_links | 429 rate_limited, quota_exceeded |
   * 503 mail_failed (the mail server did not take the mail; the link is dropped, a resend gets its earlier token back) |
   * 503 service_unavailable (sign-in check or database did not answer in time; nothing was made) |
   * 503 mail_unavailable (mail is not set up here, or the link could not be addressed).
   * The three 503s differ by code only (see createApp for why none of them is a 502).
   */
  const linkInvite = async (request: Request): Promise<Response> => {
    const auth = request.headers.get("Authorization") ?? "";
    const jwt = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
    if (!jwt) return json(401, { error: "Unauthorized" });
    let req: { target: string; email: string; via: string } | { linkId: string };
    try {
      const text = await request.text();
      if (text.length > MAX_BODY) throw new Error("body");
      const b = JSON.parse(text);
      if (!b || typeof b !== "object") throw new Error("body");
      if (b.link_id !== undefined) {
        if (typeof b.link_id !== "string" || !UUID.test(b.link_id)) throw new Error("body");
        req = { linkId: b.link_id.toLowerCase() };
      } else {
        if (
          typeof b.target !== "string" || !/^[a-z][a-z0-9-]{1,30}[a-z0-9]$/.test(b.target) ||
          (b.via !== "max" && b.via !== "console") || typeof b.email !== "string"
        ) throw new Error("body");
        const email = b.email.trim().toLowerCase();
        if (email.length > 320 || !EMAIL.test(email)) {
          return json(400, { error: "bad_email" });
        }
        req = { target: b.target, email, via: b.via };
      }
    } catch {
      return json(400, { error: "bad_request" });
    }
    // An agent link needs the base domain to be addressed; refuse before a link is issued that could not be mailed.
    const platform = "target" in req && req.target in origins;
    if (!mailer || (!platform && !baseDomain)) return json(503, { error: "mail_unavailable" });
    let facts: LinkInviteFacts;
    const ref = "linkId" in req ? req.linkId : req.target;
    try {
      const actor = await db.user(jwt);
      if (!actor) return json(401, { error: "Unauthorized" });
      facts = await db.prepareLink(actor, req);
    } catch (e) {
      if (e instanceof Refused) {
        logLink(ref, `refused ${e.code}`);
        return json(e.status, { error: e.code });
      }
      logLink(ref, `unavailable ${dbFailure(e)}`);
      return json(503, { error: "service_unavailable" });
    }
    let origin: string | null;
    if (facts.agent_slug) {
      const site = baseDomain
        ? agentSiteUrl({ agent_slug: facts.agent_slug, custom_host: facts.custom_host }, baseDomain)
        : null;
      origin = site ? site.replace(/\/+$/, "") : null;
    } else {
      origin = origins[facts.target] ?? null;
    }
    if (!origin || !isAddress(facts.recipient_email) || !TOKEN_43.test(facts.token)) {
      logLink(facts.link_id, "no origin");
      await db.mailFailed(facts.link_id, "linkId" in req, facts.token).catch(() => {});
      return json(503, { error: "mail_unavailable" });
    }
    try {
      await mailer({
        to: facts.recipient_email,
        ...linkInviteMail(facts, `${origin}/i/${facts.token}`),
      });
    } catch (e) {
      logLink(facts.link_id, `smtp failed ${smtpFailure(e)}`);
      await db.mailFailed(facts.link_id, "linkId" in req, facts.token).catch(() => {});
      return json(503, { error: "mail_failed" });
    }
    await db.mailSent(facts.link_id, facts.token).catch(() => {});
    logLink(facts.link_id, "sent");
    return json(200, {
      sent: true,
      id: facts.link_id,
      recipient: facts.recipient_email,
      expires_at: facts.expires_at,
    });
  };

  return async (request: Request): Promise<Response> => {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const path = new URL(request.url).pathname.replace(/\/+$/, "");
    if (path.endsWith("/org-invite-mail/agent")) {
      return request.method === "POST"
        ? agentInvite(request)
        : json(405, { error: "Method not allowed" });
    }
    if (path.endsWith("/org-invite-mail/link")) {
      return request.method === "POST"
        ? linkInvite(request)
        : json(405, { error: "Method not allowed" });
    }
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
      log(inviteId, `unavailable ${dbFailure(e)}`);
      return json(503, { error: "Unavailable" });
    }
    if (!isAddress(facts.email)) return json(409, { error: "invite_not_email" });

    const link = `${consoleUrl}/join/${encodeURIComponent(token)}`;
    const mail = inviteMail(facts, link);
    try {
      await mailer({ to: facts.email, ...mail });
    } catch (e) {
      log(inviteId, `smtp failed ${smtpFailure(e)}`);
      return json(503, { error: "mail_failed" });
    }
    log(inviteId, "sent");
    return json(200, { sent: true, email: facts.email });
  };
}
