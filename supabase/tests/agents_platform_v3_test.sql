-- pgTAP tests for agents platform v3 (20261019120000): brand validation, brand bucket, custom
-- domains, activity, org console RPCs and the audit log.
-- Run with: supabase test db
begin;
select plan(182);

insert into auth.users (id, email, email_confirmed_at) values
    ('b3000000-0000-4000-8000-00000000000a', 'owner-a@v3.test', now()),
    ('b3000000-0000-4000-8000-00000000000b', 'owner-b@v3.test', now()),
    ('b3000000-0000-4000-8000-00000000000c', 'user-c@v3.test', now()),
    ('b3000000-0000-4000-8000-00000000000d', 'platform@v3.test', now()),
    ('b3000000-0000-4000-8000-00000000000e', 'user-e@v3.test', now()),
    ('b3000000-0000-4000-8000-00000000000f', 'bot@v3.test', now());
insert into public.user_roles (user_id, role_id)
    select 'b3000000-0000-4000-8000-00000000000d', id from public.roles where name = 'admin';
insert into public.agents_service_accounts (user_id, purpose) values ('b3000000-0000-4000-8000-00000000000f', 'provisioner');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'vthreea', p_name => 'V3 A', p_description => null,
    p_owner_id => 'b3000000-0000-4000-8000-00000000000a', p_domain => null, p_visibility => 'private', p_join_policy => 'invite_only');
select public.create_organisation_internal(p_slug => 'vthreeb', p_name => 'V3 B', p_description => null,
    p_owner_id => 'b3000000-0000-4000-8000-00000000000b', p_domain => null, p_visibility => 'private', p_join_policy => 'invite_only');
update public.platform_flags set value = '"https://proj.supabase.test/storage/v1/object/public/agent-brand/"'::jsonb
    where key = 'agent_brand_base_url';
update public.platform_flags set value = '"bossconsole.ai"'::jsonb where key = 'agents_base_domain';

create temp table ids (k text primary key, id uuid) on commit drop;
grant all on ids to authenticated, anon, service_role;
insert into ids select 'orga', id from public.organisations where slug = 'vthreea';
insert into ids select 'orgb', id from public.organisations where slug = 'vthreeb';
insert into public.agents (slug, org_id, name, plugin_id, created_by)
    values ('vacme', (select id from ids where k = 'orga'), 'Acme', 'ai.rever.boss.plugin.dynamic.agent.vacme', 'b3000000-0000-4000-8000-00000000000a'),
           ('vbeta', (select id from ids where k = 'orgb'), 'Beta', 'ai.rever.boss.plugin.dynamic.agent.vbeta', 'b3000000-0000-4000-8000-00000000000b');
insert into ids select 'acme', id from public.agents where slug = 'vacme';
insert into ids select 'beta', id from public.agents where slug = 'vbeta';

-- Brand URL helper: <base><org>/<agent|org>/<file>
create function pg_temp.u(p_org text, p_folder text, p_file text) returns text language sql as $$
    select 'https://proj.supabase.test/storage/v1/object/public/agent-brand/'
        || (select id::text from ids where k = p_org) || '/'
        || coalesce((select id::text from ids where k = p_folder), p_folder) || '/' || p_file;
$$;
create function pg_temp.full_brand() returns jsonb language sql as $$
    select jsonb_build_object(
        'name', 'Acme', 'short_name', 'Acme', 'accent', '#1A2b3c', 'accent_dark', '#000000',
        'logo', jsonb_build_object('url', pg_temp.u('orga', 'acme', 'logo-512.png'), 'shape', 'rounded', 'background', null),
        'favicon_url', pg_temp.u('orga', 'acme', 'favicon-32.png'),
        'apple_touch_url', pg_temp.u('orga', 'org', 'apple-180.png'),
        'signin', jsonb_build_object(
            'mode', 'custom_html', 'layout', 'split', 'headline', 'Welcome', 'subtext', 'Sign in to Acme',
            'background', jsonb_build_object('type', 'image', 'image_url', pg_temp.u('orga', 'acme', 'bg.webp')),
            'show_powered_by', true,
            'footer_links', jsonb_build_array(jsonb_build_object('label', 'Privacy', 'url', 'https://acme.test/privacy')),
            'custom_css', '.x { color: red }',
            'custom_html', '<main><h1>Acme</h1><agent-signin></agent-signin></main>'));
$$;
create function pg_temp.v(p jsonb) returns text language sql as $$
    select public.validate_agent_brand(p, (select id from ids where k = 'orga'), (select id from ids where k = 'acme'));
$$;
grant execute on function pg_temp.u(text, text, text), pg_temp.full_brand(), pg_temp.v(jsonb) to authenticated, anon;

