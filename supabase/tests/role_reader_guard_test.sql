-- pgTAP tests for the role-reader guard (20260919030000).
-- Run with: supabase test db
--
-- Ordered on purpose. Assertions 1-3 are the leak and fail against a database
-- without this migration: an ordinary user asks for another user's roles and
-- gets them. 13-14 fail there too, for a session with no subject. The rest is
-- what must keep working, and passes either way. The helper-specific checks come
-- last because the helper does not exist before this migration.
--
-- The case worth reading is block 3. `boss_admin` holds `role.read` and is not
-- named `admin`, so is_user_admin() is false for it, and it can already list
-- every user through the `users` policy. An admins-only guard would pass every
-- other assertion here and silently empty the role column of the user list for
-- every BOSS administrator. Block 3 is what fails if the rule is ever narrowed
-- to is_user_admin().

begin;
select plan(20);

-- ---------------------------------------------------------------------------
-- Fixtures, as postgres. Explicit role rows with ON CONFLICT, so the assertions
-- do not depend on what handle_new_user assigns on signup.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('7e000000-0000-0000-0000-00000000000a', 'reader-plain@pgtap.test',   now()),
    ('7e000000-0000-0000-0000-00000000000b', 'reader-target@pgtap.test',  now()),
    ('7e000000-0000-0000-0000-00000000000c', 'reader-admin@pgtap.test',   now()),
    ('7e000000-0000-0000-0000-00000000000d', 'reader-bossadm@pgtap.test', now());

insert into public.user_roles (user_id, role_id, assigned_by, assigned_at)
select u.id::uuid, r.id, null, now()
  from (values ('7e000000-0000-0000-0000-00000000000a', 'user'),
               ('7e000000-0000-0000-0000-00000000000b', 'user'),
               ('7e000000-0000-0000-0000-00000000000c', 'admin'),
               ('7e000000-0000-0000-0000-00000000000d', 'boss_admin')) as u(id, role)
  join public.roles r on r.name = u.role
on conflict (user_id, role_id) do nothing;

-- ===========================================================================
-- 1-6: an ordinary signed-in user, A, asking about B.
-- ===========================================================================
select set_config('request.jwt.claims',
    '{"sub":"7e000000-0000-0000-0000-00000000000a","role":"authenticated"}', true);
set local role authenticated;

select is(
    public.get_user_roles_with_names('7e000000-0000-0000-0000-00000000000b'),
    '[]'::jsonb,
    'an ordinary user gets no roles back for another user from get_user_roles_with_names'
);

select is_empty(
    $$ select * from public.get_user_roles('7e000000-0000-0000-0000-00000000000b') $$,
    'an ordinary user gets no rows back for another user from get_user_roles'
);

select is(
    public.user_has_role('7e000000-0000-0000-0000-00000000000b', 'user'),
    false,
    'user_has_role is not an oracle for another user'
);

-- The functions now say what the table already said.
select is(
    (select count(*)::int from public.user_roles
      where user_id = '7e000000-0000-0000-0000-00000000000b'),
    0,
    'user_roles itself shows an ordinary user none of another user''s rows'
);

-- And a user can still read their own, which the table allows too.
select isnt(
    public.get_user_roles_with_names('7e000000-0000-0000-0000-00000000000a'),
    '[]'::jsonb,
    'a user still reads their own roles'
);

select is(
    public.user_has_role('7e000000-0000-0000-0000-00000000000a', 'user'),
    true,
    'user_has_role still answers for the caller themselves'
);

reset role;

-- ===========================================================================
-- 7-8: an admin, C, can read anyone's roles, as the user list needs.
-- ===========================================================================
select set_config('request.jwt.claims',
    '{"sub":"7e000000-0000-0000-0000-00000000000c","role":"authenticated"}', true);
set local role authenticated;

select isnt(
    public.get_user_roles_with_names('7e000000-0000-0000-0000-00000000000b'),
    '[]'::jsonb,
    'an admin reads another user''s roles'
);

select is(
    public.user_has_role('7e000000-0000-0000-0000-00000000000b', 'user'),
    true,
    'an admin gets a true answer from user_has_role about another user'
);

reset role;

-- ===========================================================================
-- 9-11: a BOSS administrator, D. Holds role.read, is not an admin.
-- ===========================================================================
select set_config('request.jwt.claims',
    '{"sub":"7e000000-0000-0000-0000-00000000000d","role":"authenticated"}', true);
set local role authenticated;

select is(
    public.is_user_admin('7e000000-0000-0000-0000-00000000000d'),
    false,
    'premise: a boss_admin is not an admin to is_user_admin'
);

select is(
    public.authorize('role.read'),
    true,
    'premise: a boss_admin holds role.read'
);

select isnt(
    public.get_user_roles_with_names('7e000000-0000-0000-0000-00000000000b'),
    '[]'::jsonb,
    'a boss_admin still reads another user''s roles, so the user list keeps its role column'
);

reset role;

-- ===========================================================================
-- 12: the service role, which the user_roles policy also admits.
-- ===========================================================================
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select isnt(
    public.get_user_roles_with_names('7e000000-0000-0000-0000-00000000000b'),
    '[]'::jsonb,
    'the service role reads another user''s roles'
);

-- ===========================================================================
-- 13-14: the fail-open case. A session with no subject must be refused, not
-- waved through by a NULL comparison.
-- ===========================================================================
select set_config('request.jwt.claims', '{"role":"authenticated"}', true);
set local role authenticated;

select is(
    public.get_user_roles_with_names('7e000000-0000-0000-0000-00000000000b'),
    '[]'::jsonb,
    'a session with no sub gets nothing, rather than everything'
);

select is(
    public.user_has_role('7e000000-0000-0000-0000-00000000000b', 'user'),
    false,
    'and user_has_role says false, not NULL, for it'
);

reset role;

-- ===========================================================================
-- 15-16: the grants the client path depends on are unchanged.
-- ===========================================================================
select is(
    (select pg_catalog.count(*)::int
     from (values ('public.get_user_roles(uuid)'),
                  ('public.get_user_roles_with_names(uuid)'),
                  ('public.user_has_role(uuid, text)')) as f(sig)
     where pg_catalog.has_function_privilege('authenticated', f.sig, 'EXECUTE')),
    3,
    'authenticated can still execute all three, so RoleService still works'
);

select is_empty(
    $$ select p.proname
       from pg_proc p
       join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public'
         and p.proname in ('get_user_roles', 'get_user_roles_with_names', 'user_has_role')
         and (not p.prosecdef or p.proconfig is distinct from array['search_path=""']) $$,
    'all three are still SECURITY DEFINER with an empty search_path'
);

-- ===========================================================================
-- 17-20: the helper. It must never return NULL, and clients are not offered it.
-- ===========================================================================
select set_config('request.jwt.claims', '{"role":"authenticated"}', true);

select is(
    public.can_read_user_roles('7e000000-0000-0000-0000-00000000000b'),
    false,
    'can_read_user_roles is false, not NULL, with no subject'
);

select is(
    public.can_read_user_roles(null),
    false,
    'can_read_user_roles is false, not NULL, for a NULL id'
);

select ok(
    not pg_catalog.has_function_privilege('authenticated',
        'public.can_read_user_roles(uuid)', 'EXECUTE'),
    'authenticated cannot call the helper directly'
);

select ok(
    not pg_catalog.has_function_privilege('anon',
        'public.can_read_user_roles(uuid)', 'EXECUTE'),
    'anon cannot call the helper directly'
);

select * from finish();
rollback;
