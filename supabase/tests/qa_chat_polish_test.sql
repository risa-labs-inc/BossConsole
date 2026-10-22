-- pgTAP tests for 20261023120000_qa_chat_polish: agent_signin_check, chat's sign-in precheck through the provisioner bot.
-- Run with: supabase test db
begin;
select plan(14);

insert into auth.users (id, email, email_confirmed_at) values
    ('c7000000-0000-4000-8000-0000000000a1', 'owner@acme-qcp.test', now()),
    ('c7000000-0000-4000-8000-0000000000b0', 'bot@agents-qcp.test', now()),
    ('c7000000-0000-4000-8000-0000000000c1', 'carol@elsewhere-qcp.test', now());
insert into public.agents_service_accounts (user_id, purpose) values ('c7000000-0000-4000-8000-0000000000b0', 'provisioner');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'acmeqcp', p_name => 'Acme QCP', p_description => null,
    p_owner_id => 'c7000000-0000-4000-8000-0000000000a1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
select set_config('request.jwt.claims', '', true);

insert into public.agents (slug, org_id, name, plugin_id, created_by, status, signup_policy)
    select s, (select id from public.organisations where slug = 'acmeqcp'), s, 'ai.rever.boss.plugin.dynamic.agent.' || s,
           'c7000000-0000-4000-8000-0000000000a1', st::public.agent_status, p::jsonb
    from (values ('qcpdom', 'live', '{"mode":"domain","domains":["Acme-QCP.test"," @acme-qcp.org"]}'),
                 ('qcpinv', 'live', '{"mode":"invite"}'),
                 ('qcpopen', 'live', '{"mode":"open"}'),
                 ('qcpdraft', 'draft', '{"mode":"open"}')) v(s, st, p);
insert into public.agent_invites (agent_id, email, invited_by, exception) values
    ((select id from public.agents where slug = 'qcpinv'), 'invited@anywhere-qcp.test', 'c7000000-0000-4000-8000-0000000000a1', false),
    ((select id from public.agents where slug = 'qcpdom'), 'guest@outside-qcp.test', 'c7000000-0000-4000-8000-0000000000a1', true);

create function pg_temp.as_bot() returns void language sql as $$
    select set_config('request.jwt.claim.sub', 'c7000000-0000-4000-8000-0000000000b0', true),
           set_config('request.jwt.claims', '{"role":"authenticated","sub":"c7000000-0000-4000-8000-0000000000b0","email":"bot@agents-qcp.test"}', true);
$$;
create function pg_temp.as_person() returns void language sql as $$
    select set_config('request.jwt.claim.sub', 'c7000000-0000-4000-8000-0000000000c1', true),
           set_config('request.jwt.claims', '{"role":"authenticated","sub":"c7000000-0000-4000-8000-0000000000c1","email":"carol@elsewhere-qcp.test"}', true);
$$;
grant execute on function pg_temp.as_bot(), pg_temp.as_person() to authenticated, anon;

select ok(not has_function_privilege('anon', 'public.agent_signin_check(text,text)', 'EXECUTE'), 'anon cannot ask');

select pg_temp.as_person();
set local role authenticated;
select throws_ok($$ select public.agent_signin_check('qcpinv', 'invited@anywhere-qcp.test') $$, '42501', 'Permission denied',
    'a signed-in person who is not the bot cannot ask (no invite enumeration)');
reset role;

select pg_temp.as_bot();
set local role authenticated;
select is(public.agent_signin_check('qcpopen', 'anyone@x-qcp.test'), '{"allowed": true, "domains": null}'::jsonb, 'open: allowed');
select is(public.agent_signin_check('qcpinv', 'Invited@Anywhere-QCP.test '), '{"allowed": true, "domains": null}'::jsonb, 'invite: invited (case and space folded)');
select is(public.agent_signin_check('qcpinv', 'stranger@anywhere-qcp.test'), '{"allowed": false, "domains": null}'::jsonb, 'invite: not invited, no hint');
select is(public.agent_signin_check('qcpdom', 'dana@acme-qcp.test'), '{"allowed": true, "domains": null}'::jsonb, 'domain: at the domain');
select is(public.agent_signin_check('qcpdom', 'dana@acme-qcp.org'), '{"allowed": true, "domains": null}'::jsonb, 'domain: at the second domain');
select is(public.agent_signin_check('qcpdom', 'guest@outside-qcp.test'),
    '{"allowed": true, "domains": ["acme-qcp.org", "acme-qcp.test"]}'::jsonb, 'domain: an invited exception is allowed, with the same hint...');
select is(public.agent_signin_check('qcpdom', 'nobody@outside-qcp.test'),
    '{"allowed": false, "domains": ["acme-qcp.org", "acme-qcp.test"]}'::jsonb, '...as an outsider who is refused');
select is(public.agent_signin_check('qcpdraft', 'anyone@x-qcp.test'), '{"allowed": false, "domains": null}'::jsonb, 'not live: refused');
select is(public.agent_signin_check('nosuchqcp', 'anyone@x-qcp.test'), '{"allowed": false, "domains": null}'::jsonb, 'unknown slug: refused');
select is(public.agent_signin_check('qcpopen', 'not an email'), '{"allowed": false, "domains": null}'::jsonb, 'malformed address: refused');
reset role;

select set_config('request.jwt.claim.sub', '', true), set_config('request.jwt.claims', '{"role":"service_role"}', true);
set local role service_role;
select is(public.agent_signin_check('qcpinv', 'invited@anywhere-qcp.test'), '{"allowed": true, "domains": null}'::jsonb, 'the service role may ask');
reset role;

-- Same rule as agent_can_join (asked as the service role) for every case above.
select is((select pg_catalog.array_agg((public.agent_signin_check(s, e)->>'allowed')::boolean = public.agent_can_join(s, e))
           from (values ('qcpopen', 'anyone@x-qcp.test'), ('qcpinv', 'invited@anywhere-qcp.test'), ('qcpinv', 'stranger@anywhere-qcp.test'),
                        ('qcpdom', 'dana@acme-qcp.test'), ('qcpdom', 'guest@outside-qcp.test'), ('qcpdom', 'nobody@outside-qcp.test'),
                        ('qcpdraft', 'anyone@x-qcp.test')) v(s, e)),
    array[true, true, true, true, true, true, true], 'allowed always equals agent_can_join');

select * from finish();
rollback;
