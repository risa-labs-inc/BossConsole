-- pgTAP tests for the part-4 SECURITY DEFINER search_path hardening
-- (20260920000000).
--
-- Parts 1-3 (20260916130000, 20260916140000, 20260919000000) pinned every
-- definer function whose proconfig was NULL. Part 4 pins the seven that
-- carried a fixed but NON-EMPTY search_path, the last of the repo's own
-- definer functions:
--
--   encrypt_text, decrypt_text, get_encryption_key,
--   get_user_roles_for_hook, org_is_vetted, org_visible_users,
--   user_display_name
--
-- These assertions pin the closed form (proconfig membership, SECURITY
-- DEFINER retained, grants untouched), then widen to the invariant the part
-- family has been driving at: NO SECURITY DEFINER function in public is left
-- resolving through anything but an empty path, and none ships the
-- default-PUBLIC EXECUTE ACL. The five plugin-store RPCs are excluded from
-- the invariant count until 20260919000000 lands (merge-order independence);
-- once it has, they satisfy the assertion like everything else.
--
-- The behaviour section runs each function under a hostile-first session
-- search_path holding shadow copies of the tables these functions read,
-- seeded with decoy rows that would change the answers if resolution ever
-- fell into them: a system-flagged copy of the vetted org, a hostile role
-- assignment, a hostile display name, and an empty membership shadow. Every
-- function here reads only, so the decoys are regression armor: today all
-- their references are schema-qualified, so a dropped pin alone changes
-- nothing; a dropped pin plus one future unqualified reference would resolve
-- into hostile_part4 and flip these assertions. The crypto pair round-trips
-- against a fixture vault key, so the part-2 dependency on decrypt_text's
-- path (a callee's SET clause overriding the caller's) is proven by execution.
--
-- The grant section pins the least-privilege floor: the crypto pair and
-- user_display_name stay client-revoked, org_is_vetted and org_visible_users
-- keep their deliberate authenticated EXECUTE (RLS policies evaluate as the
-- querying role), and the hook helper keeps supabase_auth_admin and
-- service_role. authenticated on the hook helper is deliberately NOT asserted
-- either way: #994's revoke is in flight for that one grant, and both merge
-- orders must stay green. All fixtures roll back with us.

begin;
select plan(24);

-- ---------------------------------------------------------------------------
-- 1-7: each hardened function pins an empty search_path in pg_proc.proconfig.
-- Array containment instead of proconfig[1] so a later-added SET clause
-- cannot push the pin out of position without tripping the assertion.
-- ---------------------------------------------------------------------------
select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
       from pg_catalog.pg_proc p
      where p.oid = 'public.encrypt_text(text)'::regprocedure),
    true,
    'encrypt_text pins an empty search_path'
);
select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
       from pg_catalog.pg_proc p
      where p.oid = 'public.decrypt_text(text)'::regprocedure),
    true,
    'decrypt_text pins an empty search_path'
);
select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
       from pg_catalog.pg_proc p
      where p.oid = 'public.get_encryption_key()'::regprocedure),
    true,
    'get_encryption_key pins an empty search_path'
);
select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
       from pg_catalog.pg_proc p
      where p.oid = 'public.get_user_roles_for_hook(uuid)'::regprocedure),
    true,
    'get_user_roles_for_hook pins an empty search_path'
);
select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
       from pg_catalog.pg_proc p
      where p.oid = 'public.org_is_vetted(uuid)'::regprocedure),
    true,
    'org_is_vetted pins an empty search_path'
);
select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
       from pg_catalog.pg_proc p
      where p.oid = 'public.org_visible_users()'::regprocedure),
    true,
    'org_visible_users pins an empty search_path'
);
select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
       from pg_catalog.pg_proc p
      where p.oid = 'public.user_display_name(uuid)'::regprocedure),
    true,
    'user_display_name pins an empty search_path'
);

