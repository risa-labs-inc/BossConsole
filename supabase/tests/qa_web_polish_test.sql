-- pgTAP tests for 20261023130000_qa_web_polish: the bot reads a person's latest ask; a dismissed one carries its note;
-- asking again after a dismissal opens a new pending ask. Run with: supabase test db
begin;
select plan(9);

insert into auth.users (id, email, email_confirmed_at) values
    ('c9000000-0000-4000-8000-0000000000a1', 'asker@qwp.test', now()),
    ('c9000000-0000-4000-8000-0000000000a2', 'platform@qwp.test', now()),
    ('c9000000-0000-4000-8000-0000000000a4', 'bot@qwp.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c9000000-0000-4000-8000-0000000000a2', r.id from public.roles r where r.name = 'admin' on conflict do nothing;
insert into public.agents_service_accounts (user_id, purpose) values ('c9000000-0000-4000-8000-0000000000a4', 'provisioner');

create temp table res (k text primary key, j jsonb) on commit drop;
grant all on res to authenticated, anon;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', ('c9000000-0000-4000-8000-0000000000' || p), true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', 'c9000000-0000-4000-8000-0000000000' || p)::text, true);
$$;
grant execute on function pg_temp.as_user(text) to authenticated, anon;

select ok(not has_function_privilege('anon', 'public.web_pod_request_latest(uuid)', 'EXECUTE'), 'anon cannot read asks');

set local role authenticated;
select pg_temp.as_user('a1');
select throws_ok($$ select public.web_pod_request_latest('c9000000-0000-4000-8000-0000000000a1') $$, '42501', null, 'a person cannot read through it');

select pg_temp.as_user('a4');
select is(public.web_pod_request_latest('c9000000-0000-4000-8000-0000000000a1'), null, 'no ask: null');
insert into res values ('r1', public.web_pod_request_add('c9000000-0000-4000-8000-0000000000a1'));
select is(public.web_pod_request_latest('c9000000-0000-4000-8000-0000000000a1')->>'status', 'pending', 'a fresh ask is pending');

select pg_temp.as_user('a2');
select lives_ok($$ select public.decide_web_pod_request((select (j->>'id')::uuid from res where k = 'r1'), false, 'Ask your org admin') $$, 'an admin dismisses with a note');

select pg_temp.as_user('a4');
insert into res values ('l1', public.web_pod_request_latest('c9000000-0000-4000-8000-0000000000a1'));
select is((select j->>'status' from res where k = 'l1'), 'dismissed', 'the latest ask reads dismissed');
select is((select j->>'note' from res where k = 'l1'), 'Ask your org admin', 'with the note');

insert into res values ('r2', public.web_pod_request_add('c9000000-0000-4000-8000-0000000000a1'));
select isnt((select j->>'id' from res where k = 'r2'), (select j->>'id' from res where k = 'r1'), 'asking again after a dismissal opens a new ask');
select is(public.web_pod_request_latest('c9000000-0000-4000-8000-0000000000a1')->>'status', 'pending', 'and the latest is that new pending ask');

select * from finish();
rollback;
