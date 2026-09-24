-- pgTAP tests for get_plugin_for_download (migration 20260923150000), the
-- serve RPC on the plugin-store download path.
-- Run with: cat supabase/tests/plugin_download_serve_rpc_test.sql | docker exec -i supabase_db_boss-main psql -U postgres -d postgres
--
-- routes/download.ts previously resolved the plugin with get_plugin_with_stats,
-- which scopes rows to auth.uid(); under the edge function's SERVICE-ROLE client
-- that is NULL, so every row resolved as an anonymous stranger and the
-- user_can_install_plugin gate that followed could never fire for the
-- org/unlisted rows it exists to admit -- entitled members got 404. This suite
-- pins the serve RPC itself:
--   - entitled callers (org member, unlisted link-holder, anonymous public)
--     get the row;
--   - unentitled callers (outsiders, strangers, anonymous for non-public rows)
--     get no row, indistinguishable from a missing plugin;
--   - unpublished rows are refused for everyone except the author (own draft)
--     and a global admin -- the two deliberate exceptions of the shared
--     install predicate;
--   - the RPC stays service_role only.

begin;
select plan(21);

-- ---------------------------------------------------------------------------
-- Fixtures: an organisation with an author, an ordinary member, an outsider
-- and a global admin; six plugins covering the visibility x publication matrix.
-- The id range is deliberately distinctive: suites share this database.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('b0a10000-0000-0000-0000-000000000001', 'pdlauthor@pgtap.test',   now()),
    ('b0a10000-0000-0000-0000-000000000002', 'pdlmember@pgtap.test',   now()),
    ('b0a10000-0000-0000-0000-000000000003', 'pdloutsider@pgtap.test', now()),
    ('b0a10000-0000-0000-0000-000000000004', 'pdladmin@pgtap.test',    now());

insert into public.user_roles (user_id, role_id)
select 'b0a10000-0000-0000-0000-000000000004', r.id
  from public.roles r where r.name = 'admin'
on conflict do nothing;

select public.create_organisation_internal(
    p_slug=>'pgtpdl', p_name=>'PGTap Download Serve',
    p_owner_id=>'b0a10000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'open');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select public.join_organisation(
    (select id from public.organisations where slug='pgtpdl'),
    'b0a10000-0000-0000-0000-000000000002');

insert into public.plugins (id, plugin_id, display_name, author_name, description, author_id, org_id, visibility, published)
select
    ('b0a11000-0000-0000-0000-00000000000' || n)::uuid,
    'pgtap.pdl.' || vis || (case when pub then '' else '.draft' end),
    'PDL ' || vis,
    'PGTap',
    'fixture',
    'b0a10000-0000-0000-0000-000000000001',
    (select id from public.organisations where slug='pgtpdl'),
    vis,
    pub
from (values
    (1, 'public',   true),
    (2, 'org',      true),
    (3, 'unlisted', true),
    (4, 'org',      false),
    (5, 'public',   false),
    (6, 'unlisted', false)
) AS f(n, vis, pub);


-- ===========================================================================
-- public + published: reachable by anyone, including anonymously
-- ===========================================================================
select ok(
    exists(select 1 from public.get_plugin_for_download('pgtap.pdl.public', null)),
    'a public published plugin is served anonymously'
);
select ok(
    exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.public', 'b0a10000-0000-0000-0000-000000000003')),
    'a public published plugin is served to an outsider'
);
select ok(
    exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.public', 'b0a10000-0000-0000-0000-000000000002')),
    'a public published plugin is served to a member'
);

-- ===========================================================================
-- org + published: the case the old anonymous-view lookup falsely denied
-- ===========================================================================
select ok(
    exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.org', 'b0a10000-0000-0000-0000-000000000002')),
    'an org-visible published plugin is served to an ordinary member'
);
select ok(
    not exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.org', 'b0a10000-0000-0000-0000-000000000003')),
    'an org-visible plugin is NOT served to an outsider'
);
select ok(
    not exists(select 1 from public.get_plugin_for_download('pgtap.pdl.org', null)),
    'an org-visible plugin is NOT served anonymously'
);

-- ===========================================================================
-- unlisted + published: the link-holder case, and the reason this RPC is not
-- the viewer-scoped view form (user_can_view_plugin_row denies it)
-- ===========================================================================
select ok(
    exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.unlisted', 'b0a10000-0000-0000-0000-000000000002')),
    'an unlisted published plugin is served to an ordinary member -- the link-holder case'
);
select ok(
    not exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.unlisted', 'b0a10000-0000-0000-0000-000000000003')),
    'an unlisted plugin is NOT served to an outsider -- a link is not an authorisation'
);
select ok(
    not exists(select 1 from public.get_plugin_for_download('pgtap.pdl.unlisted', null)),
    'an unlisted plugin is NOT served anonymously'
);

-- ===========================================================================
-- Unpublished: refused for everyone except the author and a global admin
-- ===========================================================================
select ok(
    not exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.org.draft', 'b0a10000-0000-0000-0000-000000000002')),
    'an unpublished org plugin is NOT served to an entitled member -- publication is checked inside the RPC'
);
select ok(
    exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.org.draft', 'b0a10000-0000-0000-0000-000000000001')),
    'the author is served their own unpublished org draft'
);
select ok(
    not exists(select 1 from public.get_plugin_for_download('pgtap.pdl.public.draft', null)),
    'an unpublished public plugin is NOT served anonymously'
);
select ok(
    not exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.public.draft', 'b0a10000-0000-0000-0000-000000000002')),
    'an unpublished public plugin is NOT served to a member'
);
select ok(
    exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.public.draft', 'b0a10000-0000-0000-0000-000000000001')),
    'the author is served their own unpublished public draft'
);
select ok(
    exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.org.draft', 'b0a10000-0000-0000-0000-000000000004')),
    'a global admin is served an unpublished org plugin'
);
select ok(
    not exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.unlisted.draft', 'b0a10000-0000-0000-0000-000000000002')),
    'an unpublished unlisted plugin is NOT served to an ordinary member -- the link-holder clause requires publication'
);
select ok(
    exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.unlisted.draft', 'b0a10000-0000-0000-0000-000000000001')),
    'the author is served their own unpublished unlisted draft'
);

-- ===========================================================================
-- No existence oracle
-- ===========================================================================
select ok(
    not exists(select 1 from public.get_plugin_for_download('pgtap.pdl.nosuch', null)),
    'an unknown plugin id yields no row anonymously'
);
select ok(
    not exists(select 1 from public.get_plugin_for_download(
        'pgtap.pdl.nosuch', 'b0a10000-0000-0000-0000-000000000001')),
    'an unknown plugin id yields no row even for the author -- no existence oracle'
);

-- ===========================================================================
-- Shape and grants
-- ===========================================================================
select is(
    (select required_permissions from public.get_plugin_for_download('pgtap.pdl.public', null)),
    ARRAY[]::TEXT[],
    'required_permissions comes back coalesced, so the route permission gate never sees NULL'
);
select ok(
    has_function_privilege('service_role',
        'public.get_plugin_for_download(text,uuid)', 'execute')
    AND NOT has_function_privilege('authenticated',
        'public.get_plugin_for_download(text,uuid)', 'execute')
    AND NOT has_function_privilege('anon',
        'public.get_plugin_for_download(text,uuid)', 'execute'),
    'service_role only -- a client-reachable form would probe which ids exist for which viewers'
);

select * from finish();
rollback;
