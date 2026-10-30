-- Marketplace categories and tags (20261030180000).
begin;
select plan(30);

insert into auth.users (id, email, email_confirmed_at) values
    ('c8000000-0000-4000-8000-0000000000a1', 'pub@tags.test', now()),
    ('c8000000-0000-4000-8000-0000000000c1', 'admin@tags.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c8000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';

create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', 'c8000000-0000-4000-8000-0000000000' || p, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', 'c8000000-0000-4000-8000-0000000000' || p)::text, true);
$$;
create function pg_temp.as_anon() returns void language sql as $$
    select set_config('request.jwt.claim.sub', '', true), set_config('request.jwt.claims', '{"role":"anon"}', true);
$$;
create function pg_temp.sub(slug text, cat text, tags text[]) returns jsonb language sql as $$
    select public.marketplace_submit('skill', slug, 'Skill ' || slug, 'Summary of ' || slug, '', 'Pub',
        jsonb_build_object('markdown', 'Steps for ' || slug), false, cat, tags);
$$;
create function pg_temp.approve(p_slug text) returns void language plpgsql security definer as $$
begin
    perform public.marketplace_review((select pending_version_id from public.marketplace_listings where slug = p_slug), true, null);
end $$;
create function pg_temp.audits() returns int language sql security definer as $$
    select count(*)::int from public.platform_audit_log where action in ('marketplace.category_set', 'marketplace.set_metadata') $$;
grant execute on function pg_temp.as_user(text), pg_temp.as_anon(), pg_temp.sub(text, text, text[]), pg_temp.approve(text), pg_temp.audits() to authenticated, anon;

-- ---- shape and access ----
select ok(not has_table_privilege('authenticated', 'public.marketplace_categories', 'select'), 'categories table is closed to clients');
select ok(has_function_privilege('anon', 'public.marketplace_categories()', 'EXECUTE')
      and has_function_privilege('anon', 'public.marketplace_tags(integer)', 'EXECUTE')
      and has_function_privilege('anon', 'public.marketplace_search(text,public.marketplace_kind,integer,integer,text,text)', 'EXECUTE'),
    'categories, tags and search are public');
select ok(not has_function_privilege('anon', 'public.marketplace_category_set(text,text,text,integer)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.marketplace_set_metadata(text,text,text[])', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.marketplace_norm_tags(text[])', 'EXECUTE'),
    'admin writes are not anon-callable and the tag helper is internal');
select ok(not exists (select 1 from pg_proc where proname = 'marketplace_search' and pronargs = 4), 'the 4-argument search is gone');
select ok(not exists (select 1 from pg_proc where proname = 'marketplace_submit' and pronargs = 8), 'the 8-argument submit is gone');
select is((select count(*)::int from public.marketplace_categories), 11, 'eleven seeded categories');

-- ---- tag normalisation ----
select is(public.marketplace_norm_tags(array[' Git ', 'git', 'React Native', 'c++', '', null]), array['git', 'react-native', 'c++'],
    'tags are trimmed, lowercased, dashed and deduplicated');
select throws_ok($$ select public.marketplace_norm_tags(array['a','b','c','d','e','f','g','h','i']) $$, '22023', 'too_many_tags', 'more than 8 tags refused');
select throws_ok($$ select public.marketplace_norm_tags(array['bad/tag']) $$, '22023', 'invalid_tag', 'a tag with a slash is refused');
select throws_ok($$ select public.marketplace_norm_tags(array[repeat('x', 31)]) $$, '22023', 'invalid_tag', 'a 31-character tag is refused');

-- ---- submit ----
set local role authenticated;
select pg_temp.as_user('a1');
select lives_ok($$ select pg_temp.sub('tag-git', 'development', array['Git', 'Review']) $$, 'submit with a category and tags');
select throws_ok($$ select pg_temp.sub('tag-bad', 'no-such-category', null) $$, '22023', 'invalid_category', 'an unknown category is refused');
select lives_ok($$ select public.marketplace_submit('skill', 'tag-plain', 'Plain', 'No tags here', '', 'Pub', jsonb_build_object('markdown', 'x')) $$,
    'an old-style call without category or tags still works');
select lives_ok($$ select pg_temp.sub('tag-pdf', 'documents', array['pdf', 'git']) $$, 'a second tagged skill');

select pg_temp.as_user('c1');
select pg_temp.approve('tag-git'); select pg_temp.approve('tag-plain'); select pg_temp.approve('tag-pdf');

-- ---- reads ----
select pg_temp.as_anon();
select is(public.marketplace_get('tag-git') -> 'live' ->> 'category', 'development', 'get returns the category');
select is(public.marketplace_get('tag-git') -> 'live' -> 'tags', '["git", "review"]'::jsonb, 'get returns the normalised tags');
select is(jsonb_array_length(public.marketplace_search(null, null, 50, 0, 'development', null)), 1, 'search filters by category');
select is(public.marketplace_search(null, null, 50, 0, 'development', null) -> 0 ->> 'slug', 'tag-git', 'the right listing');
select is(jsonb_array_length(public.marketplace_search(null, null, 50, 0, null, 'GIT')), 2, 'search filters by tag, case-insensitively');
select is(public.marketplace_search('review', null, 50, 0, null, null) -> 0 ->> 'slug', 'tag-git', 'a keyword matches a tag');
select is(public.marketplace_search('tag-plain') -> 0 -> 'tags', '[]'::jsonb, 'an untagged listing returns an empty tag list');
select is((select (c ->> 'count')::int from jsonb_array_elements(public.marketplace_categories()) c where c ->> 'slug' = 'documents'), 1,
    'categories count live listings');
select is(public.marketplace_tags() -> 0, '{"tag": "git", "count": 2}'::jsonb, 'the most used tag comes first');

-- ---- admin ----
select pg_temp.as_user('a1');
select throws_ok($$ select public.marketplace_category_set('cooking', 'Cooking') $$, '42501', null, 'a non-admin cannot add a category');
select throws_ok($$ select public.marketplace_set_metadata('tag-git', 'design', array['x']) $$, '42501', null, 'a non-admin cannot re-file a listing');
select pg_temp.as_user('c1');
select lives_ok($$ select public.marketplace_category_set('cooking', 'Cooking', 'Recipes and meals.', 120) $$, 'an admin adds a category');
select lives_ok($$ select public.marketplace_set_metadata('tag-plain', 'cooking', array['recipes']) $$, 'an admin re-files a live listing');
select is(public.marketplace_get('tag-plain') -> 'live' ->> 'category', 'cooking', 'the live version carries the new category');
select throws_ok($$ select public.marketplace_category_delete('cooking') $$, 'P0001', 'category_in_use', 'a used category cannot be deleted');
select is(pg_temp.audits(), 2,
    'category and metadata changes are audited');

select * from finish();
rollback;
