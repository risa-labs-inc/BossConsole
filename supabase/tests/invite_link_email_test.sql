-- pgTAP tests for 20261116100000_invite_link_email: email invites (bound links), resend with token rotation, mail limits.
-- Run with: supabase test db
begin;
select plan(78);

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
select ok(has_function_privilege('service_role', 'public.invite_link_mail_prepare(uuid,text,text,uuid,text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.invite_link_mail_prepare(uuid,text,text,uuid,text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.invite_link_mail_prepare(uuid,text,text,uuid,text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.invite_link_insert(uuid,text,jsonb,int,int,text,text)', 'EXECUTE'),
    'invite_link_mail_prepare: service role only; the helpers are closed');
select ok(has_function_privilege('anon', 'public.invite_link_preview(text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.redeem_invite_link(text)', 'EXECUTE')
      and has_function_privilege('authenticated', 'public.list_invite_links(text)', 'EXECUTE'), 'the replaced functions keep their grants');
select ok(not has_table_privilege('authenticated', 'public.invite_link_mail_log', 'SELECT')
      and not has_table_privilege('service_role', 'public.invite_link_mail_log', 'INSERT'), 'the link mail log has no client access');
select pg_temp.as_user('a5');
set local role authenticated;
select throws_ok($$ select public.invite_link_mail_prepare(pg_temp.u('a5'), 'ilinv', 'x@other-il.test') $$, '42501', null, 'a signed-in user cannot call it');
reset role;

-- ---- 2. a new email invite ----
select pg_temp.as_service();
select is((pg_temp.mpk('e1', 'a5', 'ilinv', ' Bo@Other-IL.test ') - 'token' - 'link_id' - 'expires_at'),
    jsonb_build_object('success', true, 'target', 'ilinv', 'target_name', 'Inv Brand', 'agent_slug', 'ilinv', 'custom_host', null,
        'org_name', 'Acme IL', 'inviter_name', 'Ann Smith', 'inviter_email', 'ann@other-il.test', 'recipient_email', 'bo@other-il.test'),
    'new email invite: the facts the mail needs, address normalised');
select is(length(pg_temp.tk('e1')), 43, 'the token is 43 characters');
select is((select max_uses || '/' || recipient_email::text || '/' || via || '/' || (last_sent_at is not null)
           from public.invite_links where id = pg_temp.lid('e1')), '1/bo@other-il.test/api/true', 'bound link: single use, recipient, via, last_sent_at');
select pg_temp.mpk('e1m', 'a5', 'ilinv', 'e1m@other-il.test');
select is((select via from public.invite_links where id = pg_temp.lid('e1m')), 'api', 'via defaults to api');
select is((public.invite_link_mail_prepare(pg_temp.u('a5'), 'ilinv', 'dd@other-il.test', null, 'max')->>'success'), 'true', 'p_via max accepted');
select is(public.invite_link_mail_prepare(pg_temp.u('a5'), 'ilinv', 'dd2@other-il.test', null, 'sms')->>'error', 'bad_request', 'unknown p_via');
select is(pg_temp.mp('a5', 'ilinv', 'not an email')->>'error', 'bad_email', 'bad address');
select is(pg_temp.mp('a5', 'ilinv', '')->>'error', 'bad_email', 'empty address');
select is(pg_temp.mp('a5', 'ilinv', repeat('a', 320) || '@x.test')->>'error', 'bad_email', 'overlong address');
select is(pg_temp.mp('a5', 'nosuch', 'zz@other-il.test')->>'error', 'not_found', 'unknown target');
select is(pg_temp.mp('a5', 'ildraft', 'zz@other-il.test')->>'error', 'not_found', 'an agent that is not live');
select is(public.invite_link_mail_prepare(null, 'ilinv', 'zz@other-il.test')->>'error', 'not_allowed', 'no actor');
select is((select count(*)::int from public.agent_invite_mail_log where agent_id = pg_temp.agent('ilinv') and email = 'bo@other-il.test'), 1,
    'an agent send is mirrored into the agent mail log (shared limits)');

