-- pgTAP tests for 20261120120000_page_metadata: the PageMeta validator, brand.meta, platform_page_meta RPCs, audit.
-- Run with: supabase test db
begin;
select plan(46);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
    ('9e000000-0000-4000-8000-0000000000c1', 'root@pagemeta.test', now(), '{}'),
    ('9e000000-0000-4000-8000-0000000000a1', 'user@pagemeta.test', now(), '{}');
insert into public.user_roles (user_id, role_id)
    select '9e000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';

create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', p, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', p)::text, true);
$$;
grant execute on function pg_temp.as_user(text) to authenticated, anon;

-- ---- validator: accepts ----
select is(public.page_meta_invalid_reason('{"title":"Optimist","description":"Your AI.","keywords":"ai, agent","image_url":"https://cdn.example.com/a/b.png?x=1","image_alt":"Logo","site_name":"Optimist","twitter_site":"@optimist_ai","theme_color":"#0a1B2c","robots":"noindex","locale":"en_US","custom":[{"attr":"name","key":"google-site-verification","content":"abc123"},{"attr":"property","key":"fb:app_id","content":"1"}]}'),
    null, 'a full valid object');
select is(public.page_meta_invalid_reason('{}'), null, 'empty object');
select is(public.page_meta_invalid_reason('{"title":null,"description":null,"keywords":null,"image_url":null,"image_alt":null,"site_name":null,"twitter_site":null,"theme_color":null,"robots":null,"locale":null,"custom":null}'),
    null, 'null unsets every key');
select is(public.page_meta_invalid_reason('{"locale":"fr"}'), null, 'locale without region');

-- ---- validator: rejects ----
select is(public.page_meta_invalid_reason(null), 'meta_invalid', 'NULL is not an object');
select is(public.page_meta_invalid_reason('[]'), 'meta_invalid', 'array is not an object');
select is(public.page_meta_invalid_reason('{"nope":"x"}'), 'meta_invalid', 'unknown key');
select is(public.page_meta_invalid_reason(jsonb_build_object('custom', jsonb_build_array(jsonb_build_object('attr','name','key','k','content',repeat('x',501))))),
    'meta_custom_invalid', 'custom content too long');
select is(public.page_meta_invalid_reason(jsonb_build_object('title', repeat('a', 71))), 'meta_title_invalid', 'title too long');
select is(public.page_meta_invalid_reason('{"title":"   "}'), 'meta_title_invalid', 'blank title');
select is(public.page_meta_invalid_reason('{"title":""}'), 'meta_title_invalid', 'empty string refused');
select is(public.page_meta_invalid_reason('{"title":5}'), 'meta_title_invalid', 'title not a string');
select is(public.page_meta_invalid_reason('{"description":"a <b> c"}'), 'meta_description_invalid', '< in description');
select is(public.page_meta_invalid_reason('{"description":"a > c"}'), 'meta_description_invalid', '> in description');
select is(public.page_meta_invalid_reason(jsonb_build_object('description', repeat('a', 301))), 'meta_description_invalid', 'description too long');
select is(public.page_meta_invalid_reason('{"keywords":"a\nb"}'), 'meta_keywords_invalid', 'newline in keywords');
select is(public.page_meta_invalid_reason('{"image_url":"http://cdn.example.com/a.png"}'), 'meta_image_url_invalid', 'http image');
select is(public.page_meta_invalid_reason('{"image_url":"https://cdn.example.com/a b.png"}'), 'meta_image_url_invalid', 'space in image url');
select is(public.page_meta_invalid_reason('{"image_alt":""}'), 'meta_image_alt_invalid', 'empty image_alt');
select is(public.page_meta_invalid_reason(jsonb_build_object('site_name', repeat('a', 81))), 'meta_site_name_invalid', 'site_name too long');
select is(public.page_meta_invalid_reason('{"twitter_site":"optimist"}'), 'meta_twitter_site_invalid', 'twitter_site without @');
select is(public.page_meta_invalid_reason('{"twitter_site":"@way_too_long_handle_x"}'), 'meta_twitter_site_invalid', 'twitter_site too long');
select is(public.page_meta_invalid_reason('{"theme_color":"#fff"}'), 'meta_theme_color_invalid', 'short theme_color');
select is(public.page_meta_invalid_reason('{"robots":"follow"}'), 'meta_robots_invalid', 'bad robots');
select is(public.page_meta_invalid_reason('{"locale":"en-US"}'), 'meta_locale_invalid', 'bad locale');
select is(public.page_meta_invalid_reason('{"custom":[{"attr":"name","key":"og:title","content":"x"}]}'), 'meta_custom_invalid', 'custom og: key');
select is(public.page_meta_invalid_reason('{"custom":[{"attr":"name","key":"Viewport","content":"x"}]}'), 'meta_custom_invalid', 'custom viewport, any case');
select is(public.page_meta_invalid_reason('{"custom":[{"attr":"name","key":"optimist-x","content":"x"}]}'), 'meta_custom_invalid', 'custom optimist- key');
select is(public.page_meta_invalid_reason('{"custom":[{"attr":"name","key":"Twitter:card","content":"x"}]}'), 'meta_custom_invalid', 'custom twitter: key');
select is(public.page_meta_invalid_reason(jsonb_build_object('custom', (select jsonb_agg(jsonb_build_object('attr','name','key','k'||i,'content','c')) from generate_series(1,21) i))),
    'meta_custom_invalid', 'more than 20 custom items');
