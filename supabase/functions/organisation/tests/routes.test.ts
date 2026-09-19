/**
 * End-to-end route behaviour, driven through app.request().
 *
 * These are the tests that prove the flow rather than its pieces: that the
 * handoff really does set a cookie and strip the token, that a page really is
 * refused without one, and that the CSRF gate really does run before anything
 * touches the database.
 */

import { assert, assertEquals } from "@std/assert"
import { app } from "../app.ts"
import { mintSession } from "../utils/session.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"
import { CSRF_FIELD } from "../utils/csrf.ts"
import {
  FIXTURE,
  formHeaders,
  orgDetailResponse,
  restoreServiceClient,
  type RpcStub,
  stubServiceClient,
  withTestEnv,
} from "./helpers/mocks.ts"

const BASE = "/organisation"
const CSRF = "test-csrf-nonce"

function setup(): { stub: RpcStub; restore: () => void } {
  const restoreEnv = withTestEnv()
  resetRateLimits()
  const stub = stubServiceClient()

  // Default happy path: the viewer is an admin and every list is empty.
  stub.responses.set("user_is_org_admin", true)
  stub.responses.set("user_is_org_member", true)
  stub.responses.set("get_organisation_detail", orgDetailResponse())
  for (
    const fn of [
      "list_organisation_members",
      "list_organisation_roles",
      "list_organisation_domains",
      "list_organisation_invites",
    ]
  ) {
    stub.responses.set(fn, { success: true, data: [] })
  }

  return {
    stub,
    restore: () => {
      restoreServiceClient()
      restoreEnv()
    },
  }
}

async function sessionCookie(slug = FIXTURE.slug, org = FIXTURE.orgId): Promise<string> {
  const value = await mintSession({
    sub: FIXTURE.userId,
    org,
    slug,
    csrf: CSRF,
    pur: "org_admin",
  })
  return `boss_org=${value}`
}

// ---------------------------------------------------------------------------
// Health and 404
// ---------------------------------------------------------------------------

Deno.test("health answers without a session", async () => {
  const { restore } = setup()
  try {
    const response = await app.request(`${BASE}/health`)
    assertEquals(response.status, 200)
    assertEquals((await response.json()).status, "healthy")
  } finally {
    restore()
  }
})

Deno.test("health does not disclose whether the secret is configured", async () => {
  const { restore } = setup()
  try {
    Deno.env.delete("ORG_SESSION_SECRET")
    const response = await app.request(`${BASE}/health`)
    const body = await response.text()
    assertEquals(body.toLowerCase().includes("secret"), false)
    assertEquals(body.toLowerCase().includes("config"), false)
  } finally {
    restore()
  }
})

// ---------------------------------------------------------------------------
// The handoff exchange
// ---------------------------------------------------------------------------

Deno.test("a handoff token sets a cookie and redirects without the token", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("consume_organisation_handoff_token", {
      success: true,
      user_id: FIXTURE.userId,
      org_id: FIXTURE.orgId,
      org_slug: FIXTURE.slug,
      purpose: "org_view",
      email: "a@example.com",
    })

    const response = await app.request(`${BASE}/o/${FIXTURE.slug}?t=super-secret-token`)

    assertEquals(response.status, 302)

    const location = response.headers.get("location") ?? ""
    assertEquals(location, "/functions/v1/organisation/o/acme")
    assertEquals(location.includes("super-secret-token"), false)

    const setCookie = response.headers.get("set-cookie") ?? ""
    assert(setCookie.startsWith("boss_org="))
    assert(setCookie.includes("HttpOnly"))
    assert(setCookie.includes("Path=/functions/v1/organisation"))
    assertEquals(setCookie.includes("super-secret-token"), false)
  } finally {
    restore()
  }
})

Deno.test("the whole response carries no trace of the handoff token", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("consume_organisation_handoff_token", {
      success: true,
      user_id: FIXTURE.userId,
      org_id: FIXTURE.orgId,
      org_slug: FIXTURE.slug,
      purpose: "org_view",
      email: null,
    })

    const response = await app.request(`${BASE}/o/${FIXTURE.slug}?t=super-secret-token`)
    const everything = [
      await response.text(),
      ...[...response.headers.entries()].map(([k, v]) => `${k}: ${v}`),
    ].join("\n")

    assertEquals(everything.includes("super-secret-token"), false)
  } finally {
    restore()
  }
})

