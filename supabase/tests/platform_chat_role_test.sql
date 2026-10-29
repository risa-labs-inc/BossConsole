-- pgTAP tests for 20261030120000_platform_chat_role: people's Optimist chats need platform_chat_viewer (or admin).
-- Run with: supabase test db
begin;
select plan(22);

insert into auth.users (id, email, email_confirmed_at) values
    ('dc000000-0000-4000-8000-00000000000a', 'admin@chatrole.test', now()),
    ('dc000000-0000-4000-8000-00000000000b', 'viewer@chatrole.test', now()),
    ('dc000000-0000-4000-8000-00000000000c', 'chatviewer@chatrole.test', now()),
    ('dc000000-0000-4000-8000-00000000000d', 'chatonly@chatrole.test', now()),
    ('dc000000-0000-4000-8000-00000000000e', 'someone@chatrole.test', now());
insert into public.user_roles (user_id, role_id) select 'dc000000-0000-4000-8000-00000000000a', id from public.roles where name = 'admin';
insert into public.user_roles (user_id, role_id) select u, r.id from public.roles r,
    unnest(array['dc000000-0000-4000-8000-00000000000b', 'dc000000-0000-4000-8000-00000000000c']::uuid[]) u where r.name = 'platform_viewer';

-- --- shape --------------------------------------------------------------------------------------
select is((select is_system from public.roles where name = 'platform_chat_viewer'), true, 'platform_chat_viewer is a system role');
select is((select count(*)::int from public.role_hierarchy h join public.roles p on p.id = h.parent_role_id join public.roles c on c.id = h.child_role_id
            where p.name = 'admin' and c.name = 'platform_chat_viewer'), 1, 'it sits under admin');
select ok(not has_function_privilege('anon', 'public.platform_chat_access()', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_set_chat_viewer(uuid, boolean, text)', 'EXECUTE'), 'anon executes neither RPC');
select ok(not has_function_privilege('authenticated', 'public.is_platform_chat_reader(uuid)', 'EXECUTE'), 'the helper is internal');

-- --- an admin always has chats, and grants them ------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claim.sub', 'dc000000-0000-4000-8000-00000000000a', true);
select is(public.platform_chat_access(), true, 'an admin can read chats');
select lives_ok($q$ select public.platform_record_access('someone@chatrole.test', 'optimist.conversation') $q$, 'an admin opens a chat');
select is(public.platform_set_chat_viewer('dc000000-0000-4000-8000-00000000000c', true, 'QA'), true, 'an admin gives a viewer chat access');
select is(public.platform_set_chat_viewer('dc000000-0000-4000-8000-00000000000c', true, null), true, 'granting twice is a no-op');
select is(public.platform_set_chat_viewer('dc000000-0000-4000-8000-00000000000d', true, null), true, 'chat access without viewer can be granted');
select throws_ok($q$ select public.platform_set_chat_viewer('dc000000-0000-4000-8000-0000000000ff', true, null) $q$, 'P0002', 'user_not_found', 'an unknown person is refused');
select is((select jsonb_object_agg(p->>'email', (p->>'chat_access')::boolean) from jsonb_array_elements(public.platform_people_page(null, 200, 'chatrole.test')->'people') p
            where p->>'email' in ('admin@chatrole.test', 'viewer@chatrole.test', 'chatviewer@chatrole.test')),
          '{"admin@chatrole.test": true, "viewer@chatrole.test": false, "chatviewer@chatrole.test": true}'::jsonb, 'the people page says who can read chats');
reset role;
select is((select count(*)::int from public.platform_audit_log where action = 'platform_role.chat_grant' and target_user = 'dc000000-0000-4000-8000-00000000000c'), 1,
          'one audit row for the grant, none for the no-op');

-- --- a plain viewer sees the console but not chats ----------------------------------------------
set local role authenticated;
select set_config('request.jwt.claim.sub', 'dc000000-0000-4000-8000-00000000000b', true);
select is(public.platform_access(), 'viewer', 'still a viewer');
select is(public.platform_chat_access(), false, 'but cannot read chats');
select throws_ok($q$ select public.platform_record_access('someone@chatrole.test', 'optimist.activity') $q$, '42501', 'no_chat_access', 'opening tool calls is refused too');
select throws_ok($q$ select public.platform_set_chat_viewer('dc000000-0000-4000-8000-00000000000b', true, null) $q$, '42501', null, 'and cannot grant itself chat access');

-- --- viewer + chat role reads chats, audited ----------------------------------------------------
select set_config('request.jwt.claim.sub', 'dc000000-0000-4000-8000-00000000000c', true);
select is(public.platform_chat_access(), true, 'a chat viewer can read chats');
select lives_ok($q$ select public.platform_record_access('someone@chatrole.test', 'optimist.conversation') $q$, 'and opens one');

-- --- the chat role alone opens nothing (the console needs viewer or admin) ----------------------
select set_config('request.jwt.claim.sub', 'dc000000-0000-4000-8000-00000000000d', true);
select is(public.platform_access(), null, 'chat role alone gives no platform access');
select throws_ok($q$ select public.platform_record_access('someone@chatrole.test', 'optimist.conversation') $q$, '42501', null, 'so no chat can be opened through it');
reset role;
select is((select row(actor, target_user)::text from public.platform_audit_log where action = 'view.optimist.conversation' order by id desc limit 1),
          '(dc000000-0000-4000-8000-00000000000c,dc000000-0000-4000-8000-00000000000e)', 'the chat viewer''s read is audited with actor and target');

-- --- revoke -------------------------------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claim.sub', 'dc000000-0000-4000-8000-00000000000a', true);
select is(public.platform_set_chat_viewer('dc000000-0000-4000-8000-00000000000c', false, 'rotation over'), false, 'an admin removes chat access');
reset role;

select * from finish();
rollback;
