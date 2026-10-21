-- pgTAP tests for 20261021120000_pod_tiers: pod_tiers, history, platform audit, console and bot RPCs.
-- Run with: supabase test db
begin;
select plan(49);

insert into auth.users (id, email, email_confirmed_at) values
    ('d5000000-0000-4000-8000-00000000000a', 'platform@tier.test', now()),
    ('d5000000-0000-4000-8000-00000000000b', 'alice@tier.test', now()),
    ('d5000000-0000-4000-8000-00000000000c', 'bob@tier.test', now()),
    ('d5000000-0000-4000-8000-00000000000d', 'bot@tier.test', now()),
    ('d5000000-0000-4000-8000-00000000000e', 'owner@tier.test', now()),
    ('d5000000-0000-4000-8000-00000000000f', 'gone@tier.test', now());
insert into public.user_roles (user_id, role_id)
    select 'd5000000-0000-4000-8000-00000000000a', id from public.roles where name = 'admin';
insert into public.agents_service_accounts (user_id, purpose) values ('d5000000-0000-4000-8000-00000000000d', 'provisioner');
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'tierorg', p_name => 'Tier Org', p_description => null,
    p_owner_id => 'd5000000-0000-4000-8000-00000000000e', p_domain => null, p_visibility => 'private', p_join_policy => 'invite_only');
insert into public.agents (slug, org_id, name, plugin_id, created_by)
values ('tierbot', (select id from public.organisations where slug = 'tierorg'), 'Tier Bot', 'ai.rever.boss.plugin.dynamic.agent.tierbot', 'd5000000-0000-4000-8000-00000000000e');
insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state, running_version) values
    ('d5000000-0000-4000-8000-00000000000c', (select id from public.agents where slug = 'tierbot'), 'pod-bob', 8810, 'agents/tierbot', 'running', '1.0.0');
select set_config('request.jwt.claims', '', true);

create temp table t0 (at timestamptz);
grant all on t0 to authenticated;
insert into t0 values (clock_timestamp());

-- --- shape --------------------------------------------------------------------------------------
select ok((select relrowsecurity from pg_class where oid = 'public.pod_tiers'::regclass), 'pod_tiers has RLS');
select ok((select relrowsecurity from pg_class where oid = 'public.pod_tier_history'::regclass), 'pod_tier_history has RLS');
select ok((select relrowsecurity from pg_class where oid = 'public.platform_audit_log'::regclass), 'platform_audit_log has RLS');
select ok(not has_function_privilege('anon', 'public.platform_set_pod_tier(uuid, text, text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.pod_tier_assignments(timestamptz)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.record_pod_tier_applied(jsonb)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_people_pods(text, int)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.is_platform_admin()', 'EXECUTE'), 'anon executes none of the tier functions');
select ok(not has_table_privilege('anon', 'public.pod_tiers', 'SELECT'), 'anon has no table access');

-- --- platform admin writes ----------------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claim.sub', 'd5000000-0000-4000-8000-00000000000a', true);
select ok(public.is_platform_admin(), 'is_platform_admin is true for a platform admin');
select lives_ok($q$ select public.platform_set_pod_tier('d5000000-0000-4000-8000-00000000000b', 'premium', 'demo week') $q$, 'a platform admin assigns premium');
select is((select tier from public.pod_tiers where user_id = 'd5000000-0000-4000-8000-00000000000b'), 'premium', 'the row holds the tier');
select is((select assigned_by from public.pod_tiers where user_id = 'd5000000-0000-4000-8000-00000000000b'),
          'd5000000-0000-4000-8000-00000000000a'::uuid, 'assigned_by is the caller');
select lives_ok($q$ select public.platform_set_pod_tier('d5000000-0000-4000-8000-00000000000b', 'large', null) $q$, 'and changes it');
select lives_ok($q$ insert into public.pod_tiers (user_id, tier, assigned_by, note)
                    values ('d5000000-0000-4000-8000-00000000000c', 'premium', 'd5000000-0000-4000-8000-00000000000b', ' ') $q$,
                'a platform admin may write the table directly too (RLS)');
