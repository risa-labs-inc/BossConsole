-- pgTAP tests for 20261022120000_console_feedback.
-- Run with: supabase test db
begin;
select plan(43);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
    ('cf000000-0000-4000-8000-00000000000a', 'owner@cf.test', now(), '{"full_name":"Olive Owner"}'),
    ('cf000000-0000-4000-8000-00000000000d', 'platform@cf.test', now(), '{}'),
    ('cf000000-0000-4000-8000-00000000000e', 'asker@cfnew.test', now(), '{"name":"Ash Asker"}');
insert into public.user_roles (user_id, role_id)
    select 'cf000000-0000-4000-8000-00000000000d', id from public.roles where name = 'admin';

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'cforg', p_name => 'CF Org', p_description => null,
    p_owner_id => 'cf000000-0000-4000-8000-00000000000a', p_domain => 'cf.test', p_visibility => 'private', p_join_policy => 'invite_only');
update public.platform_flags set value = 'false'::jsonb where key = 'agent_creation_open';
update public.platform_flags set value = 'true'::jsonb where key = 'agent_update_review';
insert into public.agents (slug, org_id, name, plugin_id, created_by)
values ('cfagent', (select id from public.organisations where slug = 'cforg'), 'CF', 'ai.rever.boss.plugin.dynamic.agent.cfagent',
        'cf000000-0000-4000-8000-00000000000a');
create temp table a (id uuid);
grant all on a to authenticated, anon;
insert into a select id from public.agents where slug = 'cfagent';
create temp table base as select value #>> '{}' as d from public.platform_flags where key = 'agents_base_domain';
grant select on base to anon;

-- ---- shape ----
select ok((select bool_and(prosecdef and 'search_path=""' = any(proconfig)) from pg_proc where oid in (
    'public.review_agent(uuid,boolean,text)'::regprocedure, 'public.suspend_agent(uuid,text)'::regprocedure,
    'public.unsuspend_agent(uuid)'::regprocedure, 'public.agent_unavailable(text)'::regprocedure,
    'public.platform_org_request_detail(uuid)'::regprocedure, 'public.organisation_domain_problem(text)'::regprocedure,
    'public.platform_settings_history(integer)'::regprocedure)), 'new RPCs are security definer with an empty search_path');
select ok(to_regprocedure('public.review_agent(uuid,boolean)') is null and to_regprocedure('public.suspend_agent(uuid)') is null,
    'the old signatures are gone (the new ones take the old calls through their defaults)');
select ok(not has_function_privilege('anon', 'public.unsuspend_agent(uuid)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_org_request_detail(uuid)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.organisation_domain_problem(text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_settings_history(integer)', 'EXECUTE')
      and has_function_privilege('anon', 'public.agent_unavailable(text)', 'EXECUTE'), 'only agent_unavailable is anon-callable');
select ok(not has_column_privilege('authenticated', 'public.agents', 'pending_version', 'UPDATE')
      and not has_column_privilege('authenticated', 'public.agents', 'review_note', 'UPDATE')
      and not has_column_privilege('authenticated', 'public.agents', 'suspended_reason', 'UPDATE'), 'review columns are not client-writable');

-- ---- first publish waits; an update to a live agent waits beside the live version ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000a', true);
select is(public.publish_agent((select id from a), '1.0.0')::text, 'pending_review', 'first publish waits for review');
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000d', true);
select is(public.review_agent((select id from a), true)::text, 'live', 'approved (two-argument call still works)');
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000a', true);
select is(public.publish_agent((select id from a), '1.0.1')::text, 'pending_review', 'an update waits for review');
select is((select row(status, live_version, pending_version)::text from public.agents where slug = 'cfagent'), '(live,1.0.0,1.0.1)',
    'the agent stays live on 1.0.0 while 1.0.1 waits');
select is((select submitted_by from public.agents where slug = 'cfagent'), 'cf000000-0000-4000-8000-00000000000a'::uuid, 'who submitted it');
reset role;
select ok(exists (select 1 from public.agent_audit_log where agent_id = (select id from a) and action = 'agent.submit' and detail->>'version' = '1.0.1'),
    'agent.submit audited for the org');
set local role authenticated;
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select public.review_agent((select id from a), true) $q$, '42501', null, 'an org admin cannot review');

select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000d', true);
select is((select count(*)::int from public.list_pending_agents() where slug = 'cfagent'), 1, 'the update is in the review queue');
select is((select pending_version from public.platform_agents_overview() where slug = 'cfagent'), '1.0.1', 'overview shows the waiting version');
select is((public.platform_agent_detail((select id from a))->'agent'->>'submitted_by_email'), 'owner@cf.test', 'detail names the submitter');
select is(public.review_agent((select id from a), false, 'Remove the terminal tools')::text, 'live', 'rejecting the update keeps it live');
select is((select row(live_version, pending_version, review_note)::text from public.agents where slug = 'cfagent'),
    '(1.0.0,,"Remove the terminal tools")', 'the waiting version is dropped and the reason kept');
