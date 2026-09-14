-- Identity-disclosure audit for schema `public`. Run it against the project and
-- read the `finding` column: HEALTHY means that check passed. Any other value is
-- a candidate for investigation with the object named beside it. Checks 2 and
-- 3 are heuristics, not proof of authorization: comments can match gate names,
-- indirect calls can hide identity access, and nonliteral RLS predicates and
-- views require review. CI hard-gates checks 1, 1b, 4 and 5 and separately
-- tests org visibility. Checks 2, 3, 3b, 3c and 5x are advisory candidates; CI
-- tests their detection on fixtures, not HEALTHY on every existing surface.
--
-- This exists because "we fixed the leak" is not a durable claim. On 2026-09-08
-- the Arcade published its player roster to unauthenticated callers,
-- get_encryption_key() handed the Vault master key to anyone holding the anon
-- key that ships in this public repo, and list_shareable_recipients returned 152
-- users with full email addresses to any self-registered account. None of those
-- was a typo; each was a rule enforced at one site and asked in a weaker form at
-- another. A checklist in someone's head does not catch that. This does.
--
-- Run it after any migration that adds a function or a policy.

-- ---------------------------------------------------------------------------
-- CHECK 1: nothing in schema public is callable without an account, except the
-- objects that intend to be.
--
-- PostgreSQL hardwires EXECUTE to PUBLIC on every new function, and PUBLIC
-- includes `anon` - so this check is load-bearing forever, not just after a
-- mistake. The event trigger in 20260908000000_explicit_anon_grants.sql revokes
-- it at creation time; this proves the trigger is still installed and working.
-- ---------------------------------------------------------------------------
with intentionally_anon as (
  -- The plugin store is browsable before sign-in, and the RLS
  -- helpers are invoked by anonymous queries. Adding a signature
  -- here is a deliberate decision to publish it; do not add one to silence the
  -- audit.
  -- Exact signatures refine the historical name-only `keep` snapshot in
  -- migrations/20260908030000_revoke_remaining_anon_execute.sql, which explains
  -- what breaks for each. In short: the plugin store is browsable before
  -- sign-in; authorize / is_user_admin / can_view_plugin_row are called from
  -- RLS policies on anon-readable tables, and a policy expression runs as the
  -- QUERYING role. The 20260909130000 follow-up removes the two edge-only
  -- mutators and the GoTrue hook from anonymous access.
  select to_regprocedure(signature) as oid
  from unnest(array[
    'public.search_plugins(text,text,text[],numeric,boolean,integer,integer,text)',
    'public.get_plugin_with_stats(text)', 'public.get_plugin_versions(text)',
    'public.get_popular_tags(integer)',
    'public.can_view_plugin_row(text,uuid,uuid,boolean)',
    'public.authorize(text)', 'public.is_user_admin(uuid)'
  ]) signature
),
anon_callable as (
  select distinct p.proname, p.oid
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public'
    and p.prokind in ('f', 'p')
      and not exists (
        select 1 from pg_catalog.pg_depend d
        where d.classid = 'pg_catalog.pg_proc'::regclass
          and d.objid = p.oid and d.deptype = 'e')
    and has_function_privilege('anon', p.oid, 'EXECUTE')
)
select 'CHECK 1: callable without an account' as check,
       coalesce(nullif(string_agg(ac.oid::regprocedure::text, ', ' order by ac.oid::regprocedure::text), ''), 'HEALTHY') as finding
from anon_callable ac
where not exists (select 1 from intentionally_anon i where i.oid = ac.oid)

union all

select 'CHECK 1b: expected anonymous signature missing or revoked',
       case when exists (select 1 from intentionally_anon
                         where oid is null or not has_function_privilege('anon', oid, 'EXECUTE'))
            then 'EXPECTED ANONYMOUS ACCESS MISSING - review signature allowlist'
            else 'HEALTHY' end

union all

-- ---------------------------------------------------------------------------
-- CHECK 1x (advisory): extensions installed into `public` whose routines are
-- anon-callable. CHECK 1 exempts them because the event trigger cannot hold an
-- extension to the invariant without making CREATE EXTENSION impossible - but
-- the exposure is real and must stay visible rather than vanish. `create
-- extension pgtap` alone lands 1079 anon-executable functions in public.
-- The fix is to install extensions into their own schema, not to silence this.
-- ---------------------------------------------------------------------------
select 'CHECK 1x (advisory): anon-callable extension routines in public',
       coalesce(nullif(string_agg(distinct t.extname || ' (' || t.n::text || ')', ', ' order by t.extname || ' (' || t.n::text || ')'), ''), 'HEALTHY')