select is((select row(assigned_by, note)::text from public.pod_tiers where user_id = 'd5000000-0000-4000-8000-00000000000c'),
          '(d5000000-0000-4000-8000-00000000000a,)', 'a direct write cannot forge assigned_by; a blank note is null');
select throws_ok($q$ select public.platform_set_pod_tier('d5000000-0000-4000-8000-00000000000b', 'Premium!', null) $q$, '23514', 'tier_invalid', 'a malformed tier name is refused');
select throws_ok($q$ select public.platform_set_pod_tier('d5000000-0000-4000-8000-00000000000b', 'premium', repeat('x', 501)) $q$, '23514', 'note_too_long', 'a long note is refused');
select throws_ok($q$ select public.platform_set_pod_tier('d5000000-0000-4000-8000-0000000000ff', 'premium', null) $q$, 'P0001', 'user_not_found', 'an unknown person is refused');

select is((select array_agg(event || ':' || coalesce(tier, '-') || ':' || coalesce(previous_tier, '-') || ':' || coalesce(note, '-') order by id)
             from public.pod_tier_history where user_id = 'd5000000-0000-4000-8000-00000000000b'),
          array['assign:premium:-:demo week', 'assign:large:premium:-'], 'history keeps every assignment with the previous tier and note');
select is((select count(*)::int from public.platform_audit_log where action = 'pod_tier.assign'
             and target_user in ('d5000000-0000-4000-8000-00000000000b', 'd5000000-0000-4000-8000-00000000000c')
             and actor = 'd5000000-0000-4000-8000-00000000000a'), 3, 'each assignment is in the platform audit log with its actor');

-- --- the console's reads ------------------------------------------------------------------------
select is((select array_agg(email order by email) from public.platform_people_pods('tier.test', 50)),
          array['alice@tier.test', 'bob@tier.test', 'gone@tier.test', 'owner@tier.test', 'platform@tier.test'],
          'search by email lists accounts, never the provisioner bot');
select is((select tier from public.platform_people_pods('ALICE@TIER', 10)), 'large', 'search is case-insensitive and carries the tier');
select is((select installs->0->>'instance_id' from public.platform_people_pods('bob@tier', 10)), 'pod-bob', 'installs carry the pod (instance id)');
select ok((select not (installs->0 ? 'data_dir') and not (installs->0 ? 'detail') from public.platform_people_pods('bob@tier', 10)), 'installs never carry data_dir or detail');
select ok((select bool_and(email in ('alice@tier.test', 'bob@tier.test')) from public.platform_people_pods(null, 200) where email like '%@tier.test'),
          'without a query: people with a tier or an install only');
select is((select array_agg(event order by at desc, id desc) from public.platform_pod_tier_history('d5000000-0000-4000-8000-00000000000b', 10)),
          array['assign', 'assign'], 'history for one person, newest first');
select is((select actor_email from public.platform_pod_tier_history('d5000000-0000-4000-8000-00000000000b', 1)), 'platform@tier.test', 'history names the actor');

-- --- everyone else ------------------------------------------------------------------------------
select set_config('request.jwt.claim.sub', 'd5000000-0000-4000-8000-00000000000b', true);
select ok(not public.is_platform_admin(), 'is_platform_admin is false for a plain user');
select is((select count(*)::int from public.pod_tiers), 0, 'a plain user sees no tiers, not even their own');
select is((select count(*)::int from public.pod_tier_history), 0, 'nor any history');
select is((select count(*)::int from public.platform_audit_log), 0, 'nor the platform audit log');
select throws_ok($q$ select public.platform_set_pod_tier('d5000000-0000-4000-8000-00000000000b', 'premium', null) $q$, '42501', null, 'a plain user cannot assign a tier, even to themselves');
select throws_ok($q$ insert into public.pod_tiers (user_id, tier) values ('d5000000-0000-4000-8000-00000000000b', 'premium') $q$, '42501', null, 'nor write the table directly');
select throws_ok($q$ select * from public.platform_people_pods(null, 10) $q$, '42501', null, 'nor list people and pods');
select throws_ok($q$ select * from public.pod_tier_assignments(null) $q$, '42501', null, 'nor read the bot''s assignments');
select set_config('request.jwt.claim.sub', 'd5000000-0000-4000-8000-00000000000e', true);
select throws_ok($q$ select public.platform_clear_pod_tier('d5000000-0000-4000-8000-00000000000b', null) $q$, '42501', null, 'an org owner is not a platform admin');

