-- pgTAP tests for 20261019150000_agents_team_web: the agents functions' anonymous surface, audit actors
-- for the organisation function's service-role path, the logged role seed, the web join check and the
-- invite-mail gate. Run with: supabase test db
begin;
select plan(47);

-- a1 owner, a2 second admin, a3 agent manager, a4 member, a8 invitee, a9 someone else, b1 owner of B.
insert into auth.users (id, email, email_confirmed_at) values
    ('c5000000-0000-4000-8000-0000000000a1', 'owner-a@web.test', now()),
    ('c5000000-0000-4000-8000-0000000000a2', 'admin-a@web.test', now()),
    ('c5000000-0000-4000-8000-0000000000a3', 'manager@web.test', now()),
    ('c5000000-0000-4000-8000-0000000000a4', 'member@web.test', now()),
    ('c5000000-0000-4000-8000-0000000000a8', 'invitee@web.test', now()),
    ('c5000000-0000-4000-8000-0000000000a9', 'someone@web.test', now()),
    ('c5000000-0000-4000-8000-0000000000b1', 'owner-b@web.test', now());

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'weba', p_name => 'Web A', p_description => null,
    p_owner_id => 'c5000000-0000-4000-8000-0000000000a1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
select public.create_organisation_internal(p_slug => 'webb', p_name => 'Web B', p_description => null,
    p_owner_id => 'c5000000-0000-4000-8000-0000000000b1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
select set_config('request.jwt.claims', '', true);

create temp table ids (k text primary key, id uuid) on commit drop;
grant all on ids to authenticated, anon, service_role;
insert into ids select 'orga', id from public.organisations where slug = 'weba';
insert into ids select 'orgb', id from public.organisations where slug = 'webb';
insert into ids select 'a_' || coalesce(orl.preset, orl.kind), orl.role_id from public.organisation_roles orl
    where orl.org_id = (select id from ids where k = 'orga');
create temp table toks (k text primary key, j jsonb) on commit drop;
grant all on toks to authenticated, anon, service_role;

create function pg_temp.id(p text) returns uuid language sql stable as $$ select id from ids where k = p $$;
create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c5000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.tok(p text) returns jsonb language sql stable as $$ select j from toks where k = p $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p),
               'email', (select email from auth.users where id = pg_temp.u(p)))::text, true);
$$;
create function pg_temp.as_service() returns void language sql as $$
    select set_config('request.jwt.claim.sub', '', true), set_config('request.jwt.claims', '{"role":"service_role"}', true);
$$;
grant execute on function pg_temp.id(text), pg_temp.u(text), pg_temp.tok(text), pg_temp.as_user(text), pg_temp.as_service()
    to authenticated, anon, service_role;

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
    select pg_temp.id('orga'), pg_temp.u(x), 'active', now(), 'admin' from unnest(array['a2','a3','a4']) x;
insert into public.user_roles (user_id, role_id) values
    (pg_temp.u('a2'), pg_temp.id('a_admin')),
    (pg_temp.u('a3'), pg_temp.id('a_agent_manager')),
    (pg_temp.u('a4'), pg_temp.id('a_user'));

-- ---- 1. anonymous surface ----
select ok(not has_function_privilege('anon', 'public.agent_can_join(text,text)', 'EXECUTE'), 'anon cannot call agent_can_join');
select ok(has_function_privilege('authenticated', 'public.agent_can_join(text,text)', 'EXECUTE')
      and has_function_privilege('service_role', 'public.agent_can_join(text,text)', 'EXECUTE'), 'signed-in people and the service role can');
select ok(has_function_privilege('anon', 'public.agent_for_host(text)', 'EXECUTE')
      and has_function_privilege('anon', 'public.domain_tls_allowed(text)', 'EXECUTE'), 'host lookup and the TLS ask stay anonymous (sanctioned)');
select ok(not has_function_privilege('anon', 'public.org_invite_check(text)', 'EXECUTE')
      and has_function_privilege('authenticated', 'public.org_invite_check(text)', 'EXECUTE'), 'org_invite_check: signed-in only');
