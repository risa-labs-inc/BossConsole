-- Run only on a disposable test database via supabase test db. All fixtures roll back.
begin;
select no_plan();

select ok(to_regprocedure(f) is not null and not has_function_privilege(r, to_regprocedure(f), 'EXECUTE'), r || ' cannot execute ' || f)
from unnest(array['anon', 'authenticated']) r
cross join unnest(array['public.get_encryption_key()', 'public.encrypt_text(text)',
    'public.decrypt_text(text)', 'public.safe_decrypt_recovery_codes(text)',
    'public.user_display_name(uuid)', 'public.upsert_plugin_rating(uuid,uuid,integer,text)',
    'public.record_plugin_download(uuid,uuid,uuid,text)', 'public.custom_access_token_hook(jsonb)']) f;

-- Also exercise the actual database role, not only JWT claims on postgres.
set local role authenticated;
select throws_ok('select public.get_encryption_key()', '42501',
    'permission denied for function get_encryption_key', 'signed-in key RPC is denied');
select throws_ok($$select public.decrypt_text('anything')$$, '42501',
    'permission denied for function decrypt_text', 'signed-in decryption oracle is denied');
reset role;

-- The deliberately public store/RLS signatures must remain callable.
select ok(coalesce(has_function_privilege('anon', to_regprocedure(f), 'EXECUTE'), false), 'anonymous compatibility: ' || f)
from unnest(array[
    'public.search_plugins(text,text,text[],numeric,boolean,integer,integer,text)',
    'public.get_plugin_with_stats(text)', 'public.get_plugin_versions(text)',
    'public.get_popular_tags(integer)',
    'public.can_view_plugin_row(text,uuid,uuid,boolean)',
    'public.authorize(text)', 'public.is_user_admin(uuid)']) f;
select ok(coalesce(has_function_privilege('service_role', to_regprocedure(f), 'EXECUTE'), false), 'edge mutator compatibility: ' || f)
from unnest(array['public.upsert_plugin_rating(uuid,uuid,integer,text)',
                  'public.record_plugin_download(uuid,uuid,uuid,text)']) f;

-- has_function_privilege proves the grant, not the path. The sweep's own comment
-- names the sharp edge: an RLS policy expression is evaluated as the QUERYING
-- role, so revoking a policy helper turns an anonymous SELECT into
-- 'permission denied for function ...' rather than an empty result - which no
-- privilege assertion can see. Exercise the reads themselves.
--
-- The rows matter. A policy expression is evaluated PER ROW, so on an empty
-- table it never runs and the probe below passes no matter what has been
-- revoked - both tables are empty on a fresh database. Seed one row each first.
--
-- Sensitivity, measured rather than assumed: with the row present, revoking
-- can_view_plugin_row from anon DOES fail this probe, and revoking authorize
-- does NOT. That is not a gap in the seeding - permissive policies are ORed and
-- short-circuit, so a helper in a policy that never has to be evaluated cannot
-- be detected this way by any test. This probe covers the helper the anonymous
-- browse actually depends on, plus the table grant; CHECK 1b covers the rest by
-- asserting the grants directly.
insert into public.plugins (plugin_id, display_name, author_name)
values ('pgtap.acl.probe', 'pgTAP ACL probe', 'pgtap');
-- Creating the user is enough: handle_new_user assigns the default role, which
-- is the user_roles row the second probe needs.
insert into auth.users (id, email, email_confirmed_at)
values ('42342342-0000-0000-0000-000000000001', 'aclprobe@pgtap.test', now());

select lives_ok($anonread$
do $inner$
begin
  perform set_config('role', 'anon', true);
  perform 1 from public.plugins limit 1;
  perform set_config('role', 'postgres', true);
end $inner$
$anonread$, 'anonymous SELECT on plugins still resolves its policy helpers');

select lives_ok($anonread$
do $inner$
begin
  perform set_config('role', 'anon', true);
  perform 1 from public.user_roles limit 1;
  perform set_config('role', 'postgres', true);
end $inner$
$anonread$, 'anonymous SELECT on user_roles still resolves is_user_admin');
select ok(coalesce(has_function_privilege('supabase_auth_admin',
    to_regprocedure('public.custom_access_token_hook(jsonb)'), 'EXECUTE'), false), 'token issuer retains hook access');

