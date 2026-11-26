-- pgTAP tests for 20261126090000_invite_link_mail_deadline: a prepare that ran past its caller's budget rolls back whole.
-- The budget is relative (X-Invite-Budget-Ms against clock_timestamp() - statement_timestamp()), so a late call is made
-- here by sleeping inside the same statement. throws_ok runs its query in a subtransaction and rolls it back on the
-- error, which stands in for PostgREST rolling the request's transaction back; it is not end-to-end coverage of that.
-- Run with: supabase test db
begin;
select plan(28);

-- a1 owner, a3 agent manager, a4 plain member, a8 at the domain, a9 outside it, b1 owner of B.
insert into auth.users (id, email, email_confirmed_at) values
    ('c7000000-0000-4000-8000-0000000000a1', 'owner@acme-il.test', now()),
    ('c7000000-0000-4000-8000-0000000000a3', 'manager@acme-il.test', now()),
    ('c7000000-0000-4000-8000-0000000000a4', 'member@acme-il.test', now()),
    ('c7000000-0000-4000-8000-0000000000a8', 'dana@acme-il.test', now()),
    ('c7000000-0000-4000-8000-0000000000a9', 'bob@other-il.test', now()),
    ('c7000000-0000-4000-8000-0000000000b1', 'owner@globex-il.test', now());

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'acmeil', p_name => 'Acme IL', p_description => null,
    p_owner_id => 'c7000000-0000-4000-8000-0000000000a1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
select public.create_organisation_internal(p_slug => 'globexil', p_name => 'Globex IL', p_description => null,
    p_owner_id => 'c7000000-0000-4000-8000-0000000000b1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
select set_config('request.jwt.claims', '', true);

create temp table ids (k text primary key, id uuid) on commit drop;
grant all on ids to authenticated, anon, service_role;
insert into ids select 'orga', id from public.organisations where slug = 'acmeil';
insert into ids select 'a_' || coalesce(orl.preset, orl.kind), orl.role_id from public.organisation_roles orl
    where orl.org_id = (select id from ids where k = 'orga');

create function pg_temp.id(p text) returns uuid language sql stable as $$ select id from ids where k = p $$;
create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c7000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.agent(p text) returns uuid language sql stable as $$ select id from ids where k = 'g_' || p $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p),
               'email', (select email from auth.users where id = pg_temp.u(p)))::text, true);
$$;
create function pg_temp.as_service() returns void language sql as $$
    select set_config('request.jwt.claim.sub', '', true), set_config('request.jwt.claims', '{"role":"service_role"}', true);
$$;
grant execute on function pg_temp.id(text), pg_temp.u(text), pg_temp.agent(text), pg_temp.as_user(text), pg_temp.as_service()
    to authenticated, anon, service_role;

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
    select pg_temp.id('orga'), pg_temp.u(x), 'active', now(), 'admin' from unnest(array['a3','a4']) x;
insert into public.user_roles (user_id, role_id) values
    (pg_temp.u('a3'), pg_temp.id('a_agent_manager')),
    (pg_temp.u('a4'), pg_temp.id('a_user'));


-- a5, a6, a7 outside people (a5 also uses ilinv); c1 platform admin; d1 member of the Optimist org.
insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values
    ('c7000000-0000-4000-8000-0000000000a5', 'ann@other-il.test', now(), '{"full_name":"Ann Smith"}'),
    ('c7000000-0000-4000-8000-0000000000a6', 'bo@other-il.test', now(), '{}'),
    ('c7000000-0000-4000-8000-0000000000a7', 'cy@other-il.test', now(), '{}'),
    ('c7000000-0000-4000-8000-0000000000c1', 'root@acme-il.test', now(), '{}'),
    ('c7000000-0000-4000-8000-0000000000d1', 'opt@other-il.test', now(), '{}');
insert into public.user_roles (user_id, role_id) select pg_temp.u('c1'), id from public.roles where name = 'admin';