Deno.test("a token for the wrong organisation does not mint a session", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("consume_organisation_handoff_token", {
      success: true,
      user_id: FIXTURE.userId,
      org_id: FIXTURE.otherOrgId,
      org_slug: "othercorp",
      purpose: "org_view",
      email: null,
    })

    const response = await app.request(`${BASE}/o/${FIXTURE.slug}?t=tok`)
    assertEquals(response.status, 400)
    assertEquals(response.headers.get("set-cookie"), null)
  } finally {
    restore()
  }
})

Deno.test("an invalid token and a wrong-org token are indistinguishable", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("consume_organisation_handoff_token", {
      success: false,
      error: "Token is invalid, expired or already used",
    })
    const invalid = await app.request(`${BASE}/o/${FIXTURE.slug}?t=tok`)
    const invalidBody = await invalid.text()

    stub.responses.set("consume_organisation_handoff_token", {
      success: true,
      user_id: FIXTURE.userId,
      org_id: FIXTURE.otherOrgId,
      org_slug: "othercorp",
      purpose: "org_view",
      email: null,
    })
    const mismatch = await app.request(`${BASE}/o/${FIXTURE.slug}?t=tok`)
    const mismatchBody = await mismatch.text()

    assertEquals(invalid.status, mismatch.status)
    assertEquals(stripNonce(invalidBody), stripNonce(mismatchBody))
  } finally {
    restore()
  }
})

// ---------------------------------------------------------------------------
// Page access
// ---------------------------------------------------------------------------

Deno.test("the overview needs a session", async () => {
  const { restore } = setup()
  try {
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}`)
    assertEquals(response.status, 401)
  } finally {
    restore()
  }
})

Deno.test("the overview renders for a member", async () => {
  const { restore } = setup()
  try {
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}`, {
      headers: { cookie: await sessionCookie() },
    })
    assertEquals(response.status, 200)
    const body = await response.text()
    assert(body.includes("Acme"))
    assert(body.includes("@acme"))
  } finally {
    restore()
  }
})

Deno.test("a session for one organisation cannot open another's page", async () => {
  const { restore } = setup()
  try {
    // Signed for `acme`, requested at `othercorp`.
    const response = await app.request(`${BASE}/o/othercorp`, {
      headers: { cookie: await sessionCookie() },
    })
    assertEquals(response.status, 404)
  } finally {
    restore()
  }
})

Deno.test("a member removed since the cookie was minted is refused", async () => {
  const { stub, restore } = setup()
  try {
    // The cookie is perfectly valid; the live probe is what says no.
    stub.responses.set("user_is_org_member", false)
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}`, {
      headers: { cookie: await sessionCookie() },
    })
    assertEquals(response.status, 404)
  } finally {
    restore()
  }
})

Deno.test("the admin page is refused for a non-admin member", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("user_is_org_admin", false)
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin`, {
      headers: { cookie: await sessionCookie() },
    })
    assertEquals(response.status, 404)
  } finally {
    restore()
  }
})

Deno.test("a failing admin probe denies rather than grants", async () => {
  const { stub, restore } = setup()
  try {
    stub.errors.set("user_is_org_admin", "connection reset")
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin`, {
      headers: { cookie: await sessionCookie() },
    })
    assertEquals(response.status, 404)
  } finally {
    restore()
  }
})

Deno.test("the admin page renders the CSRF nonce from the session", async () => {
  const { restore } = setup()
  try {
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin`, {
      headers: { cookie: await sessionCookie() },
    })
    assertEquals(response.status, 200)
    const body = await response.text()
    assert(body.includes(`name="${CSRF_FIELD}" value="${CSRF}"`))
  } finally {
    restore()
  }
})

// ---------------------------------------------------------------------------
// Security headers
// ---------------------------------------------------------------------------

