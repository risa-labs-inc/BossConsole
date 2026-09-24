/**
 * CSRF checks. The interesting assertions are the ones about SAME-ORIGIN
 * attackers, because that is the case SameSite does not cover and the reason
 * the nonce lives inside the signed cookie - and now, since the nonce is
 * rendered into the page HTML, the reason Sec-Fetch-Mode gates it.
 */

import { assertEquals } from "@std/assert"
import { checkCsrf, CSRF_FIELD, originIsSameSite } from "../utils/csrf.ts"
import type { SessionPayload } from "../utils/session.ts"
import { FIXTURE } from "./helpers/mocks.ts"

const ORIGIN = "https://api.risaboss.com"

function session(csrf = "nonce-a"): SessionPayload {
  return {
    sub: FIXTURE.userId,
    org: FIXTURE.orgId,
    slug: FIXTURE.slug,
    csrf,
    pur: "org_admin",
    iat: 1_800_000_000,
    exp: 1_800_001_800,
  }
}

Deno.test("a well-formed same-origin post passes", () => {
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "same-origin",
      secFetchMode: "navigate",
      secFetchDest: "document",
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    null,
  )
})

Deno.test("a token from another session is refused", () => {
  // The whole point of binding the nonce into the signed cookie: an attacker
  // who obtains SOME valid nonce cannot use it against a different session.
  assertEquals(
    checkCsrf({
      session: session("nonce-a"),
      submitted: "nonce-b",
      secFetchSite: "same-origin",
      secFetchMode: "navigate",
      secFetchDest: "document",
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    "bad_token",
  )
})

Deno.test("a missing token is refused", () => {
  for (const submitted of [undefined, null, "", 42, {}]) {
    assertEquals(
      checkCsrf({
        session: session(),
        submitted,
        secFetchSite: "same-origin",
        secFetchMode: "navigate",
        secFetchDest: "document",
        origin: ORIGIN,
        expectedOrigin: ORIGIN,
      }),
      "missing_token",
      `should refuse: ${JSON.stringify(submitted)}`,
    )
  }
})

Deno.test("a cross-site post is refused before the token is even considered", () => {
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "cross-site",
      secFetchMode: "navigate",
      secFetchDest: "document",
      origin: "https://evil.example.com",
      expectedOrigin: ORIGIN,
    }),
    "bad_origin",
  )
})

Deno.test("a request with neither Sec-Fetch-Site nor Origin is refused", () => {
  // Stricter than the "absent Origin means same-origin" convention: a browser
  // form post always carries one of the two, so this is a non-browser client.
  assertEquals(originIsSameSite(null, null, ORIGIN), false)
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: null,
      secFetchMode: null,
      secFetchDest: null,
      origin: null,
      expectedOrigin: ORIGIN,
    }),
    "bad_origin",
  )
})

Deno.test("a script-driven fetch with a HARVESTED valid nonce is refused", () => {
  // The attack from the issue: same-origin script fetches the admin page,
  // parses the csrf_token out of the rendered HTML, and posts it back. Every
  // token-level assertion passes - same origin, right session, valid nonce -
  // and only Sec-Fetch-Mode: cors gives it away, because no script can set it.
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "same-origin",
      secFetchMode: "cors",
      secFetchDest: "document",
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    "bad_fetch_mode",
  )
  // no-cors cannot read the response, but the mutation still executes, so it
  // is refused too. same-origin is fetch(mode: "same-origin") - same story.
  for (const mode of ["no-cors", "same-origin", "websocket"]) {
    assertEquals(
      checkCsrf({
        session: session(),
        submitted: "nonce-a",
        secFetchSite: "same-origin",
        secFetchMode: mode,
        secFetchDest: "document",
        origin: ORIGIN,
        expectedOrigin: ORIGIN,
      }),
      "bad_fetch_mode",
      `should refuse Sec-Fetch-Mode: ${mode}`,
    )
  }
})

Deno.test("a real form post with Sec-Fetch-Mode: navigate and a valid nonce passes", () => {
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "same-origin",
      secFetchMode: "navigate",
      secFetchDest: "document",
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    null,
  )
  // A bookmarked/typed form target: Sec-Fetch-Site none is a user-initiated
  // navigation, and navigate is still navigate.
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "none",
      secFetchMode: "navigate",
      secFetchDest: "document",
      origin: null,
      expectedOrigin: ORIGIN,
    }),
    null,
  )
})

