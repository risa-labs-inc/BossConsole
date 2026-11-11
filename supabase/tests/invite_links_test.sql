-- pgTAP tests for 20261111150000_invite_links: create/preview/redeem/revoke/list RPCs, policy, quotas, grants, audit.
-- Run with: supabase test db
begin;
select plan(99);

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

-- ---- 1. grants ----
select ok(not has_table_privilege('authenticated', 'public.invite_links', 'INSERT')
      and not has_table_privilege('authenticated', 'public.invite_links', 'UPDATE')
      and not has_column_privilege('authenticated', 'public.invite_links', 'token_hash', 'SELECT')
      and has_column_privilege('authenticated', 'public.invite_links', 'max_uses', 'SELECT')
      and not has_table_privilege('anon', 'public.invite_links', 'SELECT'), 'invite_links: token_hash never selectable, no client writes');
select ok(not has_table_privilege('authenticated', 'public.invite_link_redemptions', 'SELECT')
      and not has_table_privilege('authenticated', 'public.platform_access', 'SELECT')
      and not has_table_privilege('anon', 'public.platform_invite_policies', 'SELECT'), 'redemptions, platform_access, platform policies: no client access');
select ok(has_function_privilege('anon', 'public.invite_link_preview(text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.create_invite_link(text,int,int,text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.redeem_invite_link(text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_access_allowed(text)', 'EXECUTE')
      and has_function_privilege('authenticated', 'public.redeem_invite_link(text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.invite_target_info(text)', 'EXECUTE'), 'function grants: preview for anon, the rest signed-in only, internals closed');

-- ---- 2. create ----
select pg_temp.as_user('a5');
select is(pg_temp.mk('ilinv')->>'success', 'true', 'a user of the agent creates a link');
select is((select max_uses || '/' || (expires_at > now() + interval '6 days 23 hours' and expires_at < now() + interval '7 days 1 hour')
           from public.invite_links where created_by = pg_temp.u('a5') and target = 'ilinv' limit 1), '10/true', 'defaults: 10 uses, 7 days');
select is((pg_temp.mk('ilinv', 500, 365))->>'max_uses', '10', 'asking for more than the cap is clamped to it');
select is((pg_temp.mkk('small', 'ilinv', 3, 2))->>'max_uses', '3', 'asking for less is honoured');
select is(length(pg_temp.tk('small')), 43, 'token is 43 base64url characters');
select is((select path from (select (public.create_invite_link('ilinv', 1, 1, 'max')->>'path') path) s) ~ '^/i/[A-Za-z0-9_-]{43}$', true, 'path is /i/<token>');
select is(pg_temp.mk('ilinv', 0)->>'error', 'bad_request', 'zero uses is a bad request');
select is(public.create_invite_link('ilinv', 1, 1, 'sms')->>'error', 'bad_request', 'unknown via is a bad request');
select is(pg_temp.mk('nosuch')->>'error', 'not_found', 'unknown target');
select is(pg_temp.mk('ildraft')->>'error', 'not_found', 'an agent that is not live');
select is(pg_temp.mk('ilmem')->>'error', 'not_allowed', 'who=members: a non-member is refused');
select is(pg_temp.mk('iladm')->>'error', 'not_allowed', 'who=admins: a plain user is refused');
select is(pg_temp.mk('iloff')->>'error', 'invites_off', 'enabled=false: invites_off');
select is(pg_temp.mk('optimist')->>'error', 'not_allowed', 'optimist: someone with no platform_access is refused');
select pg_temp.as_user('a6');
select is(pg_temp.mk('ilinv')->>'error', 'not_allowed', 'a removed install is not a user of the agent');
select pg_temp.as_user('a4');
select is(pg_temp.mk('ilinv')->>'success', 'true', 'a member of the owning org counts as a user (who=users)');
select is(pg_temp.mk('ilmem')->>'success', 'true', 'who=members: a member creates');
select is(pg_temp.mk('iladm')->>'error', 'not_allowed', 'who=admins: a plain member is refused');
select pg_temp.as_user('a3');
select is(pg_temp.mk('iladm')->>'success', 'true', 'who=admins: agents.access creates');
select pg_temp.as_service();
update public.agents set invite_policy = '{"who":"off"}' where slug = 'iloff';
select pg_temp.as_user('a3');
select is(pg_temp.mk('iloff')->>'error', 'invites_off', 'who=off: invites_off');
select pg_temp.as_service();
select is(pg_temp.mk('ilinv')->>'error', 'not_allowed', 'the service role has no auth.uid(): refused');