Deno.test("every page carries the security headers", async () => {
  const { restore } = setup()
  try {
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}`, {
      headers: { cookie: await sessionCookie() },
    })
    assertEquals(response.headers.get("x-content-type-options"), "nosniff")
    assertEquals(response.headers.get("referrer-policy"), "no-referrer")
    assertEquals(response.headers.get("x-frame-options"), "DENY")
    assertEquals(response.headers.get("vary"), "Cookie")
    assert((response.headers.get("cache-control") ?? "").includes("no-store"))

    const csp = response.headers.get("content-security-policy") ?? ""
    assert(csp.includes("default-src 'none'"))
    assert(csp.includes("form-action 'self'"))
    assert(csp.includes("frame-ancestors 'none'"))
    assertEquals(csp.includes("unsafe-inline"), false)
  } finally {
    restore()
  }
})

Deno.test("the CSP nonce in the header matches the one in the markup", async () => {
  const { restore } = setup()
  try {
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}`, {
      headers: { cookie: await sessionCookie() },
    })
    const csp = response.headers.get("content-security-policy") ?? ""
    const nonce = /script-src 'nonce-([A-Za-z0-9_-]+)'/.exec(csp)?.[1]
    assert(nonce, "no nonce in the CSP")
    assert((await response.text()).includes(`<style nonce="${nonce}">`))
  } finally {
    restore()
  }
})

// ---------------------------------------------------------------------------
// CSRF, on a real POST
// ---------------------------------------------------------------------------

Deno.test("a post without a CSRF token is refused, and reaches no RPC", async () => {
  const { stub, restore } = setup()
  try {
    const before = stub.calls.length
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/settings`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({ name: "Renamed" }),
    })

    assertEquals(response.status, 403)
    // The gate must run BEFORE the admin probe, so nothing at all was called.
    assertEquals(stub.calls.length, before)
  } finally {
    restore()
  }
})

Deno.test("a cross-site post is refused even with a valid token", async () => {
  const { stub, restore } = setup()
  try {
    const headers = formHeaders(await sessionCookie())
    headers.set("sec-fetch-site", "cross-site")
    headers.set("origin", "https://evil.example.com")

    const before = stub.calls.length
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/settings`, {
      method: "POST",
      headers,
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, name: "Renamed" }),
    })

    assertEquals(response.status, 403)
    assertEquals(stub.calls.length, before)
  } finally {
    restore()
  }
})

Deno.test("a valid post reaches the RPC with the session subject as the actor", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("update_organisation_settings", { success: true })

    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/settings`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({
        [CSRF_FIELD]: CSRF,
        name: "Renamed",
        visibility: "public",
        join_policy: "open",
      }),
    })

    assertEquals(response.status, 303)
    assertEquals(
      response.headers.get("location"),
      "/functions/v1/organisation/o/acme/admin?ok=settings_saved",
    )

    const call = stub.calls.find((c) => c.fn === "update_organisation_settings")
    assert(call, "the RPC was not called")
    // The actor comes from the cookie. There is no request parameter that
    // could name anybody else.
    assertEquals(call.params.p_actor_id, FIXTURE.userId)
    assertEquals(call.params.p_org_id, FIXTURE.orgId)
    assertEquals(call.params.p_name, "Renamed")
    assertEquals(call.params.p_visibility, "public")
  } finally {
    restore()
  }
})

Deno.test("a present but unrecognised enum is refused, not silently dropped", async () => {
  const { stub, restore } = setup()
  try {
    // Previously this mapped to null - "leave unchanged" - and still answered
    // ?ok=settings_saved, so a caller asking for something specific got a success banner for a
    // no-op. That is the opposite of the rule max_uses follows, and the two sit ~120 lines
    // apart in the same file.
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/settings`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({
        [CSRF_FIELD]: CSRF,
        name: "Renamed",
        visibility: "everyone_forever",
      }),
    })

    assertEquals(
      response.headers.get("location"),
      "/functions/v1/organisation/o/acme/admin?err=invalid_input",
    )
    assertEquals(stub.calls.some((c) => c.fn === "update_organisation_settings"), false)
  } finally {
    restore()
  }
})

Deno.test("an ABSENT enum still means leave unchanged", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("update_organisation_settings", { success: true })

    await app.request(`${BASE}/o/${FIXTURE.slug}/admin/settings`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, name: "Renamed" }),
    })

    const call = stub.calls.find((c) => c.fn === "update_organisation_settings")
    assert(call)
    assertEquals(call.params.p_visibility, null)
  } finally {
    restore()
  }
})

