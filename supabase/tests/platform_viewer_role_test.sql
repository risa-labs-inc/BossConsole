-- pgTAP tests for 20261028120000_platform_viewer_role: the read-only platform viewer.
-- Run with: supabase test db
begin;
select plan(36);

insert into auth.users (id, email, email_confirmed_at) values
    ('d6000000-0000-4000-8000-00000000000a', 'admin@viewer.test', now()),
    ('d6000000-0000-4000-8000-00000000000b', 'viewer@viewer.test', now()),
    ('d6000000-0000-4000-8000-00000000000c', 'plain@viewer.test', now()),
    ('d6000000-0000-4000-8000-00000000000d', 'owner@viewer.test', now());
insert into public.user_roles (user_id, role_id)
    select 'd6000000-0000-4000-8000-00000000000a', id from public.roles where name = 'admin';
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'viewerorg', p_name => 'Viewer Org', p_description => null,
    p_owner_id => 'd6000000-0000-4000-8000-00000000000d', p_domain => null, p_visibility => 'private', p_join_policy => 'invite_only');
insert into public.agents (slug, org_id, name, plugin_id, created_by, status)
values ('viewbot', (select id from public.organisations where slug = 'viewerorg'), 'View Bot', 'ai.rever.boss.plugin.dynamic.agent.viewbot',
        'd6000000-0000-4000-8000-00000000000d', 'pending_review');
insert into public.plugins (author_id, author_name, plugin_id, display_name, published, visibility, org_id)
values ('d6000000-0000-4000-8000-00000000000d', 'Owner', 'ai.rever.boss.plugin.dynamic.agent.viewbot', 'View Bot', true, 'org',
        (select id from public.organisations where slug = 'viewerorg'));
select set_config('request.jwt.claims', '', true);
-- Ids read before switching to authenticated (RLS hides the agent from non-members).
create temp table ids as select (select id from public.agents where slug = 'viewbot') as agent,
    (select id from public.plugins where plugin_id = 'ai.rever.boss.plugin.dynamic.agent.viewbot') as plugin,
    (select value from public.platform_flags where key = 'agent_creation_open') as flag;
grant select on ids to authenticated;

