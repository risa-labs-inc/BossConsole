/**
 * Who may publish, and the one thing they may not reach: the BOSS store.
 *
 * There are two ways to be allowed. `plugins.create` is unrestricted and unchanged - it is what
 * `boss_plugin_admin` and every release API key use. The second is an organisation whose own
 * `publish_policy` admits you, which exists so an approved organisation can ship plugins on day one
 * instead of waiting for a platform admin to grant a global permission.
 *
 * THE REFUSALS ARE THE POINT. `@boss` is the system organisation every signup joins, so if the
 * org-scoped path could reach it, "publish for your organisation" would silently mean "publish to
 * the platform's own store" for every user in the system. There are three doors to it and each has
 * its own test below: naming it, naming nothing (the `plugins_default_org` trigger resolves a null
 * org to boss), and versioning a plugin already recorded against it.
 */

import { assert, assertEquals, assertFalse } from "@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import {
  authorizeExistingPluginPublish,
  authorizeVersionPublish,
  authorizeNewPluginPublish,
  preflightPublishAuthz,
} from "../services/publish-authz.ts"
import type { PublishAuthz } from "../services/publish-authz.ts"
import type { AuthResult } from "../utils/auth.ts"

const USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const ORG_MINE = "11111111-1111-4111-8111-111111111111"
const ORG_OTHER = "22222222-2222-4222-8222-222222222222"
const ORG_BOSS = "33333333-3333-4333-8333-333333333333"

interface StubOpts {
  /** Answer for user_can_publish_org_plugin, per org id. An absent id answers false. */
  canPublish?: Record<string, boolean>
  orgAdmin?: boolean
  /** Rows for get_my_organisations, in envelope `data` shape. */
  orgs?: Array<Record<string, unknown>>
  /** Answer for the user_has_permission probe (API-key callers, who carry no claim). */
  probe?: boolean
  /** RPC names that should return an error instead of data. */
  failing?: string[]
}

function stub(opts: StubOpts = {}): { client: SupabaseClient; calls: string[] } {
  const calls: string[] = []
  const client = {
    rpc: (fn: string, args: Record<string, unknown>) => {
      calls.push(fn)
      if (opts.failing?.includes(fn)) {
        return Promise.resolve({ data: null, error: { message: "boom" } })
      }
      if (fn === "user_can_publish_org_plugin") {
        return Promise.resolve({ data: opts.canPublish?.[args.p_org_id as string] === true, error: null })
      }
      if (fn === "user_is_org_admin") {
        return Promise.resolve({ data: opts.orgAdmin === true, error: null })
      }
      if (fn === "get_my_organisations") {
        return Promise.resolve({ data: { success: true, data: opts.orgs ?? [] }, error: null })
      }
      if (fn === "user_has_permission") {
        return Promise.resolve({ data: opts.probe === true, error: null })
      }
      return Promise.resolve({ data: null, error: { message: `unexpected rpc ${fn}` } })
    },
  }
  return { client: client as unknown as SupabaseClient, calls }
}

/** A signed-in caller. An empty claim list is a real answer, so no DB probe happens. */
function user(extra: Partial<AuthResult> = {}): AuthResult {
  return { userId: USER, email: "a@b.test", isAdmin: false, jwtPermissions: [], ...extra }
}

const creator = (extra: Partial<AuthResult> = {}) =>
  user({ jwtPermissions: ["plugins.create"], ...extra })

/** An active, non-system membership row. */
const member = (id: string) => ({ id, is_system: false, status: "active" })

function allowed(result: PublishAuthz): { orgId: string | null; orgScoped: boolean } {
  if (!result.ok) throw new Error(`expected success, got ${result.status}: ${result.error}`)
  return { orgId: result.orgId, orgScoped: result.orgScoped }
}

function refused(result: PublishAuthz): { status: number; error: string } {
  if (result.ok) throw new Error(`expected a refusal, got orgId=${result.orgId}`)
  return { status: result.status, error: result.error }
}

// ---------------------------------------------------------------------------
// plugins.create: unchanged in every direction
// ---------------------------------------------------------------------------

Deno.test("plugins.create still publishes to the BOSS store", async () => {
  // The default resolution: nothing named, nothing derivable, so orgId is null and the
  // plugins_default_org trigger records @boss. This is how every plugin in the store got there and
  // it has to keep working - the release pipeline is this path.
  const { client } = stub({ orgs: [] })
  const result = await authorizeNewPluginPublish(client, creator())
  assertEquals(allowed(result), { orgId: null, orgScoped: false })
})

