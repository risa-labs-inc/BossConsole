-- pgTAP tests for 20261116120000_invite_link_resend_restore_token: a failed resend gives the earlier link back.
-- Run with: supabase test db
begin;
select plan(20);

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
      and not has_function_privilege('authenticated', 'public.invite_link_mail_sent(uuid,text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.invite_link_mail_sent(uuid,text)', 'EXECUTE')
      and has_function_privilege('service_role', 'public.invite_link_mail_prepare(uuid,text,text,uuid,text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.invite_link_mail_failed(uuid,boolean,text)', 'EXECUTE'), 'grants: the mail functions are service only');

create temp table tn (k text primary key, t text) on commit drop;
grant all on tn to service_role, anon, authenticated;

-- ---- a failed resend ----
select pg_temp.mpk('x', 'a5', 'ilinv', 'cy@other-il.test');
insert into tn select 'x', pg_temp.mp('a5', null, null, pg_temp.lid('x'))->>'token';
select isnt((select t from tn where k = 'x'), pg_temp.tk('x'), 'the resend rotated the token');
set local role anon;
select is(public.invite_link_preview(pg_temp.tk('x'))->>'reason', 'not_found', 'while the resend is in flight the old token is replaced');
reset role;
select pg_temp.as_service();
select is(public.invite_link_mail_failed(pg_temp.lid('x'), true, (select t from tn where k = 'x'))->>'result', 'restored', 'the resend failed');
set local role anon;
select is(public.invite_link_preview(pg_temp.tk('x'))->>'valid', 'true', 'failed resend: the earlier token previews valid again');
select is(public.invite_link_preview((select t from tn where k = 'x'))->>'reason', 'not_found', 'failed resend: the token that was never mailed is not_found');
select is(public.invite_link_admits(pg_temp.tk('x'), 'cy@other-il.test')->>'admits', 'true', 'failed resend: admits the recipient with the earlier token');
reset role;
select is((select prev_token_hash is null and prev_sent_at is null from public.invite_links where id = pg_temp.lid('x')), true, 'the saved values are cleared');
select pg_temp.as_user('a7');
select is(public.redeem_invite_link((select t from tn where k = 'x'))->>'error', 'not_found', 'failed resend: the unmailed token cannot be redeemed');
select is(public.redeem_invite_link(pg_temp.tk('x')), '{"success":true,"target":"ilinv","already":false}'::jsonb, 'failed resend: the earlier token redeems');
select pg_temp.as_service();

-- ---- a successful resend ----
select pg_temp.mpk('y', 'a5', 'ilinv', 'bo@other-il.test');
insert into tn select 'y', pg_temp.mp('a5', null, null, pg_temp.lid('y'))->>'token';
select is((select prev_token_hash is not null from public.invite_links where id = pg_temp.lid('y')), true, 'a resend keeps the previous hash until the outcome is known');
select public.invite_link_mail_sent(pg_temp.lid('y'), (select t from tn where k = 'y'));
select is((select prev_token_hash is null and prev_sent_at is null from public.invite_links where id = pg_temp.lid('y')), true, 'a sent mail clears the saved values');
set local role anon;
select is(public.invite_link_preview(pg_temp.tk('y'))->>'reason', 'not_found', 'successful resend: the old token is not_found');
select is(public.invite_link_preview((select t from tn where k = 'y'))->>'valid', 'true', 'successful resend: the new token is valid');
reset role;
select is(public.invite_link_mail_failed(pg_temp.lid('y'), true, (select t from tn where k = 'y'))->>'result', 'stale', 'a late failure report cannot bring the old token back');
set local role anon;
select is(public.invite_link_preview(pg_temp.tk('y'))->>'reason', 'not_found', 'the old token stays not_found');
select is(public.invite_link_preview((select t from tn where k = 'y'))->>'valid', 'true', 'and the new one stays valid');
reset role;

-- ---- a second resend replaces the saved values, and a failure restores the one before it ----
select pg_temp.mpk('z', 'a5', 'ilinv', 'e1@other-il.test');
insert into tn select 'z1', pg_temp.mp('a5', null, null, pg_temp.lid('z'))->>'token';
select public.invite_link_mail_sent(pg_temp.lid('z'), (select t from tn where k = 'z1'));
insert into tn select 'z2', pg_temp.mp('a5', null, null, pg_temp.lid('z'))->>'token';
select public.invite_link_mail_failed(pg_temp.lid('z'), true, (select t from tn where k = 'z2'));
set local role anon;
select is(public.invite_link_preview((select t from tn where k = 'z1'))->>'valid', 'true', 'a failure restores the last mailed token (not the original)');
select is(public.invite_link_preview(pg_temp.tk('z'))->>'reason', 'not_found', 'the first token stays gone');
reset role;

-- a failed NEW send is unchanged: the link is deleted
select pg_temp.mpk('w', 'a5', 'ilinv', 'w@other-il.test');
select is(public.invite_link_mail_failed(pg_temp.lid('w'), false, pg_temp.tk('w'))->>'result', 'deleted', 'a failed new send still deletes the link');

select * from finish();
rollback;
