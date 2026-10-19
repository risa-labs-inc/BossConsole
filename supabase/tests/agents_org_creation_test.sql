-- pgTAP tests for 20261019160000_agents_org_creation: the console's org_create (queued or created at
-- once by org_creation_open), its limits and slug rules, my_organisation_requests, and the org.create
-- audit entry. Run with: supabase test db
begin;
select plan(53);

-- r1 requester, r2 someone else, r3 platform admin, r4 open-flag creator, r5 day limit.
insert into auth.users (id, email, email_confirmed_at) values
    ('c6000000-0000-4000-8000-0000000000a1', 'requester@orgc.test', now()),
    ('c6000000-0000-4000-8000-0000000000a2', 'other@orgc.test', now()),
    ('c6000000-0000-4000-8000-0000000000a3', 'platform@orgc.test', now()),
    ('c6000000-0000-4000-8000-0000000000a4', 'founder@orgc.test', now()),
    ('c6000000-0000-4000-8000-0000000000a5', 'busy@orgc.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c6000000-0000-4000-8000-0000000000a3', r.id from public.roles r where r.name = 'admin'
    on conflict do nothing;
update public.platform_flags set value = 'false'::jsonb where key = 'org_creation_open';

create temp table res (k text primary key, j jsonb) on commit drop;
grant all on res to authenticated, anon, service_role;
create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c6000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.r(p text) returns jsonb language sql stable as $$ select j from res where k = p $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p))::text, true);
$$;
grant execute on function pg_temp.u(text), pg_temp.r(text), pg_temp.as_user(text) to authenticated, anon, service_role;

-- ---- 1. surface ----
select is((select value from public.platform_flags where key = 'org_creation_open'), 'false'::jsonb, 'org_creation_open exists');
select ok(not has_function_privilege('anon', 'public.org_create(text,text,text,text)', 'EXECUTE')
      and has_function_privilege('authenticated', 'public.org_create(text,text,text,text)', 'EXECUTE'), 'org_create: signed-in only');
select ok(not has_function_privilege('anon', 'public.my_organisation_requests()', 'EXECUTE')
      and has_function_privilege('authenticated', 'public.my_organisation_requests()', 'EXECUTE'), 'my_organisation_requests: signed-in only');
select ok(not has_function_privilege('anon', 'public.organisation_slug_problem(text)', 'EXECUTE')
      and has_function_privilege('authenticated', 'public.organisation_slug_problem(text)', 'EXECUTE'), 'organisation_slug_problem: signed-in only');
select ok(not has_function_privilege('authenticated', 'public.organisation_requests_audit_approve()', 'EXECUTE'), 'the audit trigger function is not callable');
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok($$ select public.org_create('Nobody', 'nobody_co') $$, '42501', 'Not authenticated', 'no session, no request');

-- ---- 2. closed: a request waits for a platform admin ----
select pg_temp.as_user('a1');
insert into res values ('pend', public.org_create('Pending Co', 'pendco', 'We sell pens', 'pendco.example'));
select is(pg_temp.r('pend')->>'status', 'pending', 'closed: the request is pending');
select ok(not exists (select 1 from public.organisations where slug = 'pendco'), 'closed: no organisation yet');
select is((select count(*)::int from public.my_organisation_requests() where status = 'pending' and slug = 'pendco'), 1, 'the requester sees it');
select is((select domain from public.my_organisation_requests() where slug = 'pendco'), 'pendco.example', 'with its domain');
select pg_temp.as_user('a2');
select is((select count(*)::int from public.my_organisation_requests()), 0, 'someone else does not');
select throws_ok($$ select public.org_create('Pen Clash', 'pendco') $$, 'P0001', 'slug_requested', 'a slug with a pending request is refused');
select is(public.approve_organisation_request((pg_temp.r('pend')->>'request_id')::uuid)->>'error', 'Permission denied', 'a non-admin cannot approve');
select is(public.reject_organisation_request((pg_temp.r('pend')->>'request_id')::uuid, 'no')->>'error', 'Permission denied', 'a non-admin cannot reject');
select is(public.withdraw_organisation_request((pg_temp.r('pend')->>'request_id')::uuid)->>'error', 'Request not found', 'nor withdraw someone else''s');
select is((public.list_organisation_requests('pending')->'data')::text, '[]', 'nor list the queue');