Deno.test("plugins.create is not vetoed by the organisation its API KEY is bound to", async () => {
  // The regression this file did not cover. Every release key is bound to an organisation, and
  // `resolvePublishOrg` treats that binding as "explicit" - so when the binding is an org whose
  // publish_policy does not admit the key's owner, the resolver refuses. Rule 1 says a
  // plugins.create holder is unrestricted, so the refusal must not reach them.
  //
  // Live shape: boss-nilesh-key is bound to @boss (policy `admins`); its owner holds
  // plugins.create but is not a boss org admin. Before the fix this 403'd, which blocked the
  // first-ever publish of EVERY new pluginId while existing plugins kept working.
  const { client } = stub({ canPublish: { [ORG_BOSS]: false } })
  const result = await authorizeNewPluginPublish(client, creator({ apiKeyOrgId: ORG_BOSS }))
  assertEquals(allowed(result), { orgId: null, orgScoped: false })
})

Deno.test("plugins.create keeps the organisation its key is bound to when that org admits it", async () => {
  // The other half: the binding is still honoured as attribution when it is not a refusal, so a
  // key bound to an org it may publish for records that org rather than falling back to boss.
  const { client } = stub({ canPublish: { [ORG_MINE]: true } })
  const result = await authorizeNewPluginPublish(client, creator({ apiKeyOrgId: ORG_MINE }))
  assertEquals(allowed(result), { orgId: ORG_MINE, orgScoped: false })
})

Deno.test("plugins.create naming an organisation it may not publish for is STILL refused", async () => {
  // Deliberately not widened to "unrestricted means anything goes". Being told no about an org you
  // NAMED must not silently record the plugin somewhere else - the same reasoning the org-scoped
  // path already uses. The distinction is request vs. inherited-from-key, not permission level.
  const { client } = stub({ canPublish: { [ORG_OTHER]: false } })
  const result = await authorizeNewPluginPublish(client, creator(), ORG_OTHER)
  assertEquals(refused(result).status, 403)
})

Deno.test("plugins.create may name the system organisation explicitly", async () => {
  const { client } = stub({ canPublish: { [ORG_BOSS]: true } })
  const result = await authorizeNewPluginPublish(client, creator(), ORG_BOSS)
  assertEquals(allowed(result), { orgId: ORG_BOSS, orgScoped: false })
})

Deno.test("a plugins.create publish for an organisation is still not org-scoped", async () => {
  // orgScoped drives visibility, not attribution. Somebody holding the global permission who picks
  // their organisation gets the column default ('public') exactly as they did before this gate
  // existed; only the new path changes what lands in the store.
  const { client } = stub({ canPublish: { [ORG_MINE]: true } })
  const result = await authorizeNewPluginPublish(client, creator(), ORG_MINE)
  assertEquals(allowed(result), { orgId: ORG_MINE, orgScoped: false })
})

Deno.test("an admin needs no permission and no organisation", async () => {
  const { client, calls } = stub({ orgs: [] })
  const result = await authorizeNewPluginPublish(client, user({ isAdmin: true }))
  assertEquals(allowed(result).orgScoped, false)
  assertFalse(calls.includes("user_has_permission"), "admin short-circuits before any probe")
})

// ---------------------------------------------------------------------------
// The org-scoped path: allowed
// ---------------------------------------------------------------------------

Deno.test("an org admin with no plugins.create publishes for their own organisation", async () => {
  // The whole point. publish_policy defaults to 'admins', so the founder of an approved
  // organisation satisfies user_can_publish_org_plugin the day it is created.
  const { client } = stub({ canPublish: { [ORG_MINE]: true }, orgs: [member(ORG_MINE)] })
  const result = await authorizeNewPluginPublish(client, user(), ORG_MINE)
  assertEquals(allowed(result), { orgId: ORG_MINE, orgScoped: true })
})

Deno.test("their sole organisation is derived when they name nothing", async () => {
  const { client } = stub({
    canPublish: { [ORG_MINE]: true },
    // @boss is in everybody's list and must not count as the derived candidate.
    orgs: [{ id: ORG_BOSS, is_system: true, status: "active" }, member(ORG_MINE)],
  })
  const result = await authorizeNewPluginPublish(client, user())
  assertEquals(allowed(result), { orgId: ORG_MINE, orgScoped: true })
})

