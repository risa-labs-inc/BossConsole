/**
 * Authorization tests for the publish + API-key gates.
 *
 * The five publishing handlers and the three api-keys handlers are thin: they
 * call into the helpers below and render whatever status those return. So the
 * decisions worth pinning live here rather than behind an HTTP stack.
 *
 * What these lock down:
 *   - `plugins.create` is required to publish, and admins bypass it.
 *   - An API key is only as good as its OWNER's CURRENT roles, resolved from the
 *     database (no JWT claim exists), so a revocation bites immediately.
 *   - A valid key with the wrong scope is a 403, not the 401 it used to report —
 *     it is an authenticated caller who is not allowed, not an unknown one.
 *   - The permission probe fails CLOSED: these gate writes, so a DB outage must
 *     not become an open door. (Contrast validateDeclaredPermissions, which
 *     fails open on purpose — it only guards manifest hygiene.)
 *
 * Run: deno test --allow-all tests/auth-permissions.test.ts
 */
import { assert, assertEquals, assertFalse } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { getAuthenticatedUser, userHasPermission } from "../utils/auth.ts"
import {
  API_KEY_CREATE_PERMISSION,
  PLUGIN_CREATE_PERMISSION,
  permissionGateError,
} from "../utils/permissions.ts"

const API_KEY_ORG_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
const OWNER_ID = "11111111-1111-1111-1111-111111111111"
const VALID_KEY = "boss_pk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6" // 40 chars

/** Build a structurally valid JWT whose payload carries the RBAC claims. */
function jwt(claims: Record<string, unknown>): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  return `${b64({ alg: "HS256", typ: "JWT" })}.${b64(claims)}.sig`
}

interface StubOptions {
  /** Row returned by validate_plugin_api_key; omit to make the key invalid. */
  apiKey?: { scopes: string[] }
  /** Result of the user_has_permission probe; "error" simulates a DB failure. */
  probe?: boolean | "error"
}

/** Records what the code under test asked the database. */
interface StubCalls {
  probed: Array<{ userId: string; permission: string }>
}

function stubSupabase(opts: StubOptions = {}): { client: SupabaseClient; calls: StubCalls } {
  const calls: StubCalls = { probed: [] }

  const client = {
    auth: {
      // getUserFromToken calls this to verify the token; the claims it acts on
      // are decoded from the token string itself.
      getUser: (token: string) =>
        Promise.resolve(
          token
            ? { data: { user: { id: OWNER_ID, email: "owner@test" } }, error: null }
            : { data: { user: null }, error: new Error("bad token") },
        ),
    },
    from: (_table: string) => ({
      select: () => ({
        eq: () => ({
          single: () => Promise.resolve({ data: { email: "owner@test" }, error: null }),
        }),
      }),
    }),
    rpc: (fn: string, args: Record<string, unknown>) => {
      if (fn === "validate_plugin_api_key") {
        return Promise.resolve(
          opts.apiKey
            ? {
              // THE REAL SHAPE. 20260803000000 recreated validate_plugin_api_key as
              // RETURNS TABLE(key_id, user_id, scopes, org_id): `api_key_id` and `key_name` no
              // longer exist. This stub kept returning the old names, so it agreed with auth.ts's
              // stale reads and both were wrong together - which is how `apiKeyId: undefined`
              // survived, silently disabling API-key audit logging and the last_used_at update.
              // A stub that models a dead contract is worse than no test.
              data: [{
                key_id: "key-1",
                user_id: OWNER_ID,
                scopes: opts.apiKey.scopes,
                org_id: API_KEY_ORG_ID,
              }],
              error: null,
            }
            : { data: [], error: null },
        )
      }
      if (fn === "user_has_permission") {
        calls.probed.push({
          userId: args.p_user_id as string,
          permission: args.p_permission as string,
        })
        if (opts.probe === "error") {
          return Promise.resolve({ data: null, error: new Error("connection reset") })
        }
        return Promise.resolve({ data: opts.probe === true, error: null })
      }
      // update_api_key_last_used and friends
      return Promise.resolve({ data: null, error: null })
    },
  } as unknown as SupabaseClient

  return { client, calls }
}