Deno.test("a malformed website is named, not collapsed into the generic refusal", async () => {
  // The RPC refuses it too, but finish() maps every RPC failure to err=rejected,
  // which renders as "The change was refused" - the generic message the whole
  // check exists to replace. Only a route-level check can name the field while
  // keeping the fixed-key vocabulary that stops caller text being reflected.
  const { stub, restore } = setup()
  try {
    stub.responses.set("update_organisation_settings", { success: true })
    const res = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/settings`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, name: "Renamed", website: "acme.com" }),
    })

    assertEquals(res.status, 303)
    assert(
      (res.headers.get("location") ?? "").includes("err=invalid_website"),
      `expected err=invalid_website, got ${res.headers.get("location")}`,
    )
    // And it does not reach the database: the whole point is that the rest of the
    // submission is not attempted with a value that would abort it.
    assertEquals(stub.calls.find((c) => c.fn === "update_organisation_settings"), undefined)
  } finally {
    restore()
  }
})

Deno.test("an empty website is passed through, because empty CLEARS it", async () => {
  // rawField, not field: '' and absent mean different things to the RPC, and the
  // route check must not treat the clearing case as malformed.
  const { stub, restore } = setup()
  try {
    stub.responses.set("update_organisation_settings", { success: true })
    await app.request(`${BASE}/o/${FIXTURE.slug}/admin/settings`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, name: "Renamed", website: "" }),
    })

    const call = stub.calls.find((c) => c.fn === "update_organisation_settings")
    assert(call, "a blank website must still reach the RPC, to clear the column")
    assertEquals(call.params.p_website, "")
  } finally {
    restore()
  }
})

Deno.test("an absent website leaves the column alone", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("update_organisation_settings", { success: true })
    await app.request(`${BASE}/o/${FIXTURE.slug}/admin/settings`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, name: "Renamed" }),
    })

    const call = stub.calls.find((c) => c.fn === "update_organisation_settings")
    assert(call)
    assertEquals(call.params.p_website, null)
  } finally {
    restore()
  }
})

Deno.test("a rejected RPC does not reflect its own message into the URL", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("update_organisation_settings", {
      success: false,
      error: "Permission denied for org 22222222",
    })

    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/settings`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, name: "Renamed" }),
    })

    const location = response.headers.get("location") ?? ""
    assertEquals(location, "/functions/v1/organisation/o/acme/admin?err=rejected")
    assertEquals(location.includes("22222222"), false)
  } finally {
    restore()
  }
})

Deno.test("an unrecognised banner key renders nothing rather than being echoed", async () => {
  const { restore } = setup()
  try {
    const response = await app.request(
      `${BASE}/o/${FIXTURE.slug}/admin?ok=${encodeURIComponent("<script>alert(1)</script>")}`,
      { headers: { cookie: await sessionCookie() } },
    )
    const body = await response.text()
    assertEquals(body.includes("alert(1)"), false)
    // No banner element at all. ("banner" on its own would match the
    // stylesheet, which always defines .banner.)
    assertEquals(body.includes(`<div class="banner`), false)
  } finally {
    restore()
  }
})

// ---------------------------------------------------------------------------
// Domains
// ---------------------------------------------------------------------------

Deno.test("a domain id from another organisation is refused", async () => {
  const { stub, restore } = setup()
  try {
    // This org owns exactly one domain, and it is not the one being asked for.
    stub.responses.set("list_organisation_domains", {
      success: true,
      data: [{
        domain_id: FIXTURE.domainId,
        domain: "acme.example",
        is_primary: false,
        verified: false,
        verified_at: null,
        dns_record_type: "TXT",
        dns_record_name: "_boss-verify.acme.example",
        dns_record_value: "boss-org-verification=tok",
      }],
    })

    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/domains/verify`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({
        [CSRF_FIELD]: CSRF,
        domain_id: "99999999-9999-4999-8999-999999999999",
      }),
    })

    assertEquals(response.headers.get("location"),
      "/functions/v1/organisation/o/acme/admin?err=rejected")
    // The verification RPC must never have been reached for a foreign row.
    assertEquals(stub.calls.some((c) => c.fn === "mark_organisation_domain_verified"), false)
  } finally {
    restore()
  }
})

Deno.test("an unverified domain cannot be made primary", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("list_organisation_domains", {
      success: true,
      data: [{
        domain_id: FIXTURE.domainId,
        domain: "acme.example",
        is_primary: false,
        verified: false,
        verified_at: null,
        dns_record_type: "TXT",
        dns_record_name: "_boss-verify.acme.example",
        dns_record_value: "boss-org-verification=tok",
      }],
    })

    await app.request(`${BASE}/o/${FIXTURE.slug}/admin/domains/primary`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, domain_id: FIXTURE.domainId }),
    })

    assertEquals(stub.calls.some((c) => c.fn === "set_primary_organisation_domain"), false)
  } finally {
    restore()
  }
})

Deno.test("a malformed domain never reaches the database", async () => {
  const { stub, restore } = setup()
  try {
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/domains/add`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, domain: "not a domain" }),
    })

    assertEquals(
      response.headers.get("location"),
      "/functions/v1/organisation/o/acme/admin?err=invalid_input",
    )
    assertEquals(stub.calls.some((c) => c.fn === "add_organisation_domain"), false)
  } finally {
    restore()
  }
})

