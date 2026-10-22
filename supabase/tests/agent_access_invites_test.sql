-- pgTAP tests for 20261022140000_agent_access_invites: one join rule for the console and the database (domain mode admits
-- invited exceptions; outside invites must be marked as such), the invite columns people may write, and the invite mail gate.
-- Run with: supabase test db
begin;
select plan(34);

-- a1 owner, a3 agent manager, a4 plain member, a8 at the domain, a9 outside it, b1 owner of B.
insert into auth.users (id, email, email_confirmed_at) values
    ('c6000000-0000-4000-8000-0000000000a1', 'owner@acme-inv.test', now()),
    ('c6000000-0000-4000-8000-0000000000a3', 'manager@acme-inv.test', now()),
    ('c6000000-0000-4000-8000-0000000000a4', 'member@acme-inv.test', now()),
    ('c6000000-0000-4000-8000-0000000000a8', 'dana@acme-inv.test', now()),
    ('c6000000-0000-4000-8000-0000000000a9', 'bob@other-inv.test', now()),
    ('c6000000-0000-4000-8000-0000000000b1', 'owner@globex-inv.test', now());

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'acmeinv', p_name => 'Acme Inv', p_description => null,
    p_owner_id => 'c6000000-0000-4000-8000-0000000000a1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
select public.create_organisation_internal(p_slug => 'globexinv', p_name => 'Globex Inv', p_description => null,
    p_owner_id => 'c6000000-0000-4000-8000-0000000000b1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
select set_config('request.jwt.claims', '', true);

create temp table ids (k text primary key, id uuid) on commit drop;
grant all on ids to authenticated, anon, service_role;
insert into ids select 'orga', id from public.organisations where slug = 'acmeinv';
insert into ids select 'a_' || coalesce(orl.preset, orl.kind), orl.role_id from public.organisation_roles orl
    where orl.org_id = (select id from ids where k = 'orga');

create function pg_temp.id(p text) returns uuid language sql stable as $$ select id from ids where k = p $$;
create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c6000000-0000-4000-8000-0000000000' || p)::uuid $$;
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

insert into public.agents (slug, org_id, name, plugin_id, created_by, status, signup_policy) values
    ('invdom', pg_temp.id('orga'), 'Dom', 'ai.rever.boss.plugin.dynamic.agent.invdom', pg_temp.u('a1'), 'live',
     '{"mode":"domain","domains":["acme-inv.test"]}'),
    ('invonly', pg_temp.id('orga'), 'Only', 'ai.rever.boss.plugin.dynamic.agent.invonly', pg_temp.u('a1'), 'live', '{"mode":"invite"}'),
    ('invopen', pg_temp.id('orga'), 'Open', 'ai.rever.boss.plugin.dynamic.agent.invopen', pg_temp.u('a1'), 'live', '{"mode":"open"}'),
    ('invdraft', pg_temp.id('orga'), 'Draft', 'ai.rever.boss.plugin.dynamic.agent.invdraft', pg_temp.u('a1'), 'draft', '{"mode":"invite"}');

insert into ids select 'g_' || slug, id from public.agents where slug like 'inv%';

-- ---- 1. what people may write ----
select ok(has_column_privilege('authenticated', 'public.agent_invites', 'exception', 'INSERT')
      and not has_column_privilege('authenticated', 'public.agent_invites', 'last_sent_at', 'INSERT')
      and not has_table_privilege('authenticated', 'public.agent_invites', 'UPDATE'),
    'people write the exception mark, never the mail time, and never update an invite');
select ok(not has_function_privilege('anon', 'public.agent_invite_mail_prepare(uuid,uuid,text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.agent_invite_mail_prepare(uuid,uuid,text)', 'EXECUTE')
      and has_function_privilege('service_role', 'public.agent_invite_mail_prepare(uuid,uuid,text)', 'EXECUTE'),
    'agent_invite_mail_prepare: service role only');
select ok(not has_table_privilege('authenticated', 'public.agent_invite_mail_log', 'SELECT')
      and not has_table_privilege('anon', 'public.agent_invite_mail_log', 'SELECT')
      and not has_table_privilege('service_role', 'public.agent_invite_mail_log', 'INSERT'), 'the agent mail log has no client access');

-- ---- 2. domain mode: outside invites are explicit exceptions (as the people themselves) ----
select pg_temp.as_user('a3');
set local role authenticated;
select lives_ok($$ insert into public.agent_invites (agent_id, email, invited_by) values (pg_temp.agent('invdom'), 'Dana@Acme-Inv.test ', pg_temp.u('a3')) $$,
    'an address at the domain is invited plainly');
select is((select email::text || '/' || exception from public.agent_invites where agent_id = pg_temp.agent('invdom') and email = 'dana@acme-inv.test'),
    'dana@acme-inv.test/false', 'stored lowercased and trimmed, not an exception');
select throws_ok($$ insert into public.agent_invites (agent_id, email, invited_by) values (pg_temp.agent('invdom'), 'bob@other-inv.test', pg_temp.u('a3')) $$,
    'P0001', 'invite_outside_domain', 'an outside address without the exception mark is refused (the console asks first)');
select lives_ok($$ insert into public.agent_invites (agent_id, email, invited_by, exception) values (pg_temp.agent('invdom'), 'bob@other-inv.test', pg_temp.u('a3'), true) $$,
    'confirmed as an exception, it is invited');
select lives_ok($$ insert into public.agent_invites (agent_id, email, invited_by, exception) values (pg_temp.agent('invdom'), 'eve@acme-inv.test', pg_temp.u('a3'), true) $$,
    'an exception mark on an address at the domain is accepted...');
select is((select exception from public.agent_invites where agent_id = pg_temp.agent('invdom') and email = 'eve@acme-inv.test'), false,
    '...and dropped: it is not an exception');
select throws_ok($$ insert into public.agent_invites (agent_id, email, invited_by) values (pg_temp.agent('invonly'), 'not an email', pg_temp.u('a3')) $$,
    'P0001', 'invite_bad_email', 'a malformed address is refused');
select lives_ok($$ insert into public.agent_invites (agent_id, email, invited_by, exception) values (pg_temp.agent('invonly'), 'bob@other-inv.test', pg_temp.u('a3'), true) $$,
    'invite only: any address is invited...');
select is((select exception from public.agent_invites where agent_id = pg_temp.agent('invonly')), false, '...and none is an exception there');
select throws_ok($$ update public.agent_invites set last_sent_at = now() where agent_id = pg_temp.agent('invdom') $$,
    '42501', null, 'people cannot write the mail time');
reset role;
select pg_temp.as_user('a4');
set local role authenticated;
select throws_ok($$ insert into public.agent_invites (agent_id, email, invited_by) values (pg_temp.agent('invonly'), 'x@acme-inv.test', pg_temp.u('a4')) $$,
    '42501', null, 'a member without agents.access cannot invite');
reset role;

-- ---- 3. agent_can_join: one rule ----
select pg_temp.as_user('a8');
select is(public.agent_can_join('invdom', 'dana@acme-inv.test'), true, 'domain: someone at the domain joins');
select pg_temp.as_user('a9');
select is(public.agent_can_join('invdom', 'bob@other-inv.test'), true, 'domain: an invited exception outside it joins');
select is(public.agent_can_join('invonly', 'bob@other-inv.test'), true, 'invite only: an invited person joins');
select is(public.agent_can_join('invopen', 'bob@other-inv.test'), true, 'anyone: anyone joins');
select is(public.agent_can_join('invdom', 'dana@acme-inv.test'), false, 'nobody asks about someone else');
select pg_temp.as_service();
select is(public.agent_can_join('invdom', 'stranger@other-inv.test'), false, 'domain: an outsider who was not invited is refused');
select is(public.agent_can_join('invonly', 'dana@acme-inv.test'), false, 'invite only: someone at the org''s domain but not invited is refused');
select is(public.agent_can_join('invdom', 'dana@sub.acme-inv.test'), false, 'domain: a subdomain is not the domain');
delete from public.agent_invites where agent_id = pg_temp.agent('invdom') and email = 'bob@other-inv.test';
select is(public.agent_can_join('invdom', 'bob@other-inv.test'), false, 'domain: a revoked exception no longer joins');

-- ---- 4. invite mail gate ----
select throws_ok($$ select public.agent_invite_mail_prepare(pg_temp.u('a4'), pg_temp.agent('invonly'), 'bob@other-inv.test') $$,
    '42501', null, 'mail: a member without agents.access is refused');
select throws_ok($$ select public.agent_invite_mail_prepare(pg_temp.u('b1'), pg_temp.agent('invonly'), 'bob@other-inv.test') $$,
    '42501', null, 'mail: another org''s admin is refused');
select throws_ok($$ select public.agent_invite_mail_prepare(pg_temp.u('a3'), pg_temp.agent('invonly'), 'nobody@other-inv.test') $$,
    'P0001', 'invite_not_found', 'mail: only an existing invite is mailed (no arbitrary recipients)');
insert into public.agent_invites (agent_id, email, invited_by) values (pg_temp.agent('invdraft'), 'dana@acme-inv.test', pg_temp.u('a1'));
select throws_ok($$ select public.agent_invite_mail_prepare(pg_temp.u('a3'), pg_temp.agent('invdraft'), 'dana@acme-inv.test') $$,
    'P0001', 'agent_not_live', 'mail: an agent that is not live has no site to send people to');
select is((public.agent_invite_mail_prepare(pg_temp.u('a3'), pg_temp.agent('invonly'), ' BOB@other-inv.test ')) - 'inviter_name',
    jsonb_build_object('email', 'bob@other-inv.test', 'agent_name', 'Only', 'agent_slug', 'invonly', 'custom_host', null,
                       'org_name', 'Acme Inv', 'inviter_email', 'manager@acme-inv.test'),
    'mail: the facts the mail needs (the address as invited, the agent, its site, who invited)');
select isnt((select last_sent_at from public.agent_invites where agent_id = pg_temp.agent('invonly') and email = 'bob@other-inv.test'), null,
    'mail: the send is recorded on the invite');
select is((select count(*)::int from public.agent_audit_log where agent_id = pg_temp.agent('invonly') and action = 'invite.mail'), 1,
    'mail: audited');
select lives_ok($$ select public.agent_invite_mail_prepare(pg_temp.u('a3'), pg_temp.agent('invonly'), 'bob@other-inv.test') $$, 'a second send');
select lives_ok($$ select public.agent_invite_mail_prepare(pg_temp.u('a3'), pg_temp.agent('invonly'), 'bob@other-inv.test') $$, 'a third send');
select throws_ok($$ select public.agent_invite_mail_prepare(pg_temp.u('a3'), pg_temp.agent('invonly'), 'bob@other-inv.test') $$,
    'P0001', 'rate_limited_recipient', 'mail: a fourth to the same address within the hour is refused');

-- ---- 5. invites made before the rule ----
select pg_temp.as_service();
insert into public.agent_invites (agent_id, email) values (pg_temp.agent('invopen'), 'old@elsewhere-inv.test');
update public.agents set signup_policy = '{"mode":"domain","domains":["acme-inv.test"]}' where slug = 'invopen';
select is(public.agent_can_join('invopen', 'old@elsewhere-inv.test'), true,
    'an invite made under another mode keeps admitting its person when the agent moves to domain mode');

select * from finish();
rollback;