select pg_temp.as_user('a3');
select ok((select bool_or(x->>'slug' = 'pendco') from jsonb_array_elements(public.list_organisation_requests('pending')->'data') x), 'the platform admin lists it');
insert into res values ('appr', public.approve_organisation_request((pg_temp.r('pend')->>'request_id')::uuid, 'welcome'));
select is((pg_temp.r('appr')->>'success')::boolean, true, 'the platform admin approves');
select is((select owner_id from public.organisations where slug = 'pendco'), pg_temp.u('a1'), 'the requester owns the org');
select is((select count(*)::int from public.organisation_roles orl join public.organisations o on o.id = orl.org_id
           where o.slug = 'pendco' and orl.preset in ('agent_manager', 'agent_editor', 'agent_analyst')), 3, 'built-in agent roles seeded');
select is((select actor from public.agent_audit_log where org_id = (pg_temp.r('appr')->>'org_id')::uuid and action = 'org.create'),
    pg_temp.u('a3'), 'org.create audited with the reviewer');
select is((select detail->>'mode' from public.agent_audit_log where org_id = (pg_temp.r('appr')->>'org_id')::uuid and action = 'org.create'),
    'reviewed', 'mode reviewed');
select pg_temp.as_user('a1');
select is(public.my_org_permissions((pg_temp.r('appr')->>'org_id')::uuid),
    array['agents.view','agents.edit','agents.publish','agents.evolve','agents.access','agents.domains','organisation.admin'],
    'the owner is an admin with every agents.* permission');
select ok((select bool_or((x->>'id')::uuid = (pg_temp.r('appr')->>'org_id')::uuid and (x->>'is_admin')::boolean)
           from jsonb_array_elements(public.get_my_organisations()->'data') x), 'and sees it in get_my_organisations');
select is((select status || ':' || created_org_id::text from public.my_organisation_requests() where slug = 'pendco'),
    'approved:' || (pg_temp.r('appr')->>'org_id'), 'the request shows approved with the org');
select is((select count(*)::int from public.agent_audit_log where org_id = (pg_temp.r('appr')->>'org_id')::uuid and action = 'org.create'), 1,
    'one org.create entry');

-- ---- 3. slug, name and domain rules ----
select throws_ok($$ select public.org_create('Pen Again', 'pendco') $$, 'P0001', 'slug_taken', 'an existing slug is taken');
select throws_ok($$ select public.org_create('Console', 'console') $$, 'P0001', 'slug_reserved', 'agents reserved slug (console)');
select throws_ok($$ select public.org_create('Max', 'max') $$, 'P0001', 'slug_reserved', 'agents reserved slug (max)');
select throws_ok($$ select public.org_create('Admin', 'admin') $$, 'P0001', 'slug_reserved', 'organisation reserved slug (admin)');
select throws_ok($$ select public.org_create('Bad', 'Bad-Slug') $$, 'P0001', 'slug_invalid', 'a malformed slug');
select throws_ok($$ select public.org_create('x', 'okslug') $$, 'P0001', 'name_invalid', 'a one-letter name');
select throws_ok($$ select public.org_create('Dom Co', 'domco', null, 'not a domain') $$, 'P0001', 'domain_invalid', 'a malformed domain');
select throws_ok($$ select public.org_create('Dom Co', 'domco', null, 'gmail.com') $$, 'P0001', 'domain_reserved', 'a public mail domain');
select throws_ok($$ select public.org_create('Dom Co', 'domco', null, 'PendCo.example') $$, 'P0001', 'domain_taken', 'a claimed domain, any case');
select is(public.organisation_slug_problem('fresh_slug'), null, 'a free slug has no problem');
select is(public.organisation_slug_problem('console'), 'slug_reserved', 'slug_problem names reserved');

