-- 20260927120000_restrict_plugin_trust_columns.sql: a plugin's author must not
-- be able to write its trust columns directly. `verified` (the admin-only
-- checkmark) is not client-writable at all, and `org_id` (organisation
-- attribution) cannot be changed by a client on an existing row. Every other
-- column keeps working as the row policies allow, and INSERT of org_id keeps
-- working under the INSERT policy's own can_publish_org_plugin check.
--
-- Client statements run under `set local role authenticated` with a JWT claim,
-- the same privileges PostgREST applies; fixtures are staged as the test owner.
begin;
select plan(17);

insert into auth.users (id, email) values
    ('e1900000-0000-4000-8000-000000000001', 'author@pgtap.test'),
    ('e1900000-0000-4000-8000-000000000002', 'other-owner@pgtap.test');

-- An organisation the author does not administer, to attempt attribution to.
select public.create_organisation_internal(
    p_slug => 'ptc-other', p_name => 'PTC Other',
    p_owner_id => 'e1900000-0000-4000-8000-000000000002',
    p_visibility => 'private', p_join_policy => 'invite_only');

-- The author cannot see that private organisation, so its id is handed over the
-- way a leaked id would be (an invite link, a log line, a screenshot).
create temp table target_org as select id from public.organisations where slug = 'ptc-other';
grant select on target_org to authenticated;

-- The author's own unverified public plugin, staged by the owner so the row
-- exists regardless of the INSERT rules under test.
insert into public.plugins (id, plugin_id, display_name, author_id, author_name, visibility, published, verified)
values ('e1900000-0000-4000-8000-0000000000b1', 'dev.author.tool', 'Author Tool',
        'e1900000-0000-4000-8000-000000000001', 'author', 'public', true, false);

select set_config('request.jwt.claims',
    '{"sub":"e1900000-0000-4000-8000-000000000001","role":"authenticated"}', true);
set local role authenticated;

-- ---- The trust columns are refused, on the verbs each was reachable through.
select throws_ok(
    $$ update public.plugins set verified = true where plugin_id = 'dev.author.tool' $$,
    '42501', null,
    'an author cannot set verified on their own plugin');
select throws_ok(
    $$ insert into public.plugins (plugin_id, display_name, author_id, author_name, verified)
       values ('dev.author.evil', 'Evil', 'e1900000-0000-4000-8000-000000000001', 'author', true) $$,
    '42501', null,
    'an author cannot insert a self-verified plugin');
select throws_ok(
    $$ update public.plugins set org_id = (select id from target_org) where plugin_id = 'dev.author.tool' $$,
    '42501', null,
    'an author cannot attribute their plugin to an organisation they do not administer');

-- ---- Every other client write path still works.
select lives_ok(
    $$ update public.plugins set display_name = 'Renamed', description = 'edited', homepage_url = 'https://ex.test'
       where plugin_id = 'dev.author.tool' $$,
    'an author can still edit the ordinary columns of their own plugin');
select is(
    (select display_name from public.plugins where plugin_id = 'dev.author.tool'),
    'Renamed',
    'and the edit landed');
select lives_ok(
    $$ insert into public.plugins (plugin_id, display_name, author_id, author_name)
       values ('dev.author.second', 'Second', 'e1900000-0000-4000-8000-000000000001', 'author') $$,
    'an author can still create an ordinary plugin');
reset role;

-- The staged verified flag is untouched: the author never wrote it.
select is(
    (select verified from public.plugins where plugin_id = 'dev.author.tool'),
    false,
    'the plugin is still unverified after the author''s attempts');
select is(
    (select org_id from public.plugins where plugin_id = 'dev.author.tool'),
    null::uuid,
    'and it is still attributed to no organisation');

-- ---- The catalog states the rule, independent of any one statement above.
select is(
    (select count(*)::int from information_schema.table_privileges
     where table_schema = 'public' and table_name = 'plugins'
       and grantee in ('anon', 'authenticated') and privilege_type in ('INSERT', 'UPDATE')),
    0,
    'no client role holds a table-level INSERT or UPDATE on plugins');
select ok(
    not has_column_privilege('authenticated', 'public.plugins', 'verified', 'INSERT')
    and not has_column_privilege('authenticated', 'public.plugins', 'verified', 'UPDATE')
    and not has_column_privilege('anon', 'public.plugins', 'verified', 'INSERT')
    and not has_column_privilege('anon', 'public.plugins', 'verified', 'UPDATE'),
    'no client role can write verified, on either verb');
select ok(
    not has_column_privilege('authenticated', 'public.plugins', 'org_id', 'UPDATE')
    and not has_column_privilege('anon', 'public.plugins', 'org_id', 'UPDATE'),
    'no client role can change org_id on an existing row');
select ok(
    has_column_privilege('authenticated', 'public.plugins', 'org_id', 'INSERT'),
    'INSERT of org_id is kept, still gated by the INSERT policy''s can_publish_org_plugin');
select ok(
    has_column_privilege('authenticated', 'public.plugins', 'display_name', 'UPDATE')
    and has_column_privilege('authenticated', 'public.plugins', 'display_name', 'INSERT'),
    'authenticated keeps write access to the ordinary columns');

-- ---- The closed set, exactly. A column added to public.plugins is not
-- client-writable until a migration grants it; these fail until someone decides
-- whether it should be, and either grants it or adds it here on purpose.
select is(
    (select array_agg(a.attname::text order by a.attname) from pg_attribute a
     where a.attrelid = 'public.plugins'::regclass and a.attnum > 0 and not a.attisdropped
       and not has_column_privilege('authenticated', 'public.plugins', a.attname, 'UPDATE')),
    array['org_id', 'verified'],
    'exactly verified and org_id are closed to an authenticated UPDATE');
select is(
    (select array_agg(a.attname::text order by a.attname) from pg_attribute a
     where a.attrelid = 'public.plugins'::regclass and a.attnum > 0 and not a.attisdropped
       and not has_column_privilege('authenticated', 'public.plugins', a.attname, 'INSERT')),
    array['verified'],
    'exactly verified is closed to an authenticated INSERT');
select is(
    (select array_agg(a.attname::text order by a.attname) from pg_attribute a
     where a.attrelid = 'public.plugins'::regclass and a.attnum > 0 and not a.attisdropped
       and not has_column_privilege('anon', 'public.plugins', a.attname, 'UPDATE')),
    array['org_id', 'verified'],
    'and the same two for anon UPDATE');
select is(
    (select array_agg(a.attname::text order by a.attname) from pg_attribute a
     where a.attrelid = 'public.plugins'::regclass and a.attnum > 0 and not a.attisdropped
       and not has_column_privilege('anon', 'public.plugins', a.attname, 'INSERT')),
    array['verified'],
    'and verified alone for anon INSERT');

select * from finish();
rollback;
