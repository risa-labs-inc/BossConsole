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
              data: [{
                user_id: OWNER_ID,
                api_key_id: "key-1",
                key_name: "ci",
                scopes: opts.apiKey.scopes,
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
// ---------------------------------------------------------------------------

Deno.test("JWT holding plugins.create may publish", async () => {
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
  // before plugins.create existed.
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

Deno.test("API key publishes when its owner still holds plugins.create", async () => {
  const { client, calls } = stubSupabase({ apiKey: { scopes: ["publish"] }, probe: true })

  const outcome = await getAuthenticatedUser(client, undefined, VALID_KEY, {
    allowApiKey: true,
    requiredScopes: ["publish"],
    requiredPermission: PLUGIN_CREATE_PERMISSION,
  })

  assert(outcome.ok)
  assertEquals(outcome.user.jwtPermissions, null, "API-key auth carries no claim")
  assertEquals(outcome.user.apiKeyName, "ci")
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
 * Deleting `requiredPermission` from one of the five publish handlers leaves the
 * whole suite green and still type-checks (verified by mutation: 5 gates -> 4,
 * 15/15 passing) — the handlers are the only place the wiring exists, and there
 * is no HTTP harness here to exercise them. Reading the source is the cheap
 * guard; it costs one file read and catches the one edit most likely to reopen
 * publishing to every authenticated user.
 *
 * If these ever become awkward, replace them with real route tests — do not
 * simply delete them.
 */
const routeSource = (name: string) =>
  Deno.readTextFileSync(new URL(`../routes/${name}`, import.meta.url))

Deno.test("every publish handler passes requiredPermission to getAuthenticatedUser", () => {
  const src = routeSource("publish.ts")

  const authCalls = src.match(/getAuthenticatedUser\(/g)?.length ?? 0
  assertEquals(authCalls, 5, "the five publishing handlers: publish, version, finalize, github, github/metadata")

  const gated = src.match(/requiredPermission:\s*PLUGIN_CREATE_PERMISSION/g)?.length ?? 0
  assertEquals(
    gated,
    authCalls,
    "a getAuthenticatedUser call without requiredPermission is an ungated publish path",
  )

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
    "another handler starts between the gate and scope validation — the gate is in the wrong one",
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
