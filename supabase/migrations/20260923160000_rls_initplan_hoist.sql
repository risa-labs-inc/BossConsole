-- Hoist statement-constant calls out of row-level security policies.
--
-- Supabase's own database advisor (lint 0003, auth_rls_initplan) reports 68
-- policies in this schema that re-evaluate `auth.uid()` or `auth.jwt()` once per
-- candidate row instead of once per statement. PostgreSQL will hoist such a call
-- into an InitPlan when it is written as a scalar subquery, so the documented
-- fix is to write `(select auth.uid())` rather than `auth.uid()`. The call is
-- STABLE, so its value cannot change within one statement and the rewrite cannot
-- change which rows a policy admits.
--
-- This migration rewrites 80 policies across 29 tables, hoisting 86 call
-- sites. Every statement is an ALTER POLICY that restates only USING and
-- WITH CHECK, so the command, the TO roles and PERMISSIVE/RESTRICTIVE are
-- carried over by PostgreSQL rather than retyped here. A DROP and CREATE pair
-- would have to restate all of them, and getting one TO clause wrong silently
-- widens access.
--
-- THE RULE, and it is the whole safety argument: a call may be hoisted only if
-- its result is constant for the entire statement. That takes two things.
-- The function must be STABLE or IMMUTABLE: a VOLATILE one may answer
-- differently on every call, and for it the number of evaluations is the
-- behaviour, so hoisting would change it even with no arguments. And its
-- arguments must not vary per row: none, literals, or arguments that are
-- themselves statement-constant. A call that takes a column fails the second
-- test, because the answer then differs per row and hoisting it would return
-- one row's answer for every row. All 131 call sites in the schema's policies
-- were classified against that rule from the catalog, not from the migration
-- sources, and the four functions hoisted here are all STABLE; the pgTAP suite
-- pins that.
--
-- is_user_admin(auth.uid()) is the one hoisted call that takes an argument,
-- and it is not a counter-example to the rule: its only argument is
-- auth.uid(), which is itself statement-constant.
--
-- What is hoisted, counted as the OUTERMOST call at each site:
--
--   auth.jwt                      18 sites
--   auth.uid                      44 sites
--   authorize                     12 sites
--   is_user_admin                 12 sites
--
-- The auth.uid figure is the standalone ones. Twelve more sit inside the
-- is_user_admin calls below and are hoisted with them, so the schema's 56
-- auth.uid sites are 44 here plus those 12.
--
-- WHAT IS DELIBERATELY LEFT ALONE, because each takes a column and hoisting it
-- would be a security bug rather than an optimisation:
--
--   can_manage_secret              5 sites
--   can_publish_org_plugin         1 site
--   can_view_plugin_row            4 sites
--   is_org_admin                  14 sites
--   is_org_member                  9 sites
--
-- Two of the four hoisted kinds are invisible to the advisor, which reads the
-- policy expression but does not walk into the body of a function the policy
-- calls:
--
--   authorize('...')            12 sites. The argument is a literal, so the
--                               whole call is statement-constant. The advisor
--                               never flags it, and the body runs two bare
--                               `auth.uid()` lookups per invocation.
--
--   is_user_admin(auth.uid())   12 sites. The advisor sees the inner
--                               `auth.uid()` and its remediation text says to
--                               rewrite that one call. Doing exactly that
--                               produces `is_user_admin((select auth.uid()))`,
--                               which still calls a plpgsql SECURITY DEFINER
--                               function once per row. Hoisting the whole call
--                               is what actually removes the per-row work.
--
-- Both helpers are `LANGUAGE plpgsql STABLE SECURITY DEFINER`. STABLE is what
-- makes the rewrite sound; plpgsql is why it is worth doing, since a plpgsql
-- function cannot be inlined and so pays a full call per row.
--
-- The twelve is_user_admin sites carry TWO subqueries, deliberately, and the
-- inner one is not redundant for the reason it looks redundant:
--
--     ( SELECT is_user_admin(( SELECT auth.uid() )) )
--
-- The OUTER one is the fix. It is what stops a plpgsql call happening once per
-- row, and with it the inner call already runs once per statement, so the inner
-- subquery changes nothing about execution. The INNER one is there because the
-- advisor's check is TEXTUAL: it accepts a session call only directly behind a
-- SELECT, so with the outer hoist alone these policies stay flagged by a lint
-- the stronger rewrite has already satisfied in substance. Measured, not
-- assumed: the first run of this migration left `auth_rls_initplan` reporting
-- exactly them. Twelve sites over ten policies, two of which carry the call in
-- both USING and WITH CHECK.
--
-- Not covered: policies whose only remaining per-row work is a helper taking a
-- column, listed above. Making those cheaper means changing the helpers or the
-- policies themselves, which is a behaviour change and belongs in its own PR.