insert into public.agents (slug, org_id, name, plugin_id, created_by, status, signup_policy, brand) values
    ('ilinv', pg_temp.id('orga'), 'Inv', 'ai.rever.boss.plugin.dynamic.agent.ilinv', pg_temp.u('a1'), 'live', '{"mode":"invite"}', '{"name":"Inv Brand"}'),
    ('ildom', pg_temp.id('orga'), 'Dom', 'ai.rever.boss.plugin.dynamic.agent.ildom', pg_temp.u('a1'), 'live', '{"mode":"domain","domains":["acme-il.test"]}', '{}'),
    ('ilmem', pg_temp.id('orga'), 'Mem', 'ai.rever.boss.plugin.dynamic.agent.ilmem', pg_temp.u('a1'), 'live', '{"mode":"invite"}', '{}'),
    ('iladm', pg_temp.id('orga'), 'Adm', 'ai.rever.boss.plugin.dynamic.agent.iladm', pg_temp.u('a1'), 'live', '{"mode":"invite"}', '{}'),
    ('iloff', pg_temp.id('orga'), 'Off', 'ai.rever.boss.plugin.dynamic.agent.iloff', pg_temp.u('a1'), 'live', '{"mode":"invite"}', '{}'),
    ('ildraft', pg_temp.id('orga'), 'Draft', 'ai.rever.boss.plugin.dynamic.agent.ildraft', pg_temp.u('a1'), 'draft', '{"mode":"invite"}', '{}'),
    ('ilq', pg_temp.id('orga'), 'Quota', 'ai.rever.boss.plugin.dynamic.agent.ilq', pg_temp.u('a1'), 'live', '{"mode":"invite"}', '{}');
insert into ids select 'g_' || slug, id from public.agents where slug like 'il%';
update public.agents set invite_policy = '{"active_links_per_user":20}' where slug = 'ilinv';
update public.agents set invite_policy = '{"who":"members"}' where slug = 'ilmem';
update public.agents set invite_policy = '{"who":"admins"}' where slug = 'iladm';
update public.agents set invite_policy = '{"enabled":false}' where slug = 'iloff';
update public.agents set invite_policy = '{"per_user_per_month":2,"active_links_per_user":2,"org_per_month":3,"max_uses":5}' where slug = 'ilq';
-- a5 uses ilinv (installed); the Optimist org has d1.
insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state)
    values (pg_temp.u('a5'), pg_temp.agent('ilinv'), 'il-a5', 59001, 'il/a5', 'running');
insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state)
    values (pg_temp.u('a6'), pg_temp.agent('ilinv'), 'il-a6', 59002, 'il/a6', 'removed');
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'optimistil', p_name => 'Optimist IL', p_description => null,
    p_owner_id => pg_temp.u('d1'), p_domain => null, p_visibility => 'private', p_join_policy => 'invite_only');
select set_config('request.jwt.claims', '', true);
update public.platform_flags set value = to_jsonb((select id::text from public.organisations where slug = 'optimistil'))
    where key = 'optimist_org_id';

create function pg_temp.mk(p text, a int default null, b int default null) returns jsonb language sql as $$
    select public.create_invite_link(p, a, b, 'api') $$;
grant execute on function pg_temp.mk(text, int, int) to authenticated, anon, service_role;
create temp table toks (k text primary key, t text, id uuid) on commit drop;
grant all on toks to authenticated, anon, service_role;
create function pg_temp.mkk(k text, p text, a int default null, b int default null) returns jsonb language plpgsql as $$
declare r jsonb := public.create_invite_link(p, a, b, 'api');
begin
    if (r->>'success')::boolean then insert into toks values (k, r->>'token', (r->>'id')::uuid); end if;
    return r;
end $$;
create function pg_temp.tk(k text) returns text language sql stable as $$ select t from toks where k = $1 $$;
create function pg_temp.lid(k text) returns uuid language sql stable as $$ select id from toks where k = $1 $$;
grant execute on function pg_temp.mkk(text, text, int, int), pg_temp.tk(text), pg_temp.lid(text) to authenticated, anon, service_role;


select pg_temp.as_service();
insert into public.platform_access (email, platform) values ('ann@other-il.test', 'max');
create function pg_temp.mp(a text, t text, e text, l uuid default null, v text default 'api') returns jsonb language sql as $$
    select public.invite_link_mail_prepare(pg_temp.u(a), t, e, l, v) $$;
