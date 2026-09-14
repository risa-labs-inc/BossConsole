-- pgTAP tests for user_can_install_plugin (migration 20260805000000).
-- Run with: supabase test db
--
-- The download route is gated on this, so the assertions that matter are the
-- denials. The one case worth stating up front is `unlisted`: it is why this
-- function exists separately from user_can_view_plugin, and a change that
-- collapses the two will fail exactly two tests here.

begin;
select plan(25);

-- ---------------------------------------------------------------------------
-- Fixtures: two organisations, an author, a member of each, and an outsider.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('30000000-0000-0000-0000-000000000001', 'pivauthor@pgtap.test',   now()),
    ('30000000-0000-0000-0000-000000000002', 'pivmember@pgtap.test',   now()),
    ('30000000-0000-0000-0000-000000000003', 'pivoutsider@pgtap.test', now()),
    ('30000000-0000-0000-0000-000000000004', 'pivadmin@pgtap.test',    now());

insert into public.user_roles (user_id, role_id)
select '30000000-0000-0000-0000-000000000004', r.id
  from public.roles r where r.name = 'admin'
on conflict do nothing;

select public.create_organisation_internal(
    p_slug=>'pgtpiv', p_name=>'PGTap Install Visibility',
    p_owner_id=>'30000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'open');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select public.join_organisation(
    (select id from public.organisations where slug='pgtpiv'),
    '30000000-0000-0000-0000-000000000002');

-- Four plugins, one per visibility, all owned by that organisation.
insert into public.plugins (id, plugin_id, display_name, author_name, description, author_id, org_id, visibility, published)
select
    ('40000000-0000-0000-0000-00000000000' || n)::uuid,
    'pgtap.piv.' || vis || (case when pub then '' else '.draft' end),
    'PIV ' || vis,
    'PGTap',
    'fixture',
    '30000000-0000-0000-0000-000000000001',
    (select id from public.organisations where slug='pgtpiv'),
    vis,
    pub
from (values
    (1, 'public',   true),
    (2, 'org',      true),
    (3, 'unlisted', true),
    (4, 'public',   false)
) AS f(n, vis, pub);


-- ===========================================================================
-- public + published: reachable by anyone, including anonymously
-- ===========================================================================
select ok(
    public.user_can_install_plugin(null, '40000000-0000-0000-0000-000000000001'),
    'a public published plugin is installable anonymously'
);
select ok(
    public.user_can_install_plugin('30000000-0000-0000-0000-000000000003',
        '40000000-0000-0000-0000-000000000001'),
    'a public published plugin is installable by an outsider'
);

-- ===========================================================================
-- org visibility: members only
-- ===========================================================================
select ok(
    public.user_can_install_plugin('30000000-0000-0000-0000-000000000002',
        '40000000-0000-0000-0000-000000000002'),
    'an org-visible plugin is installable by a member'
);
select ok(
    NOT public.user_can_install_plugin('30000000-0000-0000-0000-000000000003',
        '40000000-0000-0000-0000-000000000002'),
    'an org-visible plugin is NOT installable by an outsider'
);
select ok(
    NOT public.user_can_install_plugin(null, '40000000-0000-0000-0000-000000000002'),
    'an org-visible plugin is NOT installable anonymously'
);

-- ===========================================================================
-- unlisted: the whole reason this function is not user_can_view_plugin
-- ===========================================================================
select ok(
    public.user_can_install_plugin('30000000-0000-0000-0000-000000000002',
        '40000000-0000-0000-0000-000000000003'),
    'an unlisted plugin IS installable by an ordinary member -- unlisted means absent from listings, not un-installable'
);
select ok(
    NOT public.user_can_view_plugin('30000000-0000-0000-0000-000000000002',
        '40000000-0000-0000-0000-000000000003'),
    'the same member cannot SEE it in listings -- the two predicates genuinely differ here'
);
select ok(
    NOT public.user_can_install_plugin('30000000-0000-0000-0000-000000000003',
        '40000000-0000-0000-0000-000000000003'),
    'an unlisted plugin is NOT installable by an outsider -- a link is not an authorisation'
);
select ok(
    NOT public.user_can_install_plugin(null, '40000000-0000-0000-0000-000000000003'),
    'an unlisted plugin is NOT installable anonymously'
);