-- --- shape --------------------------------------------------------------------------------------
select is((select is_system from public.roles where name = 'platform_viewer'), true, 'platform_viewer is a system role');
select ok(not has_function_privilege('anon', 'public.platform_access()', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_set_viewer(uuid, boolean, text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_record_access(text, text)', 'EXECUTE'), 'anon executes none of the new RPCs');
select ok(not has_function_privilege('authenticated', 'public.is_platform_reader(uuid)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.require_platform_reader()', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.platform_role_of(uuid)', 'EXECUTE'), 'the helpers are internal');

-- --- an admin grants the role -------------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claim.sub', 'd6000000-0000-4000-8000-00000000000a', true);
select is(public.platform_access(), 'admin', 'an admin has admin access');
select is(public.platform_set_viewer('d6000000-0000-4000-8000-00000000000b', true, 'QA'), 'viewer', 'an admin makes someone a viewer');
select is(public.platform_set_viewer('d6000000-0000-4000-8000-00000000000b', true, null), 'viewer', 'granting twice is a no-op');
select throws_ok($q$ select public.platform_set_viewer('d6000000-0000-4000-8000-0000000000ff', true, null) $q$, 'P0002', 'user_not_found', 'an unknown person is refused');
select throws_ok($q$ select public.platform_set_viewer('d6000000-0000-4000-8000-00000000000c', true, repeat('x', 501)) $q$, '22023', 'note_too_long', 'a long note is refused');
select is((select (p->>'platform_role') from jsonb_array_elements(public.platform_people_page(null, 200, 'viewer.test')->'people') p
            where p->>'email' = 'viewer@viewer.test'), 'viewer', 'the people page carries the platform role');
reset role;
select is((select count(*)::int from public.platform_audit_log where action = 'platform_role.viewer_grant'
             and target_user = 'd6000000-0000-4000-8000-00000000000b' and actor = 'd6000000-0000-4000-8000-00000000000a'), 1,
          'one audit row for the grant, none for the no-op');

-- --- the viewer reads everything ----------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claim.sub', 'd6000000-0000-4000-8000-00000000000b', true);
select is(public.platform_access(), 'viewer', 'the viewer has viewer access');
select ok(not public.is_platform_admin(), 'a viewer is not a platform admin (chat/server restart stays closed)');
select lives_ok($q$ select * from public.list_pending_agents() $q$, 'list_pending_agents');
select is((select count(*)::int from public.list_pending_agents() where slug = 'viewbot'), 1, 'and sees the pending agent');
select lives_ok($q$ select * from public.platform_agents_overview() $q$, 'platform_agents_overview');
select lives_ok($q$ select public.platform_agent_detail((select agent from ids)) $q$, 'platform_agent_detail');
select lives_ok($q$ select * from public.platform_settings_history(20, null) $q$, 'platform_settings_history');
select lives_ok($q$ select * from public.platform_web_pod_requests(true, 200) $q$, 'platform_web_pod_requests');
select lives_ok($q$ select public.platform_web_pod_request_count() $q$, 'platform_web_pod_request_count');
select lives_ok($q$ select public.platform_people_page(null, 200, null) $q$, 'platform_people_page');
select lives_ok($q$ select public.platform_inventory() $q$, 'platform_inventory');
select lives_ok($q$ select * from public.platform_pod_tier_history('d6000000-0000-4000-8000-00000000000c', 10) $q$, 'platform_pod_tier_history');
select is((public.list_organisation_requests(null, 50, 0)->>'is_reviewer')::boolean, true, 'the viewer reads the org request queue');

-- --- ...and changes nothing ---------------------------------------------------------------------
select throws_ok($q$ select public.platform_set_pod_tier('d6000000-0000-4000-8000-00000000000c', 'premium', null) $q$, '42501', null, 'no tier change');
select throws_ok($q$ select public.platform_set_viewer('d6000000-0000-4000-8000-00000000000c', true, null) $q$, '42501', null, 'no role grants');
select throws_ok($q$ select public.suspend_agent((select agent from ids), 'x') $q$, '42501', null, 'no suspend');
select throws_ok($q$ select public.review_agent((select agent from ids), true, null) $q$, '42501', null, 'no review decision');
update public.platform_flags set value = to_jsonb(not coalesce((value)::text::boolean, false)) where key = 'agent_creation_open';
select throws_ok($q$ select public.platform_record_access('Plain@Viewer.test', 'optimist.conversation') $q$, '42501', 'no_chat_access', 'a viewer cannot open people''s chats (that needs platform_chat_viewer)');
select throws_ok($q$ select public.platform_record_access('plain@viewer.test', 'something.else') $q$, '22023', 'bad_what', 'only known views are recorded');
reset role;
select is((select value from public.platform_flags where key = 'agent_creation_open'), (select flag from ids), 'no platform flag writes');
select ok(public.user_can_view_plugin('d6000000-0000-4000-8000-00000000000b', (select plugin from ids)),
          'the viewer can read an organisation''s agent plugin (review page)');
select ok(not public.user_can_view_plugin('d6000000-0000-4000-8000-00000000000c', (select plugin from ids)), 'a plain user still cannot');
select is((select count(*)::int from public.platform_audit_log where action = 'view.optimist.conversation' and actor = 'd6000000-0000-4000-8000-00000000000b'), 0,
          'the refused chat view leaves no read behind');

-- --- a plain user reads nothing -----------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claim.sub', 'd6000000-0000-4000-8000-00000000000c', true);
select is(public.platform_access(), null, 'a plain user has no platform access');
select throws_ok($q$ select public.platform_people_page(null, 200, null) $q$, '42501', null, 'and cannot read people');
select throws_ok($q$ select public.platform_record_access('viewer@viewer.test', 'optimist.activity') $q$, '42501', null, 'or record a view');
reset role;

select * from finish();
rollback;