ALTER POLICY "Service role full access to organisation domains" ON public.organisation_domains
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Service role full access to handoff tokens" ON public.organisation_handoff_tokens
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Service role full access to invite redemptions" ON public.organisation_invite_redemptions
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Users can view their own invite redemptions" ON public.organisation_invite_redemptions
  USING ((user_id = ( SELECT auth.uid() )));

ALTER POLICY "Service role full access to organisation invites" ON public.organisation_invites
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Organisation members can view the roster" ON public.organisation_members
  USING (((status = 'active'::text) AND is_org_member(org_id) AND ((user_id = ( SELECT auth.uid() )) OR is_org_admin(org_id) OR (NOT (EXISTS ( SELECT 1
   FROM organisations o
  WHERE ((o.id = organisation_members.org_id) AND o.is_system)))))));

ALTER POLICY "Service role full access to organisation members" ON public.organisation_members
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Users can view their own memberships" ON public.organisation_members
  USING ((user_id = ( SELECT auth.uid() )));

ALTER POLICY "Requesters can view their own organisation requests" ON public.organisation_requests
  USING ((requester_id = ( SELECT auth.uid() )));

ALTER POLICY "Reviewers can view all organisation requests" ON public.organisation_requests
  USING (( SELECT authorize('organisation.request_read'::text) ));

ALTER POLICY "Service role full access to organisation requests" ON public.organisation_requests
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Service role full access to organisation roles" ON public.organisation_roles
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Organisation reviewers can view all organisations" ON public.organisations
  USING (( SELECT authorize('organisation.approve'::text) ));

ALTER POLICY "Service role full access to organisations" ON public.organisations
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Allow session-based access for mobile flows" ON public.passkey_challenges
  USING (((session_id IS NOT NULL) AND ((( SELECT auth.uid() ) = user_id) OR (user_id IS NULL))));

ALTER POLICY "Service role can access all challenges" ON public.passkey_challenges
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Users can insert their own challenges" ON public.passkey_challenges
  WITH CHECK (((( SELECT auth.uid() ) = user_id) OR (user_id IS NULL)));

ALTER POLICY "Users can view their own challenges" ON public.passkey_challenges
  USING (((( SELECT auth.uid() ) = user_id) OR (user_id IS NULL)));

ALTER POLICY "Admins can create permissions" ON public.permissions
  WITH CHECK (( SELECT is_user_admin(( SELECT auth.uid() )) ));

ALTER POLICY "Admins can delete non-system permissions" ON public.permissions
  USING (((NOT is_system) AND ( SELECT is_user_admin(( SELECT auth.uid() )) )));

ALTER POLICY "Admins can update non-system permissions" ON public.permissions
  USING (((NOT is_system) AND ( SELECT is_user_admin(( SELECT auth.uid() )) )));

ALTER POLICY "Service role full access to permissions" ON public.permissions
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Users can view own API key logs" ON public.plugin_api_key_logs
  USING ((EXISTS ( SELECT 1
   FROM plugin_api_keys
  WHERE ((plugin_api_keys.id = plugin_api_key_logs.api_key_id) AND (plugin_api_keys.user_id = ( SELECT auth.uid() ))))));