-- ---- shape and grants ----
select ok((select bool_and(prosecdef and 'search_path=""' = any(proconfig)) from pg_proc where oid in (
    'public.validate_agent_brand(jsonb,uuid,uuid)'::regprocedure, 'public.agent_brand_url_ok(text,uuid,uuid)'::regprocedure,
    'public.agent_hostname_error(text)'::regprocedure, 'public.agent_domain_record_check(uuid,boolean,boolean,uuid)'::regprocedure,
    'public.agent_for_host(text)'::regprocedure, 'public.domain_tls_allowed(text)'::regprocedure,
    'public.record_agent_seen(jsonb)'::regprocedure, 'public.org_overview(uuid)'::regprocedure,
    'public.org_people(uuid,integer,integer)'::regprocedure, 'public.org_usage(uuid,integer)'::regprocedure,
    'public.org_audit_log(uuid,integer,timestamp with time zone)'::regprocedure,
    'public.is_agent_folder_admin(text)'::regprocedure)), 'v3 RPCs are security definer with an empty search_path');
select ok(not has_function_privilege('anon', 'public.validate_agent_brand(jsonb,uuid,uuid)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.agent_hostname_error(text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.record_agent_seen(jsonb)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.org_overview(uuid)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.org_people(uuid,integer,integer)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.org_usage(uuid,integer)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.org_audit_log(uuid,integer,timestamp with time zone)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.agent_domain_record_check(uuid,boolean,boolean,uuid)', 'EXECUTE'),
      'anon gets no new RPC beyond host lookup');
select ok(has_function_privilege('anon', 'public.agent_for_host(text)', 'EXECUTE')
      and has_function_privilege('anon', 'public.domain_tls_allowed(text)', 'EXECUTE'), 'host lookup and TLS ask are anon-callable');
select ok(not has_function_privilege('authenticated', 'public.agent_domain_record_check(uuid,boolean,boolean,uuid)', 'EXECUTE')
      and has_function_privilege('service_role', 'public.agent_domain_record_check(uuid,boolean,boolean,uuid)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.agent_brand_url_ok(text,uuid,uuid)', 'EXECUTE'),
      'domain status writes are service-role only');
select ok(not has_table_privilege('anon', 'public.agent_audit_log', 'SELECT')
      and not has_table_privilege('authenticated', 'public.agent_audit_log', 'SELECT')
      and not has_table_privilege('authenticated', 'public.agent_audit_log', 'INSERT')
      and not has_table_privilege('service_role', 'public.agent_audit_log', 'INSERT')
      and not has_table_privilege('service_role', 'public.agent_audit_log', 'UPDATE')
      and not has_table_privilege('service_role', 'public.agent_audit_log', 'DELETE'), 'audit log: no client access, service role read-only');
select ok(not has_table_privilege('anon', 'public.agent_activity_days', 'SELECT')
      and not has_table_privilege('authenticated', 'public.agent_activity_days', 'SELECT')
      and not has_table_privilege('authenticated', 'public.agent_activity_days', 'INSERT'), 'activity days: no client access');
select ok(not has_table_privilege('anon', 'public.agent_domains', 'SELECT')
      and not has_table_privilege('authenticated', 'public.agent_domains', 'UPDATE')
      and not has_column_privilege('authenticated', 'public.agent_domains', 'status', 'INSERT')
      and not has_column_privilege('authenticated', 'public.agent_domains', 'verify_token', 'INSERT')
      and not has_column_privilege('authenticated', 'public.agent_domains', 'verified_at', 'INSERT'), 'domain status/token are not client-writable');
select ok(not has_column_privilege('authenticated', 'public.agent_installs', 'last_seen_at', 'UPDATE')
      and not has_column_privilege('authenticated', 'public.agent_installs', 'last_seen_at', 'INSERT'), 'last_seen_at moves only through record_agent_seen');
select ok(not has_table_privilege('anon', 'public.agent_org_defaults', 'SELECT')
      and not has_table_privilege('authenticated', 'public.agent_org_defaults', 'DELETE'), 'org defaults: no anon, no delete');
select is((select allowed_mime_types from storage.buckets where id = 'agent-brand'), array['image/png', 'image/jpeg', 'image/webp'], 'agent-brand takes raster images only (no SVG)');
select ok((select public and file_size_limit = 2097152 from storage.buckets where id = 'agent-brand'), 'agent-brand is public, 2 MiB');
select is((select value from public.platform_flags where key = 'agents_base_domain'), '"bossconsole.ai"'::jsonb, 'base domain flag seeded');
-- No console RPC reads chat-shaped tables or the install's free-text detail/data_dir.
select is((select count(*)::int from pg_proc p where p.oid in (
        'public.org_overview(uuid)'::regprocedure, 'public.org_people(uuid,integer,integer)'::regprocedure,
        'public.org_usage(uuid,integer)'::regprocedure, 'public.org_audit_log(uuid,integer,timestamp with time zone)'::regprocedure)
    and (p.prosrc ~* '(message|chat|conversation|transcript|webchat)' or p.prosrc ~* 'i\.detail' or p.prosrc ~* 'data_dir')),
    0, 'org console RPCs reference no chat content, install detail or data_dir');
select is((select count(*)::int from pg_proc p where p.pronamespace = 'public'::regnamespace
    and p.oid in ('public.agent_for_host(text)'::regprocedure, 'public.record_agent_seen(jsonb)'::regprocedure,
                  'public.agent_domains_guard()'::regprocedure, 'public.agent_domain_record_check(uuid,boolean,boolean,uuid)'::regprocedure)
    and p.prosrc ilike '%service_role%'), 0, 'no v3 agents function body leans on the service role');