-- active-link cap and quotas (ilq: 2 active links, 2 per user a month, 3 per org a month, max 5 uses)
select pg_temp.as_user('a4');
select is(pg_temp.mkk('q1', 'ilq')->>'max_uses', '5', 'policy max_uses is the cap');
select is(pg_temp.mkk('q2', 'ilq')->>'success', 'true', 'second active link');
select is(pg_temp.mk('ilq')->>'error', 'too_many_active_links', 'third active link refused');
select is((select count(*)::int from public.invite_links where created_by = pg_temp.u('a4') and target = 'ilq'), 2, 'the refused link was not stored');

-- ---- 3. preview ----
select pg_temp.as_service();
insert into public.platform_access (email, platform) values ('ann@other-il.test', 'max');
select pg_temp.as_user('a5');
select pg_temp.mkk('p1', 'ilinv');
select pg_temp.mkk('pmax', 'max');
select pg_temp.as_service();
set local role anon;
select is(public.invite_link_preview(pg_temp.tk('p1')), jsonb_build_object('valid', true, 'reason', null, 'target', 'ilinv',
    'target_name', 'Inv Brand', 'inviter_name', 'Ann'), 'preview (as anon): valid, brand name, inviter first name only');
select is(public.invite_link_preview('nope')->>'reason', 'not_found', 'preview: unknown token');
select is(public.invite_link_preview('nope')->'target', 'null'::jsonb, 'preview: nothing about the target for an unknown token');
reset role;
select pg_temp.as_service();
update public.invite_links set expires_at = now() - interval '1 minute' where id = pg_temp.lid('small');
update public.invite_links set max_uses = 1, uses = 1 where id = pg_temp.lid('q2');
update public.invite_links set revoked_at = now() where id = pg_temp.lid('q1');
set local role anon;
select is(public.invite_link_preview(pg_temp.tk('small'))->>'reason', 'expired', 'preview: expired');
select is(public.invite_link_preview(pg_temp.tk('q2'))->>'reason', 'used_up', 'preview: used up');
select is(public.invite_link_preview(pg_temp.tk('q1'))->>'reason', 'revoked', 'preview: revoked');
select is(public.invite_link_preview(pg_temp.tk('q1'))->>'valid', 'false', 'preview: revoked is not valid');
reset role;
select pg_temp.as_service();
update public.agents set invite_policy = '{"enabled":false}' where slug = 'ilinv';
set local role anon;
select is(public.invite_link_preview(pg_temp.tk('p1'))->>'reason', 'invites_off', 'preview: invites off');
reset role;
select pg_temp.as_service();
update public.agents set invite_policy = '{"active_links_per_user":20}' where slug = 'ilinv';
update public.invite_links set uses = 0, max_uses = 5 where id = pg_temp.lid('q2');
update public.invite_links set revoked_at = null where id = pg_temp.lid('q1');
update public.invite_links set expires_at = now() + interval '1 day' where id = pg_temp.lid('small');
select is(public.invite_link_preview(pg_temp.tk('pmax'))->>'target_name', 'Max', 'preview: a platform link names the platform');

-- ---- 4. redeem ----
select pg_temp.as_user('a7');
set local role authenticated;
select is(public.redeem_invite_link(pg_temp.tk('p1')), jsonb_build_object('success', true, 'target', 'ilinv', 'already', false),
    'redeem an agent link (the caller has no agents.access)');
