/**
 * Organisation reads, as the pages need them.
 *
 * Everything here goes through gated RPCs with the session subject as
 * `p_actor_id`, so the database applies the same membership rules it would for
 * a direct desktop call. There is no table access.
 */

import { callForActor } from "../utils/org-rpc.ts"
import { listOrgPlugins, type OrgPluginSummary } from "./plugin.ts"

export interface OrgDetail {
  id: string
  slug: string
  name: string
  description: string | null
  /** Public site, http/https only. Optional, and rendered as a link when present. */
  website?: string | null
  visibility: string
  join_policy: string
  is_system: boolean
  owner_email: string | null
  member_count: number
  pending_count: number
  primary_domain: string | null
  is_owner: boolean
  is_admin: boolean
  can_publish: boolean
  // Admin-only. Absent for a plain member - see get_organisation_detail.
  publish_policy?: string
  publish_role_id?: string | null
  publish_role_name?: string | null
  auto_assign_member_role?: boolean
  max_custom_roles?: number
  custom_role_count?: number
  plugin_count?: number
}

export interface OrgMember {
  user_id: string
  email: string | null
  status: string
  joined_at: string | null
  requested_at: string | null
  request_message: string | null
  join_source: string | null
  is_owner: boolean
  is_admin: boolean
  roles: string[]
}

export interface OrgRole {
  role_id: string
  role_name: string
  description: string | null
  kind: string
  member_count: number
  permissions: string[]
}

export interface OrgDomain {
  domain_id: string
  domain: string
  is_primary: boolean
  verified: boolean
  verified_at: string | null
  dns_record_type: string
  dns_record_name: string
  dns_record_value: string
  /**
   * Confirmed accounts at this domain that are not yet members.
   *
   * Always 0 for an unverified row: the RPC does not compute it there, because an
   * unverified claim can never be actioned and the count scans auth.users for a domain match.
   */
  addable_user_count: number
}

export interface OrgInvite {
  invite_id: string
  token_prefix: string
  label: string | null
  role_id: string | null
  role_name: string | null
  max_uses: number | null
  uses: number
  expires_at: string
  revoked_at: string | null
  created_by_email: string | null
  is_live: boolean
  /**
   * The consume-time authority re-check, exposed separately from is_live: the inviter
   * still holds admin over the org. False means the link stops admitting until the
   * inviter is re-promoted -- it is NOT expired, and it can re-arm.
   */
  inviter_admin: boolean
}

export function getOrgDetail(actorId: string, orgId: string) {
  return callForActor<OrgDetail>("get_organisation_detail", actorId, { p_org_id: orgId })
}

export function listMembers(actorId: string, orgId: string) {
  return callForActor<OrgMember[]>("list_organisation_members", actorId, { p_org_id: orgId })
}

export function listRoles(actorId: string, orgId: string) {
  return callForActor<OrgRole[]>("list_organisation_roles", actorId, { p_org_id: orgId })
}

export function listDomains(actorId: string, orgId: string) {
  return callForActor<OrgDomain[]>("list_organisation_domains", actorId, { p_org_id: orgId })
}

export function listInvites(actorId: string, orgId: string) {
  return callForActor<OrgInvite[]>("list_organisation_invites", actorId, { p_org_id: orgId })
}

/**
 * Everything the admin page renders, fetched concurrently.
 *
 * A failed sub-read degrades to an empty list rather than failing the page: the
 * detail read is the one that decides whether the page renders at all (it is
 * the membership gate), and losing, say, the invite list should not blank out
 * members and domains too.
 */
export async function loadAdminPageData(actorId: string, orgId: string): Promise<
  | { ok: true; org: OrgDetail; members: OrgMember[]; roles: OrgRole[]; domains: OrgDomain[]; invites: OrgInvite[] }
  | { ok: false; error: string }
> {
  const [detail, members, roles, domains, invites] = await Promise.all([
    getOrgDetail(actorId, orgId),
    listMembers(actorId, orgId),
    listRoles(actorId, orgId),
    listDomains(actorId, orgId),
    listInvites(actorId, orgId),
  ])

  if (!detail.ok) return { ok: false, error: detail.error }

  return {
    ok: true,
    org: detail.data,
    members: members.ok ? members.data : [],
    roles: roles.ok ? roles.data : [],
    domains: domains.ok ? domains.data : [],
    invites: invites.ok ? invites.data : [],
  }
}

/** The member-facing overview: org detail plus the roster. */
export async function loadOrgPageData(actorId: string, orgId: string): Promise<
  | { ok: true; org: OrgDetail; members: OrgMember[]; roles: OrgRole[]; plugins: OrgPluginSummary[] }
  | { ok: false; error: string }
> {
  const [detail, members, roles, plugins] = await Promise.all([
    getOrgDetail(actorId, orgId),
    listMembers(actorId, orgId),
    listRoles(actorId, orgId),
    listOrgPlugins(actorId, orgId),
  ])

  if (!detail.ok) return { ok: false, error: detail.error }

  return {
    ok: true,
    org: detail.data,
    members: members.ok ? members.data : [],
    roles: roles.ok ? roles.data : [],
    plugins,
  }
}
