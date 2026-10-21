-- pgTAP tests for 20261021140000_web_pod_requests: Request a BOSS, recorded by the provisioner bot, decided by platform
-- admins, completed by the bot, all audited. Run with: supabase test db
begin;
select plan(24);

insert into auth.users (id, email, email_confirmed_at) values
    ('c8000000-0000-4000-8000-0000000000a1', 'asker@wpr.test', now()),
    ('c8000000-0000-4000-8000-0000000000a2', 'platform@wpr.test', now()),
    ('c8000000-0000-4000-8000-0000000000a3', 'plain@wpr.test', now()),
    ('c8000000-0000-4000-8000-0000000000a4', 'bot@wpr.test', now()),
    ('c8000000-0000-4000-8000-0000000000a5', 'second@wpr.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c8000000-0000-4000-8000-0000000000a2', r.id from public.roles r where r.name = 'admin' on conflict do nothing;
insert into public.agents_service_accounts (user_id, purpose) values ('c8000000-0000-4000-8000-0000000000a4', 'provisioner');

create temp table res (k text primary key, j jsonb) on commit drop;
grant all on res to authenticated, anon;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', ('c8000000-0000-4000-8000-0000000000' || p), true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', 'c8000000-0000-4000-8000-0000000000' || p)::text, true);
$$;
grant execute on function pg_temp.as_user(text) to authenticated, anon;

-- surface
select ok(not has_function_privilege('anon', 'public.platform_web_pod_requests(boolean,int)', 'EXECUTE'), 'anon cannot list');
select ok(not has_function_privilege('anon', 'public.web_pod_request_add(uuid)', 'EXECUTE'), 'anon cannot add');
select ok(not has_table_privilege('authenticated', 'public.web_pod_requests', 'SELECT'), 'no direct table access');

set local role authenticated;
-- only the bot records an ask
select pg_temp.as_user('a1');
select throws_ok($$ select public.web_pod_request_add('c8000000-0000-4000-8000-0000000000a1') $$, '42501', null, 'a person cannot record asks directly');
select pg_temp.as_user('a4');
insert into res values ('r1', public.web_pod_request_add('c8000000-0000-4000-8000-0000000000a1'));
select is((select j->>'status' from res where k = 'r1'), 'pending', 'the bot records a pending ask');
insert into res values ('r1b', public.web_pod_request_add('c8000000-0000-4000-8000-0000000000a1'));
select is((select j->>'id' from res where k = 'r1b'), (select j->>'id' from res where k = 'r1'), 'asking again refreshes the open ask');
insert into res values ('r2', public.web_pod_request_add('c8000000-0000-4000-8000-0000000000a5'));
select throws_ok($$ select public.web_pod_request_add('c8000000-0000-4000-8000-0000000000ff') $$, 'P0001', 'unknown_user', 'an unknown user is refused');
select throws_ok($$ select public.platform_web_pod_requests() $$, '42501', null, 'the bot cannot list as an admin');
select throws_ok($$ select public.decide_web_pod_request((select (j->>'id')::uuid from res where k = 'r1'), true) $$, '42501', null, 'the bot cannot approve');

-- plain people see and decide nothing
select pg_temp.as_user('a3');
select throws_ok($$ select public.platform_web_pod_request_count() $$, '42501', null, 'a plain person cannot count');
select throws_ok($$ select public.decide_web_pod_request((select (j->>'id')::uuid from res where k = 'r1'), true) $$, '42501', null, 'a plain person cannot approve');
select throws_ok($$ select * from public.web_pod_requests_approved() $$, '42501', null, 'a plain person cannot read approved asks');

-- platform admin
select pg_temp.as_user('a2');
select is(public.platform_web_pod_request_count(), 2, 'two pending');
select is((select count(*)::int from public.platform_web_pod_requests() where email = 'asker@wpr.test' and status = 'pending'), 1, 'the admin lists the ask with its email');
select is((public.decide_web_pod_request((select (j->>'id')::uuid from res where k = 'r1'), true))->>'status', 'approved', 'approve');
select is((public.decide_web_pod_request((select (j->>'id')::uuid from res where k = 'r2'), false, 'Join an agent instead'))->>'status', 'dismissed', 'dismiss with a note');
select throws_ok($$ select public.decide_web_pod_request((select (j->>'id')::uuid from res where k = 'r1'), false) $$, 'P0001', 'not_pending', 'a decided ask cannot be decided again');
select is(public.platform_web_pod_request_count(), 0, 'none pending');

-- the bot acts on approved asks
select pg_temp.as_user('a4');
select is((select array_agg(email) from public.web_pod_requests_approved()), array['asker@wpr.test'], 'the bot sees the approved ask, by email');
select is(public.web_pod_request_done((select (j->>'id')::uuid from res where k = 'r1'), true), true, 'provisioned');
select is(public.web_pod_request_done((select (j->>'id')::uuid from res where k = 'r1'), true), false, 'done only once');
select is((select count(*)::int from public.web_pod_requests_approved()), 0, 'nothing left to act on');
-- a dismissed person may ask again
insert into res values ('r3', public.web_pod_request_add('c8000000-0000-4000-8000-0000000000a5'));
select isnt((select j->>'id' from res where k = 'r3'), (select j->>'id' from res where k = 'r2'), 'after a dismissal a new ask is a new row');
reset role;

select is((select array_agg(action order by id) from public.platform_audit_log where target_user = 'c8000000-0000-4000-8000-0000000000a1'),
    array['web_pod.request', 'web_pod.approve', 'web_pod.provisioned'], 'request, approve and provisioned are audited');

select * from finish();
rollback;