reset role;
select pg_temp.as_service();
select is((select invited_by::text || '/' || exception from public.agent_invites
           where agent_id = pg_temp.agent('ilinv') and email = 'cy@other-il.test'), pg_temp.u('a5')::text || '/false',
    'agent invite row for the caller, invited_by = link creator');
select pg_temp.as_user('a7');
select is(public.agent_can_join('ilinv', 'cy@other-il.test'), true, 'agent_can_join admits the invitee of an invite-mode agent');
select is(public.redeem_invite_link(pg_temp.tk('p1')), jsonb_build_object('success', true, 'target', 'ilinv', 'already', true), 'idempotent: already');
select is((select uses from public.invite_links where id = pg_temp.lid('p1')), 1, 'a repeat does not use a slot');
select is(public.redeem_invite_link('garbage')->>'error', 'not_found', 'unknown token');
select pg_temp.as_user('a5');
select is(public.redeem_invite_link(pg_temp.tk('p1')), jsonb_build_object('success', true, 'target', 'ilinv', 'already', true),
    'the creator redeeming their own link: success, nothing used');
select is((select count(*)::int from public.invite_link_redemptions where link_id = pg_temp.lid('p1')), 1, 'no redemption row for the creator');
select pg_temp.as_service();
select is((select count(*)::int from public.agent_invites where email = 'ann@other-il.test'), 0, 'no self invite row');

-- domain-mode agent: an outside invitee is admitted as an exception
select pg_temp.as_user('a3');
select pg_temp.mkk('d1', 'ildom');
select pg_temp.as_user('a6');
select is(public.redeem_invite_link(pg_temp.tk('d1'))->>'success', 'true', 'domain agent: redeem works for an outside address');
select is((select exception from public.agent_invites where agent_id = pg_temp.agent('ildom') and email = 'bo@other-il.test'), true,
    'domain agent: stored as an exception');
select is(public.agent_can_join('ildom', 'bo@other-il.test'), true, 'domain agent: the outside invitee can join');

-- platform effect
select pg_temp.as_user('a5');
select pg_temp.mkk('m1', 'max', 1, 7);
select is(public.platform_access_allowed('max'), true, 'platform_access_allowed: a holder');
select pg_temp.as_user('a7');
select is(public.platform_access_allowed('max'), false, 'platform_access_allowed: not yet');
select is(public.redeem_invite_link(pg_temp.tk('m1')), jsonb_build_object('success', true, 'target', 'max', 'already', false), 'redeem a platform link');
select is(public.platform_access_allowed('max'), true, 'platform_access_allowed: true after redeem');
select is(public.platform_access_allowed('optimist'), false, 'platform_access_allowed: per platform');
select pg_temp.as_service();
select is((select link_id from public.platform_access where email = 'cy@other-il.test' and platform = 'max'), pg_temp.lid('m1'), 'platform_access row carries the link');
-- used up: max_uses = 1
select pg_temp.as_user('a6');
select is(public.redeem_invite_link(pg_temp.tk('m1'))->>'error', 'used_up', 'max_uses=1: the second person gets used_up');
select pg_temp.as_service();
select is((select uses from public.invite_links where id = pg_temp.lid('m1')), 1, 'uses never exceeds max_uses');
-- expired / revoked / off
select pg_temp.as_user('a5');
select pg_temp.mkk('e1', 'ilinv'); select pg_temp.mkk('r1', 'ilinv');
select pg_temp.as_service();
update public.invite_links set expires_at = now() - interval '1 second' where id = pg_temp.lid('e1');
select pg_temp.as_user('a5');
select is(public.revoke_invite_link(pg_temp.lid('r1'))->>'success', 'true', 'the creator revokes');
select pg_temp.as_user('a6');
select is(public.redeem_invite_link(pg_temp.tk('e1'))->>'error', 'expired', 'redeem: expired');
select is(public.redeem_invite_link(pg_temp.tk('r1'))->>'error', 'revoked', 'redeem: revoked');
select pg_temp.as_service();
update public.agents set invite_policy = '{"enabled":false}' where slug = 'ilinv';
select pg_temp.as_user('a6');
select is(public.redeem_invite_link(pg_temp.tk('small'))->>'error', 'invites_off', 'redeem: the org switched invites off after the link was made');
select pg_temp.as_service();
update public.agents set invite_policy = '{"active_links_per_user":20}' where slug = 'ilinv';

