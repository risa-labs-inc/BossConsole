-- pgTAP test: two real PostgreSQL sessions racing to redeem ONE single-use
-- organisation invite (migration 20260801040000 + 20260923173000).
-- Run with: supabase test db
--
-- The redeem path's race safety rests on the SELECT ... FOR UPDATE on the
-- invite row: the second transaction blocks on the first's lock and then
-- re-reads the committed row, so a max_uses = 1 link admits exactly one of two
-- simultaneous clickers. Until now nothing exercised it -- a single-connection
-- test cannot, because the race needs two independent backends.
--
-- This file has TWO drivers, by necessity of the same property:
--
--   superuser driver (the local harness): two REAL sessions through dblink:
--     session 1: redeems inside an OPEN transaction, holding the row lock exactly
--                as redeem_organisation_invite leaves it mid-flight;
--     session 2: starts its own redemption asynchronously and is PROVED blocked
--                (pg_stat_activity wait_event_type = 'Lock' -- no timing
--                assumptions), then session 1 commits and session 2's answer is
--                checked. If the FOR UPDATE ever disappears, session 2 reads
--                uses = 0 before session 1 commits, admits the second user, and
--                assertions 3-6 fail.
--
--     The same machinery then runs the DOUBLE-CLICK race: the SAME user redeeming
--     twice concurrently must end up exactly once a member, with exactly one
--     redemption row and one burned use (idempotency re-checked under the lock).
--
--     WHY THIS BRANCH COMMITS, unlike every other suite here: a second session
--     can only see committed rows, so the raced world (users, organisation,
--     invites) must be committed through the dblink sessions themselves, and
--     explicitly dismantled after finish() -- the file's own rollback cannot
--     un-commit what another backend committed. Cleanup runs unconditionally in
--     a final phase; on an assertion failure the tally records it and CI's
--     disposable DB is fresh for the next run.
--
--   non-superuser driver (the CI harness connects as the non-superuser
--     postgres role, and PostgreSQL only allows a SUPERUSER session to open a
--     passwordless dblink connection to itself): the two-session races cannot
--     run there -- but CI no longer skips the file. The single-session branch
--     below drives the same state machine the races drive in parallel, in
--     order: redeem, remove, re-click -- and holds the increment's own guard
--     to account: the re-admit path that landed NO new redemption row must
--     burn NO second use. That is the assertion the OLD code fails (the
--     unconditional UPDATE burned a use on the re-click that inserted
--     nothing), and it needs no second backend. The branch rolls back, so no
--     cleanup is needed.

begin;

-- Two real backends are driven over dblink, and PostgreSQL only allows a
-- non-superuser session to open a passwordless dblink connection to itself:
-- the races run when this file is driven by a superuser session (the local
-- harness) and the single-session guard runs otherwise (the CI harness).
select rolsuper as can_race from pg_roles where rolname = current_user \gset
\if :can_race
select plan(11);


create extension if not exists dblink with schema extensions;

select extensions.dblink_connect('ic1', 'dbname=postgres user=postgres host=/var/run/postgresql');
select extensions.dblink_connect('ic2', 'dbname=postgres user=postgres host=/var/run/postgresql');

-- Session 1 speaks service_role and builds the committed world. Claims are
-- SESSION-scoped (is_local = false) so they survive across the dblink
-- transactions the orchestration opens and closes.
select * from extensions.dblink('ic1',
    format('SELECT set_config(''request.jwt.claims'', %L, false)',
           '{"role":"service_role"}')) as t(cfg text);

-- email_confirmed_at omitted: nullable in a fresh auth schema, absent from the
-- drifted gate DB; nothing in the invite path depends on it.
select extensions.dblink_exec('ic1', $q$insert into auth.users (id, email) values
    ('31000000-0000-0000-0000-000000000001', 'conc-owner@pgtap.test'),
    ('31000000-0000-0000-0000-000000000002', 'conc-racer1@pgtap.test'),
    ('31000000-0000-0000-0000-000000000003', 'conc-racer2@pgtap.test'),
    ('31000000-0000-0000-0000-000000000004', 'conc-clicker@pgtap.test')$q$);

select * from extensions.dblink('ic1',
    'SELECT public.create_organisation_internal(
        p_slug=>''pgtinvconc'', p_name=>''PGTap Invite Concurrency'',
        p_owner_id=>''31000000-0000-0000-0000-000000000001'',
        p_visibility=>''private'', p_join_policy=>''invite_only'')::text')
    as t(r text);

-- Capture the world this file must dismantle: the org id and the roles rows
-- the org creation added to the GLOBAL roles table (they do not cascade from
-- the organisations row).
create temp table conc_org as
select id as org_id from public.organisations where slug = 'pgtinvconc';
create temp table conc_roles as
select role_id from public.organisation_roles
where org_id = (select org_id from conc_org);