-- ---- 3. preview and redeem ----
set local role anon;
select is(public.invite_link_preview(pg_temp.tk('e1'))->>'recipient_hint', 'b***@other-il.test', 'preview: masked recipient');
reset role;
select pg_temp.as_user('a5');
select pg_temp.mkk('plain', 'ilinv');
set local role anon;
select is(public.invite_link_preview(pg_temp.tk('plain'))->'recipient_hint', 'null'::jsonb, 'preview: no hint on an open link');
select is(public.invite_link_preview('nope')->'recipient_hint', 'null'::jsonb, 'preview: no hint for an unknown token');
reset role;
select pg_temp.as_user('a7');
select is(public.redeem_invite_link(pg_temp.tk('e1')), '{"success":false,"error":"wrong_email"}'::jsonb, 'redeem from another address: wrong_email');
select pg_temp.as_service();
select is((select uses from public.invite_links where id = pg_temp.lid('e1')), 0, 'wrong_email consumes no use');
select is((select count(*)::int from public.invite_link_redemptions where link_id = pg_temp.lid('e1')), 0, 'wrong_email leaves no redemption');
select is((select count(*)::int from public.agent_invites where agent_id = pg_temp.agent('ilinv') and email = 'cy@other-il.test'), 0, 'wrong_email grants nothing');
select pg_temp.as_user('a6');
select is(public.redeem_invite_link(pg_temp.tk('e1')), '{"success":true,"target":"ilinv","already":false}'::jsonb, 'the recipient redeems');
select is(public.agent_can_join('ilinv', 'bo@other-il.test'), true, 'the recipient can join');
select is(public.redeem_invite_link(pg_temp.tk('e1'))->>'already', 'true', 'idempotent');
select pg_temp.as_user('a5');
select is(public.redeem_invite_link(pg_temp.tk('e1'))->>'already', 'true', 'the creator redeeming an email invite is a no-op');
select is((select (e->>'recipient_email') || '/' || (e->>'last_sent_at' is not null) from jsonb_array_elements(public.list_my_invite_links('ilinv')) e
           where e->>'id' = pg_temp.lid('e1')::text), 'bo@other-il.test/true', 'list_my_invite_links: recipient_email and last_sent_at');
select is((select count(*)::int from jsonb_array_elements(public.list_my_invite_links('ilinv')) e where e->'recipient_email' = 'null'::jsonb), 
    (select count(*)::int from public.invite_links where created_by = pg_temp.u('a5') and target = 'ilinv' and recipient_email is null),
    'list_my_invite_links: null recipient on open links');
select pg_temp.as_user('a3');
select is((select e->>'recipient_email' from jsonb_array_elements(public.list_invite_links('ilinv')) e where e->>'id' = pg_temp.lid('e1')::text),
    'bo@other-il.test', 'list_invite_links: recipient_email for admins');

-- ---- 4. resend and token rotation ----
select pg_temp.as_service();
select is(pg_temp.mp('a5', null, null, pg_temp.lid('e1'))->>'error', 'not_pending', 'resend after redeem: not_pending');
select pg_temp.mpk('r1', 'a5', 'ilinv', 'cy@other-il.test');
update public.invite_links set last_sent_at = now() - interval '2 hours' where id = pg_temp.lid('r1');
create temp table rs (r jsonb) on commit drop;
grant all on rs to authenticated, anon, service_role;
insert into rs select pg_temp.mp('a5', null, null, pg_temp.lid('r1'));
select is((select r->>'token' <> pg_temp.tk('r1') and r->>'link_id' = pg_temp.lid('r1')::text and r->>'recipient_email' = 'cy@other-il.test' from rs), true,
    'resend returns a new token for the same link and recipient');
select is((select last_sent_at > now() - interval '1 minute' from public.invite_links where id = pg_temp.lid('r1')), true, 'resend sets last_sent_at');
set local role anon;
select is(public.invite_link_preview(pg_temp.tk('r1'))->>'reason', 'not_found', 'the old token no longer resolves');
reset role;
select pg_temp.as_user('a7');
select is(public.redeem_invite_link(pg_temp.tk('r1'))->>'error', 'not_found', 'the old token cannot be redeemed');
select pg_temp.as_service();
-- the rotated token works: fetch it by hashing back is impossible, so rotate once more and keep it
create temp table rot (t text) on commit drop;
grant all on rot to authenticated, anon, service_role;
select public.invite_link_mail_sent(pg_temp.lid('r1'), (select r->>'token' from rs));
insert into rot select pg_temp.mp('a3', null, null, pg_temp.lid('r1'))->>'token';
select is((select count(*)::int from public.invite_links where id = pg_temp.lid('r1')
           and token_hash = encode(extensions.digest((select t from rot), 'sha256'), 'hex')), 1, 'an admin (agents.access) may resend; the stored hash is the new token''s');
