-- pgTAP tests for plugin-defined permission registration (migration 20260629000000).
-- Run with: supabase test db
--
-- Covers register_plugin_permission(): happy path, is_system=false, provenance,
-- never-grants, idempotency, reserved-domain rejection, invalid format, and the
-- guard that refuses to register over an existing system permission.
--
-- Fixtures are created inside the test transaction and rolled back.

begin;
select plan(12);

-- ---------------------------------------------------------------------------
-- Happy path: a new namespaced permission registers as non-system + ungranted
-- ---------------------------------------------------------------------------
select is(
    (public.register_plugin_permission('invoices.read', 'Read invoices', 'com.example.invoices') ->> 'success')::boolean,
    true, 'register new permission succeeds'
);
select is(
    (select is_system from public.permissions where name = 'invoices.read'),
    false, 'registered permission is non-system'
);
select is(
    (select pp.plugin_id from public.plugin_permissions pp
        join public.permissions p on p.id = pp.permission_id
        where p.name = 'invoices.read'),
    'com.example.invoices', 'provenance records the defining plugin'
);
select is(
    (select count(*)::int from public.role_permissions rp
        join public.permissions p on p.id = rp.permission_id
        where p.name = 'invoices.read'),
    0, 'registration never grants the permission to any role'
);

-- ---------------------------------------------------------------------------
-- Idempotency: re-registering the same name does not duplicate or error
-- ---------------------------------------------------------------------------
select is(
    (public.register_plugin_permission('invoices.read', 'Read invoices', 'com.example.invoices') ->> 'created')::boolean,
    false, 're-register is idempotent (created = false)'
);
select is(
    (select count(*)::int from public.permissions where name = 'invoices.read'),
    1, 're-register does not create a duplicate permission'
);

-- ---------------------------------------------------------------------------
-- Reserved system domains are rejected
-- ---------------------------------------------------------------------------
select is(
    (public.register_plugin_permission('role.delete', 'x', 'p') ->> 'success')::boolean,
    false, 'reserved domain "role" rejected'
);
select is(
    (public.register_plugin_permission('secret.read', 'x', 'p') ->> 'success')::boolean,
    false, 'reserved domain "secret" rejected'
);
select is(
    (public.register_plugin_permission('user.read', 'x', 'p') ->> 'success')::boolean,
    false, 'reserved domain "user" rejected'
);

-- ---------------------------------------------------------------------------
-- Invalid name format is rejected
-- ---------------------------------------------------------------------------
select is(
    (public.register_plugin_permission('nodot', 'x', 'p') ->> 'success')::boolean,
    false, 'name without domain.action is rejected'
);

-- ---------------------------------------------------------------------------
-- Cannot register over an existing system permission (non-reserved domain)
-- ---------------------------------------------------------------------------
insert into public.permissions (name, description, is_system) values ('demo.thing', 'core', true);
select is(
    (public.register_plugin_permission('demo.thing', 'x', 'p') ->> 'success')::boolean,
    false, 'cannot register over an existing system permission'
);
select is(
    (select count(*)::int from public.plugin_permissions pp
        join public.permissions p on p.id = pp.permission_id
        where p.name = 'demo.thing'),
    0, 'no provenance recorded for a system permission'
);

select * from finish();
rollback;
