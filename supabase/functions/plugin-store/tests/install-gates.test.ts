/**
 * Behavioural tests for the shared install gates in services/install-gates.ts
 * (the extraction BossConsole#108's signature route prompted out of
 * routes/download.ts).
 *
 * These are the pieces that DON'T need a live database: the permission gate is
 * pure, and the visibility gate's failure mode - an RPC transport error, a
 * dropped function, a non-boolean answer - is stubbed exactly like
 * tests/auth-permissions.test.ts stubs its SupabaseClient. What these pin:
 *
 *   - an empty requiredPermissions list is open to all callers (the user.read
 *     baseline), and admins bypass the list entirely;
 *   - a denied caller's 403 body names the missing permission(s), because the
 *     message is the only diagnostic an end user ever sees;
 *   - canInstall FAILS CLOSED: an RPC error or any non-`true` answer denies.
 *     The cost of a false deny is a retry; the cost of a false allow is
 *     another organisation's private plugin.
 *
 * Run: deno test --allow-all tests/install-gates.test.ts
 */
import { assert, assertEquals } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { installGateError, canInstall } from "../services/install-gates.ts"

const PLUGIN_ROW_ID = "22222222-2222-2222-2222-222222222222"

// ---------------------------------------------------------------------------
// installGateError - the pure permission decision
// ---------------------------------------------------------------------------

Deno.test("installGateError: an empty requiredPermissions list is open to all callers", () => {
  // Legacy/baseline plugins: no list means the user.read baseline, so even an
  // anonymous caller (user = null) passes.
  assertEquals(installGateError([], null), null)
  assertEquals(installGateError(undefined, null), null)
  assertEquals(installGateError([], { isAdmin: false, permissions: [] }), null)
})

Deno.test("installGateError: admins bypass the permission list entirely", () => {
  assertEquals(
    installGateError(["jar.sign", "net.raw"], { isAdmin: true, permissions: [] }),
    null,
    "an admin who holds none of the listed permissions must still pass",
  )
})

Deno.test("installGateError: a denied caller's error names the missing permissions", () => {
  const error = installGateError(["jar.sign", "net.raw"], {
    isAdmin: false,
    permissions: ["jar.sign"],
  })
  assert(error !== null, "a non-admin missing a listed permission must be denied")
  assertEquals(
    error.includes("net.raw"),
    true,
    "the 403 body is the only diagnostic an end user ever sees; it must name the missing permission",
  )
  assertEquals(error.includes("jar.sign"), false, "held permissions are noise, not diagnostics")
})

Deno.test("installGateError: a caller holding every listed permission passes", () => {
  assertEquals(
    installGateError(["jar.sign", "net.raw"], {
      isAdmin: false,
      permissions: ["net.raw", "jar.sign", "extra"],
    }),
    null,
  )
})

Deno.test("installGateError: an anonymous caller is denied when permissions are required", () => {
  const error = installGateError(["jar.sign"], null)
  assert(error !== null)
  assertEquals(
    error.includes("jar.sign"),
    true,
    "a null user with a required list must be denied with the permission named",
  )
})

// ---------------------------------------------------------------------------
// canInstall - the organisation-visibility gate, and its failure mode
// ---------------------------------------------------------------------------

/** The SupabaseClient stub pattern from tests/auth-permissions.test.ts. */
function rpcStub(
  answer: { data: unknown, error: { message: string } | null },
): SupabaseClient {
  const client = {
    rpc: (_fn: string, _args: Record<string, unknown>) => Promise.resolve(answer),
  }
  return client as unknown as SupabaseClient
}

Deno.test("canInstall: a transport error denies - the gate fails closed", async () => {
  // An RPC error is exactly the case a dropped/misconfigured function or a
  // hiccup between isolates produces; allowing on error would make the
  // visibility gate decoration.
  const denied = await canInstall(
    rpcStub({ data: null, error: { message: "function user_can_install_plugin does not exist" } }),
    PLUGIN_ROW_ID,
    null,
  )
  assertEquals(denied, false)
})

Deno.test("canInstall: any non-true answer denies, only an actual true allows", async () => {
  assertEquals(await canInstall(rpcStub({ data: true, error: null }), PLUGIN_ROW_ID, null), true)
  for (const data of [null, false, "true", "t", 1]) {
    assertEquals(
      await canInstall(rpcStub({ data, error: null }), PLUGIN_ROW_ID, null),
      false,
      `data=${JSON.stringify(data)} must deny: the predicate is data === true, nothing looser`,
    )
  }
})