// ---------------------------------------------------------------------------
// The org-scoped path: the BOSS store stays closed
// ---------------------------------------------------------------------------

Deno.test("naming the system organisation is refused even when its policy would admit them", async () => {
  // THE test. user_can_publish_org_plugin says nothing about is_system - it evaluates membership
  // and publish_policy, and every user is a member of @boss. So a 'members' policy there, or a
  // publish_role_id everyone holds, would hand the platform's own store to everybody. Deleting the
  // nonSystemOrgIds check in authorizeNewPluginPublish makes this the only failing test.
  const { client } = stub({ canPublish: { [ORG_BOSS]: true }, orgs: [member(ORG_MINE)] })
  const result = await authorizeNewPluginPublish(client, user(), ORG_BOSS)
  assertEquals(refused(result).status, 403)
  assert(refused(result).error.includes("plugins.create"), "the refusal names what would be needed")
})

Deno.test("naming an organisation they do not belong to is refused", async () => {
  const { client } = stub({ canPublish: { [ORG_OTHER]: false }, orgs: [member(ORG_MINE)] })
  assertEquals(refused(await authorizeNewPluginPublish(client, user(), ORG_OTHER)).status, 403)
})

Deno.test("belonging to two organisations and naming neither is refused, not defaulted", async () => {
  // resolvePublishOrg deliberately derives nothing from two candidates, and for a plugins.create
  // holder that means "let the trigger pick @boss". For an org-scoped caller the same silence would
  // publish to the BOSS store, so it has to become an error that asks them to choose.
  const { client } = stub({
    canPublish: { [ORG_MINE]: true, [ORG_OTHER]: true },
    orgs: [member(ORG_MINE), member(ORG_OTHER)],
  })
  const result = await authorizeNewPluginPublish(client, user())
  assertEquals(refused(result).status, 403)
  assert(refused(result).error.includes("Name the organisation"), "it says what to do about it")
})

Deno.test("belonging to no organisation is refused with the reason", async () => {
  const { client } = stub({ orgs: [{ id: ORG_BOSS, is_system: true, status: "active" }] })
  const result = await authorizeNewPluginPublish(client, user())
  assertEquals(refused(result).status, 403)
  assert(refused(result).error.includes("you belong to none"))
})

Deno.test("a sole organisation whose policy refuses them is refused, not defaulted to BOSS", async () => {
  // publish_policy = 'owner_only' and they are not the owner. The pre-existing behaviour is to fall
  // back to the default organisation, which for this caller would be the BOSS store - so the
  // fallback has to become a refusal, and one that names the policy rather than the permission.
  const { client } = stub({ canPublish: { [ORG_MINE]: false }, orgs: [member(ORG_MINE)] })
  const result = await authorizeNewPluginPublish(client, user())
  assertEquals(refused(result).status, 403)
  assert(refused(result).error.includes("publishing policy"))
})

Deno.test("an unreadable membership list fails closed", async () => {
  // Not "no organisations" (which would be a 403 telling them to join one) and above all not the
  // BOSS default. An outage must not decide where a plugin lands.
  const { client } = stub({ failing: ["get_my_organisations"] })
  assertEquals(refused(await authorizeNewPluginPublish(client, user())).status, 500)
})

Deno.test("an unreadable membership list fails closed for a named organisation too", async () => {
  const { client } = stub({ canPublish: { [ORG_MINE]: true }, failing: ["get_my_organisations"] })
  assertEquals(refused(await authorizeNewPluginPublish(client, user(), ORG_MINE)).status, 500)
})

// ---------------------------------------------------------------------------
// New versions of an existing plugin
// ---------------------------------------------------------------------------

Deno.test("an org publisher may version a plugin their organisation owns", async () => {
  const { client } = stub({ canPublish: { [ORG_MINE]: true }, orgs: [member(ORG_MINE)] })
  const result = await authorizeExistingPluginPublish(client, user(), ORG_MINE)
  assertEquals(allowed(result), { orgId: ORG_MINE, orgScoped: true })
})