Deno.test("a client with no Sec-Fetch-Mode falls through to the nonce check alone", () => {
  // curl, CLI integrations, this function's own test suite: they never send
  // Sec-Fetch-Mode. The gate only applies when the header is present, so these
  // keep working exactly as before - the nonce alone decides.
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "same-origin",
      secFetchMode: null,
      secFetchDest: null,
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    null,
  )
  // ... and a wrong nonce is still fatal for them. Absence of the header buys
  // nothing: the existing checks govern unchanged.
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "harvested-but-wrong",
      secFetchSite: "same-origin",
      secFetchMode: null,
      secFetchDest: null,
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    "bad_token",
  )
})

Deno.test("an invalid nonce is refused on every fetch-mode branch", () => {
  for (const mode of ["cors", "navigate", null]) {
    assertEquals(
      checkCsrf({
        session: session(),
        submitted: "harvested-but-wrong",
        secFetchSite: "same-origin",
        secFetchMode: mode,
        secFetchDest: "document",
        origin: ORIGIN,
        expectedOrigin: ORIGIN,
      }),
      mode === "cors" ? "bad_fetch_mode" : "bad_token",
      `sec-fetch-mode: ${mode}`,
    )
    assertEquals(
      checkCsrf({
        session: session(),
        submitted: "",
        secFetchSite: "same-origin",
        secFetchMode: mode,
        secFetchDest: "document",
        origin: ORIGIN,
        expectedOrigin: ORIGIN,
      }),
      mode === "cors" ? "bad_fetch_mode" : "missing_token",
      `sec-fetch-mode: ${mode}`,
    )
  }
})

Deno.test("Sec-Fetch-Site is trusted over a mismatched Origin", () => {
  // Sec-Fetch-Site is set by the browser and unforgeable from page script, so
  // it decides when present.
  assertEquals(originIsSameSite("same-origin", "https://evil.example.com", ORIGIN), true)
  assertEquals(originIsSameSite("cross-site", ORIGIN, ORIGIN), false)
})

Deno.test("Sec-Fetch-Site: none is accepted as a user-initiated navigation", () => {
  // Typed URL or bookmark. It never accompanies a cross-origin form post.
  assertEquals(originIsSameSite("none", null, ORIGIN), true)
})

Deno.test("Origin is the fallback when Sec-Fetch-Site is absent", () => {
  assertEquals(originIsSameSite(null, ORIGIN, ORIGIN), true)
  assertEquals(originIsSameSite(null, "https://evil.example.com", ORIGIN), false)
  // Case-insensitive: origins are compared as origins, not as strings.
  assertEquals(originIsSameSite(null, "HTTPS://API.RISABOSS.COM", ORIGIN), true)
})

Deno.test("the field name is stable", () => {
  // The view and the guard have to agree; a rename in one place only would
  // silently reject every post.
  assertEquals(CSRF_FIELD, "csrf_token")
})

Deno.test("a navigation hidden in an iframe is refused by the dest gate", () => {
  // The bypass the mode check cannot see: page script builds a form, targets
  // a hidden iframe and calls submit() - a real navigation, so mode honestly
  // reports "navigate", but dest honestly reports "iframe", and a real form
  // post is always top-level ("document").
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "same-origin",
      secFetchMode: "navigate",
      secFetchDest: "iframe",
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    "bad_fetch_dest",
  )
})

Deno.test("a top-level navigation with dest document passes the dest gate", () => {
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "same-origin",
      secFetchMode: "navigate",
      secFetchDest: "document",
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    null,
  )
})

Deno.test("an absent dest header falls through to the remaining checks", () => {
  // Non-browser clients send no fetch metadata at all; they are judged by
  // origin and token alone, exactly as before the gate existed.
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "same-origin",
      secFetchMode: null,
      secFetchDest: null,
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    null,
  )
})

Deno.test("dest does not rescue a script-driven fetch mode", () => {
  // dest: document with mode: cors is still a fetch, not a navigation.
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "same-origin",
      secFetchMode: "cors",
      secFetchDest: "document",
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    "bad_fetch_mode",
  )
})
