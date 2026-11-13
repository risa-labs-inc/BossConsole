-- pgTAP tests for 20261113120000_platform_ai_caps: readers see caps, platform.ai_caps.manage changes them.
-- Run with: supabase test db
begin;
select plan(26);

insert into auth.users (id, email, email_confirmed_at) values
    ('dd000000-0000-4000-8000-00000000000a', 'admin@aicaps.test', now()),
    ('dd000000-0000-4000-8000-00000000000b', 'viewer@aicaps.test', now()),
    ('dd000000-0000-4000-8000-00000000000c', 'manager@aicaps.test', now()),
    ('dd000000-0000-4000-8000-00000000000d', 'someone@aicaps.test', now());
insert into public.user_roles (user_id, role_id) select 'dd000000-0000-4000-8000-00000000000a', id from public.roles where name = 'admin';
insert into public.user_roles (user_id, role_id) select u, r.id from public.roles r,
    unnest(array['dd000000-0000-4000-8000-00000000000b', 'dd000000-0000-4000-8000-00000000000c']::uuid[]) u where r.name = 'platform_viewer';

insert into public.boss_ai_connections(id, base_url, api_key_secret, api_type, enabled) values
    ('pgtap-caps', 'https://example.com/v1', 'BOSS_AI_TEST', 'openai_chat', true);
insert into public.boss_ai_models (id, display_name, connection_id, upstream_model, kind, context_length, max_output_tokens, published) values
    ('pgtap-caps-router', 'Auto', 'pgtap-caps', 'router', 'chat', 1024, 128, true),
    ('pgtap-caps-fast', 'Fast', 'pgtap-caps', 'fast', 'chat', 1024, 128, false),
    ('pgtap-caps-std', 'Std', 'pgtap-caps', 'std', 'chat', 1024, 128, false),
    ('pgtap-caps-strong', 'Strong', 'pgtap-caps', 'strong', 'chat', 1024, 128, false);
insert into public.boss_ai_routers (model_id, decider_connection_id, fast_model_id, standard_model_id, strong_model_id, monthly_usd_limit) values
    ('pgtap-caps-router', 'pgtap-caps', 'pgtap-caps-fast', 'pgtap-caps-std', 'pgtap-caps-strong', 100);
insert into public.boss_ai_requests (id, user_id, model_id, started_at, reserved_tokens, charged_tokens, settled, cost_usd, est_cost_usd, routed_from) values
    (gen_random_uuid(), 'dd000000-0000-4000-8000-00000000000d', 'pgtap-caps-std', now(), 1024, 100, true, 1.5, null, 'pgtap-caps-router'),
    (gen_random_uuid(), 'dd000000-0000-4000-8000-00000000000d', 'pgtap-caps-fast', now(), 1024, 100, false, null, 0.25, 'pgtap-caps-router'),
    (gen_random_uuid(), 'dd000000-0000-4000-8000-00000000000d', 'pgtap-caps-std', now() - interval '40 days', 1024, 100, true, 9, null, 'pgtap-caps-router'),
    (gen_random_uuid(), 'dd000000-0000-4000-8000-00000000000d', 'pgtap-caps-std', now(), 1024, 100, true, 7, null, null);

-- --- shape --------------------------------------------------------------------------------------
select is((select is_system from public.permissions where name = 'platform.ai_caps.manage'), true, 'the permission is a system permission');
select is((select count(*)::int from public.role_permissions rp join public.roles r on r.id = rp.role_id join public.permissions p on p.id = rp.permission_id
            where r.name = 'platform_ai_cap_manager' and p.name = 'platform.ai_caps.manage'), 1, 'the manager role carries it');
select is((select count(*)::int from public.role_hierarchy h join public.roles p on p.id = h.parent_role_id join public.roles c on c.id = h.child_role_id
            where p.name = 'admin' and c.name = 'platform_ai_cap_manager'), 1, 'the role sits under admin');