Deno.test("an org publisher may NOT version a plugin with no organisation", async () => {
  // org_id is nullable and the trigger's answer for null is @boss, so treating null as "nobody owns
  // it, go ahead" would be the BOSS store by another door.
  const { client } = stub({ orgs: [member(ORG_MINE)] })
  assertEquals(refused(await authorizeExistingPluginPublish(client, user(), null)).status, 403)
})

Deno.test("an org publisher may NOT version a plugin owned by another organisation", async () => {
  const { client } = stub({ canPublish: { [ORG_OTHER]: true }, orgs: [member(ORG_MINE)] })
  assertEquals(refused(await authorizeExistingPluginPublish(client, user(), ORG_OTHER)).status, 403)
})

Deno.test("an org publisher whose policy stopped admitting them may not version either", async () => {
  // Membership is not publishing rights. The organisation switching to owner_only has to stop the
  // next version, not only the next new plugin.
  const { client } = stub({ canPublish: { [ORG_MINE]: false }, orgs: [member(ORG_MINE)] })
  const result = await authorizeExistingPluginPublish(client, user(), ORG_MINE)
  assertEquals(refused(result).status, 403)
  assert(refused(result).error.includes("publishing policy"))
})

Deno.test("plugins.create versions any plugin it owns, boss-owned included", async () => {
  const { client, calls } = stub({})
  assertEquals(allowed(await authorizeExistingPluginPublish(client, creator(), null)).orgScoped, false)
  assertFalse(calls.includes("get_my_organisations"), "no membership read is needed to decide")
})

Deno.test("an API key is judged by its owner's live roles", async () => {
  // API-key auth carries no claim, so the permission is a DB probe against the OWNER - which is
  // what makes a revoked role take effect immediately instead of at key expiry.
  const withKey = user({ jwtPermissions: null, apiKeyOrgId: ORG_MINE })
  const held = stub({ probe: true })
  assertEquals(allowed(await authorizeExistingPluginPublish(held.client, withKey, ORG_MINE)).orgScoped, false)

  // Same key, role gone. It falls through to the org path, and the organisation still admits it -
  // a CI key bound to an organisation that may publish keeps working, now org-scoped.
  const lost = stub({ probe: false, canPublish: { [ORG_MINE]: true }, orgs: [member(ORG_MINE)] })
  assertEquals(allowed(await authorizeExistingPluginPublish(lost.client, withKey, ORG_MINE)).orgScoped, true)

  // And with neither, it is refused.
  const none = stub({ probe: false, canPublish: { [ORG_MINE]: false }, orgs: [member(ORG_MINE)] })
  assertEquals(refused(await authorizeExistingPluginPublish(none.client, withKey, ORG_MINE)).status, 403)
})

// ---------------------------------------------------------------------------
// Co-maintainers (owner decision 2026-10-16): an org admin may version their org's plugin
// ---------------------------------------------------------------------------

const AUTHOR = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const NOT_OWNER = "Not authorized to publish to this plugin"

Deno.test("a non-author org admin may publish a new version of their org's plugin", async () => {
  const { client } = stub({ orgAdmin: true, canPublish: { [ORG_MINE]: true }, orgs: [member(ORG_MINE)] })
  const result = await authorizeVersionPublish(client, user(), AUTHOR, ORG_MINE, NOT_OWNER)
  assertEquals(allowed(result), { orgId: ORG_MINE, orgScoped: true })
})

Deno.test("a non-author member the org's publish policy does not admit is refused with the old message", async () => {
  const { client } = stub({ canPublish: { [ORG_MINE]: false }, orgs: [member(ORG_MINE)] })
  const result = refused(await authorizeVersionPublish(client, user(), AUTHOR, ORG_MINE, NOT_OWNER))
  assertEquals(result, { status: 403, error: NOT_OWNER })
})

Deno.test("a non-author outside the plugin's org is refused", async () => {
  const { client } = stub({ canPublish: { [ORG_OTHER]: true }, orgs: [member(ORG_MINE)] })
  assertEquals(refused(await authorizeVersionPublish(client, user(), AUTHOR, ORG_OTHER, NOT_OWNER)).error, NOT_OWNER)
})

Deno.test("a plugins.create holder who is not the author cannot version someone else's plugin", async () => {
  // Public/boss-owned (no org) and org-owned alike: the permission arm is never org-scoped.
  const a = stub({})
  assertEquals(refused(await authorizeVersionPublish(a.client, creator(), AUTHOR, null, NOT_OWNER)).status, 403)
  const b = stub({ canPublish: { [ORG_OTHER]: true }, orgs: [member(ORG_MINE)] })
  assertEquals(refused(await authorizeVersionPublish(b.client, creator(), AUTHOR, ORG_OTHER, NOT_OWNER)).error, NOT_OWNER)
})