// ---------------------------------------------------------------------------
// Session (JWT) auth
//
// These exercise `getAuthenticatedUser`'s `requiredPermission` option, which is
// the mechanism, not the publish policy. No route passes it today: publishing
// moved to services/publish-authz.ts, where holding `plugins.create` is one of
// TWO ways to be allowed (the other being an organisation whose publish policy
// admits you). What is asserted here — a permission miss is 403 and not 401,
// admins bypass, an API key is judged by its owner's live roles — is what that
// module is built on top of, so it all still has to hold.
// ---------------------------------------------------------------------------

Deno.test("JWT holding plugins.create satisfies a requiredPermission gate", async () => {
  const { client } = stubSupabase()
  const token = jwt({ is_admin: false, user_permissions: ["plugins.create", "api_key.create"] })

  const outcome = await getAuthenticatedUser(client, `Bearer ${token}`, undefined, {
    allowApiKey: true,
    requiredScopes: ["publish"],
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assert(outcome.ok)
  assertEquals(outcome.user.userId, OWNER_ID)
  assertEquals(outcome.user.jwtPermissions, ["plugins.create", "api_key.create"])
})

Deno.test("JWT without plugins.create is 403, not 401", async () => {
  const { client } = stubSupabase()
  // A plain user: this is exactly what every authenticated caller could do
  // before plugins.create existed. On the publish path such a caller now gets a
  // second chance through their organisation — see tests/publish-authz.test.ts —
  // but the permission miss itself still has to read as 403.
  const token = jwt({ is_admin: false, user_permissions: ["user.read"] })

  const outcome = await getAuthenticatedUser(client, `Bearer ${token}`, undefined, {
    allowApiKey: true,
    requiredScopes: ["publish"],
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assertFalse(outcome.ok)
  assertEquals(outcome.reason, "insufficient_permission")
  assertEquals(outcome.status, 403)
})

Deno.test("plugins.admin.publish does NOT satisfy the publish gate", async () => {
  const { client } = stubSupabase()
  // The moderation permission is deliberately not a substitute: its RLS policy
  // has no author scoping, so reusing it would re-open store-wide updates.
  const token = jwt({ is_admin: false, user_permissions: ["plugins.admin.publish"] })

  const outcome = await getAuthenticatedUser(client, `Bearer ${token}`, undefined, {
    allowApiKey: true,
    requiredScopes: ["publish"],
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assertFalse(outcome.ok)
  assertEquals(outcome.status, 403)
})

Deno.test("admin bypasses the permission gate", async () => {
  const { client, calls } = stubSupabase()
  const token = jwt({ is_admin: true, user_permissions: [] })

  const outcome = await getAuthenticatedUser(client, `Bearer ${token}`, undefined, {
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assert(outcome.ok)
  assertEquals(calls.probed.length, 0, "admin short-circuits before any DB probe")
})

Deno.test("no credentials is 401", async () => {
  const { client } = stubSupabase()

  const outcome = await getAuthenticatedUser(client, undefined, undefined, {
    allowApiKey: true,
    requiredScopes: ["publish"],
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assertFalse(outcome.ok)
  assertEquals(outcome.reason, "unauthenticated")
  assertEquals(outcome.status, 401)
})

// ---------------------------------------------------------------------------
// API-key auth — permissions come from the owner's roles, not a claim
// ---------------------------------------------------------------------------

Deno.test("API key auth resolves permissions from its OWNER, not from a claim", async () => {
  const { client, calls } = stubSupabase({ apiKey: { scopes: ["publish"] }, probe: true })

  const outcome = await getAuthenticatedUser(client, undefined, VALID_KEY, {
    allowApiKey: true,
    requiredScopes: ["publish"],
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assert(outcome.ok)
  assertEquals(outcome.user.jwtPermissions, null, "API-key auth carries no claim")
  // POPULATED, not undefined. Every `if (user.apiKeyId)` audit-log call in publish.ts hangs off
  // this, and update_api_key_last_used is called with it.
  assertEquals(outcome.user.apiKeyId, "key-1")
  // And the key's organisation has to reach the publish path, which is why the RPC returns it.
  assertEquals(outcome.user.apiKeyOrgId, API_KEY_ORG_ID)
  assertEquals(calls.probed, [{ userId: OWNER_ID, permission: PLUGIN_CREATE_PERMISSION }])
})

Deno.test("API key stops working the moment its owner loses plugins.create", async () => {
  // Same key, same scope — only the owner's roles changed. This is the property
  // that makes revocation immediate instead of waiting for key expiry.
  const { client } = stubSupabase({ apiKey: { scopes: ["publish"] }, probe: false })

  const outcome = await getAuthenticatedUser(client, undefined, VALID_KEY, {
    allowApiKey: true,
    requiredScopes: ["publish"],
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assertFalse(outcome.ok)
  assertEquals(outcome.reason, "insufficient_permission")
  assertEquals(outcome.status, 403)
})

Deno.test("API key with the wrong scope is 403 (regression: was 401)", async () => {
  const { client } = stubSupabase({ apiKey: { scopes: ["version"] }, probe: true })

  const outcome = await getAuthenticatedUser(client, undefined, VALID_KEY, {
    allowApiKey: true,
    requiredScopes: ["publish"],
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assertFalse(outcome.ok)
  assertEquals(outcome.reason, "insufficient_scope")
  assertEquals(outcome.status, 403)
})

Deno.test("an unknown API key is still 401", async () => {
  const { client } = stubSupabase({ probe: true }) // no apiKey row -> not found

  const outcome = await getAuthenticatedUser(client, undefined, VALID_KEY, {
    allowApiKey: true,
    requiredScopes: ["publish"],
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assertFalse(outcome.ok)
  assertEquals(outcome.reason, "unauthenticated")
  assertEquals(outcome.status, 401)
})

Deno.test("API key is rejected when allowApiKey is not set", async () => {
  const { client } = stubSupabase({ apiKey: { scopes: ["publish"] }, probe: true })

  const outcome = await getAuthenticatedUser(client, undefined, VALID_KEY, {
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assertFalse(outcome.ok)
  assertEquals(outcome.status, 401)
})

Deno.test("the permission probe fails closed when the database errors", async () => {
  const { client } = stubSupabase({ apiKey: { scopes: ["publish"] }, probe: "error" })

  const outcome = await getAuthenticatedUser(client, undefined, VALID_KEY, {
    allowApiKey: true,
    requiredScopes: ["publish"],
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assertFalse(outcome.ok)
  assertEquals(outcome.status, 403)
})

Deno.test("an admin's API key satisfies a permission, but is still not an admin", async () => {
  // Two invariants that look contradictory and are not. `user_has_permission`
  // ORs in is_user_admin(), mirroring authorize()'s admin short-circuit — so an
  // admin-owned key clears a permission gate. But AuthResult.isAdmin stays
  // false, which is what routes/admin.ts keys off (and it never accepts API
  // keys anyway). Pinned so neither half drifts.
  const { client } = stubSupabase({ apiKey: { scopes: ["publish"] }, probe: true })

  const outcome = await getAuthenticatedUser(client, undefined, VALID_KEY, {
    allowApiKey: true,
    requiredScopes: ["publish"],
    requiredPermission: "some.permission.outside.any.closure",
  })

  assert(outcome.ok, "the DB probe is the authority on permissions")
  assertFalse(outcome.user.isAdmin, "an API key is never admin, whoever owns it")
})

Deno.test("userHasPermission reads the claim for JWT callers without a round trip", async () => {
  const { client, calls } = stubSupabase({ probe: "error" })

  const held = await userHasPermission(
    client,
    { userId: OWNER_ID, email: "", isAdmin: false, jwtPermissions: ["plugins.create"] },
    PLUGIN_CREATE_PERMISSION,
  )

  assert(held)
  assertEquals(calls.probed.length, 0)
})

// ---------------------------------------------------------------------------
// JWT-only routes (POST/GET/DELETE /api-keys)
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------

/**
 * The tests above prove the gate WORKS; these prove it is CONNECTED.
 *
 * Deleting the gate from one of the five publish handlers leaves the whole suite
 * green and still type-checks (verified by mutation: 5 gates -> 4, 15/15
 * passing) — the handlers are the only place the wiring exists, and there is no
 * HTTP harness here to exercise them. Reading the source is the cheap guard; it
 * costs one file read and catches the one edit most likely to reopen publishing
 * to every authenticated user.
 *
 * The gate used to be `requiredPermission: PLUGIN_CREATE_PERMISSION` on every
 * handler. It is now one of the services/publish-authz.ts calls, because an
 * organisation's own publish policy is a second way to be allowed and it cannot
 * be evaluated before the owning organisation is known. So this asserts the
 * INVARIANT — no publish handler without a gate — rather than one spelling of
 * it, and accepts either.
 *
 * If these ever become awkward, replace them with real route tests — do not
 * simply delete them.
 */
const routeSource = (name: string) =>
  Deno.readTextFileSync(new URL(`../routes/${name}`, import.meta.url))

Deno.test("every publish handler gates on something", () => {
  const src = routeSource("publish.ts")

  const authCalls = src.match(/getAuthenticatedUser\(/g)?.length ?? 0
  assertEquals(authCalls, 5, "the five publishing handlers: publish, version, finalize, github, github/metadata")

  // One segment per handler. The trailing createRoute definition each segment picks up cannot
  // contain a gate, so it can only ever make this test stricter, never laxer.
  const handlers = src.split("publish.openapi(").slice(1)
  assertEquals(handlers.length, authCalls, "one openapi handler per getAuthenticatedUser call")

  const GATES = [
    "authorizeNewPluginPublish(",
    "authorizeExistingPluginPublish(",
    "preflightPublishAuthz(",
    "requiredPermission:",
  ]
  handlers.forEach((handler, i) => {
    assert(
      GATES.some((gate) => handler.includes(gate)),
      `publish handler #${i + 1} reaches a write with no authorization call - ` +
        "every authenticated user can publish through it",
    )
  })

  // The two GitHub handlers must refuse a caller with no publishing rights BEFORE they pull a
  // release archive. Their real gate needs the manifest, which costs that download, so the
  // preflight is the only thing standing between an authenticated non-publisher and making this
  // function fetch arbitrary GitHub assets.
  const preflights = src.match(/preflightPublishAuthz\(/g)?.length ?? 0
  assertEquals(preflights, 2, "both GitHub publish handlers preflight before fetching")

  // Each handler must also surface the outcome's own status rather than a
  // hardcoded 401, or a 403 would be reported as "who are you?". Matched on the
  // render call alone rather than the whole `if (!auth.ok) { ... }` block, so a
  // reformat cannot fail this with a message about permissions.
  const rendered = src.match(/error:\s*auth\.error\s*\},\s*auth\.status\s*\)/g)?.length ?? 0
  assertEquals(rendered, authCalls, "every handler renders auth.status, not a hardcoded 401")
})

Deno.test("only api-key CREATION is gated on api_key.create", () => {
  const src = routeSource("api-keys.ts")

  // POST (create), GET (list), DELETE (revoke)
  const handlers = src.match(/getUserFromToken\(/g)?.length ?? 0
  assertEquals(handlers, 3)

  const gated = src.match(/permissionGateError\(user, API_KEY_CREATE_PERMISSION\)/g)?.length ?? 0
  assertEquals(
    gated,
    1,
    "exactly one gate, on creation. More than one means listing or revoking got gated: " +
      "revocation is a safety valve, and gating it behind the permission a user just lost " +
      "strands their keys. Fewer than one means any authenticated user can mint a publish key.",
  )

  // Pin WHICH handler it is: the create handler is the one validating scopes.
  // Match the call, not the import at the top of the file.
  const callIdx = src.indexOf("permissionGateError(user,")
  const scopesIdx = src.indexOf("areValidScopes(body.scopes)")
  const firstAuthIdx = src.indexOf("getUserFromToken(supabase")
  assert(callIdx > firstAuthIdx, "the gate must come after an auth check, not before")
  assert(callIdx < scopesIdx, "the gate must be in the create handler (the one validating scopes)")
  assertFalse(
    src.slice(callIdx, scopesIdx).includes("getUserFromToken("),
    "another handler starts between the gate and scope validation - the gate is in the wrong one",
  )
})

Deno.test("permissionGateError gates API-key management on api_key.create", () => {
  assertEquals(
    permissionGateError({ isAdmin: false, permissions: ["api_key.create"] }, API_KEY_CREATE_PERMISSION),
    null,
  )
  assertEquals(
    permissionGateError({ isAdmin: true, permissions: [] }, API_KEY_CREATE_PERMISSION),
    null,
    "admins bypass",
  )
  assert(
    permissionGateError({ isAdmin: false, permissions: ["plugins.create"] }, API_KEY_CREATE_PERMISSION)
      ?.includes("api_key.create"),
    "denial names the missing permission",
  )
})

Deno.test("both download handlers resolve the plugin through the serve RPC before anything else", () => {
  const src = routeSource("download.ts")

  // Two handlers: latest, and a specific version.
  const handlers = src.match(/download\.openapi\(/g)?.length ?? 0
  assertEquals(handlers, 2, "the two download handlers")

  // The serve RPC (get_plugin_for_download, migration 20260923150000) is where
  // publication state and organisation entitlements are enforced: its WHERE
  // clause IS user_can_install_plugin, so a row the caller may not have never
  // reaches this route. An ungated download handler serves another
  // organisation's private plugin to anyone who can guess a plugin id.
  const gated = src.match(/await getPluginForDownload\(supabase,/g)?.length ?? 0
  assertEquals(gated, handlers, "every download handler resolves the plugin through the serve RPC")

  // Order is the property, not just presence. The serve RPC must precede BOTH
  // the permission gate and recordDownload in each handler: a plugin the caller
  // may not have must not reach a 403 that confirms it exists, and must not
  // appear in its download counts. And the viewer must be resolved BEFORE the
  // serve RPC, or the RPC sees auth.uid() (NULL under the service-role client)
  // and org/unlisted rows 404 for exactly the members they belong to -- the
  // bug this file used to paper over with a dead canInstall probe.
  for (const [index, chunk] of src.split(/download\.openapi\(/).slice(1).entries()) {
    const viewer = chunk.indexOf("gateSubject(")
    const gate = chunk.indexOf("getPluginForDownload(")
    const permission = chunk.indexOf("installGateError(")
    const record = chunk.indexOf("recordDownload(")
    assertEquals(viewer >= 0, true, `handler ${index} never resolves the viewer`)
    assertEquals(gate >= 0, true, `handler ${index} has no serve RPC lookup`)
    assertEquals(viewer < gate, true, `handler ${index} resolves the plugin before the viewer`)
    assertEquals(gate < permission, true, `handler ${index} gates permissions before visibility`)
    assertEquals(gate < record, true, `handler ${index} records a download before gating it`)
  }
})

Deno.test("a plugin the caller cannot have is 404, never 403", () => {
  const src = routeSource("download.ts")

  // 403 would confirm the plugin exists, which is how an endpoint becomes an
  // enumeration surface for other organisations' private plugin ids.
  const gateBlocks = src.match(
    /getPluginForDownload\(supabase, pluginId, viewerId\)\s*if \(!plugin\) \{\s*return ctx\.json\(\{ error: 'Plugin not found' \}, 404\)/g,
  )
  assertEquals(gateBlocks?.length ?? 0, 2, "both handlers must deny with the same 404 a missing plugin gets")
})