-- ---- 4. withdraw and reject ----
insert into res values ('wd', public.org_create('Withdraw Me', 'wdraw'));
select is(public.withdraw_organisation_request((pg_temp.r('wd')->>'request_id')::uuid)->>'status', 'withdrawn', 'the requester withdraws');
select is(public.withdraw_organisation_request((pg_temp.r('wd')->>'request_id')::uuid)->>'error', 'Request is already withdrawn', 'only once');
select is(public.organisation_slug_problem('wdraw'), null, 'a withdrawn slug is free again');
insert into res values ('rj', public.org_create('Reject Me', 'rejme'));
select pg_temp.as_user('a3');
select is(public.reject_organisation_request((pg_temp.r('rj')->>'request_id')::uuid, ' ')->>'error',
    'A reason is required when rejecting a request', 'rejecting needs a reason');
select is(public.reject_organisation_request((pg_temp.r('rj')->>'request_id')::uuid, 'Use your existing org')->>'status', 'rejected', 'rejected with a reason');
select pg_temp.as_user('a1');
select is((select review_notes from public.my_organisation_requests() where slug = 'rejme'), 'Use your existing org', 'the requester reads the reason');

-- ---- 5. open: created at once ----
update public.platform_flags set value = 'true'::jsonb where key = 'org_creation_open';
select pg_temp.as_user('a4');
insert into res values ('open', public.org_create('Open Co', 'openco'));
select is(pg_temp.r('open')->>'status', 'created', 'open: created at once');
select is((select owner_id from public.organisations where id = (pg_temp.r('open')->>'org_id')::uuid), pg_temp.u('a4'), 'owned by the requester');
select is(public.my_org_permissions((pg_temp.r('open')->>'org_id')::uuid),
    array['agents.view','agents.edit','agents.publish','agents.evolve','agents.access','agents.domains','organisation.admin'],
    'the founder holds every agents.* permission');
select is((select status || ':' || (reviewer_id = pg_temp.u('a4'))::text from public.organisation_requests where id = (pg_temp.r('open')->>'request_id')::uuid),
    'approved:true', 'the request is approved by the open policy');
select is((select detail->>'mode' || ':' || (actor = pg_temp.u('a4'))::text from public.agent_audit_log
           where org_id = (pg_temp.r('open')->>'org_id')::uuid and action = 'org.create'), 'automatic:true', 'org.create audited as automatic');
select pg_temp.as_user('a3');
select is(public.approve_organisation_request((pg_temp.r('open')->>'request_id')::uuid)->>'error', 'Request is already approved', 'nothing left to approve');

-- ---- 6. limits ----
select pg_temp.as_user('a4');
select lives_ok($$ select public.org_create('Open Two', 'opentwo'), public.org_create('Open Three', 'openthree') $$, 'up to three owned orgs');
select throws_ok($$ select public.org_create('Open Four', 'openfour') $$, 'P0001', 'org_limit_reached', 'the fourth is refused');
update public.platform_flags set value = 'false'::jsonb where key = 'org_creation_open';
select pg_temp.as_user('a5');
do $$ declare i int; j jsonb; begin
    for i in 1..5 loop
        j := public.org_create('Busy ' || i, 'busy' || i);
        perform public.withdraw_organisation_request((j->>'request_id')::uuid);
    end loop;
end $$;
select throws_ok($$ select public.org_create('Busy Six', 'busysix') $$, 'P0001', 'request_limit_reached', 'the sixth request in a day is refused');
update public.platform_flags set value = 'true'::jsonb where key = 'org_creation_open';
select pg_temp.as_user('a3');
select lives_ok($$ do $b$ declare i int; begin for i in 1..6 loop perform public.org_create('Staff ' || i, 'staffco' || i); end loop; end $b$ $$,
    'platform admins are not limited');

select * from finish();
rollback;