Deno.test("the author is judged exactly as before, with or without a non-org-scoped grant", async () => {
  const held = stub({})
  assertEquals(allowed(await authorizeVersionPublish(held.client, creator({ userId: AUTHOR }), AUTHOR, null, NOT_OWNER)).orgScoped, false)
  const org = stub({ canPublish: { [ORG_MINE]: true }, orgs: [member(ORG_MINE)] })
  assertEquals(allowed(await authorizeVersionPublish(org.client, user({ userId: AUTHOR }), AUTHOR, ORG_MINE, NOT_OWNER)).orgScoped, true)
  const bad = stub({ canPublish: { [ORG_MINE]: false }, orgs: [member(ORG_MINE)] })
  assertEquals(refused(await authorizeVersionPublish(bad.client, user({ userId: AUTHOR }), AUTHOR, ORG_MINE, NOT_OWNER)).status, 403)
})

Deno.test("an unreadable membership list still surfaces as a 500 for a non-author", async () => {
  const { client } = stub({ failing: ["get_my_organisations"] })
  assertEquals(refused(await authorizeVersionPublish(client, user(), AUTHOR, ORG_MINE, NOT_OWNER)).status, 500)
})

Deno.test("all four version-publishing sites use authorizeVersionPublish, not a bare author check", () => {
  const src = Deno.readTextFileSync(new URL("../routes/publish.ts", import.meta.url))
  assertEquals(src.match(/authorizeVersionPublish\(/g)?.length, 4)
  assertEquals(src.match(/authorId !== user\.userId/g)?.length ?? 0, 0)
})

// ---------------------------------------------------------------------------
// The GitHub routes' preflight
// ---------------------------------------------------------------------------

Deno.test("preflight lets a plugins.create holder through without reading memberships", async () => {
  const { client, calls } = stub({})
  assertEquals(await preflightPublishAuthz(client, creator()), null)
  assertFalse(calls.includes("get_my_organisations"))
})

Deno.test("preflight lets anyone with an organisation through, whatever its policy says", async () => {
  // It must NOT evaluate publish_policy: the plugin may turn out to be an existing one owned by a
  // different organisation of theirs. Deciding here would refuse publishes that are fine.
  const { client } = stub({ orgs: [member(ORG_MINE)], canPublish: {} })
  assertEquals(await preflightPublishAuthz(client, user()), null)
})

Deno.test("preflight refuses a caller with no rights before any GitHub work", async () => {
  const { client } = stub({ orgs: [{ id: ORG_BOSS, is_system: true, status: "active" }] })
  assertEquals((await preflightPublishAuthz(client, user()))?.status, 403)
})

Deno.test("preflight fails closed when memberships cannot be read", async () => {
  const { client } = stub({ failing: ["get_my_organisations"] })
  assertEquals((await preflightPublishAuthz(client, user()))?.status, 500)
})

Deno.test("a member allowed to create org plugins cannot version another author's plugin", async () => {
  const { client } = stub({ orgAdmin: false, canPublish: { [ORG_MINE]: true }, orgs: [member(ORG_MINE)] })
  assertEquals(refused(await authorizeVersionPublish(client, user(), AUTHOR, ORG_MINE, NOT_OWNER)).status, 403)
})

Deno.test("global create permission does not remove an org admin's co-maintainer rights", async () => {
  const { client } = stub({ orgAdmin: true, canPublish: { [ORG_MINE]: true }, orgs: [member(ORG_MINE)] })
  assertEquals(allowed(await authorizeVersionPublish(client, creator(), AUTHOR, ORG_MINE, NOT_OWNER)),
    { orgId: ORG_MINE, orgScoped: true })
})

Deno.test("a failed org admin check refuses co-maintainer publishing", async () => {
  const { client } = stub({ failing: ["user_is_org_admin"], canPublish: { [ORG_MINE]: true }, orgs: [member(ORG_MINE)] })
  assertEquals(refused(await authorizeVersionPublish(client, user(), AUTHOR, ORG_MINE, NOT_OWNER)).status, 500)
})