-- ---- validate_agent_brand ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select is(pg_temp.v(pg_temp.full_brand()), null, 'a full v3 brand is valid');
select is(pg_temp.v('{}'::jsonb), null, 'an empty brand is valid');
select is(pg_temp.v('{"name":"","short_name":"","accent":""}'::jsonb), null, 'empty name/short_name/accent mean unset');
select is(public.validate_agent_brand(pg_temp.full_brand()), null, 'unbound call (console feedback) accepts the same brand');
select is(pg_temp.v('[]'::jsonb), 'brand_not_object', 'non-object rejected');
select is(pg_temp.v('{"logo_path":"x/y.png"}'::jsonb), 'brand_unknown_key:logo_path', 'unknown keys (incl. legacy logo_path) rejected');
select is(pg_temp.v('{"accent":"red"}'::jsonb), 'accent_invalid', 'accent must be #rrggbb');
select is(pg_temp.v('{"accent_dark":"#12345"}'::jsonb), 'accent_dark_invalid', 'accent_dark must be #rrggbb');
select is(pg_temp.v(jsonb_build_object('name', repeat('n', 81))), 'name_invalid', 'name over 80 chars rejected');
select is(pg_temp.v(jsonb_build_object('short_name', repeat('n', 31))), 'short_name_invalid', 'short_name over 30 chars rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{logo,url}', '"https://evil.test/storage/v1/object/public/agent-brand/x.png"')),
    'logo_url_invalid', 'logo outside the bucket rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{logo,url}', to_jsonb(pg_temp.u('orgb', 'beta', 'logo.png')))),
    'logo_url_invalid', 'logo in another org''s folder rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{logo,url}', to_jsonb(pg_temp.u('orga', 'beta', 'logo.png')))),
    'logo_url_invalid', 'logo in another agent''s folder rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{logo,url}', to_jsonb(pg_temp.u('orga', 'acme', 'logo.svg')))),
    'logo_url_invalid', 'svg logo rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{logo,url}', to_jsonb(pg_temp.u('orga', 'acme', '../x.png')))),
    'logo_url_invalid', 'path traversal rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{logo,url}', to_jsonb(pg_temp.u('orga', 'acme', 'x.png?a=1')))),
    'logo_url_invalid', 'query strings rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{logo,shape}', '"hexagon"')), 'logo_shape_invalid', 'unknown logo shape rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{logo,background}', '"blue"')), 'logo_background_invalid', 'logo background must be #rrggbb or null');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{logo,extra}', '1')), 'logo_invalid', 'extra logo keys rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{favicon_url}', '"https://evil.test/f.png"')), 'favicon_url_invalid', 'favicon outside the bucket rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{apple_touch_url}', '"javascript:alert(1)"')), 'apple_touch_url_invalid', 'javascript: URL rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,mode}', '"iframe"')), 'signin_mode_invalid', 'unknown sign-in mode rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,layout}', '"grid"')), 'signin_layout_invalid', 'unknown layout rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,headline}', to_jsonb(repeat('h', 81)))), 'signin_headline_invalid', 'headline over 80 rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,subtext}', to_jsonb(repeat('s', 241)))), 'signin_subtext_invalid', 'subtext over 240 rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,notice}', to_jsonb(repeat('n', 300)))), null, 'a 300-character notice accepted');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,notice}', to_jsonb(repeat('n', 301)))), 'signin_notice_invalid', 'notice over 300 rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,notice}', '42'::jsonb)), 'signin_notice_invalid', 'a non-string notice rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,show_powered_by}', '"yes"')), 'signin_show_powered_by_invalid', 'show_powered_by must be boolean');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,background}', '{"type":"image"}')), 'signin_background_image_invalid', 'image background needs a bucket image_url');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,background}', '{"type":"image","image_url":"https://evil.test/bg.png"}')),
    'signin_background_image_invalid', 'background image outside the bucket rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,background}', '{"type":"video"}')), 'signin_background_invalid', 'unknown background type rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,footer_links}',
    (select jsonb_agg(jsonb_build_object('label', 'L' || g, 'url', 'https://acme.test/' || g)) from generate_series(1, 6) g))),
    'signin_footer_links_invalid', 'more than 5 footer links rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,footer_links}', '[{"label":"x","url":"http://acme.test"}]')),
    'signin_footer_link_invalid', 'non-https footer link rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,footer_links}', '[{"label":"x","url":"javascript:alert(1)"}]')),
    'signin_footer_link_invalid', 'javascript: footer link rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,footer_links}', to_jsonb(array[jsonb_build_object('label', repeat('l', 41), 'url', 'https://a.test')]))),
    'signin_footer_link_invalid', 'footer label over 40 rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,custom_css}', to_jsonb(repeat('c', 20001)))), 'signin_custom_css_invalid', 'custom_css over 20000 rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,custom_html}',
    to_jsonb('<agent-signin></agent-signin>' || repeat('h', 50000)))), 'signin_custom_html_invalid', 'custom_html over 50000 rejected');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,custom_html}', '"<main>no mount</main>"')),
    'signin_custom_html_mount_invalid', 'custom_html mode needs the mount element');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,custom_html}', '"<agent-signin></agent-signin><AGENT-SIGNIN></agent-signin>"')),
    'signin_custom_html_mount_invalid', 'exactly one mount element');
