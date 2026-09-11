-- Download history is private; only the Edge Function can record downloads.
-- Run against a disposable migrated database with: supabase test db
-- All synthetic fixtures and temporary grants are rolled back.
begin;
select plan(37);

insert into auth.users (id, email, email_confirmed_at)
values ('d0488000-0000-0000-0000-000000000001', 'download-rls@pgtap.test', now());
insert into public.plugins (id, plugin_id, display_name, author_name, published, visibility)
values ('d0488000-0000-0000-0000-000000000002', 'test.download.rls', 'Download RLS', 'tester', true, 'public');
insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256)
values ('d0488000-0000-0000-0000-000000000003', 'd0488000-0000-0000-0000-000000000002', '1.0.0', 'test/download.jar', repeat('a', 64));

select ok(not has_table_privilege('anon', 'public.plugin_downloads', 'SELECT'), 'anon has no direct SELECT privilege');

select ok(not has_table_privilege('anon', 'public.plugin_downloads', 'INSERT'), 'anon has no direct INSERT privilege');

select ok(not has_table_privilege('anon', 'public.plugin_downloads', 'UPDATE'), 'anon has no direct UPDATE privilege');

select ok(not has_table_privilege('anon', 'public.plugin_downloads', 'DELETE'), 'anon has no direct DELETE privilege');

select ok(not has_table_privilege('anon', 'public.plugin_downloads', 'TRUNCATE'), 'anon has no direct TRUNCATE privilege');

select ok(not has_table_privilege('anon', 'public.plugin_downloads', 'REFERENCES'), 'anon has no direct REFERENCES privilege');

select ok(not has_table_privilege('anon', 'public.plugin_downloads', 'TRIGGER'), 'anon has no direct TRIGGER privilege');

select ok(not has_function_privilege('anon', 'public.record_plugin_download(uuid,uuid,uuid,text)', 'EXECUTE'), 'anon cannot execute the identity-taking download RPC');

select ok(not has_table_privilege('authenticated', 'public.plugin_downloads', 'SELECT'), 'authenticated has no direct SELECT privilege');

select ok(not has_table_privilege('authenticated', 'public.plugin_downloads', 'INSERT'), 'authenticated has no direct INSERT privilege');

select ok(not has_table_privilege('authenticated', 'public.plugin_downloads', 'UPDATE'), 'authenticated has no direct UPDATE privilege');

select ok(not has_table_privilege('authenticated', 'public.plugin_downloads', 'DELETE'), 'authenticated has no direct DELETE privilege');

select ok(not has_table_privilege('authenticated', 'public.plugin_downloads', 'TRUNCATE'), 'authenticated has no direct TRUNCATE privilege');

select ok(not has_table_privilege('authenticated', 'public.plugin_downloads', 'REFERENCES'), 'authenticated has no direct REFERENCES privilege');

select ok(not has_table_privilege('authenticated', 'public.plugin_downloads', 'TRIGGER'), 'authenticated has no direct TRIGGER privilege');

select ok(not has_function_privilege('authenticated', 'public.record_plugin_download(uuid,uuid,uuid,text)', 'EXECUTE'), 'authenticated cannot execute the identity-taking download RPC');

select ok((select relrowsecurity from pg_class where oid = 'public.plugin_downloads'::regclass), 'download history retains RLS');

select ok(has_function_privilege('service_role', 'public.record_plugin_download(uuid,uuid,uuid,text)', 'EXECUTE'), 'service role retains download RPC execution');

set local role service_role;

select lives_ok($sql$select public.record_plugin_download('d0488000-0000-0000-0000-000000000002', 'd0488000-0000-0000-0000-000000000003', 'd0488000-0000-0000-0000-000000000001', 'synthetic-ip-hash')$sql$, 'service-role pipeline records a download');

select is((select count(*) from public.plugin_downloads where plugin_id = 'd0488000-0000-0000-0000-000000000002'), 1::bigint, 'service role can read the recorded synthetic row');

reset role;

set local role anon;

select throws_ok($sql$select * from public.plugin_downloads$sql$, '42501', 'permission denied for table plugin_downloads', 'anon cannot read raw download history');

