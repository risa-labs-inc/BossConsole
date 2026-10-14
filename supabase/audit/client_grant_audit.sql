-- Client-grant audit for schema `public`. Run it against the project and read
-- the `finding` column: HEALTHY means that check passed. Any other value names
-- the object to investigate. G1 checks effective table privileges. G2 is a structural policy lint, not
-- a proof of effective write access: table ACLs and other restrictive policies
-- can still deny a write. Review a reported policy before changing access.
--
-- This is the companion to identity_disclosure_audit.sql, which asks who can
-- READ identity. This one asks a narrower question that the other deliberately
-- does not: which client roles can WRITE, and whether anything but a policy is
-- stopping them.
--
-- It exists because of the recurrence tracked in #547. `20251023000014_grants.sql`
-- sets `ALTER DEFAULT PRIVILEGES ... GRANT ALL ON TABLES TO anon, authenticated`,
-- so every table created after it inherits client grants. Three tables then
-- shipped with a permissive write policy on top of that inherited grant:
-- plugin_downloads (#488), completed_authentications (#536) and the pair
-- secret_access_log / plugin_api_key_logs (#538). Each was found by a person
-- reading a migration, three separate times. G2 detects the literal-true shape
-- from #488 and #538, not #536: session_id IS NOT NULL is always true on that
-- table, but is not a literal predicate. Its dedicated suite remains necessary.
--
-- Scope: G1 checks table-wide privileges, not column-only grants or views. G2
-- intentionally inspects direct TO anon/authenticated/PUBLIC policies without
-- requiring a table grant, so dormant open policies are caught before regrant.
-- Policies applying only via membership in another role are outside this lint.
-- Neither check proves complete authorization or examines updatable views.
--
-- 20260908000000_explicit_anon_grants.sql closed the `anon` half of the
-- inheritance for objects created after it. It deliberately left `authenticated`
-- alone, because for tables RLS is the intended gate. That is a reasonable
-- decision, and it is exactly why these two checks are worth having: once RLS is
-- the only thing standing in front of a client role, "RLS is enabled" and "the
-- policy is not a rubber stamp" stop being style points and become the control.
--
-- Future-default changes in #586/#592 do not revoke existing object grants.
-- Run this after any migration that creates a table or a policy.

-- ---------------------------------------------------------------------------
-- CHECK G1: no table reachable by a client role is missing RLS.
--
-- Without RLS the table privilege IS the access decision, and the inherited
-- default grant hands that privilege out silently. CHECK 3b in the identity
-- audit asks a similar question, but only of tables carrying an `email`,
-- `display_name` or `full_name` column, and only as an advisory. Two of the
-- three recurrences above carry no such column, so that filter would have
-- skipped them both.
--
-- No column filter and no allowlist here. Every one of the 34 tables in `public`
-- enables RLS today, so an empty exception list is the accurate description of
-- the schema rather than an aspiration, and a new table without RLS is a mistake
-- until someone argues otherwise in review.
-- ---------------------------------------------------------------------------
select 'CHECK G1: client-reachable table without RLS' as "check",
       coalesce(
         nullif(string_agg(distinct c.oid::regclass::text, ', ' order by c.oid::regclass::text), ''),
         'HEALTHY'
       ) as finding
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relkind in ('r', 'p')
  and not c.relrowsecurity
  and exists (
    select 1
    -- Cast to `name` explicitly. Both has_table_privilege overloads take a role
    -- as `name` or as `oid`, and an uncast text variable leaves the choice to
    -- type resolution rather than stating it.
    from unnest(array['anon', 'authenticated']::name[]) as client(role_name)
    where has_table_privilege(client.role_name, c.oid, 'SELECT')
       or has_table_privilege(client.role_name, c.oid, 'INSERT')
       or has_table_privilege(client.role_name, c.oid, 'UPDATE')
       or has_table_privilege(client.role_name, c.oid, 'DELETE')
  )

union all

-- ---------------------------------------------------------------------------
-- CHECK G2: no write policy reachable by a client role is a rubber stamp.
--
-- A PERMISSIVE policy whose predicate is literal `true` can open access.
-- A RESTRICTIVE policy is ANDed with permissive policies, so literal true
-- cannot open a write path and must not be flagged on its own. On SELECT
-- that is sometimes intended, and the identity audit's CHECK 3 already covers
-- the identity-bearing case. On INSERT, UPDATE or DELETE it means any caller
-- holding the role may write any row, which is what produced all three
-- recurrences above.
--
-- The discriminating signal is the predicate, NOT a missing `TO` clause. A
-- policy with no `TO` applies to PUBLIC, which is how all three slipped past
-- review in #488/#538, but 101 of the 128 policies written in this schema omit `TO`. Gating
-- on that alone would report most of the schema and be switched off within a
-- week. Taken together, "write" plus "client role" plus "literally true" matches
-- two policies in the whole schema and nothing else.
--
-- `qual` is the USING predicate and `with_check` the WITH CHECK predicate.
-- INSERT policies carry only the latter, DELETE only the former, and UPDATE or
-- ALL may carry both. A permissive WITH CHECK on an otherwise scoped UPDATE
-- still lets a caller rewrite a row it owns into any shape, so either predicate
-- being `true` is enough to report. service_role is not a client role and is
-- excluded by the role filter, so a deliberate `TO service_role WITH CHECK
-- (true)` writer policy is correctly silent here.
-- ---------------------------------------------------------------------------
select 'CHECK G2: permissive write policy for a client role' as "check",
       coalesce(
         nullif(string_agg(distinct pol.tablename || '.' || pol.policyname, ', '
                           order by pol.tablename || '.' || pol.policyname), ''),
         'HEALTHY'
       ) as finding
from pg_policies pol
where pol.schemaname = 'public'
  and pol.permissive = 'PERMISSIVE'
  and pol.cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
  and pol.roles && array['anon', 'authenticated', 'public']::name[]
  and (
    btrim(lower(coalesce(pol.qual, ''))) in ('true', '(true)')
    or btrim(lower(coalesce(pol.with_check, ''))) in ('true', '(true)')
  );