-- Replacement intentionally requires a new explicit grant, documented by behavior.
create function public.pgtap_anon_guard() returns integer language sql as 'select 1';
select ok(not has_function_privilege('anon', 'public.pgtap_anon_guard()', 'EXECUTE'),
    'new function is not implicitly anonymous');
grant execute on function public.pgtap_anon_guard() to anon;
select ok(has_function_privilege('anon', 'public.pgtap_anon_guard()', 'EXECUTE'),
    'explicit anonymous grant works');
create or replace function public.pgtap_anon_guard() returns integer language sql as 'select 2';
select ok(not has_function_privilege('anon', 'public.pgtap_anon_guard()', 'EXECUTE'),
    'replacement requires re-issuing the anonymous grant');
grant execute on function public.pgtap_anon_guard() to anon;
select ok(has_function_privilege('anon', 'public.pgtap_anon_guard()', 'EXECUTE'),
    'post-replacement explicit grant works');

-- A creator without Supabase defaults exposes the PUBLIC-only regression.
create role pgtap_acl_creator;
grant pgtap_acl_creator to postgres;
grant usage, create on schema public to pgtap_acl_creator;
set local role pgtap_acl_creator;
create function public.pgtap_public_only() returns integer language sql as 'select 3';
reset role;
select ok(not has_function_privilege('authenticated', 'public.pgtap_public_only()', 'EXECUTE'),
    'guard does not materialise PUBLIC-derived authenticated access');
select ok(not has_function_privilege('service_role', 'public.pgtap_public_only()', 'EXECUTE'),
    'guard does not materialise PUBLIC-derived service access');
select ok(not has_function_privilege('anon', 'public.pgtap_public_only()', 'EXECUTE'),
    'guard removes PUBLIC-derived anonymous access');
grant execute on function public.pgtap_public_only() to authenticated, service_role;
create or replace function public.pgtap_public_only() returns integer language sql as 'select 4';
select ok(has_function_privilege('authenticated', 'public.pgtap_public_only()', 'EXECUTE'),
    'replacement preserves a direct authenticated grant');
select ok(has_function_privilege('service_role', 'public.pgtap_public_only()', 'EXECUTE'),
    'replacement preserves a direct service grant');
revoke execute on function public.pgtap_public_only() from authenticated;
create or replace function public.pgtap_public_only() returns integer language sql as 'select 5';
select ok(not has_function_privilege('authenticated', 'public.pgtap_public_only()', 'EXECUTE'),
    'replacement does not restore deliberately revoked signed-in access');
create procedure public.pgtap_anon_procedure() language sql as 'select 1';
select ok(not has_function_privilege('anon', 'public.pgtap_anon_procedure()', 'EXECUTE'),
    'procedures receive the same guard');

-- SET membership allows the fixture creator to be entered, but INHERIT FALSE
-- means the guard's postgres definer cannot act as that routine's owner.
create role pgtap_acl_outsider;
grant pgtap_acl_outsider to postgres with inherit false, set true;
grant usage, create on schema public to pgtap_acl_outsider;
grant usage on schema extensions to pgtap_acl_outsider;
select ok(not pg_has_role('postgres', 'pgtap_acl_outsider', 'USAGE'),
    'guard definer does not inherit the outsider owner privileges');
set local role pgtap_acl_outsider;
select throws_ok('create function public.pgtap_unrevokable() returns integer language sql as ''select 1''',
    '42501', null, 'DDL fails when guard cannot revoke the creator ACL');
reset role;
select ok(to_regprocedure('public.pgtap_unrevokable()') is null,
    'failed DDL leaves no anonymously executable routine');

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
    ('42300000-0000-0000-0000-000000000001', 'actor@pgtap.test', now(), '{}'),
    ('42300000-0000-0000-0000-000000000002', 'mate@pgtap.test', now(), '{"display_name":"Visible Mate"}'),
    ('42300000-0000-0000-0000-000000000003', 'outsider@pgtap.test', now(), '{}');
