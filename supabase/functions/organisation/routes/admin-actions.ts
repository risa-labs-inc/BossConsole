/**
 * POST /o/:slug/admin/* -- the mutating admin actions.
 *
 * Every handler runs the same sequence, and it is the sequence, not any one
 * step, that is the protection:
 *
 *   requireCsrfBody  ->  requireOrgAdmin (live probe)  ->  rate limit
 *                    ->  validate  ->  callForActor  ->  303
 *
 * CSRF first so a forged post cannot spend rate-limit budget or reach the
 * database. The admin probe second, because it is the authorization decision
 * and it must not be inferred from the page having rendered a button. Answering
 * 303 last so a reload does not repeat the action.
 *
 * The one deliberate exception is invite creation, which renders 200 inline:
 * the plaintext token exists for exactly one response and a redirect would
 * discard it.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { callForActor } from "../utils/org-rpc.ts"
import { loadAdminPageData } from "../services/org.ts"
import { htmlResponse, redirectResponse } from "../utils/responses.ts"
import { clientKey, rateLimit } from "../utils/rate-limit.ts"
import { publicBaseUrl } from "../utils/config.ts"
import { mintSession, newCsrfToken, sessionCookieHeader } from "../utils/session.ts"
import { inviteOnlyPage } from "../views/admin.ts"
import { checkbox, field, intField, isHttpUrl, rawField, uuidField } from "../utils/request.ts"
import { requireCsrfBody, requireOrgAdmin, requireOrgSession } from "./guards.ts"
import { adminPage } from "../views/admin.ts"
import type { SessionPayload } from "../utils/session.ts"
import type { RequestFacts } from "../utils/request.ts"

/** 60 admin writes per minute per client. A human clicking; not a script. */
const WRITE_LIMIT = 60
const WRITE_WINDOW_SECONDS = 60

export const adminActionRoutes = new OpenAPIHono()

interface Prepared {
  session: SessionPayload
  facts: RequestFacts
  body: Record<string, unknown>
}

/**
 * Run the shared preamble for a mutating admin request.
 *
 * Returns the pieces a handler needs, or the response it must return instead.
 */
async function prepare(
  // deno-lint-ignore no-explicit-any
  ctx: any,
): Promise<{ ok: true; value: Prepared } | { ok: false; response: Response }> {
  // 1. Session, from the cookie alone. No database.
  const sessionGuard = await requireOrgSession(ctx)
  if (!sessionGuard.ok) return { ok: false, response: sessionGuard.response }
  const { session, facts } = sessionGuard.value

  // 2. CSRF, before anything touches the database, so a forged post costs one
  //    HMAC verification and stops there.
  const csrf = await requireCsrfBody(ctx, session, facts.expectedOrigin)
  if (!csrf.ok) return { ok: false, response: csrf.response }

  // 3. The live authority probe. This is the authorization decision.
  const adminGuard = await requireOrgAdmin(sessionGuard.value)
  if (!adminGuard.ok) return { ok: false, response: adminGuard.response }

  // 4. Rate limit, last of the gates: an authenticated admin's own budget.
  const limit = rateLimit(
    `adminwrite:${clientKey(ctx.req.raw.headers)}`,
    WRITE_LIMIT,
    WRITE_WINDOW_SECONDS,
  )
  if (!limit.allowed) {
    return { ok: false, response: redirectTo(facts, session.slug, { err: "rate_limited" }) }
  }

  return { ok: true, value: { session, facts, body: csrf.value } }
}

/** 303 back to the admin page with a result key. */
function redirectTo(
  facts: RequestFacts,
  slug: string,
  result: { ok?: string; err?: string },
): Response {
  const base = `${facts.basePath}/o/${encodeURIComponent(slug)}/admin`
  const query = result.err
    ? `?err=${encodeURIComponent(result.err)}`
    : result.ok
    ? `?ok=${encodeURIComponent(result.ok)}`
    : ""
  return redirectResponse(`${base}${query}`)
}