-- ---------------------------------------------------------------------------
-- 8-9: the invariant itself. No SECURITY DEFINER function in public is left
-- resolving through anything but the empty path (the plugin-store five are
-- excluded until the sibling part-3 migration lands; they satisfy the
-- assertion like everything else once it has), and none ships the
-- default-PUBLIC ACL.
-- ---------------------------------------------------------------------------
select is(
    (select count(*)::int
       from pg_catalog.pg_proc p
       join pg_catalog.pg_namespace n on n.oid = p.pronamespace
      where n.nspname = 'public'
        and p.prosecdef
        and (p.proconfig is null
             or not (p.proconfig @> ARRAY['search_path=""']::text[]))
        and p.proname not in (
            'record_plugin_download',
            'upsert_plugin_rating',
            'update_api_key_last_used',
            'log_api_key_action',
            'get_user_api_key_count'
        )),
    0,
    'every SECURITY DEFINER function in public outside the sibling part-3 set carries search_path=""'
);
select is(
    (select count(*)::int
       from pg_catalog.pg_proc p
       join pg_catalog.pg_namespace n on n.oid = p.pronamespace
      where n.nspname = 'public'
        and p.prosecdef
        and p.proacl is null),
    0,
    'no SECURITY DEFINER function in public ships the default-PUBLIC EXECUTE ACL (20260912120000 floor, pinned)'
);

-- ---------------------------------------------------------------------------
-- Fixtures. Fresh rows only: the probabilistic cleanup triggers sweep expired
-- records, so nothing here carries an aged created_at.
-- ---------------------------------------------------------------------------
do $fixture$
declare existing uuid;
begin
    select id into existing from vault.secrets where name = 'master_encryption_key';
    if existing is null then
        perform vault.create_secret('iv-test-key-0123456789abcdef01234567', 'master_encryption_key', 'pgTAP only');
    else
        perform vault.update_secret(existing, 'iv-test-key-0123456789abcdef01234567', 'master_encryption_key', 'pgTAP only');
    end if;
end;
$fixture$;

-- Only columns present in both GoTrue schema generations: the codespace gate
-- database carries confirmed_at, a fresh CI database email_confirmed_at, and
-- id/email/raw_user_meta_data exist in both.
insert into auth.users (id, email, raw_user_meta_data) values
    ('d4f44000-0000-4000-8000-00000000000a', 'part4a@pgtap.test', '{"full_name":"Part Four Name"}'::jsonb),
    ('d4f44000-0000-4000-8000-00000000000b', 'part4b@pgtap.test', null),
    ('d4f44000-0000-4000-8000-00000000000c', 'part4hook@pgtap.test', null);

insert into public.roles (name, description, is_system)
values ('part4_role_one', 'pgTAP fixture role one for the hook helper', false),
       ('part4_role_two', 'pgTAP fixture role two for the hook helper', false)
on conflict (name) do nothing;

insert into public.user_roles (user_id, role_id, assigned_by, assigned_at)
select 'd4f44000-0000-4000-8000-00000000000c', r.id, null, now()
  from public.roles r where r.name = 'part4_role_one';
insert into public.user_roles (user_id, role_id, assigned_by, assigned_at)
select 'd4f44000-0000-4000-8000-00000000000c', r.id, null, now() + interval '1 second'
  from public.roles r where r.name = 'part4_role_two';

select public.create_organisation_internal(
    p_slug=>'part4vetted', p_name=>'Part Four Vetted',
    p_owner_id=>'d4f44000-0000-4000-8000-00000000000a',
    p_visibility=>'private', p_join_policy=>'invite_only');
select public.create_organisation_internal(
    p_slug=>'part4open', p_name=>'Part Four Open',
    p_owner_id=>'d4f44000-0000-4000-8000-00000000000a',
    p_visibility=>'private', p_join_policy=>'open');
select public.create_organisation_internal(
    p_slug=>'part4system', p_name=>'Part Four System',
    p_owner_id=>'d4f44000-0000-4000-8000-00000000000a',
    p_visibility=>'private', p_join_policy=>'invite_only');