-- Use invite_only to isolate the is_system arm from the open-org arm. The
-- fixture really has co-members; dropping the system predicate must fail.
select public.create_organisation_internal(p_slug=>'pgt423system', p_name=>'System test',
    p_owner_id=>'42300000-0000-0000-0000-000000000001',
    p_visibility=>'public', p_join_policy=>'invite_only', p_is_system=>true);
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
select o.id, u.id, 'active', now(), 'admin'
from public.organisations o cross join auth.users u
where o.slug='pgt423system'
  and u.id in ('42300000-0000-0000-0000-000000000002', '42300000-0000-0000-0000-000000000003');
select is((select count(*) from public.organisation_members m
           join public.organisations o on o.id=m.org_id where o.slug='pgt423system'),
    3::bigint, 'system fixture has three actual co-members');
select set_config('request.jwt.claims', '{}', true);
select is((select count(*) from public.org_visible_users()), 0::bigint,
    'signed-out visibility is empty');
select set_config('request.jwt.claims',
    '{"sub":"42300000-0000-0000-0000-000000000001","role":"authenticated"}', true);
set local role authenticated;
select is((select count(*) from public.org_visible_users()), 1::bigint,
    'system invite-only membership exposes only self');
select is(public.list_shareable_recipients()->'data', '[]'::jsonb,
    'system-only caller cannot enumerate recipients');
reset role;
select public.create_organisation_internal(p_slug=>'pgt423', p_name=>'Visibility test',
    p_owner_id=>'42300000-0000-0000-0000-000000000001',
    p_visibility=>'public', p_join_policy=>'invite_only');
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
select id, '42300000-0000-0000-0000-000000000002', 'active', now(), 'admin'
from public.organisations where slug='pgt423';
set local role authenticated;
select is((select count(*) from public.org_visible_users()), 2::bigint,
    'vetted org includes active co-member and self only');
select is(jsonb_array_length(public.list_shareable_recipients()->'data'), 1,
    'picker excludes self and outsider');
select is(public.list_shareable_recipients('Visible Mate')->'data'->0->>'email',
    'mate@pgtap.test', 'display_name metadata is searchable');
select is(public.list_shareable_recipients('%')->'data', '[]'::jsonb,
    'percent query is literal');
select is(public.list_shareable_recipients('_')->'data', '[]'::jsonb,
    'underscore query is literal');
reset role;
update public.organisations set join_policy='open' where slug='pgt423';
select is((select count(*) from public.org_visible_users()), 1::bigint,
    'open org does not establish visibility');
update public.organisations set join_policy='invite_only' where slug='pgt423';
update public.organisation_members set status='pending'
where user_id='42300000-0000-0000-0000-000000000002'
  and org_id=(select id from public.organisations where slug='pgt423');
select is((select count(*) from public.org_visible_users()), 1::bigint,
    'inactive co-members are invisible');

-- Owner-run RPCs must still decrypt under the real authenticated role.
-- Provision the fixture key without assuming the database has none: a seed, a
-- migration or a previous run may already hold the name, and vault.secrets.name
-- is unique. Unconditional create_secret() made the whole suite fail in exactly
-- that case.
do $fixture$
declare existing uuid;
begin
  select id into existing from vault.secrets where name = 'master_encryption_key';
  if existing is null then
    perform vault.create_secret('cGd0YXAtdGVzdC1rZXktMzItYnl0ZXMtYWVzLW9r', 'master_encryption_key', 'transaction-local fixture');
  else
    perform vault.update_secret(existing, 'cGd0YXAtdGVzdC1rZXktMzItYnl0ZXMtYWVzLW9r', 'master_encryption_key', 'transaction-local fixture');
  end if;
end $fixture$;
set local role authenticated;
select is(public.create_secret('pgt423.example','actor','fixture-password', p_twofa_enabled=>true, p_twofa_type=>'app', p_recovery_codes=>array['fixture-recovery'])->>'success',
    'true', 'signed-in create RPC still encrypts');
select is((select password from public.get_user_secrets(50,0)
    where website='pgt423.example'), 'fixture-password', 'signed-in read RPC still decrypts');
select is((select metadata->'recovery_codes' from public.get_user_secrets(50,0)
    where website='pgt423.example'), '["fixture-recovery"]'::jsonb,
    'signed-in secret RPC still decrypts recovery codes');
reset role;
select * from finish();
rollback;