ALTER POLICY "Users can create own API keys" ON public.plugin_api_keys
  WITH CHECK ((( SELECT auth.uid() ) = user_id));

ALTER POLICY "Users can delete own API keys" ON public.plugin_api_keys
  USING ((( SELECT auth.uid() ) = user_id));

ALTER POLICY "Users can update own API keys" ON public.plugin_api_keys
  USING ((( SELECT auth.uid() ) = user_id));

ALTER POLICY "Users can view own API keys" ON public.plugin_api_keys
  USING ((( SELECT auth.uid() ) = user_id));

ALTER POLICY "role.read can view plugin permission provenance" ON public.plugin_permissions
  USING (( SELECT authorize('role.read'::text) ));

ALTER POLICY "Authors can manage own plugin screenshots" ON public.plugin_screenshots
  USING ((EXISTS ( SELECT 1
   FROM plugins
  WHERE ((plugins.id = plugin_screenshots.plugin_id) AND (plugins.author_id = ( SELECT auth.uid() ))))));

ALTER POLICY "Users with plugins.admin.delete can manage all screenshots" ON public.plugin_screenshots
  USING (( SELECT authorize('plugins.admin.delete'::text) ));

ALTER POLICY "Users with plugins.admin.view can view all screenshots" ON public.plugin_screenshots
  USING (( SELECT authorize('plugins.admin.view'::text) ));

ALTER POLICY "Authors can manage own plugin tags" ON public.plugin_tags
  USING ((EXISTS ( SELECT 1
   FROM plugins
  WHERE ((plugins.id = plugin_tags.plugin_id) AND (plugins.author_id = ( SELECT auth.uid() ))))));

ALTER POLICY "Users with plugins.admin.delete can manage all tags" ON public.plugin_tags
  USING (( SELECT authorize('plugins.admin.delete'::text) ));

ALTER POLICY "Users with plugins.admin.view can view all tags" ON public.plugin_tags
  USING (( SELECT authorize('plugins.admin.view'::text) ));

ALTER POLICY "Authors can add versions" ON public.plugin_versions
  WITH CHECK ((EXISTS ( SELECT 1
   FROM plugins
  WHERE ((plugins.id = plugin_versions.plugin_id) AND (plugins.author_id = ( SELECT auth.uid() ))))));

ALTER POLICY "Authors can view own plugin versions" ON public.plugin_versions
  USING ((EXISTS ( SELECT 1
   FROM plugins
  WHERE ((plugins.id = plugin_versions.plugin_id) AND (plugins.author_id = ( SELECT auth.uid() ))))));

ALTER POLICY "Users with plugins.admin.delete can delete versions" ON public.plugin_versions
  USING (( SELECT authorize('plugins.admin.delete'::text) ));

ALTER POLICY "Users with plugins.admin.view can view all versions" ON public.plugin_versions
  USING (( SELECT authorize('plugins.admin.view'::text) ));

ALTER POLICY "Authorised publishers can create plugins" ON public.plugins
  WITH CHECK (((( SELECT auth.uid() ) = author_id) AND ((org_id IS NULL) OR can_publish_org_plugin(org_id))));

ALTER POLICY "Authors and organisation admins can update plugins" ON public.plugins
  USING (((( SELECT auth.uid() ) = author_id) OR ((org_id IS NOT NULL) AND is_org_admin(org_id))))
  WITH CHECK (((( SELECT auth.uid() ) = author_id) OR ((org_id IS NOT NULL) AND is_org_admin(org_id))));

ALTER POLICY "Authors can delete own plugins" ON public.plugins
  USING ((( SELECT auth.uid() ) = author_id));

ALTER POLICY "Authors can view own plugins" ON public.plugins
  USING ((( SELECT auth.uid() ) = author_id));

ALTER POLICY "Users with plugins.admin.delete can delete any plugin" ON public.plugins
  USING (( SELECT authorize('plugins.admin.delete'::text) ));