select is(pg_temp.v(jsonb_set(pg_temp.full_brand(), '{signin,custom_html}', '"<agent-signin>x</agent-signin>"')),
    'signin_custom_html_mount_invalid', 'mount element must be empty');
select is(pg_temp.v('{"signin":{"mode":"standard"}}'::jsonb), null, 'standard mode needs no custom_html');
select is(pg_temp.v(pg_temp.full_brand() || jsonb_build_object('name', repeat('n', 80), 'short_name', repeat('s', 30),
    'signin', (pg_temp.full_brand()->'signin') || jsonb_build_object('headline', repeat('h', 80), 'subtext', repeat('t', 240),
        'custom_css', repeat('c', 20000), 'custom_html', repeat('x', 49971) || '<agent-signin></agent-signin>'))),
    null, 'brand at every field limit is accepted');
select is(pg_temp.v(jsonb_build_object('signin', jsonb_build_object('mode', 'standard', 'custom_html', repeat('€', 50000)))),
    'brand_too_large', 'brand over 100 kB (bytes) rejected');

-- ---- brand enforced on agents ----
select lives_ok($q$ update public.agents set brand = pg_temp.full_brand() where slug = 'vacme' $q$, 'admin saves a valid v3 brand');
select throws_ok($q$ update public.agents set brand = '{"accent":"nope"}' where slug = 'vacme' $q$, '23514', 'invalid_brand: accent_invalid', 'invalid brand rejected on update');
select throws_ok($q$ update public.agents set brand = jsonb_set(pg_temp.full_brand(), '{logo,url}', to_jsonb(pg_temp.u('orga', 'beta', 'l.png'))) where slug = 'vacme' $q$,
    '23514', 'invalid_brand: logo_url_invalid', 'brand URL must be in this agent''s own folder');
select throws_ok($q$ insert into public.agents (slug, org_id, name, brand, created_by)
    values ('vbad', (select id from ids where k = 'orga'), 'Bad', '{"signin":{"mode":"standard","headline":1}}', 'b3000000-0000-4000-8000-00000000000a') $q$,
    '23514', 'invalid_brand: signin_headline_invalid', 'invalid brand rejected on insert');
reset role;
update public.platform_flags set value = 'null'::jsonb where key = 'agent_brand_base_url';
set local role authenticated;
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select is(pg_temp.v(pg_temp.full_brand()), 'logo_url_invalid', 'no asset URL is accepted while the bucket base URL is unset');
reset role;
update public.platform_flags set value = '"https://proj.supabase.test/storage/v1/object/public/agent-brand/"'::jsonb where key = 'agent_brand_base_url';

-- ---- org default brand ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ insert into public.agent_org_defaults (org_id, default_brand) values ((select id from ids where k = 'orga'),
    jsonb_build_object('accent', '#112233', 'logo', jsonb_build_object('url', pg_temp.u('orga', 'org', 'logo.png'), 'shape', 'square'))) $q$,
    'org admin saves a default brand from the org folder');
select throws_ok($q$ update public.agent_org_defaults set default_brand = jsonb_build_object('logo', jsonb_build_object('url', pg_temp.u('orgb', 'org', 'logo.png'), 'shape', 'square')) $q$,
    '23514', 'invalid_brand: logo_url_invalid', 'default brand cannot point at another org');
select throws_ok($q$ insert into public.agent_org_defaults (org_id) values ((select id from ids where k = 'orgb')) $q$, '42501', null, 'cannot write another org''s defaults');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000b', true);
select is((select count(*)::int from public.agent_org_defaults), 0, 'other org admin cannot read the defaults');

-- ---- brand bucket ----
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', (select id from ids where k = 'orga') || '/org/logo.png') $q$, 'admin uploads to the org folder');
select lives_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', (select id from ids where k = 'orga') || '/' || (select id from ids where k = 'acme') || '/logo.png') $q$, 'admin uploads to own agent folder');
select is((select count(*)::int from storage.objects where bucket_id = 'agent-brand'), 2, 'admin lists own brand objects');
select throws_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', (select id from ids where k = 'orgb') || '/org/logo.png') $q$, '42501', null, 'cannot upload to another org');
select throws_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', (select id from ids where k = 'orga') || '/' || (select id from ids where k = 'beta') || '/logo.png') $q$,
    '42501', null, 'cannot file another org''s agent under own org');
select throws_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', (select id from ids where k = 'orga') || '/org/deeper/logo.png') $q$, '42501', null, 'no nested folders');
select throws_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', (select id from ids where k = 'acme') || '/logo.png') $q$, '42501', null, 'old <agentId>/ layout refused');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000b', true);
select is((select count(*)::int from storage.objects where bucket_id = 'agent-brand'), 0, 'other org admin cannot list A''s objects');
select is_empty($q$ update storage.objects set metadata = '{}' where bucket_id = 'agent-brand' returning 1 $q$, 'other org admin cannot overwrite A''s objects');
reset role;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
set local role anon;
select throws_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', (select id from ids where k = 'orga') || '/org/x.png') $q$, '42501', null, 'anon cannot upload');
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