-- --- the provisioner bot: reads only ------------------------------------------------------------
select set_config('request.jwt.claim.sub', 'd5000000-0000-4000-8000-00000000000d', true);
select is((select array_agg(tier order by user_id) from public.pod_tier_assignments(null)), array['large', 'premium'], 'the bot reads every current assignment');
select is((select count(*)::int from public.pod_tiers), 0, 'the bot has no table access');
select throws_ok($q$ select public.platform_set_pod_tier('d5000000-0000-4000-8000-00000000000b', 'premium', null) $q$, '42501', null, 'the bot cannot assign');
select throws_ok($q$ delete from public.pod_tier_history $q$, '42501', null, 'the bot cannot touch the history');
select is(public.record_pod_tier_applied('[{"user_id":"d5000000-0000-4000-8000-00000000000b","tier":"large","method":"in_place"}]'::jsonb), 1, 'the bot records what it applied');
select throws_ok($q$ select public.record_pod_tier_applied('[{"user_id":"d5000000-0000-4000-8000-00000000000b","tier":"large","method":"teleport"}]'::jsonb) $q$,
                 'P0001', 'invalid_applied', 'an unknown method is refused');
select throws_ok($q$ select public.record_pod_tier_applied('{"user_id":"x"}'::jsonb) $q$, 'P0001', 'invalid_applied', 'a non-array is refused');

-- --- clear and the incremental read --------------------------------------------------------------
select set_config('request.jwt.claim.sub', 'd5000000-0000-4000-8000-00000000000a', true);
select ok(public.platform_clear_pod_tier('d5000000-0000-4000-8000-00000000000c', 'trial over'), 'a platform admin clears a tier');
select is((select row(event, previous_tier, note)::text from public.pod_tier_history
            where user_id = 'd5000000-0000-4000-8000-00000000000c' order by id desc limit 1), '(clear,premium,"trial over")', 'the clear is in the history with its note');
select set_config('request.jwt.claim.sub', 'd5000000-0000-4000-8000-00000000000d', true);
select is((select array_agg(coalesce(tier, 'standard') order by changed_at, user_id) from public.pod_tier_assignments((select at from t0))),
          array['large', 'standard'], 'since a time: everyone changed after it, a cleared person with tier NULL');
select is((select count(*)::int from public.pod_tier_assignments(clock_timestamp())), 0, 'nothing changed after now');

-- --- history and audit are append-only, and outlive the person ---------------------------------
reset role;
select set_config('request.jwt.claims', '', true);
select is((select row(event, tier, previous_tier, method)::text from public.pod_tier_history
            where user_id = 'd5000000-0000-4000-8000-00000000000b' and event = 'applied'), '(applied,large,,in_place)', 'the applied row is the billing record');
select throws_ok($q$ update public.pod_tier_history set tier = 'standard' $q$, '42501', null, 'history cannot be changed, even by the owner');
select throws_ok($q$ delete from public.platform_audit_log $q$, '42501', null, 'nor the platform audit log');
delete from auth.users where id = 'd5000000-0000-4000-8000-00000000000b';
select is((select count(*)::int from public.pod_tier_history where user_id = 'd5000000-0000-4000-8000-00000000000b' and event = 'clear'), 1,
          'deleting the account clears the tier (cascade), recorded');
select ok((select count(*) from public.pod_tier_history where user_id = 'd5000000-0000-4000-8000-00000000000b') = 4, 'and the whole trail is kept');

select * from finish();
rollback;