ALTER POLICY "Users with plugins.admin.publish can update any plugin" ON public.plugins
  USING (( SELECT authorize('plugins.admin.publish'::text) ));

ALTER POLICY "Users with plugins.admin.view can view all plugins" ON public.plugins
  USING (( SELECT authorize('plugins.admin.view'::text) ));

ALTER POLICY "Service role full access to reserved email domains" ON public.reserved_email_domains
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Admins can manage role hierarchy" ON public.role_hierarchy
  USING (( SELECT is_user_admin(( SELECT auth.uid() )) ))
  WITH CHECK (( SELECT is_user_admin(( SELECT auth.uid() )) ));

ALTER POLICY "Service role full access to role hierarchy" ON public.role_hierarchy
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Admins can manage role permissions" ON public.role_permissions
  USING (( SELECT is_user_admin(( SELECT auth.uid() )) ));

ALTER POLICY "Service role full access to role_permissions" ON public.role_permissions
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Admins can create roles" ON public.roles
  WITH CHECK (( SELECT is_user_admin(( SELECT auth.uid() )) ));

ALTER POLICY "Admins can delete non-system roles" ON public.roles
  USING (((NOT is_system) AND ( SELECT is_user_admin(( SELECT auth.uid() )) )));

ALTER POLICY "Admins can update non-system roles" ON public.roles
  USING (((NOT is_system) AND ( SELECT is_user_admin(( SELECT auth.uid() )) )));

ALTER POLICY "Service role full access to roles" ON public.roles
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "secret_access_log_select" ON public.secret_access_log
  USING (((user_id = ( SELECT auth.uid() )) OR (EXISTS ( SELECT 1
   FROM (user_roles ur
     JOIN roles r ON ((r.id = ur.role_id)))
  WHERE ((ur.user_id = ( SELECT auth.uid() )) AND (r.name = 'admin'::text))))));

ALTER POLICY "Users can create own secret metadata" ON public.secret_metadata
  WITH CHECK ((EXISTS ( SELECT 1
   FROM secrets s
  WHERE ((s.id = secret_metadata.secret_id) AND ((s.user_id = ( SELECT auth.uid() )) OR ((s.org_id IS NOT NULL) AND is_org_admin(s.org_id)))))));

ALTER POLICY "Users can delete own secret metadata" ON public.secret_metadata
  USING ((EXISTS ( SELECT 1
   FROM secrets s
  WHERE ((s.id = secret_metadata.secret_id) AND ((s.user_id = ( SELECT auth.uid() )) OR ((s.org_id IS NOT NULL) AND is_org_admin(s.org_id)))))));

ALTER POLICY "Users can update own secret metadata" ON public.secret_metadata
  USING ((EXISTS ( SELECT 1
   FROM secrets s
  WHERE ((s.id = secret_metadata.secret_id) AND ((s.user_id = ( SELECT auth.uid() )) OR ((s.org_id IS NOT NULL) AND is_org_admin(s.org_id)))))));

ALTER POLICY "Users can view own secret metadata" ON public.secret_metadata
  USING ((EXISTS ( SELECT 1
   FROM secrets s
  WHERE ((s.id = secret_metadata.secret_id) AND ((s.user_id = ( SELECT auth.uid() )) OR ((s.org_id IS NOT NULL) AND is_org_member(s.org_id)))))));

ALTER POLICY "secret_shares_select" ON public.secret_shares
  USING ((can_manage_secret(secret_id) OR (shared_with_user_id = ( SELECT auth.uid() )) OR (shared_with_role_id IN ( SELECT my_effective_share_role_ids() AS my_effective_share_role_ids)) OR ((shared_with_org_id IS NOT NULL) AND is_org_member(shared_with_org_id))));

ALTER POLICY "Users can create own secret tags" ON public.secret_tags
  WITH CHECK ((EXISTS ( SELECT 1
   FROM secrets s
  WHERE ((s.id = secret_tags.secret_id) AND ((s.user_id = ( SELECT auth.uid() )) OR ((s.org_id IS NOT NULL) AND is_org_admin(s.org_id)))))));