-- ---- hostname rules ----
select is(public.agent_hostname_error('chat.acme-corp.com'), null, 'plain FQDN accepted');
select is(public.agent_hostname_error('xn--bcher-kva.example'), null, 'punycode accepted');
select is(public.agent_hostname_error('Chat.Acme.com'), 'hostname_not_lowercase', 'uppercase rejected');
select is(public.agent_hostname_error('*.acme.com'), 'hostname_wildcard', 'wildcard rejected');
select is(public.agent_hostname_error('10.0.0.1'), 'hostname_ip', 'IPv4 rejected');
select is(public.agent_hostname_error('::1'), 'hostname_ip', 'IPv6 rejected');
select is(public.agent_hostname_error('localhost'), 'hostname_invalid', 'single label rejected');
select is(public.agent_hostname_error('acme.com.'), 'hostname_invalid', 'trailing dot rejected');
select is(public.agent_hostname_error('a..acme.com'), 'hostname_invalid', 'empty label rejected');
select is(public.agent_hostname_error('bad_host.acme.com'), 'hostname_invalid', 'underscore rejected');
select is(public.agent_hostname_error('-bad.acme.com'), 'hostname_invalid', 'leading hyphen rejected');
select is(public.agent_hostname_error(repeat('a', 64) || '.com'), 'hostname_invalid', 'label over 63 rejected');
select is(public.agent_hostname_error(repeat('abcdefghi.', 26) || 'com'), 'hostname_too_long', 'over 253 chars rejected');
select is(public.agent_hostname_error('acme.123'), 'hostname_invalid', 'numeric TLD rejected');
select is(public.agent_hostname_error('foo.bossconsole.ai'), 'hostname_reserved', 'bossconsole.ai subdomain reserved');
select is(public.agent_hostname_error('risalabs.ai'), 'hostname_reserved', 'risalabs.ai reserved');
select is(public.agent_hostname_error('x.y.risaboss.com'), 'hostname_reserved', 'risaboss.com reserved');
select is(public.agent_hostname_error('notbossconsole.ai'), null, 'suffix match is label-aligned');

-- ---- domains: RLS and limits ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ insert into public.agent_domains (agent_id, hostname) values ((select id from ids where k = 'acme'), 'chat.acme-corp.com') $q$, 'org admin adds a domain');
select ok((select status = 'pending' and verify_token ~ '^[0-9a-f]{32}$' and created_by = 'b3000000-0000-4000-8000-00000000000a' and verified_at is null
           from public.agent_domains where hostname = 'chat.acme-corp.com'), 'new domain is pending with a token and the creator recorded');
select throws_ok($q$ insert into public.agent_domains (agent_id, hostname, status) values ((select id from ids where k = 'acme'), 'x.acme-corp.com', 'active') $q$, '42501', null, 'cannot insert a status');
select throws_ok($q$ insert into public.agent_domains (agent_id, hostname, verify_token) values ((select id from ids where k = 'acme'), 'x.acme-corp.com', repeat('a', 32)) $q$, '42501', null, 'cannot choose the token');
select throws_ok($q$ insert into public.agent_domains (agent_id, hostname) values ((select id from ids where k = 'acme'), 'Up.acme-corp.com') $q$, '23514', 'hostname_not_lowercase', 'insert enforces hostname rules');
select throws_ok($q$ insert into public.agent_domains (agent_id, hostname) values ((select id from ids where k = 'acme'), 'vacme.bossconsole.ai') $q$, '23514', 'hostname_reserved', 'cannot claim a platform host');
select throws_ok($q$ insert into public.agent_domains (agent_id, hostname) values ((select id from ids where k = 'acme'), 'chat.acme-corp.com') $q$, '23505', null, 'hostname is unique');
select throws_ok($q$ update public.agent_domains set status = 'active' $q$, '42501', null, 'admin cannot set a status directly');
select lives_ok($q$ insert into public.agent_domains (agent_id, hostname)
    select (select id from ids where k = 'acme'), 'd' || g || '.acme-corp.com' from generate_series(2, 5) g $q$, 'up to five domains per agent');
select throws_ok($q$ insert into public.agent_domains (agent_id, hostname) values ((select id from ids where k = 'acme'), 'd6.acme-corp.com') $q$, 'P0001', 'domain_limit_reached', 'sixth domain refused');
select throws_ok($q$ insert into public.agent_domains (agent_id, hostname) values ((select id from ids where k = 'beta'), 'b.acme-corp.com') $q$, '42501', null, 'cannot add a domain to another org''s agent');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000b', true);
select is((select count(*)::int from public.agent_domains), 0, 'other org admin sees no domains');
select is_empty($q$ delete from public.agent_domains returning 1 $q$, 'other org admin cannot delete domains');
select throws_ok($q$ insert into public.agent_domains (agent_id, hostname) values ((select id from ids where k = 'acme'), 'z.acme-corp.com') $q$, '42501', null, 'other org admin cannot add to A''s agent');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000c', true);
select is((select count(*)::int from public.agent_domains), 0, 'plain user sees no domains');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000f', true);
select is((select count(*)::int from public.agent_domains), 0, 'provisioner bot sees no domain rows (uses agent_for_host)');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select public.agent_domain_record_check((select id from public.agent_domains where hostname = 'chat.acme-corp.com'), true, true, auth.uid()) $q$,
    '42501', null, 'admin cannot record a DNS result');