select ok(not has_function_privilege('anon', 'public.org_invite_mail_prepare(uuid,uuid,uuid,text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.org_invite_mail_prepare(uuid,uuid,uuid,text)', 'EXECUTE')
      and has_function_privilege('service_role', 'public.org_invite_mail_prepare(uuid,uuid,uuid,text)', 'EXECUTE'),
    'org_invite_mail_prepare: service role only');
select ok(not has_table_privilege('authenticated', 'public.org_invite_mail_log', 'SELECT')
      and not has_table_privilege('anon', 'public.org_invite_mail_log', 'SELECT')
      and not has_table_privilege('service_role', 'public.org_invite_mail_log', 'INSERT'), 'the mail log has no client access');

insert into public.agents (slug, org_id, name, plugin_id, created_by, status, signup_policy) values
    ('wopen', pg_temp.id('orga'), 'Open', 'ai.rever.boss.plugin.dynamic.agent.wopen', pg_temp.u('a1'), 'live', '{"mode":"open"}'),
    ('winv', pg_temp.id('orga'), 'Inv', 'ai.rever.boss.plugin.dynamic.agent.winv', pg_temp.u('a1'), 'live', '{"mode":"invite"}');
insert into public.agent_invites (agent_id, email) select id, 'invitee@web.test' from public.agents where slug = 'winv';
select pg_temp.as_user('a8');
select is(public.agent_can_join('winv', 'invitee@web.test'), true, 'a person asks about themselves');
select is(public.agent_can_join('winv', ' INVITEE@web.test '), true, 'their own email in any case');
select pg_temp.as_user('a9');
select is(public.agent_can_join('winv', 'invitee@web.test'), false, 'another account cannot probe someone else''s invite');
select is(public.agent_can_join('wopen', 'invitee@web.test'), false, 'not even for an open agent');
select is(public.agent_can_join('wopen', 'someone@web.test'), true, 'its own email on an open agent');
select pg_temp.as_service();
select is(public.agent_can_join('winv', 'invitee@web.test'), true, 'the service role may ask about anyone');
select set_config('request.jwt.claims', '', true);

-- ---- 2. audit actor for the organisation function's service-role path ----
select pg_temp.as_service();
select is((public.assign_organisation_role(pg_temp.id('orga'), pg_temp.u('a4'), pg_temp.id('a_agent_editor'), pg_temp.u('a2')))->>'success',
    'true', 'org function assigns a role as admin A2');
select is((select actor from public.agent_audit_log where org_id = pg_temp.id('orga') and action = 'member.role_assign'
           and detail->>'user_id' = pg_temp.u('a4')::text order by id desc limit 1), pg_temp.u('a2'),
    'the assignment names the acting admin, not null');
select is((public.remove_organisation_role(pg_temp.id('orga'), pg_temp.u('a4'), pg_temp.id('a_agent_editor'), pg_temp.u('a2')))->>'success',
    'true', 'org function removes it again');
select is((select actor from public.agent_audit_log where org_id = pg_temp.id('orga') and action = 'member.role_remove'
           order by id desc limit 1), pg_temp.u('a2'), 'the removal names the acting admin');
select is((public.revoke_organisation_role_permission(pg_temp.id('orga'), pg_temp.id('a_agent_analyst'), 'agents.view', pg_temp.u('a1')))->>'success',
    'true', 'org function revokes a permission as the owner');
select is((select actor from public.agent_audit_log where org_id = pg_temp.id('orga') and action = 'role.permission_revoke'
           order by id desc limit 1), pg_temp.u('a1'), 'the permission change names the owner');
select is((public.grant_organisation_role_permission(pg_temp.id('orga'), pg_temp.id('a_agent_analyst'), 'agents.view', pg_temp.u('a1')))->>'success',
    'true', 'and grants it back');
select is((select actor from public.agent_audit_log where org_id = pg_temp.id('orga') and action = 'role.permission_grant'
           order by id desc limit 1), pg_temp.u('a1'), 'the grant names the owner');
select is(public.resolve_org_actor('c5000000-0000-4000-8000-00000000ffff'), null::uuid, 'an unknown actor resolves to nothing');
select set_config('request.jwt.claims', '', true);
-- A signed-in caller can never name another actor.
select pg_temp.as_user('a2');
select is(public.resolve_org_actor(pg_temp.u('a1')), pg_temp.u('a2'), 'a signed-in caller is always themselves');
select set_config('request.jwt.claims', '', true);