-- org_per_month and per_user_per_month (ilq: 2 / 3)
select pg_temp.as_user('a4');
select pg_temp.as_user('a6');
select is(public.redeem_invite_link(pg_temp.tk('q1'))->>'success', 'true', 'ilq: first redemption');
select pg_temp.as_user('a7');
select is(public.redeem_invite_link(pg_temp.tk('q2'))->>'success', 'true', 'ilq: second redemption');
select pg_temp.as_user('d1');
select is(public.redeem_invite_link(pg_temp.tk('q2'))->>'error', 'quota_exceeded', 'ilq: the inviter is at per_user_per_month');
select pg_temp.as_service();
update public.agents set invite_policy = '{"per_user_per_month":50,"org_per_month":2,"max_uses":5,"active_links_per_user":5}' where slug = 'ilq';
select pg_temp.as_user('d1');
select is(public.redeem_invite_link(pg_temp.tk('q2'))->>'error', 'quota_exceeded', 'ilq: the target is at org_per_month');
select pg_temp.as_user('a4');
select is(pg_temp.mk('ilq')->>'error', 'quota_exceeded', 'create is refused once the quota is spent');

-- ---- 5. optimist platform ----
select pg_temp.as_user('d1');
select is(pg_temp.mkk('o1', 'optimist')->>'success', 'true', 'a member of the Optimist org may invite to optimist');
select pg_temp.as_service();
update public.platform_flags set value = 'null'::jsonb where key = 'optimist_org_id';
select pg_temp.as_user('d1');
select is(pg_temp.mk('optimist')->>'error', 'not_allowed', 'no Optimist org configured: only platform_access holders');
select pg_temp.as_user('a7');
select is(public.redeem_invite_link(pg_temp.tk('o1'))->>'success', 'true', 'redeem an optimist link');
select is(public.platform_access_allowed('optimist'), true, 'platform_access row for optimist');

-- ---- 6. revoke rights and list ----
select pg_temp.as_user('a7');
select is(public.revoke_invite_link(pg_temp.lid('p1'))->>'error', 'not_allowed', 'revoke: a stranger is refused');
select is(public.revoke_invite_link(gen_random_uuid())->>'error', 'not_found', 'revoke: unknown id');
select pg_temp.as_user('a3');
select is(public.revoke_invite_link(pg_temp.lid('p1'))->>'success', 'true', 'revoke: agents.access on the owning org');
select is(public.revoke_invite_link(pg_temp.lid('p1'))->>'success', 'true', 'revoke: idempotent');
select pg_temp.as_user('b1');
select is(public.revoke_invite_link(pg_temp.lid('d1'))->>'error', 'not_allowed', 'revoke: another org''s admin is refused');
select pg_temp.as_user('c1');
select is(public.revoke_invite_link(pg_temp.lid('m1'))->>'success', 'true', 'revoke: a platform admin revokes a platform link');
select pg_temp.as_user('a5');
select is((select count(*)::int from jsonb_array_elements(public.list_my_invite_links('ilinv'))), 7, 'list: my links for a target');
select is((select status from jsonb_to_recordset(public.list_my_invite_links()) as x(id uuid, status text) where id = pg_temp.lid('p1')),
    'revoked', 'list: status shows revoked');
select is((select status from jsonb_to_recordset(public.list_my_invite_links()) as x(id uuid, status text) where id = pg_temp.lid('e1')),
    'expired', 'list: status shows expired');
select is((select status from jsonb_to_recordset(public.list_my_invite_links()) as x(id uuid, status text) where id = pg_temp.lid('m1')),
    'revoked', 'list: platform link revoked by an admin');
select is((select count(*)::int from jsonb_array_elements(public.list_my_invite_links()) e where e::text like '%token%' or e ? 'token_hash'),
    0, 'list never returns a token');