select lives_ok($q$ delete from public.agent_domains where hostname = 'd5.acme-corp.com' $q$, 'org admin deletes a domain');
select is((select count(*)::int from public.agent_domains), 4, 'domain deleted');
reset role;

-- ---- domain status, host lookup, TLS ask ----
update public.agents set status = 'live' where slug = 'vacme';
select set_config('request.jwt.claim.sub', '', true);
create temp table dom (id uuid) on commit drop;
grant all on dom to service_role, authenticated, anon;
insert into dom select id from public.agent_domains where hostname = 'chat.acme-corp.com';
set local role anon;
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select is((select count(*)::int from public.agent_for_host('chat.acme-corp.com')), 0, 'a pending domain routes nowhere');
select is(public.domain_tls_allowed('chat.acme-corp.com'), false, 'no TLS for a pending domain');
reset role;
set local role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select is(public.agent_domain_record_check((select id from dom), true, false, 'b3000000-0000-4000-8000-00000000000a'), 'verified', 'TXT only -> verified');
select ok((select verified_at is not null and last_checked_at is not null from public.agent_domains where id = (select id from dom)), 'verified_at and last_checked_at set');
select is(public.agent_domain_record_check((select id from dom), true, true, 'b3000000-0000-4000-8000-00000000000a'), 'active', 'TXT + CNAME -> active');
select throws_ok($q$ select public.agent_domain_record_check(gen_random_uuid(), true, true, null) $q$, 'P0001', 'domain_not_found', 'unknown domain id');
reset role;
set local role anon;
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select is((select row(slug, status)::text from public.agent_for_host('chat.acme-corp.com')), '(vacme,live)', 'active custom domain routes to its agent');
select is((select agent_id from public.agent_for_host('CHAT.acme-corp.com.:443')), (select id from ids where k = 'acme'), 'host lookup ignores case, trailing dot and port');
select is((select slug from public.agent_for_host('vacme.bossconsole.ai')), 'vacme', '<slug>.<base> routes');
select is((select count(*)::int from public.agent_for_host('vbeta.bossconsole.ai')), 0, 'anon does not see a draft agent');
select is((select count(*)::int from public.agent_for_host('x.vacme.bossconsole.ai')), 0, 'nested labels under base do not route');
select is((select count(*)::int from public.agent_for_host('d2.acme-corp.com')), 0, 'non-active domain does not route');
select is(public.domain_tls_allowed('chat.acme-corp.com'), true, 'TLS allowed for an active domain of a live agent');
select is(public.domain_tls_allowed('vacme.bossconsole.ai'), true, 'TLS allowed for a live agent host');
select is(public.domain_tls_allowed('vbeta.bossconsole.ai'), false, 'no TLS for a draft agent host');
select is(public.domain_tls_allowed('unknown.example.com'), false, 'no TLS for unknown hosts');
select is(public.domain_tls_allowed(null), false, 'no TLS for null');
reset role;
set local role authenticated;
select set_config('request.jwt.claims', '{"role":"authenticated"}', true);
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000f', true);
select is((select status::text from public.agent_for_host('vbeta.bossconsole.ai')), 'draft', 'the provisioner bot sees every status');
reset role;
update public.agents set status = 'suspended' where slug = 'vacme';
set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select is(public.domain_tls_allowed('chat.acme-corp.com'), false, 'no TLS once the agent is suspended');
reset role;
update public.agents set status = 'live' where slug = 'vacme';
set local role service_role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select is(public.agent_domain_record_check((select id from dom), false, true, 'b3000000-0000-4000-8000-00000000000a'), 'failed', 'records gone -> failed');
reset role;
set local role anon;
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select is((select count(*)::int from public.agent_for_host('chat.acme-corp.com')), 0, 'a failed domain stops routing');
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select is(public.agent_domain_record_check((select id from dom), true, true, null), 'active', 're-verify reactivates');

-- ---- activity: record_agent_seen ----
insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state, detail, running_version) values
    ('b3000000-0000-4000-8000-00000000000c', (select id from ids where k = 'acme'), 'v3-c', 9810, 'agents/vacme', 'running', 'CHAT-CONTENT-MARKER', '1.2.0'),
    ('b3000000-0000-4000-8000-00000000000e', (select id from ids where k = 'acme'), 'v3-e', 9811, 'agents/vacme', 'failed', 'CHAT-CONTENT-MARKER', null),
    ('b3000000-0000-4000-8000-00000000000c', (select id from ids where k = 'beta'), 'v3-cb', 9812, 'agents/vbeta', 'running', null, '0.1.0');