// ---------------------------------------------------------------------------
// Invites
// ---------------------------------------------------------------------------

Deno.test("a created invite link is shown once, inline", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("create_organisation_invite", {
      success: true,
      invite_id: FIXTURE.roleId,
      token: "boss_inv_abcdefghijklmnopqrstuvwxyz0123456789ABCD",
      token_prefix: "boss_inv_abcdefg",
      expires_at: "2026-09-01T00:00:00Z",
      max_uses: null,
    })

    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/invites/create`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, expires_in_hours: "168" }),
    })

    // Rendered, not redirected: the plaintext token exists for this one
    // response only.
    assertEquals(response.status, 200)
    const body = await response.text()

    // ABSOLUTE, with a host. This string's entire purpose is to be copied out of the browser
    // and sent to someone else, and a bare path is useless the moment it is pasted anywhere.
    // The token is shown once, so a wrong copy costs a revoke and a re-mint.
    const link = /value="(https?:\/\/[^"]*\/join\/boss_inv_[^"]*)"/.exec(body)?.[1]
    assert(link, `invite link is not an absolute url: ${body.match(/\/join\/[^"]*/)?.[0]}`)
    assert(link.includes("/functions/v1/organisation/join/boss_inv_"))

    // The highlight class must actually apply. `class="card" class="highlight"` parses as the
    // first attribute only, so the marker on the one-time-credential card silently vanished.
    assert(
      body.includes('class="card highlight"'),
      "the new-invite card must carry both classes in ONE attribute",
    )
    assertEquals(/class="[^"]*"\s+class="/.test(body), false, "duplicate class attribute")
  } finally {
    restore()
  }
})

Deno.test("reloading the invite page cannot mint a second invite", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("create_organisation_invite", {
      success: true,
      token: "boss_inv_abcdefghijklmnopqrstuvwxyz0123456789ABCD",
      token_prefix: "boss_inv_abcdefg",
    })

    const cookie = await sessionCookie()
    const body = () => new URLSearchParams({ [CSRF_FIELD]: CSRF, expires_in_hours: "168" })

    const first = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/invites/create`, {
      method: "POST",
      headers: formHeaders(cookie),
      body: body(),
    })
    assertEquals(first.status, 200)

    // The response rotates the session's CSRF nonce, so the form still sitting in that page is
    // stale. A browser reload re-POSTs it and must be refused - otherwise F5 silently mints a
    // second live invite while the first stays live.
    const rotated = first.headers.get("set-cookie")
    assert(rotated, "the invite response must rotate the session")
    const newCookie = rotated.split(";")[0]

    const before = stub.calls.filter((c) => c.fn === "create_organisation_invite").length
    const reload = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/invites/create`, {
      method: "POST",
      headers: formHeaders(newCookie),
      body: body(),
    })

    assertEquals(reload.status, 403)
    assertEquals(
      stub.calls.filter((c) => c.fn === "create_organisation_invite").length,
      before,
      "the reload must not reach the mint RPC",
    )
  } finally {
    restore()
  }
})