-- The system arm of org_is_vetted needs an is_system org that is not open:
-- flip the third fixture org so join_policy stays controlled.
update public.organisations set is_system = true where slug = 'part4system';

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
select id, 'd4f44000-0000-4000-8000-00000000000b', 'active', now(), 'admin'
  from public.organisations where slug = 'part4vetted';

-- The hostile schema: shadow copies seeded with decoys that would change
-- every answer below if resolution ever fell into them.
create schema hostile_part4;
create table hostile_part4.organisations (id uuid, is_system boolean, join_policy text);
create table hostile_part4.organisation_members (org_id uuid, user_id uuid, status text);
create table hostile_part4.roles (id uuid, name text);
create table hostile_part4.user_roles (user_id uuid, role_id uuid, assigned_at timestamptz);
create table hostile_part4.users (id uuid, email text, raw_user_meta_data jsonb);

insert into hostile_part4.organisations
select o.id, true, 'invite_only' from public.organisations o where o.slug = 'part4vetted';
insert into hostile_part4.roles values ('d4f44000-0000-4000-8000-00000000000d', 'part4_hostile_canary_role');
insert into hostile_part4.user_roles values ('d4f44000-0000-4000-8000-00000000000c', 'd4f44000-0000-4000-8000-00000000000d', now());
insert into hostile_part4.users values ('d4f44000-0000-4000-8000-00000000000a', 'hostile@pgtap.test', '{"full_name":"Hostile Name"}'::jsonb);
-- hostile_part4.organisation_members stays empty: a misresolved read would
-- drop the fellow member and yield 1 instead of 2.

-- Flip the session search_path hostile-first to prove the pins ignore the
-- caller's path, but keep pgTAP's own schema reachable: the harness installs
-- pgTAP outside public (CI) while a bare local install puts it in public, so
-- resolve its schema from the catalog and append it. The tested functions run
-- under their own proconfig search_path (empty), so appending pgTAP's schema
-- changes nothing about where their references resolve.
do $$
declare
    pgtap_schema text;
begin
    select n.nspname into pgtap_schema
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where p.proname = 'lives_ok'
    limit 1;
    execute format('set search_path to hostile_part4, public, %I', pgtap_schema);
end
$$;

-- ---------------------------------------------------------------------------
-- 10-17: behaviour under the hostile-first path. The pin, not the caller,
-- decides where the references resolve.
-- ---------------------------------------------------------------------------
select is(
    (select public.decrypt_text(public.encrypt_text('part4-round-trip'))),
    'part4-round-trip',
    'the AES pair still round-trips under the closed path (part-2''s callee SET clause dependency, proven by execution)'
);
select is(
    (select public.get_encryption_key()),
    'iv-test-key-0123456789abcdef01234567',
    'get_encryption_key still reads the real vault row under the closed path'
);
select is(
    (select public.org_is_vetted(o.id) from public.organisations o where o.slug = 'part4vetted'),
    true,
    'org_is_vetted answers true for the invite-only fixture org, ignoring the system-flagged hostile decoy'
);
select is(
    (select public.org_is_vetted(o.id) from public.organisations o where o.slug = 'part4open'),
    false,
    'org_is_vetted answers false for an open-join org'
);
select is(
    (select public.org_is_vetted(o.id) from public.organisations o where o.slug = 'part4system'),
    false,
    'org_is_vetted answers false for a system org'
);
-- PostgREST sets the whole token as request.jwt.claims and each top-level
-- claim as request.jwt.claim.<name>; the stock auth.uid() parses the former
-- while a hardened variant reads the latter, so set both to stay correct on
-- either shape.
select set_config('request.jwt.claims', '{"sub":"d4f44000-0000-4000-8000-00000000000a"}', true);
select set_config('request.jwt.claim.sub', 'd4f44000-0000-4000-8000-00000000000a', true);
select is(
    (select count(*)::int from public.org_visible_users()),
    2,
    'org_visible_users under a hostile-first path still sees the caller and the active fellow member of the vetted org only'
);
select is(
    (select public.user_display_name('d4f44000-0000-4000-8000-00000000000a')),
    'Part Four Name',
    'user_display_name still reads auth.users metadata, ignoring the hostile decoy name'
);
select is(
    (select array_agg(x order by ord)
       from unnest(public.get_user_roles_for_hook('d4f44000-0000-4000-8000-00000000000c'))
            with ordinality as t(x, ord)
      where x in ('part4_role_one', 'part4_role_two')),
    ARRAY['part4_role_one', 'part4_role_two'],
    'get_user_roles_for_hook still reads the real user_roles/roles in assigned_at order, ignoring the hostile canary role'
);