create temp table snap (updated_at timestamptz) on commit drop;
insert into snap select updated_at from public.agent_installs where instance_id = 'v3-c';
set local role authenticated;
select set_config('request.jwt.claims', '{"role":"authenticated"}', true);
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select public.record_agent_seen('[]') $q$, '42501', null, 'org admin cannot record activity');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000c', true);
select throws_ok($q$ select public.record_agent_seen('[]') $q$, '42501', null, 'a user cannot record their own activity');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000f', true);
select is(public.record_agent_seen(jsonb_build_array(
    jsonb_build_object('agent_id', (select id from ids where k = 'acme'), 'user_id', 'b3000000-0000-4000-8000-00000000000c', 'seen_at', now() + interval '1 day'),
    jsonb_build_object('agent_id', (select id from ids where k = 'acme'), 'user_id', 'b3000000-0000-4000-8000-00000000000c', 'seen_at', now() - interval '3 days'),
    jsonb_build_object('agent_id', (select id from ids where k = 'acme'), 'user_id', 'b3000000-0000-4000-8000-00000000000a'),
    jsonb_build_object('agent_id', (select id from ids where k = 'beta'), 'user_id', 'b3000000-0000-4000-8000-00000000000c', 'seen_at', now() - interval '100 days'))),
    1, 'bot records activity; unknown installs and >90-day-old entries are ignored');
reset role;
select is((select last_seen_at from public.agent_installs where instance_id = 'v3-c'), now(), 'a future seen_at is clamped to now');
select is((select updated_at from public.agent_installs where instance_id = 'v3-c'), (select updated_at from snap), 'a last-seen bump does not touch updated_at');
select is((select count(*)::int from public.agent_activity_days where user_id = 'b3000000-0000-4000-8000-00000000000c'), 2, 'one activity row per agent, person and day');
set local role authenticated;
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000f', true);
select is(public.record_agent_seen(jsonb_build_array(jsonb_build_object('agent_id', (select id from ids where k = 'acme'),
    'user_id', 'b3000000-0000-4000-8000-00000000000c', 'seen_at', now() - interval '1 hour'))), 0, 'last_seen_at never moves backwards');
select throws_ok($q$ select public.record_agent_seen((select jsonb_agg(jsonb_build_object('agent_id', gen_random_uuid(), 'user_id', gen_random_uuid())) from generate_series(1, 1001))) $q$,
    'P0001', 'invalid_seen', 'batches over 1000 refused');
select throws_ok($q$ select public.record_agent_seen('{}') $q$, 'P0001', 'invalid_seen', 'non-array refused');
select throws_ok($q$ update public.agent_installs set last_seen_at = now() + interval '1 year' $q$, '42501', null, 'bot cannot write last_seen_at directly');
reset role;

-- ---- invites (for the audit trail) ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
insert into public.agent_invites (agent_id, email, invited_by) values ((select id from ids where k = 'acme'), 'guest@v3.test', 'b3000000-0000-4000-8000-00000000000a');
delete from public.agent_invites where email = 'guest@v3.test';
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
update public.agents set name = 'Acme 2', signup_policy = '{"mode":"invite"}' where slug = 'vacme';
select public.publish_agent((select id from ids where k = 'acme'), '1.2.0');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000d', true);
select public.suspend_agent((select id from ids where k = 'acme'));
reset role;

-- ---- org_overview ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select is(public.org_overview((select id from ids where k = 'orga')) - 'pods',
    '{"agents_by_status":{"draft":0,"pending_review":0,"live":0,"suspended":1},"installs_total":2,"people_total":2,"active_people_7d":1,"active_people_30d":1}'::jsonb,
    'overview counts agents, installs, people and actives');
select is(public.org_overview((select id from ids where k = 'orga'))->'pods', '{"running":1,"failed":1,"pending":0}'::jsonb, 'overview counts pod states');
select ok(position('CHAT-CONTENT-MARKER' in public.org_overview((select id from ids where k = 'orga'))::text) = 0, 'overview carries no install detail');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000b', true);
select throws_ok($q$ select public.org_overview((select id from ids where k = 'orga')) $q$, '42501', null, 'other org admin denied overview');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000c', true);
select throws_ok($q$ select public.org_overview((select id from ids where k = 'orga')) $q$, '42501', null, 'plain user denied overview');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000f', true);
select throws_ok($q$ select public.org_overview((select id from ids where k = 'orga')) $q$, '42501', null, 'provisioner bot denied overview');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000d', true);
select lives_ok($q$ select public.org_overview((select id from ids where k = 'orga')) $q$, 'platform admin (global admin) may read any org');

-- ---- org_people ----
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select is((select array_agg(email order by email) from public.org_people((select id from ids where k = 'orga'))), array['user-c@v3.test', 'user-e@v3.test'], 'people lists the org''s agent users');
select is((select agents->0->>'running_version' from public.org_people((select id from ids where k = 'orga')) where email = 'user-c@v3.test'), '1.2.0', 'running version per agent');
select ok((select last_seen_at is not null and first_seen_at is not null from public.org_people((select id from ids where k = 'orga')) where email = 'user-c@v3.test'), 'first and last seen');
select is((select jsonb_array_length(agents) from public.org_people((select id from ids where k = 'orga')) where email = 'user-c@v3.test'), 1, 'only this org''s agents are listed');
select ok((select bool_and(position('CHAT-CONTENT-MARKER' in p::text) = 0 and position('agents/vacme' in p::text) = 0) from public.org_people((select id from ids where k = 'orga')) p),
    'people carries no install detail or data_dir');