select pg_temp.as_user('a6');
select is(public.list_my_invite_links(), '[]'::jsonb, 'list: nothing of other people''s links');

-- RLS: creator sees own rows, an admin of the org sees the agent's, strangers see none, token_hash is not readable
select pg_temp.as_user('a3');
set local role authenticated;
select is((select count(*)::int from public.invite_links where target = 'ilinv') > 0, true, 'RLS: agents.access sees the agent''s links');
select throws_ok($$ select token_hash from public.invite_links $$, '42501', null, 'RLS: token_hash is not selectable');
reset role;
select pg_temp.as_user('b1');
set local role authenticated;
select is((select count(*)::int from public.invite_links where target = 'ilinv'), 0, 'RLS: another org sees none');
reset role;

-- ---- 7. policy edits ----
select pg_temp.as_user('a4');
set local role authenticated;
update public.agents set invite_policy = '{"who":"off"}' where slug = 'ilinv';
reset role;
select pg_temp.as_service();
select is((select invite_policy from public.agents where slug = 'ilinv'), '{"active_links_per_user":20}'::jsonb,
    'policy edit: a member without agents.access reaches no row');
set local role authenticated;
reset role;
select pg_temp.as_user('a3');
set local role authenticated;
select lives_ok($$ update public.agents set invite_policy = '{"who":"members","max_uses":3}' where slug = 'ilinv' $$, 'policy edit: agents.access can');
select throws_ok($$ update public.agents set invite_policy = '{"who":"everyone"}' where slug = 'ilinv' $$, '23514', null, 'policy edit: invalid values are refused');
select throws_ok($$ update public.agents set invite_policy = '{"max_uses":0}' where slug = 'ilinv' $$, '23514', null, 'policy edit: zero is refused');
reset role;
select pg_temp.as_user('a5');
select is(pg_temp.mk('ilinv')->>'error', 'not_allowed', 'policy edit takes effect: who=members refuses a non-member user');
select pg_temp.as_user('a4');
select is(pg_temp.mk('ilinv', 9)->>'max_uses', '3', 'policy edit takes effect: the cap is the new max_uses');
select pg_temp.as_user('a5');
select is(public.set_platform_invite_policy('max', '{"who":"off"}')->>'error', 'not_allowed', 'platform policy: non-admin refused');
select pg_temp.as_user('c1');
select is(public.set_platform_invite_policy('max', '{"who":"nobody"}')->>'error', 'bad_request', 'platform policy: invalid refused');
select is(public.set_platform_invite_policy('max', '{"max_uses":2}')->'policy'->>'max_uses', '2', 'platform policy: admin sets it');
select pg_temp.as_user('a5');
select is(pg_temp.mk('max', 9)->>'max_uses', '2', 'platform policy: caps apply to the platform');

-- ---- 8. audit ----
select pg_temp.as_service();
select is((select array_agg(distinct action order by action) from public.agent_audit_log
           where action like 'invite_link.%' or action = 'invite_policy.change'),
    array['invite_link.create', 'invite_link.redeem', 'invite_link.revoke', 'invite_policy.change'], 'audit: agent trail has create, redeem, revoke and policy change');
select is((select array_agg(distinct action order by action) from public.platform_audit_log
           where action like 'invite_link.%' or action = 'invite_policy.change'),
    array['invite_link.create', 'invite_link.redeem', 'invite_link.revoke', 'invite_policy.change'], 'audit: platform trail has them too');
select is((select count(*)::int from public.agent_audit_log where action = 'invite_link.redeem' and agent_id = pg_temp.agent('ilinv')
           and actor = pg_temp.u('a7') and detail->>'redeemer' = pg_temp.u('a7')::text), 1, 'audit: redeem is attributed to the redeemer');
select is((select count(*)::int from public.agent_audit_log where detail::text ~ '[A-Za-z0-9_-]{43}' and action like 'invite_link.%'), 0, 'audit: no token in any invite_link row');

select * from finish();
rollback;
