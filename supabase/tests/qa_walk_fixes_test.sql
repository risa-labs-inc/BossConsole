-- pgTAP tests for 20261023100000_qa_walk_fixes.
-- Run with: supabase test db
begin;
select plan(30);

insert into auth.users (id, email, email_confirmed_at) values
    ('4a000000-0000-4000-8000-00000000000a', 'owner@qaw.test', now()),
    ('4a000000-0000-4000-8000-00000000000b', 'editor@qaw.test', now()),
    ('4a000000-0000-4000-8000-00000000000c', 'analyst@qaw.test', now()),
    ('4a000000-0000-4000-8000-00000000000d', 'platform@qaw.test', now()),
    ('4a000000-0000-4000-8000-00000000000e', 'other@qawother.test', now());
insert into public.user_roles (user_id, role_id)
    select '4a000000-0000-4000-8000-00000000000d', id from public.roles where name = 'admin';

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'qaworg', p_name => 'QAW Org', p_description => null,
    p_owner_id => '4a000000-0000-4000-8000-00000000000a', p_domain => 'qaw.test', p_visibility => 'private', p_join_policy => 'invite_only');
select public.create_organisation_internal(p_slug => 'qawother', p_name => 'QAW Other', p_description => null,
    p_owner_id => '4a000000-0000-4000-8000-00000000000e', p_domain => 'qawother.test', p_visibility => 'private', p_join_policy => 'invite_only');
create temp table ids (k text primary key, id uuid);
grant all on ids to authenticated, anon, service_role;
insert into ids select 'org', id from public.organisations where slug = 'qaworg';
insert into ids select 'other', id from public.organisations where slug = 'qawother';
-- editor: agents.edit; analyst: agents.view only
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
    select (select id from ids where k = 'org'), u, 'active', now(), 'admin'
    from unnest(array['4a000000-0000-4000-8000-00000000000b', '4a000000-0000-4000-8000-00000000000c']::uuid[]) u;
insert into public.user_roles (user_id, role_id)
    select '4a000000-0000-4000-8000-00000000000b', orl.role_id from public.organisation_roles orl
    where orl.org_id = (select id from ids where k = 'org') and orl.preset = 'agent_editor';
insert into public.user_roles (user_id, role_id)
    select '4a000000-0000-4000-8000-00000000000c', orl.role_id from public.organisation_roles orl
    where orl.org_id = (select id from ids where k = 'org') and orl.preset = 'agent_analyst';
select set_config('request.jwt.claims', '', true);
create function pg_temp.id(p text) returns uuid language sql stable as $$ select id from ids where k = p $$;
grant execute on function pg_temp.id(text) to authenticated, anon, service_role;

-- ---- shape ----
select ok(to_regprocedure('public.set_org_default_brand(uuid,jsonb)') is not null
      and not (select prosecdef from pg_proc where oid = 'public.set_org_default_brand(uuid,jsonb)'::regprocedure),
    'set_org_default_brand exists and runs as the caller (RLS applies)');
select ok(not has_function_privilege('anon', 'public.set_org_default_brand(uuid,jsonb)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.organisation_domain_owner(text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.org_email_domains(uuid)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_settings_history(integer,timestamp with time zone)', 'EXECUTE'),
    'none of the new RPCs is anon-callable');
select ok(to_regprocedure('public.platform_settings_history(integer)') is null, 'the one-argument history signature is gone');
select ok(not has_column_privilege('authenticated', 'public.agent_org_defaults', 'org_id', 'UPDATE'),
    'org_id stays non-updatable for clients (the RPC does not need it)');

-- ---- 1. default brand: the owner and an editor save it; an analyst and another org cannot ----
set local role authenticated;
select set_config('request.jwt.claim.sub', '4a000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ select public.set_org_default_brand(pg_temp.id('org'), '{"accent":"#b42318","accent_dark":"#f97066"}') $q$,
    'the org owner saves a default brand');
select lives_ok($q$ select public.set_org_default_brand(pg_temp.id('org'), '{"accent":"#c01048","accent_dark":"#f670c7"}') $q$,
    'saving again updates it (the old upsert failed here)');
select is((select default_brand->>'accent' from public.agent_org_defaults where org_id = pg_temp.id('org')), '#c01048', 'the latest accent is stored');
select set_config('request.jwt.claim.sub', '4a000000-0000-4000-8000-00000000000b', true);
select lives_ok($q$ select public.set_org_default_brand(pg_temp.id('org'), '{"accent":"#b42318","accent_dark":"#f97066"}') $q$,
    'an agent editor (agents.edit) saves it');
select set_config('request.jwt.claim.sub', '4a000000-0000-4000-8000-00000000000c', true);
select throws_ok($q$ select public.set_org_default_brand(pg_temp.id('org'), '{"accent":"#000000"}') $q$, '42501', null,
    'an analyst (view only) cannot');
select set_config('request.jwt.claim.sub', '4a000000-0000-4000-8000-00000000000e', true);
select throws_ok($q$ select public.set_org_default_brand(pg_temp.id('org'), '{"accent":"#000000"}') $q$, '42501', null,
    'another org''s owner cannot');
select set_config('request.jwt.claim.sub', '4a000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select public.set_org_default_brand(pg_temp.id('org'), '{"accent":"red"}') $q$, null, null,
    'the brand is still validated');