create function pg_temp.mpk(k text, a text, t text, e text) returns jsonb language plpgsql as $$
declare r jsonb := public.invite_link_mail_prepare(pg_temp.u(a), t, e);
begin
    if (r->>'success')::boolean then insert into toks values (k, r->>'token', (r->>'link_id')::uuid); end if;
    return r;
end $$;
grant execute on function pg_temp.mp(text, text, text, uuid, text), pg_temp.mpk(text, text, text, text) to authenticated, anon, service_role;


create function pg_temp.budget(p text) returns void language sql as $$
    select set_config('request.headers', json_build_object('x-invite-budget-ms', p, 'x-other', 'y')::text, true) $$;
create function pg_temp.seen(p text) returns integer language sql as $$
    select pg_temp.budget(p); select public.invite_link_mail_budget_ms() $$;
create function pg_temp.links(e text) returns int language sql stable as $$
    select count(*)::int from public.invite_links where recipient_email = e::extensions.citext $$;
create function pg_temp.logs(e text) returns int language sql stable as $$
    select count(*)::int from public.invite_link_mail_log where email = e::extensions.citext $$;
grant execute on function pg_temp.budget(text), pg_temp.seen(text), pg_temp.links(text), pg_temp.logs(text) to authenticated, anon, service_role;

select ok(has_function_privilege('service_role', 'public.invite_link_mail_budget_ms()', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.invite_link_mail_budget_ms()', 'EXECUTE')
      and not has_function_privilege('anon', 'public.invite_link_mail_budget_ms()', 'EXECUTE')
      and has_function_privilege('service_role', 'public.invite_link_mail_over_budget(integer)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.invite_link_mail_over_budget(integer)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.invite_link_mail_over_budget(integer)', 'EXECUTE')
      and has_function_privilege('service_role', 'public.invite_link_mail_prepare(uuid,text,text,uuid,text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.invite_link_mail_prepare(uuid,text,text,uuid,text)', 'EXECUTE'),
      'grants: service only');

-- ---- A: reading the budget. No header, or anything but 100..60000 whole milliseconds, is no budget ----
select is(public.invite_link_mail_budget_ms(), null, 'no header: no budget');
select is(pg_temp.seen('5000'), 5000, 'a budget in milliseconds is read');
select is(array[pg_temp.seen('100'), pg_temp.seen('60000')], array[100, 60000], 'the bounds themselves are accepted');
select is(array[pg_temp.seen('99'), pg_temp.seen('0'), pg_temp.seen('60001'), pg_temp.seen('99999'), pg_temp.seen('100000')],
    array[null, null, null, null, null]::int[], 'a budget outside 100..60000 ms is ignored');
select is(array[pg_temp.seen('soon'), pg_temp.seen('-5'), pg_temp.seen('5000.5'), pg_temp.seen(' 5000'), pg_temp.seen(''),
                pg_temp.seen('5e3'), pg_temp.seen('1795686203624'), pg_temp.seen('1795686203')],
    array[null, null, null, null, null, null, null, null]::int[],
    'a budget that is not a plain number is ignored, an epoch in milliseconds or seconds included');
select set_config('request.headers', 'not json', true);
select is(public.invite_link_mail_budget_ms(), null, 'headers that are not JSON are ignored');
select set_config('request.headers', '["x-invite-budget-ms", 5000]', true);
select is(public.invite_link_mail_budget_ms(), null, 'headers that are not a JSON object are ignored');
select set_config('request.headers', '{"x-invite-budget-ms": {"ms": 5000}}', true);
select is(public.invite_link_mail_budget_ms(), null, 'a budget that is not a string or number is ignored');
select ok(pg_get_functiondef('public.invite_link_mail_budget_ms()'::regprocedure) !~* 'when\s+others'
      and pg_get_functiondef('public.invite_link_mail_over_budget(integer)'::regprocedure) !~* 'when\s+others',
      'no catch-all handler: a cancel or a statement timeout is never swallowed');

-- ---- B: the comparison is elapsed time on the database's own clock ----
select is(public.invite_link_mail_over_budget(null), false, 'no budget is never over');
select is(public.invite_link_mail_over_budget(5000), false, 'a statement that just started is within its budget');
with z as materialized (select pg_sleep(0.25))
select is((select public.invite_link_mail_over_budget(100) from z), true, 'a statement that has run longer than its budget is over it');
with z as materialized (select pg_sleep(0.25))
select is((select public.invite_link_mail_over_budget(5000) from z), false, 'the same statement is within a larger budget');

-- ---- C: within budget, without one, and with a malformed one, an invite is prepared as before ----
select set_config('request.headers', '', true);
select is((select array[r->>'success', (r ? 'budget_ms')::text, coalesce(r->>'budget_ms', 'null')]
           from pg_temp.mpk('a', 'a5', 'ilinv', 'n1@other-il.test') r),
    array['true', 'true', 'null'], 'no header: prepared, and the answer says no budget was applied');
select pg_temp.budget('5000');
select is((select array[r->>'success', r->>'budget_ms'] from pg_temp.mpk('b', 'a5', 'ilinv', 'n2@other-il.test') r),
    array['true', '5000'], 'within budget: prepared, and the answer carries the budget that was applied');
select pg_temp.budget('-5');
select is((select array[r->>'success', coalesce(r->>'budget_ms', 'null')] from pg_temp.mpk('c', 'a5', 'ilinv', 'n3@other-il.test') r),
    array['true', 'null'], 'a malformed budget: prepared, no budget applied');
select is((select r ?& array['link_id', 'token', 'target', 'recipient_email', 'expires_at']
           from public.invite_link_mail_prepare(pg_temp.u('a5'), 'ilinv', 'n4@other-il.test') r), true,
    'the facts the mail needs are all still in the answer');

-- ---- D: a new invite over budget raises and leaves no link and no mail-log row ----
select pg_temp.budget('100');
select throws_ok($$ with z as materialized (select pg_sleep(0.25))
                    select public.invite_link_mail_prepare(pg_temp.u('a5'), 'ilinv', 'late@other-il.test') from z $$,
    'PT504', 'budget_exceeded', 'a new invite over its budget raises PT504 budget_exceeded');
select is(pg_temp.links('late@other-il.test'), 0, 'no link is left behind');
select is(pg_temp.logs('late@other-il.test'), 0, 'no mail-log row is left behind (limits untouched)');
select is((select count(*)::int from public.invite_links where created_by = pg_temp.u('a5') and target = 'ilinv'
           and revoked_at is null and uses < max_uses), 4, 'the sender''s active links are only the four that were answered');
select is((with z as materialized (select pg_sleep(0.25))
           select public.invite_link_mail_prepare(pg_temp.u('a5'), 'ilinv', 'late@other-il.test')->>'success' from z), 'true',
    'the same slow call without a budget still prepares (the header is what arms it)')
    from (select set_config('request.headers', '', true)) h;
select set_config('request.headers', '', true);

-- ---- E: a refusal is still a refusal over budget (nothing was made, so nothing to undo) ----
select pg_temp.budget('100');
select is((with z as materialized (select pg_sleep(0.25))
           select public.invite_link_mail_prepare(pg_temp.u('a5'), 'ilinv', 'not-an-address')->>'error' from z), 'bad_email',
    'refusals are answered as before');

-- ---- F: a resend over budget keeps the earlier token working ----
select set_config('request.headers', '', true);
select public.invite_link_mail_sent(pg_temp.lid('a'), pg_temp.tk('a'));
select pg_temp.budget('100');
select throws_ok($$ with z as materialized (select pg_sleep(0.25))
                    select public.invite_link_mail_prepare(pg_temp.u('a5'), null, null, pg_temp.lid('a')) from z $$,
    'PT504', 'budget_exceeded', 'a resend over its budget raises too');
select is((select token_hash = encode(extensions.digest(pg_temp.tk('a'), 'sha256'), 'hex') and prev_token_hash is null
           from public.invite_links where id = pg_temp.lid('a')), true, 'the token was not rotated and no resend is pending');
select is((select count(*)::int from public.invite_link_mail_log where link_id = pg_temp.lid('a')), 1, 'the resend is not logged');
select pg_temp.budget('5000');
select is((select array[r->>'success', r->>'budget_ms']
           from public.invite_link_mail_prepare(pg_temp.u('a5'), null, null, pg_temp.lid('a')) r), array['true', '5000'],
    'the same resend within its budget works and reports the budget');

select * from finish();
rollback;