reset role;
select ok(exists (select 1 from public.platform_audit_log where action = 'agent.reject' and detail->>'reason' = 'Remove the terminal tools'),
    'the rejection is in the platform audit log');
set local role authenticated;

select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000a', true);
select is(public.publish_agent((select id from a), '1.0.2')::text, 'pending_review', 'published again');
select is((select review_note from public.agents where slug = 'cfagent'), null, 'a new submission clears the old reason');
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000d', true);
select is(public.review_agent((select id from a), true)::text, 'live', 'approve the update');
select is((select row(live_version, pending_version)::text from public.agents where slug = 'cfagent'), '(1.0.2,)', 'the approved version is live');

-- ---- with the flag off, updates go live at once ----
reset role;
update public.platform_flags set value = 'false'::jsonb where key = 'agent_update_review';
set local role authenticated;
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000a', true);
select is(public.publish_agent((select id from a), '1.0.3')::text, 'live', 'agent_update_review off: the update is live at once');
select is((select row(live_version, pending_version)::text from public.agents where slug = 'cfagent'), '(1.0.3,)', 'live_version moved');

-- ---- flag changes are audited ----
reset role;
select ok(exists (select 1 from public.platform_audit_log where action = 'platform.flag_set' and detail->>'key' = 'agent_update_review'
                  and detail->'to' = 'false'::jsonb), 'flag changes land in platform_audit_log');
set local role authenticated;
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000d', true);
select is((select key from public.platform_settings_history(5) limit 1), 'agent_update_review', 'the settings history lists it');
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select * from public.platform_settings_history(5) $q$, '42501', null, 'an org admin cannot read the settings history');
select is_empty($q$ update public.platform_flags set value = 'true'::jsonb where key = 'agent_update_review' returning 1 $q$, 'an org admin cannot change a flag');

-- ---- suspend and unsuspend ----
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select public.unsuspend_agent((select id from a)) $q$, '42501', null, 'an org admin cannot unsuspend');
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000d', true);
select lives_ok($q$ select public.suspend_agent((select id from a), 'Abuse report') $q$, 'suspend with a reason');
select is((select row(status, status_before_suspend, suspended_reason)::text from public.agents where slug = 'cfagent'),
    '(suspended,live,"Abuse report")', 'suspended, remembering it was live');
select throws_ok($q$ select public.suspend_agent((select id from a)) $q$, 'P0001', 'agent_already_suspended', 'cannot suspend twice');
reset role;
set local role anon;
select is(public.agent_unavailable('cfagent.' || (select d from base)), true,
    'anon: the suspended address says unavailable');
select is(public.agent_unavailable('nosuch.' || (select d from base)), false,
    'anon: an unknown address does not');
reset role;
set local role authenticated;
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000d', true);
select is(public.unsuspend_agent((select id from a))::text, 'live', 'unsuspend restores live');
select is((select row(status, live_version, suspended_reason)::text from public.agents where slug = 'cfagent'), '(live,1.0.3,)', 'same live version, reason cleared');
reset role;
select ok(exists (select 1 from public.agent_audit_log where agent_id = (select id from a) and action = 'agent.unsuspend'), 'agent.unsuspend audited for the org');
set local role authenticated;

-- ---- organisation requests and domains ----
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000e', true);
select is(public.organisation_domain_problem('cf.test'), 'domain_taken', 'a claimed domain is taken');
select is(public.organisation_domain_problem('@CFNEW.test'), null, 'a free domain is fine (case and @ ignored)');
select is(public.organisation_domain_problem('not a domain'), 'domain_invalid', 'malformed');
reset role;
-- org_create refuses a claimed domain; an older request (or the desktop app's) can still name one.
insert into public.organisation_requests (requester_id, name, slug, description, domain)
    values ('cf000000-0000-4000-8000-00000000000e', 'New Co', 'cfnewco', 'Things', 'cf.test');
select is((select status from public.organisation_requests where slug = 'cfnewco'), 'pending', 'a request naming a claimed domain');
set local role authenticated;
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000d', true);
select is((public.platform_org_request_detail((select id from public.organisation_requests where slug = 'cfnewco'))->'requester'->>'display_name'), 'Ash Asker',
    'the detail names the requester');
select is((public.platform_org_request_detail((select id from public.organisation_requests where slug = 'cfnewco'))->'domain'->'claimed_by'->>'slug'), 'cforg',
    'and says which organisation already claims the domain');
select set_config('request.jwt.claim.sub', 'cf000000-0000-4000-8000-00000000000e', true);
select throws_ok($q$ select public.platform_org_request_detail((select id from public.organisation_requests where slug = 'cfnewco')) $q$, '42501', null,
    'the requester cannot read the platform detail');
reset role;

select * from finish();
rollback;
