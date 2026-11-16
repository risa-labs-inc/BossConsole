-- pgTAP tests for 20261116110000_invite_link_mail_failed: a failed send leaves nothing behind; invite_link_admits.
-- Run with: supabase test db
begin;
select plan(45);

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


-- ---- 1. grants ----
select ok(has_function_privilege('service_role', 'public.invite_link_mail_failed(uuid,boolean)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.invite_link_mail_failed(uuid,boolean)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.invite_link_mail_failed(uuid,boolean)', 'EXECUTE')
      and has_function_privilege('anon', 'public.invite_link_admits(text,text)', 'EXECUTE')
      and has_function_privilege('authenticated', 'public.invite_link_admits(text,text)', 'EXECUTE')
      and has_function_privilege('service_role', 'public.invite_link_mail_prepare(uuid,text,text,uuid,text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.invite_link_mail_prepare(uuid,text,text,uuid,text)', 'EXECUTE'),
    'grants: mail_failed service only, admits open, prepare still service only');

-- ---- 2. a failed new send ----
select pg_temp.as_service();
create temp table base (k text primary key, n bigint) on commit drop;
grant all on base to service_role;
insert into base select 'agent_log', count(*) from public.agent_invite_mail_log where email = 'bo@other-il.test';
select pg_temp.mpk('n1', 'a5', 'ilinv', 'bo@other-il.test');
select is((select count(*)::int from public.agent_invite_mail_log where email = 'bo@other-il.test'), 1, 'the send was logged (mirror row)');
select is((select count(*)::int from public.invite_link_mail_log where link_id = pg_temp.lid('n1')), 1, 'the send was logged (link log, with the link)');
select is(public.invite_link_mail_failed(pg_temp.lid('n1'), false), '{"success":true,"result":"deleted"}'::jsonb, 'a failed new send: the link is deleted');
select is((select count(*)::int from public.invite_links where id = pg_temp.lid('n1')), 0, 'no link is left');
select is((select count(*)::int from public.agent_invite_mail_log where email = 'bo@other-il.test')
        + (select count(*)::int from public.invite_link_mail_log where email = 'bo@other-il.test'), 0, 'no mail is counted against the recipient or the org');
select is(pg_temp.mp('a5', 'ilinv', 'bo@other-il.test')->>'success', 'true', 'the address can be mailed again at once');
select is((select count(*)::int from public.invite_links where created_by = pg_temp.u('a5') and target = 'ilinv' and revoked_at is null), 1,
    'a deleted link no longer counts toward the active-link cap');
select is(public.invite_link_mail_failed(pg_temp.lid('n1'), false)->>'error', 'not_found', 'a second call: not_found');
select is(public.invite_link_mail_failed(gen_random_uuid(), false)->>'error', 'not_found', 'unknown link');
select is((select count(*)::int from public.agent_audit_log where action = 'invite_link.mail_failed' and agent_id = pg_temp.agent('ilinv')
           and detail->>'result' = 'deleted' and actor = pg_temp.u('a5') and detail::text !~ '(@|[A-Za-z0-9_-]{43})'), 1,
    'audited with the sender, no address or token');

-- limits are not consumed: three failed sends to one address leave room for three real ones
select pg_temp.mpk('f1', 'a5', 'ilinv', 'lim@other-il.test'); select public.invite_link_mail_failed(pg_temp.lid('f1'), false);
select pg_temp.mpk('f2', 'a5', 'ilinv', 'lim@other-il.test'); select public.invite_link_mail_failed(pg_temp.lid('f2'), false);
select pg_temp.mpk('f3', 'a5', 'ilinv', 'lim@other-il.test'); select public.invite_link_mail_failed(pg_temp.lid('f3'), false);
select is((pg_temp.mp('a5', 'ilinv', 'lim@other-il.test')->>'success') || (pg_temp.mp('a5', 'ilinv', 'lim@other-il.test')->>'success')
       || (pg_temp.mp('a5', 'ilinv', 'lim@other-il.test')->>'success'), 'truetruetrue', 'failed sends did not spend the per-recipient limit');
select is(pg_temp.mp('a5', 'ilinv', 'lim@other-il.test')->>'error', 'rate_limited', 'the limit still holds for real sends');

-- a platform send
select pg_temp.mpk('pf', 'a5', 'max', 'pf@other-il.test');
select is(public.invite_link_mail_failed(pg_temp.lid('pf'), false)->>'result', 'deleted', 'a platform link is deleted too');
select is((select count(*)::int from public.invite_link_mail_log where email = 'pf@other-il.test'), 0, 'and its mail-log row');
select is((select count(*)::int from public.platform_audit_log where action = 'invite_link.mail_failed' and actor = pg_temp.u('a5')), 1,
    'platform audit names the sender');

-- guards: only a pending email invite, and a "new" one only when just made
select pg_temp.mpk('g1', 'a5', 'ilinv', 'g1@other-il.test');
update public.invite_links set created_at = now() - interval '1 hour' where id = pg_temp.lid('g1');
select is(public.invite_link_mail_failed(pg_temp.lid('g1'), false)->>'error', 'not_pending', 'an old link is not deleted as a failed new send');
select pg_temp.as_user('a5');
select pg_temp.mkk('open1', 'ilinv');
select pg_temp.as_service();
select is(public.invite_link_mail_failed(pg_temp.lid('open1'), false)->>'error', 'not_pending', 'an open (non-email) link is never touched');
select pg_temp.mpk('g2', 'a5', 'ilinv', 'cy@other-il.test');
select pg_temp.as_user('a7');
select public.redeem_invite_link(pg_temp.tk('g2'));
select pg_temp.as_service();
select is(public.invite_link_mail_failed(pg_temp.lid('g2'), false)->>'error', 'not_pending', 'a redeemed link is never touched');
select is((select count(*)::int from public.invite_links where id = pg_temp.lid('g2')), 1, 'and it stays');