-- ---- 3. the built-in role seed is one system entry ----
select is((select count(*)::int from public.agent_audit_log where org_id = pg_temp.id('orga') and action = 'role.seed'), 1,
    'a new org logs its built-in role seed once');
select is((select actor from public.agent_audit_log where org_id = pg_temp.id('orga') and action = 'role.seed'), null::uuid, 'as a system action (no actor)');
select is((select detail->'roles' from public.agent_audit_log where org_id = pg_temp.id('orga') and action = 'role.seed'),
    '["weba_agent_manager", "weba_agent_editor", "weba_agent_analyst"]'::jsonb, 'naming the roles it made');
select is((select count(*)::int from public.agent_audit_log where org_id = pg_temp.id('orga') and action like 'role.%'
           and action not in ('role.seed', 'role.permission_grant', 'role.permission_revoke')), 0, 'and no per-role entries');
select public.seed_org_agent_roles(pg_temp.id('orga'));
select is((select count(*)::int from public.agent_audit_log where org_id = pg_temp.id('orga') and action = 'role.seed'), 1,
    're-running the idempotent seed logs nothing');
select pg_temp.as_user('a1');
select ok(exists (select 1 from public.org_audit_log(pg_temp.id('orga'), 200) where action = 'role.seed' and actor is null),
    'org admins see it in the audit log');
select set_config('request.jwt.claims', '', true);

-- ---- 4. the web join check ----
select pg_temp.as_user('a1');
insert into toks select 'inv', public.org_team_invite(pg_temp.id('orga'), 'invitee@web.test', pg_temp.id('a_agent_editor'));
insert into toks select 'rev', public.org_team_invite(pg_temp.id('orga'), 'someone@web.test', null);
select public.org_team_revoke_invite(pg_temp.id('orga'), (pg_temp.tok('rev')->>'invite_id')::uuid);
select set_config('request.jwt.claims', '', true), set_config('request.jwt.claim.sub', '', true);
select throws_ok($q$ select public.org_invite_check('x') $q$, '42501', null, 'no session, no answer');
select pg_temp.as_user('a8');
select is(public.org_invite_check('boss_inv_nonexistent0000000000000000000000000000000'), '{"state":"invalid"}'::jsonb,
    'an unknown token says invalid and nothing else');
select is((select row(j->>'state', j->>'org_name', j->>'role_name')::text from (select public.org_invite_check(pg_temp.tok('inv')->>'token') j) x),
    '(valid,"Web A",weba_agent_editor)', 'the invitee sees the org and the role');
select pg_temp.as_user('a9');
select is((select row(j->>'state', j->>'email_hint')::text from (select public.org_invite_check(pg_temp.tok('inv')->>'token') j) x),
    '(wrong_account,i…@web.test)', 'another account is told it is the wrong account, with a hint, not the address');
select is((public.redeem_organisation_invite(pg_temp.tok('inv')->>'token'))->>'success', 'false', 'and still cannot redeem');
select is(public.org_invite_check(pg_temp.tok('rev')->>'token')->>'state', 'revoked', 'a revoked invite says revoked');
select pg_temp.as_user('a4');
select is(public.org_invite_check(pg_temp.tok('inv')->>'token')->>'state', 'already_member', 'a member is told they are in');
select pg_temp.as_user('a8');
select is((public.redeem_organisation_invite(pg_temp.tok('inv')->>'token'))->>'success', 'true', 'the invitee redeems with their own session');
select is(public.org_invite_check(pg_temp.tok('inv')->>'token')->>'state', 'already_member', 'then the check says already a member');
select set_config('request.jwt.claims', '', true);
update public.organisation_invites set expires_at = now() - interval '1 minute'
    where id = (pg_temp.tok('rev')->>'invite_id')::uuid;
update public.organisation_invites set revoked_at = null where id = (pg_temp.tok('rev')->>'invite_id')::uuid;
select pg_temp.as_user('a9');
select is(public.org_invite_check(pg_temp.tok('rev')->>'token')->>'state', 'expired', 'an expired invite says expired');
select set_config('request.jwt.claims', '', true);

