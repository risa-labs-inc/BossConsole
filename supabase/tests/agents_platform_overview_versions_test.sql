-- pgTAP tests for 20261019130000: platform_agents_overview().running_versions.
-- Run with: supabase test db
begin;
select plan(8);

insert into auth.users (id, email, email_confirmed_at) values
    ('c4000000-0000-4000-8000-00000000000a', 'owner@ov.test', now()),
    ('c4000000-0000-4000-8000-00000000000b', 'u1@ov.test', now()),
    ('c4000000-0000-4000-8000-00000000000c', 'u2@ov.test', now()),
    ('c4000000-0000-4000-8000-00000000000d', 'platform@ov.test', now()),
    ('c4000000-0000-4000-8000-00000000000e', 'u3@ov.test', now()),
    ('c4000000-0000-4000-8000-00000000000f', 'u4@ov.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c4000000-0000-4000-8000-00000000000d', id from public.roles where name = 'admin';

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'ovorg', p_name => 'Ov Org', p_description => null,
    p_owner_id => 'c4000000-0000-4000-8000-00000000000a', p_domain => null, p_visibility => 'private', p_join_policy => 'invite_only');
insert into public.agents (slug, org_id, name, plugin_id, created_by)
values ('ovroll', (select id from public.organisations where slug = 'ovorg'), 'Rolled', 'ai.rever.boss.plugin.dynamic.agent.ovroll', 'c4000000-0000-4000-8000-00000000000a'),
       ('ovnone', (select id from public.organisations where slug = 'ovorg'), 'None', 'ai.rever.boss.plugin.dynamic.agent.ovnone', 'c4000000-0000-4000-8000-00000000000a');
update public.agents set status = 'live', live_version = '1.0.2' where slug = 'ovroll';
create temp table ov (id uuid);
grant all on ov to authenticated;
insert into ov select id from public.agents where slug = 'ovroll';
-- Two pods rolled back to 1.0.1, one on the live 1.0.2, one failed with nothing running, one removed on 0.9.0.
insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state, running_version) values
    ('c4000000-0000-4000-8000-00000000000a', (select id from ov), 'o-a', 8901, 'agents/ovroll', 'running', '1.0.1'),
    ('c4000000-0000-4000-8000-00000000000b', (select id from ov), 'o-b', 8902, 'agents/ovroll', 'running', '1.0.1'),
    ('c4000000-0000-4000-8000-00000000000c', (select id from ov), 'o-c', 8903, 'agents/ovroll', 'running', '1.0.2'),
    ('c4000000-0000-4000-8000-00000000000e', (select id from ov), 'o-e', 8904, 'agents/ovroll', 'failed', null),
    ('c4000000-0000-4000-8000-00000000000f', (select id from ov), 'o-f', 8905, 'agents/ovroll', 'removed', '0.9.0');

set local role authenticated;
select set_config('request.jwt.claim.sub', 'c4000000-0000-4000-8000-00000000000d', true);
select is((select running_versions from public.platform_agents_overview() where slug = 'ovroll'),
          '[{"version": "1.0.1", "count": 2}, {"version": "1.0.2", "count": 1}]'::jsonb,
          'running_versions groups what the pods run, most common first; removed and none are left out');
select is((select live_version from public.platform_agents_overview() where slug = 'ovroll'), '1.0.2', 'live_version is still the published one');
select is((select running_versions from public.platform_agents_overview() where slug = 'ovnone'), '[]'::jsonb, 'an agent with no installs has an empty list, not null');
select is((select row(installs_total, installs_running, installs_failed)::text from public.platform_agents_overview() where slug = 'ovroll'),
          '(4,3,1)', 'the existing counts are unchanged');

select set_config('request.jwt.claim.sub', 'c4000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select * from public.platform_agents_overview() $q$, '42501', null, 'an org admin still cannot read the overview');
select set_config('request.jwt.claim.sub', 'c4000000-0000-4000-8000-00000000000b', true);
select throws_ok($q$ select * from public.platform_agents_overview() $q$, '42501', null, 'a plain user still cannot read the overview');
reset role;
select ok(not has_function_privilege('anon', 'public.platform_agents_overview()', 'EXECUTE'), 'anon cannot execute the overview');
select ok(has_function_privilege('authenticated', 'public.platform_agents_overview()', 'EXECUTE'), 'authenticated keeps EXECUTE (the function gates platform admins)');

select * from finish();
rollback;