/**
 * Map an RPC outcome onto a redirect.
 *
 * The RPC's own error text is NOT forwarded into the URL. Those messages are
 * written for a developer ("Permission denied", "Organisation not found") and
 * reflecting one into the query string would put RPC-controlled text on the
 * page. The fixed key table in admin-page.ts is the whole vocabulary.
 */
function finish(
  facts: RequestFacts,
  slug: string,
  ok: boolean,
  okKey: string,
): Response {
  return redirectTo(facts, slug, ok ? { ok: okKey } : { err: "rejected" })
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

adminActionRoutes.post("/o/:slug/admin/settings", async (ctx) => {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const name = field(body, "name")
  if (!name || name.length > 120) {
    return redirectTo(facts, session.slug, { err: "invalid_input" })
  }

  const publishRoleId = uuidField(body, "publish_role_id")

  let visibility: string | null
  let joinPolicy: string | null
  let publishPolicy: string | null
  try {
    visibility = oneOf(field(body, "visibility"), ["private", "public"])
    joinPolicy = oneOf(field(body, "join_policy"), ["invite_only", "request_to_join", "open"])
    publishPolicy = oneOf(field(body, "publish_policy"), ["owner_only", "admins", "members"])
  } catch {
    return redirectTo(facts, session.slug, { err: "invalid_input" })
  }

  // Validated HERE as well as in the RPC, because the RPC's readable sentence never
  // reaches the page: finish() collapses every failure to err=rejected, which renders
  // as "The change was refused" - the exact generic message the database-side check
  // was added to replace. A dedicated key is the only way to name the field while
  // keeping the fixed-key vocabulary that stops caller text being reflected.
  //
  // Deliberately duplicated rather than forwarded: the RPC keeps its own check for
  // callers that are not this route, and this one exists so the admin is told which
  // field to fix.
  const websiteRaw = rawField(body, "website")
  if (websiteRaw !== null && websiteRaw.trim() !== "" && !isHttpUrl(websiteRaw.trim())) {
    return redirectTo(facts, session.slug, { err: "invalid_website" })
  }

  const result = await callForActor("update_organisation_settings", session.sub, {
    p_org_id: session.org,
    p_name: name,
    // PRESENT-but-empty means clear; only ABSENT means leave unchanged. field() collapses the
    // two to null and the RPC does COALESCE(p_description, description), so emptying the
    // textarea reported ?ok=settings_saved with the old text still on the page - the same
    // no-op-reported-as-success shape as the max_uses bug.
    p_description: rawField(body, "description"),
    // rawField, like the description: an empty string CLEARS it, and only a missing
    // value leaves it alone. field() would collapse the two.
    p_website: rawField(body, "website"),
    p_visibility: visibility,
    p_join_policy: joinPolicy,
    p_publish_policy: publishPolicy,
    p_publish_role_id: publishRoleId,
    // An empty <select> means "use the policy", which COALESCE cannot express:
    // NULL already means "leave unchanged" for every other parameter.
    p_clear_publish_role: publishRoleId === null,
    p_auto_assign_member_role: checkbox(body, "auto_assign_member_role"),
  })

  return finish(facts, session.slug, result.ok, "settings_saved")
})

// ---------------------------------------------------------------------------
// Members
// ---------------------------------------------------------------------------

adminActionRoutes.post("/o/:slug/admin/members/approve", (ctx) => memberAction(ctx, "approve"))
adminActionRoutes.post("/o/:slug/admin/members/reject", (ctx) => memberAction(ctx, "reject"))
adminActionRoutes.post("/o/:slug/admin/members/remove", (ctx) => memberAction(ctx, "remove"))

// deno-lint-ignore no-explicit-any
async function memberAction(ctx: any, kind: "approve" | "reject" | "remove"): Promise<Response> {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const userId = uuidField(body, "user_id")
  if (!userId) return redirectTo(facts, session.slug, { err: "invalid_input" })

  const fn = {
    approve: "approve_organisation_member",
    reject: "reject_organisation_member",
    remove: "remove_organisation_member",
  }[kind]

  const okKey = { approve: "member_approved", reject: "member_rejected", remove: "member_removed" }[
    kind
  ]

  const result = await callForActor(fn, session.sub, {
    p_org_id: session.org,
    p_user_id: userId,
  })

  return finish(facts, session.slug, result.ok, okKey)
}

adminActionRoutes.post("/o/:slug/admin/members/role", async (ctx) => {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const userId = uuidField(body, "user_id")
  const roleId = uuidField(body, "role_id")
  if (!userId || !roleId) return redirectTo(facts, session.slug, { err: "invalid_input" })

  // assign_organisation_role re-checks that the role belongs to THIS org. The
  // uuid being well-formed says nothing about which organisation owns it.
  const result = await callForActor("assign_organisation_role", session.sub, {
    p_org_id: session.org,
    p_user_id: userId,
    p_role_id: roleId,
  })

  return finish(facts, session.slug, result.ok, "role_assigned")
})

// ---------------------------------------------------------------------------
// Roles
// ---------------------------------------------------------------------------

adminActionRoutes.post("/o/:slug/admin/roles/create", async (ctx) => {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const suffix = field(body, "suffix")
  if (!suffix || !/^[a-z][a-z0-9_]{1,30}$/.test(suffix)) {
    return redirectTo(facts, session.slug, { err: "invalid_input" })
  }

  const result = await callForActor("create_organisation_role", session.sub, {
    p_org_id: session.org,
    p_suffix: suffix,
    p_description: field(body, "description"),
  })

  return finish(facts, session.slug, result.ok, "role_created")
})

adminActionRoutes.post("/o/:slug/admin/roles/delete", async (ctx) => {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const roleId = uuidField(body, "role_id")
  if (!roleId) return redirectTo(facts, session.slug, { err: "invalid_input" })

  const result = await callForActor("delete_organisation_role", session.sub, {
    p_org_id: session.org,
    p_role_id: roleId,
  })

  return finish(facts, session.slug, result.ok, "role_deleted")
})

// ---------------------------------------------------------------------------
// Invites
// ---------------------------------------------------------------------------

adminActionRoutes.post("/o/:slug/admin/invites/create", async (ctx) => {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const expiresInHours = intField(body, "expires_in_hours", 1, 720)
  if (expiresInHours === null) return redirectTo(facts, session.slug, { err: "invalid_input" })

  // max_uses is optional, so absent is fine - but PRESENT AND INVALID is not.
  // intField returns null for both, and null means "unlimited" downstream, so
  // max_uses=5000 or max_uses=0 used to produce a link with no cap at all: the
  // exact opposite of what the admin asked for. The min/max on the input are
  // client-side only.
  const maxUsesRaw = field(body, "max_uses")
  const maxUses = intField(body, "max_uses", 1, 1000)
  if (maxUsesRaw !== null && maxUses === null) {
    return redirectTo(facts, session.slug, { err: "invalid_input" })
  }

  const result = await callForActor<{ token?: unknown }>(
    "create_organisation_invite",
    session.sub,
    {
      p_org_id: session.org,
      p_role_id: uuidField(body, "role_id"),
      p_label: field(body, "label"),
      p_max_uses: maxUses,
      p_expires_in_hours: expiresInHours,
    },
  )

  if (!result.ok) return redirectTo(facts, session.slug, { err: "rejected" })

  const token = typeof result.data.token === "string" ? result.data.token : null
  if (!token) return redirectTo(facts, session.slug, { err: "rejected" })

  // The invite link. Absolute ONLY when ORG_PUBLIC_BASE_URL is configured --
  // publicBaseUrl() deliberately takes no request headers any more: Host and
  // X-Forwarded-Host are client-suppliable wherever the edge is not behind a
  // strict proxy that overwrites them, and this URL embeds the one-time join
  // token. The old builder preferred x-forwarded-host, so a poisoned header
  // minted `https://attacker.example/join/<token>` and handed the token over.
  // With the env var unset the card shows the relative path, which still works
  // pasted into the origin the admin is reading and cannot be pointed at
  // anyone else's host. The token is shown exactly once either way.
  const inviteUrl = `${publicBaseUrl()}/join/${encodeURIComponent(token)}`

  // A FRESH session, so reloading this page cannot mint a second invite.
  //
  // This handler answers 200 rather than 303 because the plaintext token exists for exactly one
  // response - which means the browser's reload button re-submits the form, and an admin hitting
  // F5 silently created a second live link while the first stayed live. Rotating the CSRF nonce
  // invalidates the form that is still sitting in the page, so the resubmission is refused by the
  // existing gate rather than needing a new mechanism.
  // Held in a variable rather than recovered by re-verifying the token just minted. The round
  // trip was wasteful, but the `?? session.csrf` fallback was the real problem: had it fired,
  // the page would render the OLD nonce while the cookie carried the new one, so every form on
  // the freshly-rendered admin page would 403 immediately after the operator was told the invite
  // was created. Failing into a broken page is worse than failing loudly.
  const rotatedCsrf = newCsrfToken()
  const rotated = await mintSession({
    sub: session.sub,
    org: session.org,
    slug: session.slug,
    csrf: rotatedCsrf,
    pur: session.pur,
  })
  const rotatedCookie = sessionCookieHeader(rotated, facts.secure, facts.basePath)

  // Rendered inline rather than redirected: this response is the only place the plaintext token
  // will ever exist.
  const data = await loadAdminPageData(session.sub, session.org)

  // If the page read fails the invite still EXISTS and is live, so redirecting would lose its
  // plaintext forever while telling the admin it worked - they would have to hunt it down by
  // prefix and revoke it. The url does not depend on `data`, so render the card alone.
  if (!data.ok) {
    return htmlResponse(
      (nonce) => inviteOnlyPage(nonce, facts.basePath, session.slug, inviteUrl),
      { headers: { "Set-Cookie": rotatedCookie } },
    )
  }

  // Rendered inline rather than redirected: this response is the only place the
  // plaintext token will ever exist -- in the URL publicBaseUrl() just built,
  // absolute when ORG_PUBLIC_BASE_URL is configured and relative otherwise.
  return htmlResponse((nonce) =>
    adminPage({
      nonce,
      basePath: facts.basePath,
      csrf: rotatedCsrf,
      org: data.org,
      members: data.members,
      roles: data.roles,
      domains: data.domains,
      invites: data.invites,
      newInviteUrl: inviteUrl,
    }), { headers: { "Set-Cookie": rotatedCookie } })
})

adminActionRoutes.post("/o/:slug/admin/invites/revoke", async (ctx) => {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const inviteId = uuidField(body, "invite_id")
  if (!inviteId) return redirectTo(facts, session.slug, { err: "invalid_input" })

  // revoke_organisation_invite authorizes against the invite's OWN org, not one
  // we pass in, so an admin of another org cannot revoke this one by id.
  const result = await callForActor("revoke_organisation_invite", session.sub, {
    p_invite_id: inviteId,
  })

  return finish(facts, session.slug, result.ok, "invite_revoked")
})

/**
 * The value if it is in the allowed set; null when ABSENT ("leave unchanged").
 *
 * Throws [InvalidEnum] for a value that is present but unrecognised, rather than silently
 * mapping it to "leave unchanged" and answering with a success banner. That is the same rule
 * max_uses follows a hundred lines up, and for the same reason: the caller asked for something
 * specific and a no-op reported as success is the opposite of the answer.
 */
function oneOf(value: string | null, allowed: readonly string[]): string | null {
  if (value === null) return null
  if (allowed.includes(value)) return value
  throw new InvalidEnum()
}

class InvalidEnum extends Error {}

export { prepare, redirectTo }