from (
  select e.extname, count(*) as n
  from pg_catalog.pg_extension e
  join pg_catalog.pg_depend d on d.refobjid = e.oid and d.deptype = 'e'
   and d.classid = 'pg_catalog.pg_proc'::regclass
  join pg_catalog.pg_proc p on p.oid = d.objid
  join pg_catalog.pg_namespace n on n.oid = p.pronamespace and n.nspname = 'public'
  where exists (
    select 1 from aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
    left join pg_catalog.pg_roles r on r.oid = a.grantee
    where a.privilege_type = 'EXECUTE'
      and (a.grantee = 0 or r.rolname = 'anon'))
  group by e.extname
) t
union all

-- ---------------------------------------------------------------------------
-- CHECK 2: every function that can reach an identity applies a gate.
--
-- A gate is the visibility rule (org_visible_users / org_is_vetted and the
-- arcade aliases), an admin/permission check, or a self-scope on auth.uid().
-- A function that reaches auth.users with none of those returns whoever it
-- likes to whoever asks - which is what arcade_leaderboard and poker_lobby did.
-- ---------------------------------------------------------------------------
select 'CHECK 2: identity reachable with no gate',
       coalesce(nullif(string_agg(t.proname, ', ' order by t.proname), ''), 'HEALTHY')
from (
  select p.proname, pg_get_functiondef(p.oid) as def, p.oid
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public'
    and p.prokind in ('f', 'p')
    and p.prorettype not in ('trigger'::regtype, 'event_trigger'::regtype)
) t
where t.def ~* '(auth\.users|raw_user_meta_data|user_display_name|arcade_display_name)'
  -- reachable by a client role at all
  and exists (
    select 1
    from aclexplode(coalesce((select proacl from pg_proc where oid = t.oid),
                             acldefault('f', (select proowner from pg_proc where oid = t.oid)))) a
    left join pg_roles r on r.oid = a.grantee
    where a.privilege_type = 'EXECUTE'
      and (a.grantee = 0 or r.rolname in ('anon', 'authenticated'))
  )
  -- ...and gated by none of the three accepted gates. Keep this list in step
  -- with the helpers actually used; a gate under a new name reads as no gate.
  and t.def !~* '(org_visible_users|org_is_vetted|arcade_visible_users|arcade_may_see)'
  and t.def !~* '(is_admin|is_user_admin|arcade_is_admin|authorize\(|user_is_org_admin|user_holds_permission|can_manage_secret|can_view)'
  and t.def !~* 'auth\.uid\(\)'
  -- The rule itself, and the name formatter it calls, ARE the gate rather than
  -- users of it.
  and t.proname not in ('user_display_name', 'arcade_display_name',
                        'org_visible_users', 'org_is_vetted')
  -- search_organisations is a reviewed exception, not an oversight. It touches
  -- auth.users only to read the CALLER'S OWN email domain, and returns
  -- organisations - never another user's identity - scoped to public orgs, orgs
  -- the caller belongs to, and orgs whose verified domain matches that domain.
  -- Re-read it before removing this line.
  and t.proname <> 'search_organisations'

union all

-- ---------------------------------------------------------------------------
-- CHECK 3: no relation carrying a name or an email is readable by everyone.
--
-- `using (true)` is not access control in this project: signup is open with
-- email autoconfirm, so `authenticated` includes anyone on the internet who
-- registered. This is what exposed poker chat.
-- ---------------------------------------------------------------------------
select 'CHECK 3: identity-bearing relation readable by all',
       coalesce(nullif(string_agg(distinct pol.tablename, ', ' order by pol.tablename), ''), 'HEALTHY')
from pg_policies pol
where pol.schemaname = 'public'
  and pol.cmd in ('SELECT', 'ALL')
  and (pol.qual is null or btrim(lower(pol.qual)) in ('true', '(true)'))
  and pol.roles && array['anon','authenticated','public']::name[]
  and exists (
    select 1
    from pg_attribute a
    join pg_class c on c.oid = a.attrelid
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relname = pol.tablename
      and a.attnum > 0 and not a.attisdropped
      and a.attname ~* '(email|display_name|full_name)'
  )

union all

-- Missing RLS has no pg_policies row, so examine identity-bearing tables too.
select 'CHECK 3b: identity-bearing table without RLS',
       coalesce(string_agg(c.oid::regclass::text, ', ' order by c.oid::regclass::text), 'HEALTHY')
