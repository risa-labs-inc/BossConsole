-- pgTAP tests for 20261116130000_invite_link_resend_serialize: one resend at a time, outcome calls bound to their token.
-- Run with: supabase test db
begin;
select plan(27);

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


select ok(has_function_privilege('service_role', 'public.invite_link_mail_sent(uuid,text)', 'EXECUTE')
      and has_function_privilege('service_role', 'public.invite_link_mail_failed(uuid,boolean,text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.invite_link_mail_sent(uuid,text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.invite_link_mail_sent(uuid,text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.invite_link_mail_failed(uuid,boolean,text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.invite_link_mail_failed(uuid,boolean,text)', 'EXECUTE')
      and to_regprocedure('public.invite_link_mail_sent(uuid)') is null
      and to_regprocedure('public.invite_link_mail_failed(uuid,boolean)') is null, 'grants: service only, old signatures gone');

create temp table tn (k text primary key, t text) on commit drop;
grant all on tn to service_role, anon, authenticated;
create function pg_temp.tn(k text) returns text language sql stable as $$ select t from tn where k = $1 $$;
create function pg_temp.rs(k text, l text) returns jsonb language plpgsql as $$
declare r jsonb := public.invite_link_mail_prepare(pg_temp.u('a5'), null, null, pg_temp.lid(l));
begin
    if (r->>'success')::boolean then insert into tn values (k, r->>'token'); end if;
    return r;
end $$;
create function pg_temp.valid(t text) returns text language sql as $$ select public.invite_link_preview($1)->>'valid' $$;
grant execute on function pg_temp.tn(text), pg_temp.rs(text, text), pg_temp.valid(text) to authenticated, anon, service_role;

-- ---- A: a second resend while the first is pending ----
select pg_temp.mpk('a', 'a5', 'ilinv', 'cy@other-il.test');
select is(pg_temp.rs('a1', 'a')->>'success', 'true', 'the first resend is prepared');
select is(pg_temp.rs('a2', 'a'), '{"success":false,"error":"resend_in_progress"}'::jsonb, 'a second resend while the first is pending: resend_in_progress');
select is((select count(*)::int from public.invite_link_mail_log where link_id = pg_temp.lid('a')), 2, 'the refused resend is not logged (limits untouched)');
set local role anon;
select is(pg_temp.valid(pg_temp.tn('a1')), 'true', 'the pending resend''s token is still the current one');
reset role;
select public.invite_link_mail_sent(pg_temp.lid('a'), pg_temp.tn('a1'));
select is(pg_temp.rs('a3', 'a')->>'success', 'true', 'after the first resend is mailed, a new resend works');

-- ---- B: a stale pending is taken over; late reports from the taken-over send do nothing ----
select pg_temp.mpk('b', 'a5', 'ilinv', 'bo@other-il.test');
select pg_temp.rs('b1', 'b');
select is(pg_temp.rs('b1x', 'b')->>'error', 'resend_in_progress', 'pending within the lease: refused');
update public.invite_links set last_sent_at = now() - interval '4 minutes' where id = pg_temp.lid('b');
select is(pg_temp.rs('b1y', 'b')->>'error', 'resend_in_progress', 'still refused at 4 minutes');
update public.invite_links set last_sent_at = now() - interval '6 minutes' where id = pg_temp.lid('b');
select is(pg_temp.rs('b2', 'b')->>'success', 'true', 'a pending older than the lease is taken over');
select is(public.invite_link_mail_failed(pg_temp.lid('b'), true, pg_temp.tn('b1')), '{"success":true,"result":"stale"}'::jsonb, 'a late failure from the taken-over send is a no-op');
select public.invite_link_mail_sent(pg_temp.lid('b'), pg_temp.tn('b1'));
select is((select prev_token_hash is not null from public.invite_links where id = pg_temp.lid('b')), true, 'a late sent from the taken-over send clears nothing');
set local role anon;
select is(pg_temp.valid(pg_temp.tn('b2')), 'true', 'the current token still previews');
select is(public.invite_link_preview(pg_temp.tn('b1'))->>'reason', 'not_found', 'the taken-over token is not usable');
select is(public.invite_link_admits(pg_temp.tn('b2'), 'bo@other-il.test')->>'admits', 'true', 'the current token admits the recipient');
reset role;
select pg_temp.as_user('a6');
select is(public.redeem_invite_link(pg_temp.tn('b2'))->>'success', 'true', 'the current token redeems');
select pg_temp.as_service();

-- ---- C: failed resend with the matching token restores; a wrong token does not ----
select pg_temp.mpk('c', 'a5', 'ilinv', 'e2@other-il.test');
select pg_temp.rs('c1', 'c');
select is(public.invite_link_mail_failed(pg_temp.lid('c'), true, 'not-the-token')->>'result', 'stale', 'a wrong token is a no-op');
select is(public.invite_link_mail_failed(pg_temp.lid('c'), true, null)->>'result', 'stale', 'a null token is a no-op');
select is(public.invite_link_mail_failed(pg_temp.lid('c'), true, pg_temp.tn('c1'))->>'result', 'restored', 'the matching token restores the earlier one');
set local role anon;
select is(pg_temp.valid(pg_temp.tk('c')), 'true', 'the earlier token is valid again');
select is(public.invite_link_preview(pg_temp.tn('c1'))->>'reason', 'not_found', 'the unmailed token is gone');
reset role;
select is((select prev_token_hash is null and prev_sent_at is null from public.invite_links where id = pg_temp.lid('c')), true, 'saved values cleared');
select is(pg_temp.rs('c2', 'c')->>'success', 'true', 'a new resend is allowed again');

-- ---- D: failed NEW send ----
select pg_temp.mpk('d', 'a5', 'ilinv', 'd@other-il.test');
select is(public.invite_link_mail_failed(pg_temp.lid('d'), false, 'not-the-token')->>'result', 'stale', 'new send, wrong token: kept');
select is((select count(*)::int from public.invite_links where id = pg_temp.lid('d')), 1, 'the link is still there');
select is(public.invite_link_mail_failed(pg_temp.lid('d'), false, pg_temp.tk('d'))->>'result', 'deleted', 'new send, matching token: deleted');
select is((select count(*)::int from public.invite_links where id = pg_temp.lid('d')), 0, 'the link is gone');

-- ---- E: sent with the wrong token does nothing ----
select pg_temp.mpk('e', 'a5', 'ilinv', 'e3@other-il.test');
select pg_temp.rs('e1', 'e');
select public.invite_link_mail_sent(pg_temp.lid('e'), 'not-the-token');
select is((select prev_token_hash is not null from public.invite_links where id = pg_temp.lid('e')), true, 'sent with another token leaves the pending state');

select * from finish();
rollback;