select is((select count(*)::int from public.org_people((select id from ids where k = 'orga'), 1, 1)), 1, 'people paginates');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000b', true);
select throws_ok($q$ select * from public.org_people((select id from ids where k = 'orga')) $q$, '42501', null, 'other org admin denied people');
select is((select array_agg(email) from public.org_people((select id from ids where k = 'orgb'))), array['user-c@v3.test'], 'org B sees only its own agent''s people');

-- ---- org_usage ----
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select is((select count(*)::int from public.org_usage((select id from ids where k = 'orga'), 7)), 7, 'one row per day per agent');
select is((select row(joins, active_people)::text from public.org_usage((select id from ids where k = 'orga'), 7) where day = (now() at time zone 'UTC')::date),
    '(2,1)', 'today: joins and active people');
select is((select count(*)::int from public.org_usage((select id from ids where k = 'orga'), 1000)), 90, 'window is clamped to 90 days');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000c', true);
select throws_ok($q$ select * from public.org_usage((select id from ids where k = 'orga'), 7) $q$, '42501', null, 'plain user denied usage');

-- ---- org_audit_log ----
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select ok((select array_agg(distinct action) from public.org_audit_log((select id from ids where k = 'orga'), 200)) @> array[
    'agent.create', 'agent.brand_change', 'agent.settings_change', 'agent.publish', 'agent.suspend',
    'domain.add', 'domain.remove', 'domain.status', 'invite.add', 'invite.remove', 'org.default_brand_change'], 'every audited action is logged');
select is((select detail from public.org_audit_log((select id from ids where k = 'orga'), 200) where action = 'agent.settings_change' order by id desc limit 1),
    '{"fields":["name","signup_policy"]}'::jsonb, 'settings change names the fields');
select is((select detail->'keys' from public.org_audit_log((select id from ids where k = 'orga'), 200) where action = 'agent.brand_change' order by id limit 1),
    '["accent", "accent_dark", "apple_touch_url", "favicon_url", "logo", "name", "short_name", "signin"]'::jsonb, 'brand change names the keys, not the content');
select is((select row(actor_email, detail->>'version')::text from public.org_audit_log((select id from ids where k = 'orga'), 200) where action = 'agent.publish'),
    '(owner-a@v3.test,1.2.0)', 'publish records the actor and version');
select is((select actor_email from public.org_audit_log((select id from ids where k = 'orga'), 200) where action = 'agent.suspend' order by id desc limit 1), 'platform@v3.test', 'suspend records the platform admin');
select is((select count(*)::int from public.org_audit_log((select id from ids where k = 'orga'), 200)
           where action = 'domain.status' and actor = 'b3000000-0000-4000-8000-00000000000a'), 3,
    'service-role status writes carry the edge function''s actor');
select is((select count(*)::int from public.org_audit_log((select id from ids where k = 'orga'), 3)), 3, 'limit honoured');
select ok((select bool_and(at < (select min(at) from public.org_audit_log((select id from ids where k = 'orga'), 3)))
           from public.org_audit_log((select id from ids where k = 'orga'), 3, (select min(at) from public.org_audit_log((select id from ids where k = 'orga'), 3)))), 'p_before pages backwards');
select ok((select bool_and(position('CHAT-CONTENT-MARKER' in l::text) = 0) from public.org_audit_log((select id from ids where k = 'orga'), 200) l), 'audit carries no install detail');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000b', true);
select throws_ok($q$ select * from public.org_audit_log((select id from ids where k = 'orga')) $q$, '42501', null, 'other org admin denied A''s audit log');
select ok((select bool_and(agent_id is distinct from (select id from ids where k = 'acme')) from public.org_audit_log((select id from ids where k = 'orgb'), 200)), 'org B''s log has none of A''s rows');
select set_config('request.jwt.claim.sub', 'b3000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select count(*) from public.agent_audit_log $q$, '42501', null, 'clients cannot read the table directly');
select throws_ok($q$ insert into public.agent_audit_log (org_id, action) values ((select id from ids where k = 'orga'), 'forged') $q$, '42501', null, 'clients cannot forge entries');
reset role;
set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok($q$ select * from public.org_audit_log((select id from ids where k = 'orga')) $q$, '42501', null, 'anon denied the audit log');
reset role;
select throws_ok($q$ update public.agent_audit_log set action = 'x' $q$, '42501', 'agent_audit_log is append-only', 'no update even for the owner');
select throws_ok($q$ delete from public.agent_audit_log $q$, '42501', 'agent_audit_log is append-only', 'no delete even for the owner');
select throws_ok($q$ truncate public.agent_audit_log $q$, '42501', 'agent_audit_log is append-only', 'no truncate even for the owner');

select * from finish();
rollback;