-- ---- 3. a failed resend ----
select pg_temp.mpk('s1', 'a5', 'ilinv', 'rs@other-il.test');
update public.invite_links set last_sent_at = timestamptz '2026-01-02 03:04:05+00' where id = pg_temp.lid('s1');
create temp table tk2 (t text) on commit drop;
grant all on tk2 to service_role;
insert into tk2 select pg_temp.mp('a5', null, null, pg_temp.lid('s1'))->>'token';
select is((select count(*)::int from public.invite_link_mail_log where link_id = pg_temp.lid('s1')), 2, 'the resend was logged');
select is(public.invite_link_mail_failed(pg_temp.lid('s1'), true), '{"success":true,"result":"restored"}'::jsonb, 'a failed resend: restored');
select is((select last_sent_at from public.invite_links where id = pg_temp.lid('s1')), timestamptz '2026-01-02 03:04:05+00', 'last_sent_at is back to its earlier value');
select is((select count(*)::int from public.invite_links where id = pg_temp.lid('s1')), 1, 'the link is kept');
select is((select count(*)::int from public.invite_link_mail_log where link_id = pg_temp.lid('s1')), 1, 'only that send''s log row is removed');
select is((select count(*)::int from public.agent_invite_mail_log where email = 'rs@other-il.test'), 1, 'and its mirror row');
select is(pg_temp.mp('a5', null, null, pg_temp.lid('s1'))->>'success', 'true', 'the owner can resend again');
select is((select count(*)::int from public.agent_audit_log where action = 'invite_link.mail_failed' and detail->>'result' = 'restored'), 1, 'resend failure audited');

-- ---- 4. invite_link_admits ----
select pg_temp.as_user('a5');
select pg_temp.mkk('open', 'ilinv'); select pg_temp.mkk('expd', 'ilinv'); select pg_temp.mkk('used', 'ilinv', 1); select pg_temp.mkk('rev', 'ilinv');
select pg_temp.as_service();
select pg_temp.mpk('bound', 'a5', 'ilinv', 'Bound@Other-IL.test');
update public.invite_links set expires_at = now() - interval '1 minute' where id = pg_temp.lid('expd');
update public.invite_links set uses = 1 where id = pg_temp.lid('used');
update public.invite_links set revoked_at = now() where id = pg_temp.lid('rev');
set local role anon;
select is(public.invite_link_admits(pg_temp.tk('open'), 'anyone@other-il.test'), '{"admits":true,"target":"ilinv"}'::jsonb, 'an open link admits any address (as anon)');
select is(public.invite_link_admits(pg_temp.tk('bound'), ' BOUND@other-il.test '), '{"admits":true,"target":"ilinv"}'::jsonb, 'a bound link admits its recipient, case and spaces aside');
select is(public.invite_link_admits(pg_temp.tk('bound'), 'someone@other-il.test'), '{"admits":false,"target":null}'::jsonb, 'a bound link refuses another address');
select is(public.invite_link_admits(pg_temp.tk('bound'), null), '{"admits":false,"target":null}'::jsonb, 'a bound link refuses a missing address');
select is(public.invite_link_admits(pg_temp.tk('open'), null), '{"admits":true,"target":"ilinv"}'::jsonb, 'an open link does not need an address');
select is(public.invite_link_admits(pg_temp.tk('expd'), 'a@b.test')->>'admits', 'false', 'expired: no');
select is(public.invite_link_admits(pg_temp.tk('used'), 'a@b.test')->>'admits', 'false', 'used up: no');
select is(public.invite_link_admits(pg_temp.tk('rev'), 'a@b.test')->>'admits', 'false', 'revoked: no');
select is(public.invite_link_admits('nope', 'a@b.test'), '{"admits":false,"target":null}'::jsonb, 'unknown token: no');
select is(public.invite_link_admits('', ''), '{"admits":false,"target":null}'::jsonb, 'empty: no');
select is(public.invite_link_admits(pg_temp.tk('bound'), 'someone@other-il.test')::text !~ 'bound', true, 'the answer never carries the recipient');
reset role;
select pg_temp.as_service();
update public.agents set invite_policy = '{"enabled":false}' where slug = 'ilinv';
select is(public.invite_link_admits(pg_temp.tk('open'), 'a@b.test')->>'admits', 'false', 'invites off: no');
update public.agents set invite_policy = '{"active_links_per_user":20}', status = 'suspended' where slug = 'ilinv';
select is(public.invite_link_admits(pg_temp.tk('open'), 'a@b.test')->>'admits', 'false', 'a target that is not live: no');
update public.agents set status = 'live' where slug = 'ilinv';
select is(public.invite_link_admits(pg_temp.tk('open'), 'a@b.test')->>'admits', 'true', 'back on: yes');
select is(public.invite_link_admits(pg_temp.tk('open'), 'a@b.test')->>'target', 'ilinv', 'the target is the link''s, not the caller''s');
select pg_temp.as_user('a5');
select pg_temp.mkk('mx', 'max');
select is(public.invite_link_admits(pg_temp.tk('mx'), 'a@b.test'), '{"admits":true,"target":"max"}'::jsonb, 'a platform link admits (target max)');
select is((public.invite_link_admits(pg_temp.tk('open'), 'a@b.test')->>'target') <> 'max', true, 'another target''s link names its own target');

select * from finish();
rollback;