Deno.test("a failed page read still shows the invite link", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("create_organisation_invite", {
      success: true,
      token: "boss_inv_abcdefghijklmnopqrstuvwxyz0123456789ABCD",
      token_prefix: "boss_inv_abcdefg",
    })
    // The invite already exists and is live; losing its plaintext here would leave the admin
    // hunting it by prefix to revoke it, having been told it worked.
    stub.errors.set("get_organisation_detail", "connection reset")

    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/invites/create`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, expires_in_hours: "168" }),
    })

    assertEquals(response.status, 200)
    const text = await response.text()
    assert(/value="https?:\/\/[^"]*\/join\/boss_inv_/.test(text), "the link must still render")
  } finally {
    restore()
  }
})

Deno.test("an out-of-range max_uses is refused, not silently made unlimited", async () => {
  const { stub, restore } = setup()
  try {
    // intField returns null for out-of-range AND for absent, and null means
    // "unlimited" downstream - so max_uses=5000 used to produce a link with no
    // cap at all, the opposite of what was asked for.
    for (const maxUses of ["0", "5000", "-1", "abc"]) {
      const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/invites/create`, {
        method: "POST",
        headers: formHeaders(await sessionCookie()),
        body: new URLSearchParams({ [CSRF_FIELD]: CSRF, expires_in_hours: "168", max_uses: maxUses }),
      })
      assertEquals(
        response.headers.get("location"),
        "/functions/v1/organisation/o/acme/admin?err=invalid_input",
        `max_uses=${maxUses} should be refused`,
      )
    }
    assertEquals(stub.calls.some((c) => c.fn === "create_organisation_invite"), false)
  } finally {
    restore()
  }
})

Deno.test("an ABSENT max_uses still means unlimited", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("create_organisation_invite", {
      success: true,
      token: "boss_inv_abcdefghijklmnopqrstuvwxyz0123456789ABCD",
      token_prefix: "boss_inv_abcdefg",
    })
    await app.request(`${BASE}/o/${FIXTURE.slug}/admin/invites/create`, {
      method: "POST",
      headers: formHeaders(await sessionCookie()),
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, expires_in_hours: "168" }),
    })
    const call = stub.calls.find((c) => c.fn === "create_organisation_invite")
    assert(call)
    assertEquals(call.params.p_max_uses, null)
  } finally {
    restore()
  }
})

Deno.test("an out-of-range expiry is refused before the RPC", async () => {
  const { stub, restore } = setup()
  try {
    for (const hours of ["0", "721", "abc", "-5"]) {
      await app.request(`${BASE}/o/${FIXTURE.slug}/admin/invites/create`, {
        method: "POST",
        headers: formHeaders(await sessionCookie()),
        body: new URLSearchParams({ [CSRF_FIELD]: CSRF, expires_in_hours: hours }),
      })
    }
    assertEquals(stub.calls.some((c) => c.fn === "create_organisation_invite"), false)
  } finally {
    restore()
  }
})

// ---------------------------------------------------------------------------
// The invite link's authority
// ---------------------------------------------------------------------------