-- Two single-use invites, minted by the owner.
create temp table t_inv (label text, token text, invite_id uuid);
insert into t_inv
select 'race', r::jsonb ->> 'token', (r::jsonb ->> 'invite_id')::uuid
from extensions.dblink('ic1',
    format('SELECT public.create_organisation_invite(
                (SELECT id FROM public.organisations WHERE slug = ''pgtinvconc''),
                null, %L, 1, 24,
                ''31000000-0000-0000-0000-000000000001'')::text', 'race-me')) as t(r text);
insert into t_inv
select 'double-click', r::jsonb ->> 'token', (r::jsonb ->> 'invite_id')::uuid
from extensions.dblink('ic1',
    format('SELECT public.create_organisation_invite(
                (SELECT id FROM public.organisations WHERE slug = ''pgtinvconc''),
                null, %L, 1, 24,
                ''31000000-0000-0000-0000-000000000001'')::text', 'double-click-me')) as t(r text);

-- ===========================================================================
-- RACE 1: two different users, one single-use link, simultaneously.
-- ===========================================================================
-- Session 1 becomes racer1 and redeems WITHOUT committing: the redeem runs to
-- completion, so the row lock from its SELECT ... FOR UPDATE is held exactly
-- the way a mid-flight redemption holds it.
select * from extensions.dblink('ic1',
    format('SELECT set_config(''request.jwt.claims'', %L, false)',
           '{"sub":"31000000-0000-0000-0000-000000000002","role":"authenticated"}'))
    as t(cfg text);
select extensions.dblink_exec('ic1', 'begin');

create temp table ra1 as
select r from extensions.dblink('ic1',
    format('SELECT public.redeem_organisation_invite(%L)::text',
           (select token from t_inv where label = 'race'))) as t(r text);

select is(
    (select r::jsonb ->> 'success' from ra1),
    'true',
    'session 1 redeems the single-use link inside its open transaction'
);

-- Session 2 (a different user) starts its own redemption and provably blocks.
select * from extensions.dblink('ic2',
    format('SELECT set_config(''request.jwt.claims'', %L, false)',
           '{"sub":"31000000-0000-0000-0000-000000000003","role":"authenticated"}'))
    as t(cfg text);
select extensions.dblink_exec('ic2', 'begin');
select extensions.dblink_send_query('ic2',
    format('SELECT public.redeem_organisation_invite(%L)::text',
           (select token from t_inv where label = 'race')));

do $$
declare i int;
begin
    for i in 1..50 loop
        exit when exists (
            select 1 from pg_catalog.pg_stat_activity a
            where a.pid <> pg_backend_pid()
              and a.query like '%redeem_organisation_invite%'
              and a.wait_event_type = 'Lock'
        );
        perform pg_catalog.pg_sleep(0.1);
    end loop;
end $$;

select ok(
    exists (
        select 1 from pg_catalog.pg_stat_activity a
        where a.pid <> pg_backend_pid()
          and a.query like '%redeem_organisation_invite%'
          and a.wait_event_type = 'Lock'
    ),
    'the second concurrent accept is BLOCKED on the first''s row lock -- serialization, not timing'
);

-- Session 1 commits: session 2 unblocks, re-reads the committed row under the
-- FOR UPDATE, and must see the link exhausted.
select extensions.dblink_exec('ic1', 'commit');

create temp table ra2 as
select r from extensions.dblink_get_result('ic2') as t(r text);
select extensions.dblink_exec('ic2', 'rollback');

select is(
    (select r::jsonb ->> 'error' from ra2),
    'Invite link is invalid or expired',
    'the blocked second accept re-reads committed state and is REFUSED'
);
select is(
    (select count(*)::int from public.organisation_members m
      where m.org_id = (select org_id from conc_org)
        and m.user_id = '31000000-0000-0000-0000-000000000003'),
    0,
    'the losing racer is not a member: exactly one admission per single-use link'
);
select is(
    (select i.uses from public.organisation_invites i
       join t_inv on t_inv.invite_id = i.id and t_inv.label = 'race'),
    1,
    'exactly one use burned -- no lost update on the increment'
);
select is(
    (select count(*)::int from public.organisation_invite_redemptions red
       join t_inv on t_inv.invite_id = red.invite_id and t_inv.label = 'race'),
    1,
    'exactly one redemption row exists'
);

-- ===========================================================================
-- RACE 2: the SAME user double-clicking one single-use link concurrently.
-- ===========================================================================
select * from extensions.dblink('ic1',
    format('SELECT set_config(''request.jwt.claims'', %L, false)',
           '{"sub":"31000000-0000-0000-0000-000000000004","role":"authenticated"}'))
    as t(cfg text);
select extensions.dblink_exec('ic1', 'begin');

create temp table rb1 as
select r from extensions.dblink('ic1',
    format('SELECT public.redeem_organisation_invite(%L)::text',
           (select token from t_inv where label = 'double-click'))) as t(r text);

select is(
    (select r::jsonb ->> 'success' from rb1),
    'true',
    'the first click of the double-click redeems'
);

-- The second click, concurrently, as the SAME user.
select * from extensions.dblink('ic2',
    format('SELECT set_config(''request.jwt.claims'', %L, false)',
           '{"sub":"31000000-0000-0000-0000-000000000004","role":"authenticated"}'))
    as t(cfg text);
select extensions.dblink_exec('ic2', 'begin');
select extensions.dblink_send_query('ic2',
    format('SELECT public.redeem_organisation_invite(%L)::text',
           (select token from t_inv where label = 'double-click')));

select extensions.dblink_exec('ic1', 'commit');

create temp table rb2 as
select r from extensions.dblink_get_result('ic2') as t(r text);
select extensions.dblink_exec('ic2', 'rollback');

select is(
    (select r::jsonb ->> 'already_member' from rb2),
    'true',
    'the concurrent second click reports already_member -- idempotency re-checked under the lock'
);
select is(
    (select count(*)::int from public.organisation_members m
      where m.org_id = (select org_id from conc_org)
        and m.user_id = '31000000-0000-0000-0000-000000000004'),
    1,
    'the double-clicker has exactly ONE membership row'
);
select is(
    (select i.uses from public.organisation_invites i
       join t_inv on t_inv.invite_id = i.id and t_inv.label = 'double-click'),
    1,
    'the double-click burns exactly one use'
);
select is(
    (select count(*)::int from public.organisation_invite_redemptions red
       join t_inv on t_inv.invite_id = red.invite_id and t_inv.label = 'double-click'),
    1,
    'the double-click leaves exactly one redemption row'
);

select extensions.dblink_disconnect('ic1');
select extensions.dblink_disconnect('ic2');

select * from finish();
commit;

-- ---------------------------------------------------------------------------
-- Committed cleanup. The dblink sessions could only see committed state, so
-- the raced world is committed and this file's rollback cannot remove it --
-- it must be dismantled explicitly. Order matters: the organisations delete
-- cascades members/invites/redemptions/mappings, then the global roles rows
-- the org created, then the fixture users (their role grants cascade from the
-- roles delete; their membership rows already died with the organisation).
-- ---------------------------------------------------------------------------
delete from public.organisations where id = (select org_id from conc_org);
delete from public.role_hierarchy
 where parent_role_id in (select role_id from conc_roles)
    or child_role_id in (select role_id from conc_roles);
delete from public.roles where id in (select role_id from conc_roles);
delete from auth.users where id in (
    '31000000-0000-0000-0000-000000000001',
    '31000000-0000-0000-0000-000000000002',
    '31000000-0000-0000-0000-000000000003',
    '31000000-0000-0000-0000-000000000004');
commit;

\else
-- Not a superuser (the CI role): no second backend, so no two-session race --
-- but the increment's own guard is a single-session property, and it is the
-- one piece of the race this branch can still hold to account. The sequence
-- the races drive in parallel is driven here in order.
select plan(4);

-- Fixtures, transaction-local: this branch rolls back at the end.
-- email_confirmed_at omitted: nullable in a fresh auth schema, absent from the
-- drifted gate DB; nothing in the invite path depends on it.
insert into auth.users (id, email) values
    ('31000000-0000-0000-0000-000000000001', 'conc-owner@pgtap.test'),
    ('31000000-0000-0000-0000-000000000002', 'conc-racer1@pgtap.test');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select public.create_organisation_internal(
    p_slug=>'pgtinvconc', p_name=>'PGTap Invite Concurrency',
    p_owner_id=>'31000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

create temp table t_inv (label text, token text, invite_id uuid);
insert into t_inv
select 're-admit-me', r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug = 'pgtinvconc'),
    null, 're-admit-me', 2, 24,
    '31000000-0000-0000-0000-000000000001') r;

-- The first redemption: a member, a redemption row, one use burned.
select set_config('request.jwt.claims',
    '{"sub":"31000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite(
        (select token from t_inv where label = 're-admit-me')) ->> 'success'),
    'true',
    'the capped link redeems'
);
select is(
    (select i.uses from public.organisation_invites i
       join t_inv on t_inv.invite_id = i.id and t_inv.label = 're-admit-me'),
    1,
    'the first redemption burns the first use'
);

-- Re-admit-after-removal, the single-session re-enactment of the state the
-- races exercise: the member is removed, re-clicks the still-live link, and is
-- re-admitted through the redemption row they already hold -- which inserts
-- NO new row, so the guarded increment must burn NO second use. The OLD
-- unconditional increment burned one here; this assertion fails on it.
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.remove_organisation_member(
    (select id from public.organisations where slug = 'pgtinvconc'),
    '31000000-0000-0000-0000-000000000002',
    '31000000-0000-0000-0000-000000000001');

select set_config('request.jwt.claims',
    '{"sub":"31000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite(
        (select token from t_inv where label = 're-admit-me')) ->> 'status'),
    'active',
    'the removed member re-clicking the still-live link is re-admitted'
);
select is(
    (select i.uses from public.organisation_invites i
       join t_inv on t_inv.invite_id = i.id and t_inv.label = 're-admit-me'),
    1,
    'the re-admit burns NO second use -- the increment is guarded on the redemption insert'
);

select * from finish();
rollback;
\endif