from pg_class c join pg_namespace n on n.oid=c.relnamespace
where n.nspname='public' and c.relkind in ('r', 'p') and not c.relrowsecurity
  and (has_table_privilege('anon', c.oid, 'SELECT')
       or has_table_privilege('authenticated', c.oid, 'SELECT'))
  and exists (select 1 from pg_attribute a where a.attrelid=c.oid
              and a.attnum>0 and not a.attisdropped
              and a.attname ~* '(email|display_name|full_name)')

union all

-- Definer views can bypass underlying RLS. Column-name matching is advisory;
-- security_invoker does not prove the underlying policy is correctly scoped.
select 'CHECK 3c: identity-bearing view without security_invoker',
       coalesce(string_agg(c.oid::regclass::text, ', ' order by c.oid::regclass::text), 'HEALTHY')
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public' and c.relkind = 'v'
  and not coalesce((select option_value::boolean
                    from pg_catalog.pg_options_to_table(c.reloptions)
                    where option_name = 'security_invoker'), false)
  and (has_table_privilege('anon', c.oid, 'SELECT')
       or has_table_privilege('authenticated', c.oid, 'SELECT'))
  and exists (select 1 from pg_attribute a where a.attrelid = c.oid
              and a.attnum > 0 and not a.attisdropped
              and a.attname ~* '(email|display_name|full_name)')

union all

-- ---------------------------------------------------------------------------
-- CHECK 4: the guard from 20260908000000 is still installed and enabled.
-- Without it, checks 1 and 2 go stale the moment someone adds a function.
-- This is a structural check. CI separately exercises guard behavior, including
-- warning-only REVOKE, in explicit_anon_and_org_visibility_test.sql. Matching
-- function-body text here would not prove that its postcondition executes.
-- ---------------------------------------------------------------------------
select 'CHECK 4: explicit-anon-grant event trigger',
       coalesce(
         (select case when evtenabled in ('O', 'A') and evttags @> array['CREATE FUNCTION', 'CREATE PROCEDURE']::text[] then 'HEALTHY' else 'DISABLED OR WRONG FIRING MODE/TAGS' end
            from pg_event_trigger where evtname = 'enforce_explicit_anon_grants'),
         'MISSING - new functions are anon-callable at birth')

union all

-- Explicit grants to signed-in accounts must not reopen the key or an oracle.
select 'CHECK 5: client access to internal-only routines',
       coalesce(string_agg(signature || ' (' || role_name || ')' ||
           case when to_regprocedure(signature) is null then ' MISSING SIGNATURE' else '' end, ', '), 'HEALTHY')
from unnest(array['public.get_encryption_key()', 'public.encrypt_text(text)',
                  'public.decrypt_text(text)', 'public.safe_decrypt_recovery_codes(text)',
                  'public.upsert_plugin_rating(uuid,uuid,integer,text)',
                  'public.record_plugin_download(uuid,uuid,uuid,text)',
                  'public.custom_access_token_hook(jsonb)']) signature
cross join unnest(array['anon', 'authenticated']) role_name
where to_regprocedure(signature) is null
   or has_function_privilege(role_name, to_regprocedure(signature), 'EXECUTE')

union all

-- ---------------------------------------------------------------------------
-- CHECK 5x (advisory): inventory every SECURITY DEFINER routine callable by a
-- self-registered account. `authenticated` is not a trust boundary while signup
-- is open. Existing client RPCs make a zero-result gate impractical today, but
-- keeping the exact signatures visible prevents the surface from growing
-- silently and gives reviewers a concrete list to triage into an allowlist.
-- ---------------------------------------------------------------------------
select 'CHECK 5x (advisory): authenticated-callable SECURITY DEFINER routines',
       coalesce(string_agg(p.oid::regprocedure::text, ', ' order by p.oid::regprocedure::text), 'HEALTHY')
from pg_catalog.pg_proc p
join pg_catalog.pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and p.prokind in ('f', 'p')
  and p.prosecdef
  and p.prorettype not in ('trigger'::regtype, 'event_trigger'::regtype)
  and pg_catalog.to_regrole('authenticated') is not null
  and pg_catalog.has_function_privilege('authenticated', p.oid, 'EXECUTE')
  and not exists (
    select 1 from pg_catalog.pg_depend d
    where d.classid = 'pg_catalog.pg_proc'::regclass
      and d.objid = p.oid and d.deptype = 'e');
