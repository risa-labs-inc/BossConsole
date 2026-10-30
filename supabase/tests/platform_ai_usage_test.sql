-- pgTAP tests for 20261029120000_platform_ai_usage: per-person BOSS AI usage for platform readers.
-- Run with: supabase test db
begin;
select plan(13);

insert into auth.users (id, email, email_confirmed_at) values
    ('da000000-0000-4000-8000-00000000000a', 'admin@aiusage.test', now()),
    ('da000000-0000-4000-8000-00000000000b', 'viewer@aiusage.test', now()),
    ('da000000-0000-4000-8000-00000000000c', 'plain@aiusage.test', now()),
    ('da000000-0000-4000-8000-00000000000d', 'user@aiusage.test', now());
insert into public.user_roles (user_id, role_id) select 'da000000-0000-4000-8000-00000000000a', id from public.roles where name = 'admin';
insert into public.user_roles (user_id, role_id) select 'da000000-0000-4000-8000-00000000000b', id from public.roles where name = 'platform_viewer';

insert into public.boss_ai_connections(id, base_url, api_key_secret, api_type, enabled) values
    ('pgtap-usage', 'https://example.com/v1', 'BOSS_AI_TEST', 'openai_chat', true);
insert into public.boss_ai_models (id, display_name, connection_id, upstream_model, kind, context_length, max_output_tokens, published) values
    ('pgtap-usage-chat', 'Chat', 'pgtap-usage', 'chat', 'chat', 1024, 128, true),
    ('pgtap-usage-stt', 'STT', 'pgtap-usage', 'stt', 'transcription', 1024, 120, true);

-- UTC month starts, as the function computes them.
create temp table m as select date_trunc('month', now() at time zone 'UTC') at time zone 'UTC' as this_month,
    date_trunc('month', (now() at time zone 'UTC') - interval '1 month') at time zone 'UTC' as last_month;
grant select on m to authenticated;
insert into public.boss_ai_requests (id, user_id, model_id, started_at, reserved_tokens, charged_tokens, settled, cost_usd, est_cost_usd)
select gen_random_uuid(), 'da000000-0000-4000-8000-00000000000d', v.model, v.at, 1024, v.tokens, true, v.cost, v.est
  from m, lateral (values
    ('pgtap-usage-chat', m.this_month + interval '1 hour', 1000::bigint, 0.25::numeric, null::numeric),
    ('pgtap-usage-chat', m.this_month + interval '2 hours', 500, null, 0.10),          -- estimate only
    ('pgtap-usage-stt', m.this_month + interval '3 hours', 30, 0.01, null),            -- seconds, not tokens
    ('pgtap-usage-chat', m.this_month - interval '1 microsecond', 200, null, null),     -- last month, unpriced
    ('pgtap-usage-chat', m.last_month - interval '1 day', 7, null, null)                -- older: all time only
  ) as v(model, at, tokens, cost, est);

-- --- shape --------------------------------------------------------------------------------------
select ok(not has_function_privilege('anon', 'public.platform_ai_usage(uuid[])', 'EXECUTE'), 'anon cannot call it');

-- --- a plain user reads nothing -----------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claim.sub', 'da000000-0000-4000-8000-00000000000c', true);
select throws_ok($q$ select public.platform_ai_usage(array['da000000-0000-4000-8000-00000000000d']::uuid[]) $q$, '42501', null, 'a plain user is refused');

-- --- a viewer reads the periods -----------------------------------------------------------------
select set_config('request.jwt.claim.sub', 'da000000-0000-4000-8000-00000000000b', true);
create temp table u as select public.platform_ai_usage(array['da000000-0000-4000-8000-00000000000d', 'da000000-0000-4000-8000-00000000000c']::uuid[]) as j;
select is((select (j->'users'->'da000000-0000-4000-8000-00000000000d'->'month'->>'requests')::int from u), 3, 'this month: every call, audio included');
select is((select (j->'users'->'da000000-0000-4000-8000-00000000000d'->'month'->>'tokens')::bigint from u), 1500::bigint, 'this month: chat tokens only (audio seconds left out)');
select is((select (j->'users'->'da000000-0000-4000-8000-00000000000d'->'month'->>'cost_usd')::numeric from u), 0.36, 'this month: cost, falling back to the estimate');
select is((select (j->'users'->'da000000-0000-4000-8000-00000000000d'->'month'->>'unpriced')::int from u), 0, 'this month: nothing unpriced');
select is((select (j->'users'->'da000000-0000-4000-8000-00000000000d'->'last_month'->>'tokens')::bigint from u), 200::bigint, 'last month ends at the UTC month boundary');
select is((select (j->'users'->'da000000-0000-4000-8000-00000000000d'->'last_month'->>'unpriced')::int from u), 1, 'last month: the unpriced call is counted');
select is((select (j->'users'->'da000000-0000-4000-8000-00000000000d'->'all_time'->>'tokens')::bigint from u), 1707::bigint, 'all time: every chat token');
select is((select (j->'users'->'da000000-0000-4000-8000-00000000000d'->'all_time'->>'requests')::int from u), 5, 'all time: every call');
select ok((select not (j->'users' ? 'da000000-0000-4000-8000-00000000000c') from u), 'someone with no calls is absent');
select is((select (j->>'month_start')::timestamptz from u), (select this_month from m), 'the month start is returned for the console');

-- --- an admin reads too; a huge batch is refused ------------------------------------------------
select set_config('request.jwt.claim.sub', 'da000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select public.platform_ai_usage(array_fill('da000000-0000-4000-8000-00000000000d'::uuid, array[501])) $q$, '22023', 'too_many_users', 'more than 500 people at once is refused');
reset role;

select * from finish();
rollback;
