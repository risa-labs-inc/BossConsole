-- pgTAP tests for 20261116190000_marketplace_reviewer_role: reviewers decide pending versions and do nothing else.
begin;
select plan(32);

-- p1 publisher, r1 reviewer (granted below), u1 plain user, c1 platform admin.
insert into auth.users (id, email, email_confirmed_at) values
    ('c7000000-0000-4000-8000-0000000000a1', 'publisher@mrev.test', now()),
    ('c7000000-0000-4000-8000-0000000000b1', 'reviewer@mrev.test', now()),
    ('c7000000-0000-4000-8000-0000000000d1', 'plain@mrev.test', now()),
    ('c7000000-0000-4000-8000-0000000000c1', 'platform@mrev.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c7000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';

create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c7000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p))::text, true);
$$;
create function pg_temp.skill(md text) returns jsonb language sql immutable as $$ select jsonb_build_object('markdown', md) $$;
grant execute on function pg_temp.u(text), pg_temp.as_user(text), pg_temp.skill(text) to authenticated;
create temp table vids (k text primary key, id uuid) on commit drop;
grant all on vids to authenticated;

-- Shape.
select is((select is_system from public.roles where name = 'marketplace_reviewer'), true, 'marketplace_reviewer is a system role');
select ok(exists (select 1 from public.role_hierarchy h join public.roles p on p.id = h.parent_role_id
                   join public.roles c on c.id = h.child_role_id where p.name = 'admin' and c.name = 'marketplace_reviewer'),
    'it sits under admin');
select ok(not has_function_privilege('authenticated', 'public.is_marketplace_reviewer(uuid)', 'EXECUTE'), 'the helper is internal');
select ok(not has_function_privilege('anon', 'public.marketplace_review_access()', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_set_marketplace_reviewer(text, boolean)', 'EXECUTE'), 'anon executes neither new RPC');

-- A publisher submits three things for review.
set local role authenticated;
select pg_temp.as_user('a1');
select public.marketplace_submit('skill', 'rev-one', 'Rev one', 'Sum', '', 'Pub', pg_temp.skill('# one'));
select public.marketplace_submit('skill', 'rev-two', 'Rev two', 'Sum', '', 'Pub', pg_temp.skill('# two'));
select public.marketplace_submit('skill', 'rev-three', 'Rev three', 'Sum', '', 'Pub', pg_temp.skill('# three'));

-- Before the grant a reviewer-to-be is a plain user.
select pg_temp.as_user('b1');
select is(public.marketplace_review_access(), false, 'no access before the grant');
select throws_ok($$ select public.marketplace_pending() $$, '42501', null, 'no queue before the grant');
select throws_ok($$ select public.marketplace_review(gen_random_uuid(), true) $$, '42501', null, 'no review before the grant');

-- A plain user and a reviewer cannot grant the role; an admin can.
select pg_temp.as_user('d1');
select throws_ok($$ select public.platform_set_marketplace_reviewer('plain@mrev.test', true) $$, '42501', null, 'a plain user cannot grant');
select pg_temp.as_user('c1');
select throws_ok($$ select public.platform_set_marketplace_reviewer('nobody@mrev.test', true) $$, 'P0002', 'user_not_found', 'unknown email');
select throws_ok($$ select public.platform_set_marketplace_reviewer('  ', true) $$, '22023', 'bad_input', 'empty email');
select is(public.platform_set_marketplace_reviewer('Reviewer@MREV.test', true), true, 'an admin grants it (case-insensitive email)');
select is(public.platform_set_marketplace_reviewer('reviewer@mrev.test', true), true, 'granting twice is a no-op');
select is(public.marketplace_review_access(), true, 'an admin has review access');
reset role;
select is((select count(*)::int from public.platform_audit_log where action = 'platform_role.marketplace_reviewer_grant'
             and target_user = pg_temp.u('b1') and actor = pg_temp.u('c1')), 1, 'one audit row for the grant, none for the no-op');

-- The reviewer sees the queue and decides.
set local role authenticated;
select pg_temp.as_user('b1');
select is(public.marketplace_review_access(), true, 'the reviewer has review access');
select is(jsonb_array_length(public.marketplace_pending()), 3, 'the reviewer sees the queue');
insert into vids select 'r1', ((x->'pending')->>'id')::uuid from jsonb_array_elements(public.marketplace_pending()) x where x->>'slug' = 'rev-one';
insert into vids select 'r2', ((x->'pending')->>'id')::uuid from jsonb_array_elements(public.marketplace_pending()) x where x->>'slug' = 'rev-two';
select is(public.marketplace_review((select id from vids where k = 'r1'), true)::text, 'live', 'the reviewer approves');
select throws_ok($$ select public.marketplace_review((select id from vids where k = 'r2'), false) $$, 'P0001', 'reason_required', 'rejecting still needs a reason');
select is(public.marketplace_review((select id from vids where k = 'r2'), false, 'Not ready')::text, 'rejected', 'the reviewer rejects');
reset role;
select is((select count(*)::int from public.platform_audit_log where action in ('marketplace.approve', 'marketplace.reject')
             and actor = pg_temp.u('b1')), 2, 'decisions are audited under the reviewer');

-- Everything else stays admin-only.
set local role authenticated;
select pg_temp.as_user('b1');
select throws_ok($$ select public.marketplace_suspend('rev-one', true, 'x') $$, '42501', null, 'cannot suspend');
select throws_ok($$ select public.marketplace_mark_reviewed('rev-one') $$, '42501', null, 'cannot mark reviewed');
select throws_ok($$ select public.marketplace_trust_set('publisher@mrev.test', true) $$, '42501', null, 'cannot set a trusted publisher');
select throws_ok($$ select public.marketplace_set_auto_install('rev-one', array['test.rev.plugin']) $$, '42501', null, 'cannot set auto-install');
select throws_ok($$ select public.marketplace_set_metadata('rev-one', null, array['x']) $$, '42501', null, 'cannot set metadata');
select throws_ok($$ select public.marketplace_category_set('mrev-cat', 'Cat') $$, '42501', null, 'cannot edit categories');
select throws_ok($$ select public.platform_set_marketplace_reviewer('plain@mrev.test', true) $$, '42501', null, 'cannot grant the role');

-- A plain user still cannot review; an admin still can; revoking removes access.
select pg_temp.as_user('d1');
select throws_ok($$ select public.marketplace_pending() $$, '42501', null, 'a plain user has no queue');
select throws_ok($$ select public.marketplace_review(gen_random_uuid(), true) $$, '42501', null, 'a plain user cannot review');
select pg_temp.as_user('c1');
insert into vids select 'r3', ((x->'pending')->>'id')::uuid from jsonb_array_elements(public.marketplace_pending()) x where x->>'slug' = 'rev-three';
select is(public.marketplace_review((select id from vids where k = 'r3'), true)::text, 'live', 'an admin still approves');
select is(public.platform_set_marketplace_reviewer('reviewer@mrev.test', false), false, 'an admin revokes it');
select pg_temp.as_user('b1');
select throws_ok($$ select public.marketplace_pending() $$, '42501', null, 'a revoked reviewer is out');

select * from finish();
rollback;
