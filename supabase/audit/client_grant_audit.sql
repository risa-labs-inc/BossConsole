-- Client-grant audit for schema `public`. Run it against the project and read
-- the `finding` column: HEALTHY means that check passed. Any other value names
-- the object to investigate. Both checks here are exact catalog facts rather
-- than heuristics, so neither needs human judgement to interpret.
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
-- reading a migration, three separate times.
--
-- 20260908000000_explicit_anon_grants.sql closed the `anon` half of the
-- inheritance for objects created after it. It deliberately left `authenticated`
-- alone, because for tables RLS is the intended gate. That is a reasonable
-- decision, and it is exactly why these two checks are worth having: once RLS is
-- the only thing standing in front of a client role, "RLS is enabled" and "the
-- policy is not a rubber stamp" stop being style points and become the control.
--
-- Run it after any migration that creates a table or a policy.

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
-- A policy whose predicate is the literal `true` adds no restriction. On SELECT
-- that is sometimes intended, and the identity audit's CHECK 3 already covers
-- the identity-bearing case. On INSERT, UPDATE or DELETE it means any caller
-- holding the role may write any row, which is what produced all three
-- recurrences above.
--
-- The discriminating signal is the predicate, NOT a missing `TO` clause. A
-- policy with no `TO` applies to PUBLIC, which is how all three slipped past
-- review, but 101 of the 128 policies written in this schema omit `TO`. Gating
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
  and pol.cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
  and pol.roles && array['anon', 'authenticated', 'public']::name[]
  and (
    btrim(lower(coalesce(pol.qual, ''))) in ('true', '(true)')
    or btrim(lower(coalesce(pol.with_check, ''))) in ('true', '(true)')
  );