reset role;
select ok(exists (select 1 from public.agent_audit_log where org_id = pg_temp.id('org') and action = 'org.default_brand_change'),
    'the change is audited');

-- ---- 2. new agents: default accent, closed sign-up ----
set local role authenticated;
select set_config('request.jwt.claim.sub', '4a000000-0000-4000-8000-00000000000b', true);
insert into public.agents (slug, org_id, name, plugin_id, created_by)
    values ('qawone', pg_temp.id('org'), 'QAW One', 'ai.rever.boss.plugin.dynamic.agent.qawone', '4a000000-0000-4000-8000-00000000000b');
select is((select brand->>'accent' from public.agents where slug = 'qawone'), '#b42318', 'a new agent starts with the org default accent');
select is((select brand->>'accent_dark' from public.agents where slug = 'qawone'), '#f97066', 'and its dark accent');
select is((select signup_policy from public.agents where slug = 'qawone'), '{"mode":"invite"}'::jsonb,
    'no verified domain: a new agent is invite only (an editor without agents.access may create it)');
insert into public.agents (slug, org_id, name, plugin_id, created_by, brand)
    values ('qawtwo', pg_temp.id('org'), 'QAW Two', 'ai.rever.boss.plugin.dynamic.agent.qawtwo', '4a000000-0000-4000-8000-00000000000b',
            '{"accent":"#1d5fd6"}');
select is((select brand->>'accent' from public.agents where slug = 'qawtwo'), '#1d5fd6', 'an explicit accent wins');
select throws_ok($q$ insert into public.agents (slug, org_id, name, plugin_id, created_by, signup_policy)
    values ('qawthree', pg_temp.id('org'), 'QAW Three', 'ai.rever.boss.plugin.dynamic.agent.qawthree',
            '4a000000-0000-4000-8000-00000000000b', '{"mode":"open"}') $q$, '42501', null,
    'an explicit open policy still needs agents.access');
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select (public.mark_organisation_domain_verified((select d.id from public.organisation_domains d
    where d.org_id = pg_temp.id('org') and d.domain = 'qaw.test')))->>'success';
set local role authenticated;
select set_config('request.jwt.claim.sub', '4a000000-0000-4000-8000-00000000000a', true);
insert into public.agents (slug, org_id, name, plugin_id, created_by)
    values ('qawfour', pg_temp.id('org'), 'QAW Four', 'ai.rever.boss.plugin.dynamic.agent.qawfour', '4a000000-0000-4000-8000-00000000000a');
select is((select signup_policy from public.agents where slug = 'qawfour'), '{"mode":"domain","domains":["qaw.test"]}'::jsonb,
    'with a verified domain a new agent signs up that domain');
insert into public.agents (slug, org_id, name, plugin_id, created_by, signup_policy)
    values ('qawfive', pg_temp.id('org'), 'QAW Five', 'ai.rever.boss.plugin.dynamic.agent.qawfive', '4a000000-0000-4000-8000-00000000000a',
            '{"mode":"open"}');
select is((select signup_policy from public.agents where slug = 'qawfive'), '{"mode":"open"}'::jsonb, 'an explicit policy is kept');

-- ---- 3. email domains ----
select is(public.organisation_domain_owner('qawother.test'), 'QAW Other', 'a claimed domain names its org');
select is(public.organisation_domain_owner(' @QAW.test '), 'QAW Org', 'trimmed and lower-cased');
select is(public.organisation_domain_owner('free.test'), null, 'unclaimed: null');
select is((select array_agg(domain || ':' || verified) from public.org_email_domains(pg_temp.id('org'))), array['qaw.test:true'],
    'an org lists its own domains');
select throws_ok($q$ select * from public.org_email_domains(pg_temp.id('other')) $q$, '42501', null,
    'not another org''s');

-- ---- 4. audit rows keep the agent's name ----
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
delete from public.agents where slug = 'qawfive';
set local role authenticated;
select set_config('request.jwt.claim.sub', '4a000000-0000-4000-8000-00000000000a', true);
select is((select agent_name from public.org_audit_log(pg_temp.id('org'), 50) where action = 'agent.delete'), 'QAW Five',
    'a deleted agent''s row keeps its name');
select is((select count(*)::int from public.org_audit_log(pg_temp.id('org'), 50) where agent_id is not null and agent_name is null), 0,
    'every agent row has a name');

-- ---- 5/6. platform: reasons required, history paged ----
select set_config('request.jwt.claim.sub', '4a000000-0000-4000-8000-00000000000d', true);
select throws_ok($q$ select public.suspend_agent((select id from public.agents where slug = 'qawone'), '  ') $q$, 'P0001', 'reason_required',
    'suspending needs a reason');
select lives_ok($q$ select public.suspend_agent((select id from public.agents where slug = 'qawone'), 'Policy check') $q$,
    'with a reason it suspends');
select throws_ok($q$ select public.review_agent((select id from public.agents where slug = 'qawtwo'), false) $q$, 'P0001', 'reason_required',
    'rejecting needs a reason');
update public.platform_flags set value = value where false;
select is((select count(*)::int from public.platform_settings_history(5, now() - interval '100 years')), 0,
    'history before a long-gone time is empty (p_before pages)');
select finish();
rollback;