select is(public.page_meta_invalid_reason('{"custom":[{"attr":"name","key":"k","content":"x","extra":1}]}'), 'meta_custom_invalid', 'extra key in a custom item');
select is(public.page_meta_invalid_reason('{"custom":[{"attr":"other","key":"k","content":"x"}]}'), 'meta_custom_invalid', 'bad attr');
select is(public.page_meta_invalid_reason('{"custom":[{"attr":"name","key":"k","content":"a\nb"}]}'), 'meta_custom_invalid', 'newline in custom content');
select is(public.page_meta_invalid_reason('{"custom":{"attr":"name"}}'), 'meta_custom_invalid', 'custom not an array');

-- ---- brand.meta ----
select is(public.validate_agent_brand('{"meta":{"title":"Acme","robots":"index"}}'), null, 'brand accepts meta');
select is(public.validate_agent_brand('{"meta":null}'), null, 'brand accepts null meta');
select is(public.validate_agent_brand('{"meta":{"title":"<x>"}}'), 'meta_title_invalid', 'brand returns the meta code');

-- ---- site_page_meta ----
set local role anon;
select is(public.site_page_meta('optimist'), '{}'::jsonb, 'anon reads a seeded site');
select is(public.site_page_meta('nowhere'), null, 'unknown site is NULL');
reset role;

-- ---- set_platform_page_meta ----
set local role authenticated;
select pg_temp.as_user('9e000000-0000-4000-8000-0000000000a1');
select is(public.set_platform_page_meta('optimist', '{"title":"X"}')->>'error', 'not_allowed', 'non-admin refused');
select pg_temp.as_user('9e000000-0000-4000-8000-0000000000c1');
select is(public.set_platform_page_meta('nowhere', '{"title":"X"}')->>'error', 'bad_request', 'bad site');
select is(public.set_platform_page_meta('max', '{"title":"<x>"}')->>'error', 'meta_title_invalid', 'invalid meta returns the code');
select is(public.set_platform_page_meta('max', '{"title":"Max","description":null,"robots":"index"}'),
    '{"success":true,"meta":{"title":"Max","robots":"index"}}'::jsonb, 'admin stores valid meta, null keys stripped');
select is(public.site_page_meta('max'), '{"title":"Max","robots":"index"}'::jsonb, 'site_page_meta returns what was stored');
reset role;
select is((select updated_by from public.platform_page_meta where site = 'max'), '9e000000-0000-4000-8000-0000000000c1'::uuid, 'updated_by is the admin');
select is((select detail->>'site' || '|' || (detail->'to'->>'title') from public.platform_audit_log
           where action = 'page_meta.change' order by id desc limit 1), 'max|Max', 'audited in platform_audit_log');

select * from finish();
rollback;