select pg_temp.as_user('a7');
select is(public.redeem_invite_link((select t from rot)), '{"success":true,"target":"ilinv","already":false}'::jsonb, 'the newest token redeems for the recipient');
select pg_temp.as_service();
select is(pg_temp.mp('a4', null, null, pg_temp.lid('e1'))->>'error', 'not_allowed', 'resend: a stranger is refused (even before pending is checked)');
select pg_temp.mpk('r2', 'a5', 'ilinv', 'ee@other-il.test');
select is(pg_temp.mp('a4', null, null, pg_temp.lid('r2'))->>'error', 'not_allowed', 'resend: a member who is neither creator nor admin');
select is(pg_temp.mp('b1', null, null, pg_temp.lid('r2'))->>'error', 'not_allowed', 'resend: another org''s admin');
select is(pg_temp.mp('a5', null, null, gen_random_uuid())->>'error', 'not_found', 'resend: unknown link');
select is(pg_temp.mp('a5', null, null, pg_temp.lid('plain'))->>'error', 'not_pending', 'resend: an open link is not an email invite');
update public.invite_links set revoked_at = now() where id = pg_temp.lid('r2');
select is(pg_temp.mp('a5', null, null, pg_temp.lid('r2'))->>'error', 'not_pending', 'resend: revoked');
select pg_temp.mpk('r3', 'a5', 'ilinv', 'ff@other-il.test');
update public.invite_links set expires_at = now() - interval '1 second' where id = pg_temp.lid('r3');
select is(pg_temp.mp('a5', null, null, pg_temp.lid('r3'))->>'error', 'not_pending', 'resend: expired');
select pg_temp.mpk('r4', 'a5', 'ilinv', 'gg@other-il.test');
update public.agents set invite_policy = '{"enabled":false}' where slug = 'ilinv';
select is(pg_temp.mp('a5', null, null, pg_temp.lid('r4'))->>'error', 'invites_off', 'resend: the org switched invites off');
select is(pg_temp.mp('a5', 'ilinv', 'hh@other-il.test')->>'error', 'invites_off', 'new: invites off');
update public.agents set invite_policy = '{"active_links_per_user":20}' where slug = 'ilinv';

-- ---- 5. policy and quota on the mail path ----
select is(pg_temp.mp('a6', 'ilinv', 'hh@other-il.test')->>'error', 'not_allowed', 'a removed install is not a user of the agent');
select is(pg_temp.mp('a5', 'ilmem', 'hh@other-il.test')->>'error', 'not_allowed', 'who=members: a non-member is refused');
select is(pg_temp.mp('a4', 'ilmem', 'hh@other-il.test')->>'success', 'true', 'who=members: a member sends');
select is(pg_temp.mp('a4', 'iladm', 'hh@other-il.test')->>'error', 'not_allowed', 'who=admins: a plain member is refused');
select is(pg_temp.mp('a3', 'iladm', 'hh@other-il.test')->>'success', 'true', 'who=admins: agents.access sends');
select is(pg_temp.mp('a5', 'optimist', 'hh@other-il.test')->>'error', 'not_allowed', 'optimist: no platform_access, refused');
-- ilq: 2 active links per user, 2 redemptions per user a month
select is(pg_temp.mp('a4', 'ilq', 'q1@other-il.test')->>'success', 'true', 'ilq: first email invite');
select is(pg_temp.mp('a4', 'ilq', 'q2@other-il.test')->>'success', 'true', 'ilq: second');
select is(pg_temp.mp('a4', 'ilq', 'q3@other-il.test')->>'error', 'too_many_active_links', 'ilq: the active-link cap applies to email invites');
select is((select count(*)::int from public.invite_links where target = 'ilq' and recipient_email = 'q3@other-il.test'), 0, 'a refused invite leaves no link');
insert into public.invite_link_redemptions (link_id, user_id, inviter_id, target)
    select l.id, x.u, pg_temp.u('a4'), 'ilq' from public.invite_links l, (values (pg_temp.u('a6')), (pg_temp.u('a7'))) x(u)
    where l.target = 'ilq' and l.recipient_email = 'q1@other-il.test' limit 2;
update public.invite_links set revoked_at = now() where target = 'ilq';
select is(pg_temp.mp('a4', 'ilq', 'q4@other-il.test')->>'error', 'quota_exceeded', 'ilq: the monthly quota applies to email invites');

-- ---- 6. platform email invite ----
select is((pg_temp.mpk('pm', 'a5', 'max', 'pm@other-il.test') - 'token' - 'link_id' - 'expires_at'),
    jsonb_build_object('success', true, 'target', 'max', 'target_name', 'Max', 'agent_slug', null, 'custom_host', null, 'org_name', null,
        'inviter_name', 'Ann Smith', 'inviter_email', 'ann@other-il.test', 'recipient_email', 'pm@other-il.test'), 'platform email invite: no agent or org facts');