select throws_ok($sql$insert into public.plugin_downloads (plugin_id, version_id, user_id) values ('d0488000-0000-0000-0000-000000000002', 'd0488000-0000-0000-0000-000000000003', 'd0488000-0000-0000-0000-000000000001')$sql$, '42501', 'permission denied for table plugin_downloads', 'anon cannot forge a direct download row');

select throws_ok($sql$select public.record_plugin_download('d0488000-0000-0000-0000-000000000002', 'd0488000-0000-0000-0000-000000000003', 'd0488000-0000-0000-0000-000000000001', 'synthetic-ip-hash')$sql$, '42501', 'permission denied for function record_plugin_download', 'anon cannot bypass the Edge Function through the RPC');

select is((select download_count from public.get_plugin_with_stats('test.download.rls')), 1::bigint, 'anon still receives aggregate plugin counts');

select is((select download_count from public.get_plugin_versions('test.download.rls') where version = '1.0.0'), 1::bigint, 'anon still receives aggregate version counts');

select is((select (elem ->> 'downloadCount')::bigint from public.search_plugins('test.download.rls') s, jsonb_array_elements(s.plugins) elem where elem ->> 'pluginId' = 'test.download.rls'), 1::bigint, 'anon search retains aggregate counts');

reset role;

set local role authenticated;

select throws_ok($sql$select * from public.plugin_downloads$sql$, '42501', 'permission denied for table plugin_downloads', 'authenticated cannot read raw download history');

select throws_ok($sql$insert into public.plugin_downloads (plugin_id, version_id, user_id) values ('d0488000-0000-0000-0000-000000000002', 'd0488000-0000-0000-0000-000000000003', 'd0488000-0000-0000-0000-000000000001')$sql$, '42501', 'permission denied for table plugin_downloads', 'authenticated cannot forge a direct download row');

select throws_ok($sql$select public.record_plugin_download('d0488000-0000-0000-0000-000000000002', 'd0488000-0000-0000-0000-000000000003', 'd0488000-0000-0000-0000-000000000001', 'synthetic-ip-hash')$sql$, '42501', 'permission denied for function record_plugin_download', 'authenticated cannot bypass the Edge Function through the RPC');

select is((select download_count from public.get_plugin_with_stats('test.download.rls')), 1::bigint, 'authenticated still receives aggregate plugin counts');

select is((select download_count from public.get_plugin_versions('test.download.rls') where version = '1.0.0'), 1::bigint, 'authenticated still receives aggregate version counts');

select is((select (elem ->> 'downloadCount')::bigint from public.search_plugins('test.download.rls') s, jsonb_array_elements(s.plugins) elem where elem ->> 'pluginId' = 'test.download.rls'), 1::bigint, 'authenticated search retains aggregate counts');

reset role;

select is((select count(*) from public.plugin_downloads where plugin_id = 'd0488000-0000-0000-0000-000000000002'), 1::bigint, 'denied client calls did not inflate the count');

-- Exercise RLS independently of the revoked table grants.
grant select, insert on public.plugin_downloads to anon, authenticated;

set local role anon;

select is((select count(*) from public.plugin_downloads), 0::bigint, 'anon RLS hides raw history even if table SELECT is regranted');

select throws_ok($sql$insert into public.plugin_downloads (plugin_id, version_id, user_id) values ('d0488000-0000-0000-0000-000000000002', 'd0488000-0000-0000-0000-000000000003', 'd0488000-0000-0000-0000-000000000001')$sql$, '42501', 'new row violates row-level security policy for table "plugin_downloads"', 'anon RLS blocks writes even if table INSERT is regranted');

reset role;

set local role authenticated;

select is((select count(*) from public.plugin_downloads), 0::bigint, 'authenticated RLS hides raw history even if table SELECT is regranted');

select throws_ok($sql$insert into public.plugin_downloads (plugin_id, version_id, user_id) values ('d0488000-0000-0000-0000-000000000002', 'd0488000-0000-0000-0000-000000000003', 'd0488000-0000-0000-0000-000000000001')$sql$, '42501', 'new row violates row-level security policy for table "plugin_downloads"', 'authenticated RLS blocks writes even if table INSERT is regranted');

reset role;

select * from finish();
rollback;