-- ===========================================================================
-- Unpublished drafts
-- ===========================================================================
select ok(
    public.user_can_install_plugin('30000000-0000-0000-0000-000000000001',
        '40000000-0000-0000-0000-000000000004'),
    'an author reaches their own unpublished draft'
);
select ok(
    NOT public.user_can_install_plugin('30000000-0000-0000-0000-000000000002',
        '40000000-0000-0000-0000-000000000004'),
    'a fellow member does NOT reach an unpublished draft'
);
select ok(
    NOT public.user_can_install_plugin(null, '40000000-0000-0000-0000-000000000004'),
    'an unpublished public draft is not installable anonymously'
);

-- An unlisted plugin that is not published must stay unreachable: the extra
-- clause requires p.published, and dropping that would make every draft
-- installable org-wide.
update public.plugins set published = false
 where id = '40000000-0000-0000-0000-000000000003';
select ok(
    NOT public.user_can_install_plugin('30000000-0000-0000-0000-000000000002',
        '40000000-0000-0000-0000-000000000003'),
    'an UNPUBLISHED unlisted plugin is not installable by a member'
);
update public.plugins set published = true
 where id = '40000000-0000-0000-0000-000000000003';

-- ===========================================================================
-- Admin and unknown ids
-- ===========================================================================
select ok(
    public.user_can_install_plugin('30000000-0000-0000-0000-000000000004',
        '40000000-0000-0000-0000-000000000002'),
    'a global admin reaches an org-visible plugin'
);
select ok(
    NOT public.user_can_install_plugin('30000000-0000-0000-0000-000000000002',
        '40000000-0000-0000-0000-0000000000ff'),
    'an unknown plugin id is false, not an error'
);

-- ===========================================================================
-- Store download lookup: installability, not listing visibility
-- ===========================================================================
select is(
    (select count(*)::int from public.get_plugin_install_info_for_viewer(
        'pgtap.piv.org', '30000000-0000-0000-0000-000000000002')),
    1,
    'the viewer-scoped install lookup returns an org plugin to its member'
);
select is(
    (select count(*)::int from public.get_plugin_install_info_for_viewer(
        'pgtap.piv.org', '30000000-0000-0000-0000-000000000003')),
    0,
    'the viewer-scoped install lookup hides an org plugin from an outsider'
);
select is(
    (select count(*)::int from public.get_plugin_install_info_for_viewer(
        'pgtap.piv.unlisted', '30000000-0000-0000-0000-000000000002')),
    1,
    'the viewer-scoped install lookup preserves direct-link installs for unlisted members'
);
select is(
    (select count(*)::int from public.get_plugin_install_info_for_viewer(
        'pgtap.piv.unlisted', null)),
    0,
    'the viewer-scoped install lookup does not make an unlisted link public'
);

select is(
    (select count(*)::int from public.get_plugin_install_info_for_viewer('pgtap.piv.public', null)),
    1, 'anonymous readers can download a public published plugin through the lookup'
);
select is(
    (select required_permissions from public.get_plugin_install_info_for_viewer('pgtap.piv.public', null)),
    '{}'::text[], 'the lookup returns an empty permission array for a baseline plugin'
);
update public.plugins set required_permissions = ARRAY['plugin.read'] where plugin_id = 'pgtap.piv.org';
select is(
    (select required_permissions from public.get_plugin_install_info_for_viewer(
        'pgtap.piv.org', '30000000-0000-0000-0000-000000000002')),
    ARRAY['plugin.read']::text[], 'the lookup preserves required permissions for the route gate'
);
select is(
    (select count(*)::int from public.get_plugin_install_info_for_viewer(
        'pgtap.piv.public.draft', '30000000-0000-0000-0000-000000000001')),
    1, 'the lookup preserves the install predicate author access to drafts'
);

-- ===========================================================================
-- Grants: the edge function calls this with the service-role client
-- ===========================================================================
select ok(
    has_function_privilege('service_role',
        'public.user_can_install_plugin(uuid,uuid)', 'execute')
    AND NOT has_function_privilege('authenticated',
        'public.user_can_install_plugin(uuid,uuid)', 'execute')
    AND NOT has_function_privilege('anon',
        'public.user_can_install_plugin(uuid,uuid)', 'execute'),
    'service_role only -- an authenticated client asking about arbitrary users would be an enumeration surface'
);
select ok(
    has_function_privilege('service_role',
        'public.get_plugin_install_info_for_viewer(text,uuid)', 'execute')
    AND NOT has_function_privilege('authenticated',
        'public.get_plugin_install_info_for_viewer(text,uuid)', 'execute')
    AND NOT has_function_privilege('anon',
        'public.get_plugin_install_info_for_viewer(text,uuid)', 'execute'),
    'the install metadata lookup is service_role only'
);

select * from finish();
rollback;