Deno.test("an unset ORG_PUBLIC_BASE_URL never mints a host from client headers", async () => {
  const { stub, restore } = setup()
  try {
    // The exact deployment the issue describes: no configured public origin,
    // so the OLD builder fell back to X-Forwarded-Host/Host -- headers the
    // client can set wherever the edge does not overwrite them.
    Deno.env.delete("ORG_PUBLIC_BASE_URL")
    stub.responses.set("create_organisation_invite", {
      success: true,
      token: "boss_inv_abcdefghijklmnopqrstuvwxyz0123456789ABCD",
      token_prefix: "boss_inv_abcdefg",
      expires_at: "2026-09-01T00:00:00Z",
      max_uses: null,
    })

    const headers = formHeaders(await sessionCookie())
    // The old builder PREFERRED x-forwarded-host over host, so this is the
    // exact header a poisoner sends. The CSRF gate is unaffected: it checks
    // Origin against the host header, and both still say api.risaboss.com --
    // which is precisely why the poisoned header sailed through to the URL.
    headers.set("x-forwarded-host", "attacker.example")

    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/invites/create`, {
      method: "POST",
      headers,
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, expires_in_hours: "168" }),
    })

    assertEquals(response.status, 200)
    const body = await response.text()

    // THE assertion of the fix: a credential-bearing URL must not carry an
    // authority the CLIENT chose. The one-time join token would otherwise be
    // handed to attacker.example on a plate.
    assertEquals(
      body.includes("attacker.example"),
      false,
      "the poisoned host must not appear anywhere in the response",
    )

    // And the link that IS rendered carries no host at all: the relative
    // publicBasePath. It still works pasted into the origin the admin is
    // reading, and it cannot be pointed at anyone else's.
    assertEquals(
      body.includes(`value="/functions/v1/organisation/join/boss_inv_abcdefghijklmnopqrstuvwxyz0123456789ABCD"`),
      true,
      "the unset-env invite link must be the relative path",
    )
    assertEquals(/value="https?:\/\//.test(body), false, "no absolute link may be minted without ORG_PUBLIC_BASE_URL")
  } finally {
    restore()
  }
})

Deno.test("a configured ORG_PUBLIC_BASE_URL supplies the host, ignoring client headers", async () => {
  const { stub, restore } = setup()
  try {
    // withTestEnv configures https://boss.example; set it explicitly so this
    // test says what it means even if the helper's value ever changes.
    Deno.env.set("ORG_PUBLIC_BASE_URL", "https://boss.example")
    stub.responses.set("create_organisation_invite", {
      success: true,
      token: "boss_inv_abcdefghijklmnopqrstuvwxyz0123456789ABCD",
      token_prefix: "boss_inv_abcdefg",
      expires_at: "2026-09-01T00:00:00Z",
      max_uses: null,
    })

    const headers = formHeaders(await sessionCookie())
    headers.set("x-forwarded-host", "attacker.example")

    const response = await app.request(`${BASE}/o/${FIXTURE.slug}/admin/invites/create`, {
      method: "POST",
      headers,
      body: new URLSearchParams({ [CSRF_FIELD]: CSRF, expires_in_hours: "168" }),
    })

    assertEquals(response.status, 200)
    const body = await response.text()
    assertEquals(
      body.includes("attacker.example"),
      false,
      "a configured base must not be overridden by client headers",
    )
    assertEquals(
      body.includes(`value="https://boss.example/functions/v1/organisation/join/boss_inv_abcdefghijklmnopqrstuvwxyz0123456789ABCD"`),
      true,
      "the invite link must use the configured base, unchanged",
    )
  } finally {
    restore()
  }
})

// ---------------------------------------------------------------------------
// The invite landing page
// ---------------------------------------------------------------------------

Deno.test("a valid invite renders the org name and a deep link", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("get_organisation_invite_preview", {
      success: true,
      valid: true,
      name: "Acme",
      slug: "acme",
      description: "A fixture",
    })

    const token = `boss_inv_${"a".repeat(43)}`
    const response = await app.request(`${BASE}/join/${token}`)

    assertEquals(response.status, 200)
    const body = await response.text()
    assert(body.includes("Acme"))
    assert(body.includes(`boss://organisation/join?token=${token}`))
  } finally {
    restore()
  }
})

Deno.test("every unusable invite renders one identical page", async () => {
  const { stub, restore } = setup()
  try {
    const token = `boss_inv_${"a".repeat(43)}`

    stub.responses.set("get_organisation_invite_preview", { success: true, valid: false })
    const unknown = await app.request(`${BASE}/join/${token}`)
    const unknownBody = await unknown.text()

    stub.responses.delete("get_organisation_invite_preview")
    stub.errors.set("get_organisation_invite_preview", "connection reset")
    const broken = await app.request(`${BASE}/join/${token}`)
    const brokenBody = await broken.text()

    assertEquals(unknown.status, 404)
    assertEquals(broken.status, 404)
    assertEquals(stripNonce(unknownBody), stripNonce(brokenBody))
  } finally {
    restore()
  }
})

Deno.test("a malformed invite token never reaches the database", async () => {
  const { stub, restore } = setup()
  try {
    for (const token of ["short", "notboss_inv_aaaa", `boss_inv_${"!".repeat(43)}`]) {
      const response = await app.request(`${BASE}/join/${encodeURIComponent(token)}`)
      assertEquals(response.status, 404)
    }
    assertEquals(stub.calls.some((c) => c.fn === "get_organisation_invite_preview"), false)
  } finally {
    restore()
  }
})

// ---------------------------------------------------------------------------
// Configuration failure
// ---------------------------------------------------------------------------

Deno.test("a missing session secret fails closed with a 503", async () => {
  const { restore } = setup()
  try {
    Deno.env.delete("ORG_SESSION_SECRET")
    const response = await app.request(`${BASE}/o/${FIXTURE.slug}`)
    assertEquals(response.status, 503)
    // Deliberately unlike plugin-store's signing util, which fails open.
    assertEquals((await response.text()).includes("ORG_SESSION_SECRET"), false)
  } finally {
    restore()
  }
})

/** Blank the per-response CSP nonce so two pages can be compared. */
function stripNonce(html: string): string {
  return html.replace(/nonce="[A-Za-z0-9_-]+"/g, 'nonce="N"')
}
