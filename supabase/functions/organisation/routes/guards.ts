/**
 * The gates every org route passes through.
 *
 * Split into two deliberately, because the ORDER matters and a single combined
 * guard would force the wrong one:
 *
 *   requireOrgSession  -- cookie only, no database. Cheap, so it can run first.
 *   requireOrgAdmin    -- the live authority probe. One round trip.
 *
 * A mutating handler runs session -> CSRF -> admin probe, so a forged request
 * is rejected before it can reach the database at all. A plain GET has no CSRF
 * step and runs session -> admin probe.
 */

import type { Context } from "hono"
import { isOrgAdmin } from "../services/authority.ts"
import { htmlResponse } from "../utils/responses.ts"
import { checkCsrf, CSRF_FIELD } from "../utils/csrf.ts"
import { isValidSlug, readRequestFacts, type RequestFacts } from "../utils/request.ts"
import type { SessionPayload } from "../utils/session.ts"
import { errorPage, NOT_AVAILABLE_MESSAGE, SESSION_EXPIRED_MESSAGE } from "../views/error.ts"

export interface OrgContext {
  session: SessionPayload
  facts: RequestFacts
}

export type Guarded<T> = { ok: true; value: T } | { ok: false; response: Response }

/**
 * Require a live session whose organisation matches the slug in the path.
 *
 * NO DATABASE ACCESS: this is cookie verification only. It establishes WHO the
 * request is, never what they may do.
 *
 * The slug match is not redundant with the later admin probe. A user can be an
 * admin of org A and open /o/b/admin; without this check the page would render
 * and mutate org A's data under org B's URL.
 */
export async function requireOrgSession(ctx: Context): Promise<Guarded<OrgContext>> {
  // `param` is `string | undefined`: a handler can be reached by a path with
  // no such segment. Empty string fails isValidSlug, so the guard below is
  // the single place that decision is made.
  const slug = ctx.req.param("slug") ?? ""
  const facts = await readRequestFacts(ctx)

  if (!isValidSlug(slug)) return { ok: false, response: notAvailable() }

  if (!facts.session) {
    return {
      ok: false,
      response: htmlResponse(
        (nonce) =>
          errorPage({
            nonce,
            title: "Session expired - BOSS",
            heading: "Session expired",
            message: SESSION_EXPIRED_MESSAGE,
          }),
        { status: 401 },
      ),
    }
  }

  if (facts.session.slug !== slug) return { ok: false, response: notAvailable() }

  return { ok: true, value: { session: facts.session, facts } }
}

/**
 * The live admin probe.
 *
 * Never `authorize('organisation.admin')`, which is org-blind and answers true
 * for any global admin anywhere. Fails closed, so an outage denies rather than
 * grants.
 */
export async function requireOrgAdmin(context: OrgContext): Promise<Guarded<OrgContext>> {
  if (!await isOrgAdmin(context.session.sub, context.session.org)) {
    return { ok: false, response: notAvailable() }
  }
  return { ok: true, value: context }
}

/**
 * Validate a mutating request's CSRF posture and return the parsed body.
 *
 * Runs on the session alone, before any probe, so a forged post costs one HMAC
 * verification and nothing else.
 */
export async function requireCsrfBody(
  ctx: Context,
  session: SessionPayload,
  expectedOrigin: string | null,
): Promise<Guarded<Record<string, unknown>>> {
  let body: Record<string, unknown>
  try {
    body = await ctx.req.parseBody() as Record<string, unknown>
  } catch {
    return { ok: false, response: forbidden() }
  }

  const failure = checkCsrf({
    session,
    submitted: body[CSRF_FIELD],
    secFetchSite: ctx.req.header("sec-fetch-site") ?? null,
    secFetchMode: ctx.req.header("sec-fetch-mode") ?? null,
    secFetchDest: ctx.req.header("sec-fetch-dest") ?? null,
    origin: ctx.req.header("origin") ?? null,
    expectedOrigin,
  })

  if (failure) {
    // The reason is logged, never rendered: telling a caller whether the token
    // or the origin was wrong helps them fix their forgery.
    console.warn(`csrf rejected: ${failure}`)
    return { ok: false, response: forbidden() }
  }

  return { ok: true, value: body }
}

/**
 * A CSRF failure is an HTML 403, never a redirect.
 *
 * Redirecting would send the browser back to the page it posted from, which
 * looks exactly like the action succeeding and doing nothing. The operator has
 * to learn that the request was refused.
 */
export function forbidden(): Response {
  return htmlResponse(
    (nonce) =>
      errorPage({
        nonce,
        title: "Request refused - BOSS",
        heading: "Request refused",
        message: "This request could not be verified. Reload the page from BOSS and try again.",
      }),
    { status: 403 },
  )
}

export function notAvailable(): Response {
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
