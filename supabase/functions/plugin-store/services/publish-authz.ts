/**
 * May this caller publish at all, and if so, for whom?
 *
 * TWO WAYS TO BE ALLOWED, and keeping them apart is the whole point of this module:
 *
 *  1. `plugins.create` (or global admin). Unrestricted, the BOSS store included. This is what
 *     `boss_plugin_admin` exists for and what every release API key uses; its behaviour here is
 *     deliberately byte-identical to the `requiredPermission` gate this module replaced.
 *
 *  2. An organisation you may publish for. No global permission at all: the authority is the
 *     organisation's own `publish_policy` / `publish_role_id`, evaluated by
 *     `user_can_publish_org_plugin`. An org admin satisfies the default policy (`admins`), so an
 *     approved organisation can ship plugins the day it is created instead of waiting for a
 *     platform admin to grant `plugins.create`.
 *
 * THE SECOND PATH CAN NEVER REACH THE BOSS STORE, and that is structural rather than a permission
 * check. `@boss` is `is_system`, and `nonSystemOrgIds` - the only list this module authorises
 * against - drops system organisations. So the three ways a publish lands on `@boss` all refuse:
 * naming it explicitly, naming nothing (the `plugins_default_org` trigger resolves null to boss),
 * and a plugin whose recorded `org_id` is boss.
 *
 * Refusals name the reason. "You have no organisation", "your organisation's policy refuses you"
 * and "that is the BOSS store" send the reader to three different places, and collapsing them into
 * one "permission denied" is what made the previous gate unactionable for the people it caught.
 */

import type { SupabaseClient } from "@supabase/supabase-js"
import type { AuthResult } from "../utils/auth.ts"
import { userHasPermission } from "../utils/auth.ts"
import { PLUGIN_CREATE_PERMISSION } from "../utils/permissions.ts"
import {
  canPublishForOrg,
  nonSystemOrgIds,
  ORG_CHECK_UNAVAILABLE,
  resolvePublishOrg,
} from "./publish-org.ts"

/**
 * The outcome of the gate.
 *
 * `orgScoped` says WHICH path allowed it, and callers act on it: an org-scoped publish of a NEW
 * plugin records `visibility = 'org'`, because a plugin an organisation published for itself
 * belongs on that organisation's shelf, not on the global one. A `plugins.create` publish keeps the
 * column default (`'public'`), which is what every existing publisher already gets.
 */
export type PublishAuthz =
  | { ok: true; orgId: string | null; orgScoped: boolean }
  | { ok: false; status: 403 | 500; error: string }

const BOSS_STORE_REFUSAL =
  `Publishing to the BOSS store requires the "${PLUGIN_CREATE_PERMISSION}" permission. ` +
  "Name one of your own organisations to publish for instead."

const POLICY_REFUSAL =
  "Your organisation's publishing policy does not let you publish plugins for it. " +
  "An organisation admin can change that in the Organisation panel."

const NO_ORG_REFUSAL =
  `Publishing requires either the "${PLUGIN_CREATE_PERMISSION}" permission or an organisation ` +
  "you may publish for, and you belong to none."

const AMBIGUOUS_ORG_REFUSAL =
  "Name the organisation to publish for. You belong to more than one, and publishing without " +
  `naming one goes to the BOSS store, which requires the "${PLUGIN_CREATE_PERMISSION}" permission.`

/**
 * Gate a NEW plugin, and decide which organisation owns it.
 *
 * Resolution comes first and the permission decision second, which is the reverse of the old
 * order: the org-scoped path cannot be answered without knowing where the plugin would land.
 * A caller holding `plugins.create` therefore takes exactly the resolution it always did,
 * refusals included.
 */
export async function authorizeNewPluginPublish(
  supabase: SupabaseClient,
  user: AuthResult,
  requestedOrgId?: string | null,
): Promise<PublishAuthz> {
  const resolution = await resolvePublishOrg(supabase, user, requestedOrgId)

  // Rule 1 is decided BEFORE the resolver's refusal is honoured, and the order is the whole point.
  // `resolvePublishOrg` refuses an "explicit" organisation the caller cannot publish for, and
  // explicit includes the one an API KEY is bound to - so checking the permission afterwards let an
  // organisation's publish_policy veto a global publisher, which is exactly what rule 1 says it
  // cannot do. Observed live: `boss-nilesh-key` is bound to `@boss`, whose policy is `admins`, and
  // its owner holds `plugins.create` but is not a boss org admin - so every first-ever publish of a
  // new pluginId 403'd while existing plugins (which never resolve an org) kept working.
  if (await userHasPermission(supabase, user, PLUGIN_CREATE_PERMISSION)) {
    if (resolution.ok) return { ok: true, orgId: resolution.orgId, orgScoped: false }
    // An organisation the caller NAMED and cannot publish for stays a refusal even here: silently
    // recording the plugin somewhere else than they asked is the failure this file already refuses
    // to make for org publishers, and being unrestricted is not a reason to guess.
    if (requestedOrgId?.trim()) return resolution
    // An organisation inherited from the key is not a request. Fall back to the default and let
    // `plugins_default_org` decide, which is what a global publisher got before this gate existed.
    return { ok: true, orgId: null, orgScoped: false }
  }

  if (!resolution.ok) return resolution

  if (resolution.orgId === null) {
    switch (resolution.source) {
      case "unreadable":
        return { ok: false, status: 500, error: ORG_CHECK_UNAVAILABLE }
      case "policy_refused":
        return { ok: false, status: 403, error: POLICY_REFUSAL }
      case "many_orgs":
        return { ok: false, status: 403, error: AMBIGUOUS_ORG_REFUSAL }
      default:
        return { ok: false, status: 403, error: NO_ORG_REFUSAL }
    }
  }

  // A DERIVED organisation came out of `nonSystemOrgIds` already, so it is known to be the
  // caller's and known not to be a system org. Only an explicitly named one still has to be
  // checked - `user_can_publish_org_plugin` short-circuits true for a global admin and says
  // nothing about `is_system`, so naming `@boss` would otherwise pass for anyone its own publish
  // policy admits.
  if (resolution.source === "explicit") {
    const mine = await nonSystemOrgIds(supabase, user.userId)
    if (mine === null) return { ok: false, status: 500, error: ORG_CHECK_UNAVAILABLE }
    if (!mine.includes(resolution.orgId)) {
      return { ok: false, status: 403, error: BOSS_STORE_REFUSAL }
    }
  }

  return { ok: true, orgId: resolution.orgId, orgScoped: true }
}

