-- pgTAP tests for 20261120130000_page_meta_platform_uploads: platform admins upload Optimist/Max share images.
-- Run with: supabase test db
begin;
select plan(9);

insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
    ('9e100000-0000-4000-8000-0000000000c1', 'root@pmupload.test', now(), '{}'),
    ('9e100000-0000-4000-8000-0000000000a1', 'user@pmupload.test', now(), '{}');
insert into public.user_roles (user_id, role_id)
    select '9e100000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';

create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', p, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', p)::text, true);
$$;
grant execute on function pg_temp.as_user(text) to authenticated, anon;

set local role authenticated;
select pg_temp.as_user('9e100000-0000-4000-8000-0000000000c1');
select ok(public.is_platform_brand_path('platform/optimist/share-abc.jpg'), 'admin: optimist folder');
select ok(public.is_platform_brand_path('platform/max/share-abc.jpg'), 'admin: max folder');
select ok(not public.is_platform_brand_path('platform/acme/share.jpg'), 'admin: unknown site refused');
select ok(not public.is_platform_brand_path('platform/max/sub/share.jpg'), 'admin: nested path refused');
select ok(not public.is_platform_brand_path('platform/max/.hidden'), 'admin: bad file name refused');
select lives_ok($$insert into storage.objects (bucket_id, name, owner) values ('agent-brand', 'platform/max/share-t.jpg', auth.uid())$$,
    'admin inserts into platform/max');

select pg_temp.as_user('9e100000-0000-4000-8000-0000000000a1');
select ok(not public.is_platform_brand_path('platform/max/share-abc.jpg'), 'non-admin refused');
select throws_ok($$insert into storage.objects (bucket_id, name, owner) values ('agent-brand', 'platform/optimist/share-u.jpg', auth.uid())$$,
    '42501', null, 'non-admin insert blocked by RLS');
reset role;

set local role anon;
select throws_ok($$select public.is_platform_brand_path('platform/max/a.jpg')$$, '42501', null, 'anon cannot call the check');
reset role;

select * from finish();
rollback;