ALTER POLICY "Users can delete own secret tags" ON public.secret_tags
  USING ((EXISTS ( SELECT 1
   FROM secrets s
  WHERE ((s.id = secret_tags.secret_id) AND ((s.user_id = ( SELECT auth.uid() )) OR ((s.org_id IS NOT NULL) AND is_org_admin(s.org_id)))))));

ALTER POLICY "Users can view own secret tags" ON public.secret_tags
  USING ((EXISTS ( SELECT 1
   FROM secrets s
  WHERE ((s.id = secret_tags.secret_id) AND ((s.user_id = ( SELECT auth.uid() )) OR ((s.org_id IS NOT NULL) AND is_org_member(s.org_id)))))));

ALTER POLICY "Owners and organisation admins can delete secrets" ON public.secrets
  USING (((( SELECT auth.uid() ) = user_id) OR ((org_id IS NOT NULL) AND is_org_admin(org_id))));

ALTER POLICY "Owners and organisation admins can update secrets" ON public.secrets
  USING (((( SELECT auth.uid() ) = user_id) OR ((org_id IS NOT NULL) AND is_org_admin(org_id))))
  WITH CHECK (((( SELECT auth.uid() ) = user_id) OR ((org_id IS NOT NULL) AND is_org_admin(org_id))));

ALTER POLICY "Users can create own or organisation secrets" ON public.secrets
  WITH CHECK (((( SELECT auth.uid() ) = user_id) AND ((org_id IS NULL) OR is_org_member(org_id))));

ALTER POLICY "Users can view own or organisation secrets" ON public.secrets
  USING (((( SELECT auth.uid() ) = user_id) OR ((org_id IS NOT NULL) AND is_org_member(org_id))));

ALTER POLICY "Service role can access all passkeys" ON public.user_passkeys
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Users can delete their own passkeys" ON public.user_passkeys
  USING ((( SELECT auth.uid() ) = user_id));

ALTER POLICY "Users can insert their own passkeys" ON public.user_passkeys
  WITH CHECK ((( SELECT auth.uid() ) = user_id));

ALTER POLICY "Users can update their own passkeys" ON public.user_passkeys
  USING ((( SELECT auth.uid() ) = user_id));

ALTER POLICY "Users can view their own passkeys" ON public.user_passkeys
  USING ((( SELECT auth.uid() ) = user_id));

ALTER POLICY "Admins can assign roles" ON public.user_roles
  WITH CHECK (( SELECT is_user_admin(( SELECT auth.uid() )) ));

ALTER POLICY "Admins can remove roles" ON public.user_roles
  USING ((( SELECT is_user_admin(( SELECT auth.uid() )) ) AND (NOT ((user_id = ( SELECT auth.uid() )) AND (role_id IN ( SELECT roles.id
   FROM roles
  WHERE (roles.name = 'admin'::text)))))));

ALTER POLICY "Admins can view all roles" ON public.user_roles
  USING (( SELECT is_user_admin(( SELECT auth.uid() )) ));

ALTER POLICY "Service role full access to user_roles" ON public.user_roles
  USING (((( SELECT auth.jwt() ) ->> 'role'::text) = 'service_role'::text));

ALTER POLICY "Users can view their own roles" ON public.user_roles
  USING ((( SELECT auth.uid() ) = user_id));

ALTER POLICY "Privileged users can read all users" ON public.users
  USING ((COALESCE(((( SELECT auth.jwt() ) -> 'is_admin'::text))::boolean, false) OR ((( SELECT auth.jwt() ) -> 'user_permissions'::text) ? 'role.read'::text)));

ALTER POLICY "Users can read own data" ON public.users
  USING ((( SELECT auth.uid() ) = id));

ALTER POLICY "Users can update own data" ON public.users
  USING ((( SELECT auth.uid() ) = id));
