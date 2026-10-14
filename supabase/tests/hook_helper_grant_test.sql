-- pgTAP tests for the hook-helper revoke (20260923122000).
-- Run with: supabase test db
--
-- Scope, because the function name invites a stronger reading than the change
-- supports: this removes one unused grant so the helper matches the two beside
-- it. It does NOT close role disclosure generally. get_user_roles,
-- get_user_roles_with_names and user_has_role still take a caller-supplied
-- subject id with no authorization check and are still executable by
-- authenticated. Assertion 12 pins exactly that, so this suite records the state
-- of the whole family rather than implying a fix it did not make.
--
-- Assertion 4 is the one to keep. It establishes by reading the policies that
-- RLS on user_roles gives an ordinary reader their own rows only, which is the
-- control every SECURITY DEFINER function in this family bypasses. If that stops
-- being true, this suite is measuring the wrong thing and should fail loudly.

begin;
select plan(12);

-- ---------------------------------------------------------------------------
-- 1-3: the client roles cannot execute it any more.
-- ---------------------------------------------------------------------------
select ok(
    not pg_catalog.has_function_privilege('authenticated',
        'public.get_user_roles_for_hook(uuid)', 'EXECUTE'),
    'authenticated cannot execute get_user_roles_for_hook'
);

select ok(
    not pg_catalog.has_function_privilege('anon',
        'public.get_user_roles_for_hook(uuid)', 'EXECUTE'),
    'anon cannot execute get_user_roles_for_hook'
);

-- Asked through has_function_privilege, not by reading proacl: a NULL proacl
-- means the default ACL, in which PUBLIC can execute, and aclexplode(NULL)
-- returns no rows, so reading the entries would pass in exactly that case.
select ok(
    not pg_catalog.has_function_privilege('public',
        'public.get_user_roles_for_hook(uuid)', 'EXECUTE'),
    'and PUBLIC cannot execute it either'
);

-- ---------------------------------------------------------------------------
-- 4: why it mattered. RLS on user_roles is per user, so the table itself never
-- gave a client another user's rows. The definer function did.
-- ---------------------------------------------------------------------------
select isnt_empty(
    $$ select p.polname
       from pg_policy p
       join pg_class c on c.oid = p.polrelid
       join pg_namespace n on n.oid = c.relnamespace
       where n.nspname = 'public' and c.relname = 'user_roles'
         and pg_get_expr(p.polqual, p.polrelid) ~ 'auth\.uid\(\)' $$,
    'user_roles still restricts an ordinary reader to rows matching their own id'
);

select ok(
    (select c.relrowsecurity from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relname = 'user_roles'),
    'user_roles still has row level security enabled'
);

-- ---------------------------------------------------------------------------
-- 6-8: the login path is untouched. A revoke that caught supabase_auth_admin
-- would stop GoTrue minting any token at all, which is worse than the leak.
--
-- 6 covers the whole chain, not only this helper. The hook is not SECURITY
-- DEFINER, so GoTrue's calls to all three helpers are made as
-- supabase_auth_admin, and a lost grant on any of them stops every login. Before
-- this, nothing in the suite noticed supabase_auth_admin losing
-- get_effective_permissions. It is a privilege check rather than a call made as
-- that role because the test role cannot SET ROLE supabase_auth_admin; 8 calls
-- the hook as the test role, so it proves the body runs, not the grants.
-- ---------------------------------------------------------------------------
select is(
    array(select f.sig
          from (values ('public.custom_access_token_hook(jsonb)'),
                       ('public.get_user_roles_for_hook(uuid)'),
                       ('public.get_effective_permissions(uuid)'),
                       ('public.get_user_orgs_for_hook(uuid)')) as f(sig)
          where not pg_catalog.has_function_privilege('supabase_auth_admin', f.sig, 'EXECUTE')),
    array[]::text[],
    'supabase_auth_admin can still execute the hook and all three helpers it calls'
);

select ok(
    pg_catalog.has_function_privilege('service_role',
        'public.get_user_roles_for_hook(uuid)', 'EXECUTE'),
    'service_role can still execute it'
);

select lives_ok(
    $$ select public.custom_access_token_hook(
         '{"user_id":"00000000-0000-0000-0000-000000000000","claims":{}}'::jsonb) $$,
    'the token hook body still runs end to end'
);

-- ---------------------------------------------------------------------------
-- 9: the function itself is unchanged. This migration is a grant change, and a
-- later one that rewrote the body while "fixing" this would be a different PR.
-- ---------------------------------------------------------------------------
select ok(
    (select p.prosecdef from pg_catalog.pg_proc p
     where p.oid = 'public.get_user_roles_for_hook(uuid)'::regprocedure),
    'get_user_roles_for_hook is still SECURITY DEFINER'
);

-- ---------------------------------------------------------------------------
-- 10-11: its two siblings keep the grant set this one now matches. They are the
-- reason this was read as a leftover rather than a decision, so a change to them
-- should break this test and make someone re-read the argument.
-- ---------------------------------------------------------------------------
select ok(
    not pg_catalog.has_function_privilege('authenticated',
        'public.get_effective_permissions(uuid)', 'EXECUTE'),
    'get_effective_permissions is still closed to authenticated'
);

select ok(
    not pg_catalog.has_function_privilege('authenticated',
        'public.get_user_orgs_for_hook(uuid)', 'EXECUTE'),
    'get_user_orgs_for_hook is still closed to authenticated'
);

-- ---------------------------------------------------------------------------
-- 12: the family this migration does NOT fix, recorded as still open.
--
-- This pins their GRANTS, not the absence of a check inside them. A guard added
-- inside the functions keeps these grants, so it passes this; only a revoke would
-- fail it, which is the moment to come back and drop the scoping paragraph.
-- ---------------------------------------------------------------------------
select is(
    (select pg_catalog.count(*)::int
     from (values ('public.get_user_roles(uuid)'),
                  ('public.get_user_roles_with_names(uuid)'),
                  ('public.user_has_role(uuid, text)')) as f(sig)
     where pg_catalog.has_function_privilege('authenticated', f.sig, 'EXECUTE')),
    3,
    'the three role readers are still executable by authenticated; their grants are not this PR'
);

select * from finish();
rollback;