-- ---------------------------------------------------------------------------
-- 18-24: grants are untouched. The crypto pair and user_display_name stay
-- client-revoked, the org pair keeps its deliberate authenticated EXECUTE,
-- and the hook helper keeps the callers the token hook needs. authenticated
-- on the hook helper is deliberately not asserted: #994 revokes it.
-- ---------------------------------------------------------------------------
select is(
    (select count(*)::int
       from pg_catalog.pg_proc p
      where p.oid in (
            'public.encrypt_text(text)'::regprocedure,
            'public.decrypt_text(text)'::regprocedure,
            'public.get_encryption_key()'::regprocedure,
            'public.user_display_name(uuid)'::regprocedure
          )
        and pg_catalog.has_function_privilege('anon', p.oid, 'EXECUTE')),
    0,
    'anon cannot execute any of the four client-revoked RPCs (20260909120000 / 20260908010000; unchanged here)'
);
select is(
    (select count(*)::int
       from pg_catalog.pg_proc p
      where p.oid in (
            'public.encrypt_text(text)'::regprocedure,
            'public.decrypt_text(text)'::regprocedure,
            'public.get_encryption_key()'::regprocedure,
            'public.user_display_name(uuid)'::regprocedure
          )
        and pg_catalog.has_function_privilege('authenticated', p.oid, 'EXECUTE')),
    0,
    'authenticated cannot execute any of the four client-revoked RPCs'
);
select is(
    (select count(*)::int
       from pg_catalog.pg_proc p
      where p.oid in (
            'public.encrypt_text(text)'::regprocedure,
            'public.decrypt_text(text)'::regprocedure,
            'public.get_encryption_key()'::regprocedure,
            'public.user_display_name(uuid)'::regprocedure
          )
        and pg_catalog.has_function_privilege('service_role', p.oid, 'EXECUTE')),
    4,
    'service_role keeps EXECUTE on all four (the secrets edge-function path)'
);
select is(
    (select count(*)::int
       from pg_catalog.pg_proc p
      where p.oid in (
            'public.org_is_vetted(uuid)'::regprocedure,
            'public.org_visible_users()'::regprocedure
          )
        and pg_catalog.has_function_privilege('authenticated', p.oid, 'EXECUTE')),
    2,
    'org_is_vetted and org_visible_users keep their deliberate authenticated EXECUTE (RLS policies evaluate as the querying role)'
);
select is(
    (select count(*)::int
       from pg_catalog.pg_proc p
      where p.oid in (
            'public.org_is_vetted(uuid)'::regprocedure,
            'public.org_visible_users()'::regprocedure
          )
        and pg_catalog.has_function_privilege('anon', p.oid, 'EXECUTE')),
    0,
    'anon still cannot execute the org visibility pair (20260908010000; unchanged here)'
);
select is(
    (select count(*)::int
       from pg_catalog.pg_roles r
      where r.rolname in ('service_role', 'supabase_auth_admin')
        and pg_catalog.has_function_privilege(r.rolname, 'public.get_user_roles_for_hook(uuid)', 'EXECUTE')),
    2,
    'service_role and supabase_auth_admin keep EXECUTE on the hook helper (stable across the #994 revoke)'
);
select ok(
    not pg_catalog.has_function_privilege('anon', 'public.get_user_roles_for_hook(uuid)', 'EXECUTE'),
    'anon still cannot execute the hook helper (20260908030000; unchanged here)'
);

select * from finish();
rollback;