select is((select actor from public.platform_audit_log where action = 'invite_link.create' and detail->>'link_id' = pg_temp.lid('pm')::text),
    pg_temp.u('a5'), 'platform audit names the sender, not the service role');
select is(pg_temp.mp('a5', null, null, pg_temp.lid('pm'))->>'success', 'true', 'platform resend');
select is((select count(*)::int from public.platform_audit_log where action = 'invite_link.resend'), 1, 'platform resend audited');

-- ---- 7. mail limits ----
-- per recipient: 3 an hour (e1, cy, r1 mails already went to other addresses; use a fresh one)
select is(pg_temp.mp('a5', 'ilinv', 'lim@other-il.test')->>'success', 'true', 'limit: 1st to one address');
select is(pg_temp.mp('a5', 'ilinv', 'lim@other-il.test')->>'success', 'true', 'limit: 2nd');
select is(pg_temp.mp('a5', 'ilinv', 'lim@other-il.test')->>'success', 'true', 'limit: 3rd');
select is(pg_temp.mp('a5', 'ilinv', 'lim@other-il.test')->>'error', 'rate_limited', 'limit: the 4th within the hour is refused');
select is((select count(*)::int from public.invite_links where recipient_email = 'lim@other-il.test'), 3, 'a refused send leaves no link');
select is(pg_temp.mp('a5', 'optimist', 'lim@other-il.test')->>'error', 'not_allowed', 'policy is checked before limits');
select is(pg_temp.mp('a5', 'max', 'lim@other-il.test')->>'error', 'rate_limited', 'limit: shared across targets for one recipient');
insert into public.agent_invite_mail_log (org_id, agent_id, email, actor)
    select pg_temp.id('orga'), pg_temp.agent('ilinv'), 'shared@other-il.test', pg_temp.u('a3') from generate_series(1, 3);
select is(pg_temp.mp('a5', 'ilinv', 'shared@other-il.test')->>'error', 'rate_limited', 'limit: agent invite mails count toward a recipient''s limit');
-- the other direction: a link mail counts for agent_invite_mail_prepare
insert into public.agent_invites (agent_id, email, invited_by) values (pg_temp.agent('ilinv'), 'lim@other-il.test', pg_temp.u('a3'));
select throws_ok($$ select public.agent_invite_mail_prepare(pg_temp.u('a3'), pg_temp.agent('ilinv'), 'lim@other-il.test') $$,
    'P0001', 'rate_limited_recipient', 'limit: link mails count toward agent invite mails too');
-- per org: 30 an hour
insert into public.agent_invite_mail_log (org_id, agent_id, email, actor)
    select pg_temp.id('orga'), pg_temp.agent('ilinv'), 'bulk' || g || '@other-il.test', pg_temp.u('a3') from generate_series(1, 30) g;
select is(pg_temp.mp('a5', 'ilinv', 'fresh@other-il.test')->>'error', 'rate_limited', 'limit: the org''s 30 an hour');
select is(pg_temp.mp('a3', null, null, pg_temp.lid('r1'))->>'error', 'not_pending', 'resend is not_pending before limits (r1 was redeemed)');
select pg_temp.mpk('r9', 'a5', 'max', 'r9@other-il.test');
insert into public.invite_link_mail_log (target, email, actor) select 'max', 'x' || g || '@other-il.test', pg_temp.u('a5') from generate_series(1, 30) g;
select is(pg_temp.mp('a5', 'max', 'fresh2@other-il.test')->>'error', 'rate_limited', 'limit: platform sends are limited per sender, 30 an hour');
select is(pg_temp.mp('a5', null, null, pg_temp.lid('r9'))->>'error', 'rate_limited', 'limit: resend is limited too');

-- ---- 8. audit and secrets ----
select is((select count(*)::int from public.agent_audit_log where action = 'invite_link.resend' and agent_id = pg_temp.agent('ilinv')), 2, 'agent resends are audited');
select is((select count(*)::int from public.agent_audit_log where action like 'invite_link.%' and agent_id = pg_temp.agent('ilinv')
           and detail::text ~ '(@|[A-Za-z0-9_-]{43})'), 0, 'audit rows carry neither an address nor a token');
select is((select actor from public.agent_audit_log where action = 'invite_link.create' and detail->>'link_id' = pg_temp.lid('e1')::text), pg_temp.u('a5'),
    'agent audit names the sender');

select * from finish();
rollback;