-- ---- 5. the invite-mail gate ----
select pg_temp.as_user('a1');
insert into toks select 'm1', public.org_team_invite(pg_temp.id('orga'), 'new1@web.test', null);
select set_config('request.jwt.claims', '', true);
select pg_temp.as_service();
select throws_ok(format('select public.org_invite_mail_prepare(%L, %L, %L, %L)', pg_temp.u('a3'), pg_temp.id('orga'),
    pg_temp.tok('m1')->>'invite_id', pg_temp.tok('m1')->>'token'), '42501', null, 'an agent manager may not send team invites');
select throws_ok(format('select public.org_invite_mail_prepare(%L, %L, %L, %L)', pg_temp.u('b1'), pg_temp.id('orga'),
    pg_temp.tok('m1')->>'invite_id', pg_temp.tok('m1')->>'token'), '42501', null, 'another org''s owner may not');
select throws_ok(format('select public.org_invite_mail_prepare(%L, %L, %L, %L)', pg_temp.u('b1'), pg_temp.id('orgb'),
    pg_temp.tok('m1')->>'invite_id', pg_temp.tok('m1')->>'token'), 'P0001', 'invite_not_found', 'an invite is only mailed by its own org');
select throws_ok(format('select public.org_invite_mail_prepare(%L, %L, %L, %L)', pg_temp.u('a2'), pg_temp.id('orga'),
    pg_temp.tok('m1')->>'invite_id', 'boss_inv_wrongtoken000000000000000000000000000000000'), 'P0001', 'invite_not_found',
    'the token must be the invite''s own');
select throws_ok(format('select public.org_invite_mail_prepare(%L, %L, %L, %L)', pg_temp.u('a2'), pg_temp.id('orga'),
    pg_temp.tok('rev')->>'invite_id', pg_temp.tok('rev')->>'token'), 'P0001', 'invite_not_pending', 'a dead invite is not mailed');
select is((select row(j->>'email', j->>'org_name', j->>'inviter_email')::text from
           (select public.org_invite_mail_prepare(pg_temp.u('a2'), pg_temp.id('orga'), (pg_temp.tok('m1')->>'invite_id')::uuid, pg_temp.tok('m1')->>'token') j) x),
    '(new1@web.test,"Web A",admin-a@web.test)', 'a second admin mails it; the recipient is the invite''s own email');
select public.org_invite_mail_prepare(pg_temp.u('a2'), pg_temp.id('orga'), (pg_temp.tok('m1')->>'invite_id')::uuid, pg_temp.tok('m1')->>'token');
select public.org_invite_mail_prepare(pg_temp.u('a2'), pg_temp.id('orga'), (pg_temp.tok('m1')->>'invite_id')::uuid, pg_temp.tok('m1')->>'token');
select throws_ok(format('select public.org_invite_mail_prepare(%L, %L, %L, %L)', pg_temp.u('a2'), pg_temp.id('orga'),
    pg_temp.tok('m1')->>'invite_id', pg_temp.tok('m1')->>'token'), 'P0001', 'rate_limited_recipient', 'a fourth mail to one person in an hour is refused');
insert into public.org_invite_mail_log (org_id, invite_id, email, actor)
    select pg_temp.id('orga'), gen_random_uuid(), 'bulk' || g || '@web.test', pg_temp.u('a1') from generate_series(1, 27) g;
select set_config('request.jwt.claims', '', true);
select pg_temp.as_user('a1');
insert into toks select 'm2', public.org_team_invite(pg_temp.id('orga'), 'new2@web.test', null);
select set_config('request.jwt.claims', '', true);
select pg_temp.as_service();
select throws_ok(format('select public.org_invite_mail_prepare(%L, %L, %L, %L)', pg_temp.u('a1'), pg_temp.id('orga'),
    pg_temp.tok('m2')->>'invite_id', pg_temp.tok('m2')->>'token'), 'P0001', 'rate_limited_org', 'the org''s 31st mail in an hour is refused');
select set_config('request.jwt.claims', '', true);
select is((select count(*)::int from public.agent_audit_log where org_id = pg_temp.id('orga') and action = 'team.invite_mail'), 3,
    'each mail sent is audited');

select * from finish();
rollback;