select ok(not has_function_privilege('anon', 'public.platform_ai_caps(uuid[])', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_set_ai_cap(uuid, text, numeric, text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_set_ai_cap_manager(uuid, boolean, text)', 'EXECUTE'), 'anon executes none of the RPCs');
select ok(not has_function_privilege('authenticated', 'public.is_platform_ai_cap_manager(uuid)', 'EXECUTE'), 'the helper is internal');

-- --- a viewer reads caps and spend but cannot change them --------------------------------------
set local role authenticated;
select set_config('request.jwt.claim.sub', 'dd000000-0000-4000-8000-00000000000b', true);
select is(public.platform_ai_cap_access(), false, 'a viewer cannot change caps');
select is(public.platform_ai_caps(array['dd000000-0000-4000-8000-00000000000d']::uuid[])->'can_edit', 'false'::jsonb, 'the read says so');
select is(public.platform_ai_caps(array['dd000000-0000-4000-8000-00000000000d']::uuid[])->'users'->'dd000000-0000-4000-8000-00000000000d'->'caps'->'pgtap-caps-router',
          '{"limit_usd": 100, "override_usd": null, "note": null, "updated_at": null, "spent_usd": 1.7500}'::jsonb,
          'default cap, and this month''s routed spend (cost, else estimate; not last month, not direct calls)');
select ok(public.platform_ai_caps(array[]::uuid[])->'routers' @> '[{"model_id": "pgtap-caps-router", "name": "Auto", "default_usd": 100}]'::jsonb, 'routers list their default');
select throws_ok($q$ select public.platform_set_ai_cap('dd000000-0000-4000-8000-00000000000d', 'pgtap-caps-router', 50, null) $q$, '42501', 'no_ai_cap_access', 'a viewer cannot set a cap');
select throws_ok($q$ select public.platform_set_ai_cap_manager('dd000000-0000-4000-8000-00000000000b', true, null) $q$, '42501', null, 'nor grant itself the permission');

-- --- an admin sets caps and grants the permission ----------------------------------------------
select set_config('request.jwt.claim.sub', 'dd000000-0000-4000-8000-00000000000a', true);
select is(public.platform_ai_cap_access(), true, 'an admin can change caps');
select is(public.platform_set_ai_cap('dd000000-0000-4000-8000-00000000000d', 'pgtap-caps-router', 250.555, ' heavy user '),
          '{"limit_usd": 250.56, "override_usd": 250.56}'::jsonb, 'an admin sets an override (cents)');
select is(public.platform_set_ai_cap('dd000000-0000-4000-8000-00000000000d', 'pgtap-caps-router', 250.56, 'heavy user')->>'limit_usd', '250.56', 'setting the same value again');
select throws_ok($q$ select public.platform_set_ai_cap('dd000000-0000-4000-8000-00000000000d', 'pgtap-caps-router', 0, null) $q$, '22023', 'cap_out_of_range', 'zero is refused');
select throws_ok($q$ select public.platform_set_ai_cap('dd000000-0000-4000-8000-00000000000d', 'nope', 10, null) $q$, 'P0002', 'router_not_found', 'an unknown router is refused');
select throws_ok($q$ select public.platform_set_ai_cap('dd000000-0000-4000-8000-0000000000ff', 'pgtap-caps-router', 10, null) $q$, 'P0002', 'user_not_found', 'an unknown person is refused');
select is(public.platform_set_ai_cap_manager('dd000000-0000-4000-8000-00000000000c', true, 'finance'), true, 'an admin grants cap management');
select is(public.platform_ai_caps(array['dd000000-0000-4000-8000-00000000000c', 'dd000000-0000-4000-8000-00000000000a', 'dd000000-0000-4000-8000-00000000000b']::uuid[])->'users'
            #> '{dd000000-0000-4000-8000-00000000000c,cap_manager}', 'true'::jsonb, 'the read says who manages caps');
reset role;
select is((select limit_usd from (select monthly_usd_limit as limit_usd, note from public.boss_ai_user_budgets
            where user_id = 'dd000000-0000-4000-8000-00000000000d') b), 250.56::numeric, 'the router reads the stored override');
select is((select count(*)::int from public.platform_audit_log where action = 'ai_cap.set' and target_user = 'dd000000-0000-4000-8000-00000000000d'), 1,
          'one audit row for the change, none for the repeat');
select is((select detail - 'default_usd' from public.platform_audit_log where action = 'ai_cap.set' and target_user = 'dd000000-0000-4000-8000-00000000000d'),
          '{"router": "pgtap-caps-router", "from_usd": null, "to_usd": 250.56, "note": "heavy user"}'::jsonb, 'audited with old, new and note');

-- --- a granted manager changes caps until revoked ------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claim.sub', 'dd000000-0000-4000-8000-00000000000c', true);
select is(public.platform_set_ai_cap('dd000000-0000-4000-8000-00000000000d', 'pgtap-caps-router', null, null)->'override_usd', 'null'::jsonb, 'a manager clears an override');
select is(public.platform_ai_caps(array['dd000000-0000-4000-8000-00000000000d']::uuid[]) #> '{users,dd000000-0000-4000-8000-00000000000d,caps,pgtap-caps-router,limit_usd}',
          '100'::jsonb, 'back to the router default');

select set_config('request.jwt.claim.sub', 'dd000000-0000-4000-8000-00000000000a', true);
select is(public.platform_set_ai_cap_manager('dd000000-0000-4000-8000-00000000000c', false, null), false, 'an admin revokes it');
select set_config('request.jwt.claim.sub', 'dd000000-0000-4000-8000-00000000000c', true);
select throws_ok($q$ select public.platform_set_ai_cap('dd000000-0000-4000-8000-00000000000d', 'pgtap-caps-router', 20, null) $q$, '42501', 'no_ai_cap_access', 'and they can no longer change caps');
reset role;

select * from finish();
rollback;