/**
 * A cheap "could this caller publish anything at all?" check, for routes that do expensive work
 * before the owning organisation is even knowable.
 *
 * The GitHub publish routes fetch and hash a release JAR before they can read its manifest, and the
 * manifest is what says whether the plugin already exists - so the real decision cannot happen
 * until after the download. The permission gate this module replaced refused before it, and
 * dropping that would let an authenticated caller with no publishing rights at all make the
 * function pull arbitrary release archives. This restores the early refusal for exactly that case
 * and decides nothing else: a caller who passes here is still fully authorised later, against the
 * organisation the plugin actually lands in.
 *
 * Returns a refusal, or null to carry on.
 */
export async function preflightPublishAuthz(
  supabase: SupabaseClient,
  user: AuthResult,
): Promise<{ status: 403 | 500; error: string } | null> {
  if (await userHasPermission(supabase, user, PLUGIN_CREATE_PERMISSION)) return null

  const mine = await nonSystemOrgIds(supabase, user.userId)
  if (mine === null) return { status: 500, error: ORG_CHECK_UNAVAILABLE }
  if (mine.length === 0) return { status: 403, error: NO_ORG_REFUSAL }
  return null
}

/**
 * Gate a new VERSION of a plugin that already exists.
 *
 * Gates on the organisation the plugin is RECORDED against, never a fresh resolution: ownership is
 * a property of the plugin (see `resolvePublishOrg`'s own note), so re-deriving here would let a
 * membership change move somebody else's plugin.
 *
 * Authorship is NOT checked here; `authorizeVersionPublish` layers it on top.
 *
 * Owner decision (2026-10-16): organisation admins may publish new versions of their organisation's
 * plugins even when they are not the original author (co-maintainers). That is allowed only when
 * this returns ok with `orgScoped === true`, i.e. the caller is an active member of the plugin's
 * recorded organisation and passes its publish policy. A `plugins.create` holder who is not the
 * author is NOT let in through the permission arm (`orgScoped: false`): that grant must not reach
 * somebody else's plugin.
 */
export async function authorizeExistingPluginPublish(
  supabase: SupabaseClient,
  user: AuthResult,
  pluginOrgId: string | null,
): Promise<PublishAuthz> {
  if (await userHasPermission(supabase, user, PLUGIN_CREATE_PERMISSION)) {
    return { ok: true, orgId: pluginOrgId, orgScoped: false }
  }

  // Null means the row predates `plugins_default_org` or was written around it; either way it is
  // not an organisation this caller can claim, and the trigger's answer would have been `@boss`.
  if (pluginOrgId === null) {
    return { ok: false, status: 403, error: BOSS_STORE_REFUSAL }
  }

  const mine = await nonSystemOrgIds(supabase, user.userId)
  if (mine === null) return { ok: false, status: 500, error: ORG_CHECK_UNAVAILABLE }
  if (!mine.includes(pluginOrgId)) {
    return { ok: false, status: 403, error: BOSS_STORE_REFUSAL }
  }

  const allowed = await canPublishForOrg(supabase, user.userId, pluginOrgId)
  if (allowed === null) return { ok: false, status: 500, error: ORG_CHECK_UNAVAILABLE }
  if (!allowed) return { ok: false, status: 403, error: POLICY_REFUSAL }

  return { ok: true, orgId: pluginOrgId, orgScoped: true }
}

/**
 * Gate a new version of an existing plugin: the author (as before), or - since 2026-10-16 - an
 * organisation admin of the plugin's recorded organisation (see `authorizeExistingPluginPublish`).
 * Non-authors who do not qualify get `notOwnerError` with 403, the message the per-route author
 * check used to give; an unavailable org lookup still surfaces as its 500.
 */
export async function authorizeVersionPublish(
  supabase: SupabaseClient,
  user: AuthResult,
  pluginAuthorId: string | null,
  pluginOrgId: string | null,
  notOwnerError: string,
): Promise<PublishAuthz> {
  const authz = await authorizeExistingPluginPublish(supabase, user, pluginOrgId)
  if (pluginAuthorId === user.userId) return authz
  if (authz.ok && authz.orgScoped) return authz
  if (!authz.ok && authz.status === 500) return authz
  return { ok: false, status: 403, error: notOwnerError }
}
