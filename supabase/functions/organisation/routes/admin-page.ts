/**
 * GET /o/:slug/admin -- the admin configuration page.
 *
 * The result banner is carried in the query string (`?ok=` / `?err=`) rather
 * than in a flash cookie, because the POST handlers answer 303 and the browser
 * arrives here as a fresh GET. The message KEY is echoed, not the message: the
 * text is looked up from a fixed table, so nothing a caller writes into the URL
 * can be reflected onto the page.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { loadAdminPageData } from "../services/org.ts"
import { htmlResponse } from "../utils/responses.ts"
import { requireOrgAdmin, requireOrgSession } from "./guards.ts"
import { consumeHandoffToken } from "./handoff-exchange.ts"
import { isValidSlug, readRequestFacts } from "../utils/request.ts"
import { adminPage } from "../views/admin.ts"
import { errorPage, NOT_AVAILABLE_MESSAGE } from "../views/error.ts"

export const adminPageRoutes = new OpenAPIHono()

/**
 * Result messages, keyed. A handler redirects with `?ok=settings_saved`, and
 * only a key that appears here can ever render.
 */
const RESULT_MESSAGES: Record<string, string> = {
  settings_saved: "Settings saved.",
  member_approved: "Member approved.",
  member_rejected: "Join request rejected.",
  member_removed: "Member removed.",
  role_assigned: "Role assigned.",
  role_created: "Role created.",
  role_deleted: "Role deleted.",
  invite_revoked: "Invite link revoked.",
  domain_added: "Domain added. Add the TXT record, then press Verify.",
  domain_removed: "Domain removed.",
  domain_verified: "Domain verified.",
  domain_primary: "Primary domain updated.",
  domain_unverified: "The TXT record was not found. DNS changes can take a few minutes.",
  domain_users_added: "Members added. Everyone with an address at that domain is now in the organisation.",
  a2a_saved: "Agent connections saved.",
}

// invite_created and dns_failed were removed as unreachable: the invite handler renders its
// result inline rather than redirecting (the token exists for one response), and the DNS
// not-found case reports domain_unverified, which is information rather than an error. A key
// nothing can emit is a message nobody can ever see.
const ERROR_MESSAGES: Record<string, string> = {
  invalid_input: "That value was not accepted. Check the field and try again.",
  rejected: "The change was refused.",
  invalid_website: "The website must be a full http:// or https:// address.",
  rate_limited: "Too many attempts. Please wait a moment and try again.",
}

adminPageRoutes.get("/o/:slug/admin", async (ctx) => {
  // BEFORE the session guard, because on this path there IS no session yet. The desktop panel's
  // Configure button opens this URL with a freshly minted `?t=` handoff token and nothing else;
  // until now only /o/:slug consumed one, so this route fell straight through to
  // requireOrgSession, found no cookie and answered "Session expired". Configure therefore never
  // worked from a cold start - only after Open page had already left a cookie behind.
  //
  // The slug is validated here rather than trusted, because it is about to be built into a
  // Location header. requireOrgSession would have checked it, but it does not run first any more.
  const slug = ctx.req.param("slug") ?? ""
  if (isValidSlug(slug)) {
    const facts = await readRequestFacts(ctx)
    const exchanged = await consumeHandoffToken(
      ctx,
      facts,
      slug,
      `/o/${encodeURIComponent(slug)}/admin`,
    )
    if (exchanged) return exchanged
  }

  const sessionGuard = await requireOrgSession(ctx)
  if (!sessionGuard.ok) return sessionGuard.response

  const adminGuard = await requireOrgAdmin(sessionGuard.value)
  if (!adminGuard.ok) return adminGuard.response

  const { session, facts } = adminGuard.value
  const data = await loadAdminPageData(session.sub, session.org)

  if (!data.ok) {
    return htmlResponse(
      (nonce) =>
        errorPage({
          nonce,
          title: "Not available - BOSS",
          heading: "Not available",
          message: NOT_AVAILABLE_MESSAGE,
        }),
      { status: 404 },
    )
  }

  const url = new URL(ctx.req.url)
  const banner = resolveBanner(url.searchParams.get("ok"), url.searchParams.get("err"))

  return htmlResponse((nonce) =>
    adminPage({
      nonce,
      basePath: facts.basePath,
      csrf: session.csrf,
      org: data.org,
      members: data.members,
      roles: data.roles,
      domains: data.domains,
      invites: data.invites,
      a2a: data.a2a,
      banner,
    })
  )
})

/**
 * A banner from a result key, or null.
 *
 * An unrecognised key renders nothing at all. It is not echoed and it is not
 * reported as unknown -- either would reflect caller-controlled text.
 */
export function resolveBanner(
  okKey: string | null,
  errKey: string | null,
): { kind: "ok" | "error"; message: string } | null {
  if (errKey && Object.hasOwn(ERROR_MESSAGES, errKey)) {
    return { kind: "error", message: ERROR_MESSAGES[errKey] }
  }
  if (okKey && Object.hasOwn(RESULT_MESSAGES, okKey)) {
    return { kind: "ok", message: RESULT_MESSAGES[okKey] }
  }
  return null
}
