-- pgTAP tests for A2A org auto-connect (20261104120000).
-- Run with: supabase test db
--
-- Weighted towards what must be REFUSED: an org grant that leaks across products, past an
-- opt-out, past a block, after leaving, through the system org, or with the policy off.
BEGIN;
SELECT plan(55);

INSERT INTO auth.users (id, email, email_confirmed_at) VALUES
 ('a0a00000-0000-4000-8000-00000000000a', 'org-alice@acme.test', now()),
 ('a0a00000-0000-4000-8000-00000000000b', 'org-bob@acme.test',   now()),
 ('a0a00000-0000-4000-8000-00000000000c', 'org-carol@acme.test', now()),
 ('a0a00000-0000-4000-8000-00000000000d', 'org-dave@other.test', now()),
 ('a0a00000-0000-4000-8000-00000000000e', 'org-erin@other.test', now());

SELECT set_config('request.jwt.claims', '{"role":"service_role"}', true);
SELECT ok((public.create_organisation_internal(
        p_slug => 'acme_a2a', p_name => 'Acme', p_description => null,
        p_owner_id => 'a0a00000-0000-4000-8000-00000000000a',
        p_domain => null, p_visibility => 'private', p_join_policy => 'invite_only') ->> 'success')::boolean,
    'fixture: the org is created with alice as owner');
SELECT set_config('request.jwt.claims', '', true);
CREATE TEMP TABLE ids ON COMMIT DROP AS SELECT (SELECT id FROM public.organisations WHERE slug = 'acme_a2a') AS org;
GRANT SELECT ON ids TO authenticated;
INSERT INTO public.organisation_members (org_id, user_id, status, joined_at, join_source)
SELECT (SELECT org FROM ids), u, 'active', now(), 'admin'
FROM unnest(ARRAY['a0a00000-0000-4000-8000-00000000000b', 'a0a00000-0000-4000-8000-00000000000c']::uuid[]) u
ON CONFLICT DO NOTHING;

CREATE TEMP TABLE t (name text PRIMARY KEY, token text) ON COMMIT DROP;
GRANT ALL ON t TO authenticated;

-- ---- grants and shape ----
SELECT ok(NOT has_function_privilege('anon', 'public.org_set_a2a_auto_connect(uuid,boolean,uuid)', 'EXECUTE')
      AND NOT has_function_privilege('anon', 'public.fluck_a2a_set_org_opt_out(uuid,boolean)', 'EXECUTE')
      AND NOT has_function_privilege('anon', 'public.fluck_peer_list(text)', 'EXECUTE'), 'anon cannot call the new RPCs');
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_a2a_relation(uuid,uuid,text,text)', 'EXECUTE')
      AND NOT has_function_privilege('authenticated', 'public.fluck_a2a_shared_org(uuid,uuid)', 'EXECUTE'),
    'the decision helpers are not callable by clients');
SELECT ok(NOT has_table_privilege('authenticated', 'public.organisation_a2a_opt_outs', 'INSERT')
      AND NOT has_table_privilege('authenticated', 'public.organisation_a2a_notices', 'SELECT'), 'no direct opt-out writes or notice reads');
SELECT ok((SELECT bool_and(prosecdef AND 'search_path=""' = ANY(proconfig)) FROM pg_proc WHERE oid IN (
        'public.fluck_peer_list(text)'::regprocedure, 'public.fluck_a2a_consume_ticket(text,text)'::regprocedure,
        'public.fluck_a2a_mint_ticket(text,uuid,text)'::regprocedure, 'public.fluck_peer_unblock(uuid)'::regprocedure,
        'public.fluck_a2a_orgs()'::regprocedure, 'public.fluck_a2a_set_org_opt_out(uuid,boolean)'::regprocedure,
        'public.fluck_a2a_org_notices()'::regprocedure, 'public.fluck_a2a_claim_org_notice(uuid)'::regprocedure,
        'public.org_a2a_policy(uuid,uuid)'::regprocedure, 'public.org_set_a2a_auto_connect(uuid,boolean,uuid)'::regprocedure)),
    'RPCs are security definer with an empty search_path');
SELECT is((SELECT a2a_auto_connect FROM public.organisations WHERE id = (SELECT org FROM ids)), false, 'the policy is off by default');

-- ---- instances: every product reports its kind; b-old is an older client that does not ----
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT public.fluck_web_upsert_instance('a-max', 'Alice Mac', 'Max', 'https://a-max.example.test', NULL, 'max');
SELECT public.fluck_web_upsert_instance('a-fl', 'Alice Fluck', 'Fluck', 'https://a-fl.example.test', NULL, 'fluck');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT public.fluck_web_upsert_instance('b-max', 'Bob Mac', 'Max', 'https://b-max.example.test', NULL, 'max');
SELECT public.fluck_web_upsert_instance('b-fl', 'Bob Fluck', 'Fluck', 'https://b-fl.example.test', NULL, 'fluck');
SELECT public.fluck_web_upsert_instance('b-old', 'Bob Old', 'Max', 'https://b-old.example.test', NULL);
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000c', true);
SELECT public.fluck_web_upsert_instance('c-max', 'Carol Mac', 'Max', 'https://c-max.example.test', NULL, 'max');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000d', true);
SELECT public.fluck_web_upsert_instance('d-max', 'Dave Mac', 'Max', 'https://d-max.example.test', NULL, 'max');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000e', true);
SELECT public.fluck_web_upsert_instance('e-max', 'Erin Mac', 'Max', 'https://e-max.example.test', NULL, 'max');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('e-x', 'X', 'X', 'https://x.example.test', NULL, 'other') $q$,
    '22023', NULL, 'an unknown kind is refused');

-- ---- policy off: colleagues are strangers ----
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT is((SELECT array_agg(instance_id ORDER BY instance_id) FROM public.fluck_peer_list('b-max')),
    ARRAY['b-fl','b-max','b-old'], 'policy off: bob lists only his own instances');
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-max', 'a0a00000-0000-4000-8000-00000000000a', 'a-max') $q$,
    'P0001', 'peer_unavailable', 'policy off: no ticket to a colleague');
SELECT is((public.org_set_a2a_auto_connect((SELECT org FROM ids), true)) ->> 'error', 'Permission denied', 'a plain member cannot turn it on');

-- ---- an admin turns it on ----
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((public.org_set_a2a_auto_connect((SELECT org FROM ids), true)) ->> 'success', 'true', 'the org admin turns it on');
RESET ROLE;
SELECT is((SELECT count(*)::int FROM public.agent_audit_log WHERE org_id = (SELECT org FROM ids) AND action = 'org.a2a_auto_connect'
           AND actor = 'a0a00000-0000-4000-8000-00000000000a' AND detail = '{"from": false, "to": true}'::jsonb), 1,
    'the change is in the org audit log with its actor');
SET LOCAL ROLE authenticated;

-- ---- same-product colleagues, and only those ----
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT is((SELECT array_agg(instance_id || ':' || source ORDER BY instance_id) FROM public.fluck_peer_list('b-max') WHERE NOT same_user),
    ARRAY['a-max:org','c-max:org'], 'bob''s Max lists only the colleagues'' Max instances');
SELECT is((SELECT DISTINCT org_name FROM public.fluck_peer_list('b-max') WHERE source = 'org'), 'Acme', 'colleague rows name the org');
SELECT is((SELECT array_agg(instance_id ORDER BY instance_id) FROM public.fluck_peer_list('b-fl') WHERE NOT same_user),
    ARRAY['a-fl'], 'bob''s Fluck lists only the colleagues'' Fluck instances');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list() WHERE NOT same_user), 0, 'without the calling instance no colleagues are listed');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('b-old') WHERE NOT same_user), 0, 'an instance with no kind gets no colleagues');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('a-max') WHERE NOT same_user), 0, 'someone else''s instance name selects no colleagues');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('b-max') WHERE instance_id IN ('d-max','e-max')), 0, 'non-members are not listed');

INSERT INTO t SELECT 'bob-alice', public.fluck_a2a_mint_ticket('b-max', 'a0a00000-0000-4000-8000-00000000000a', 'a-max');
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-max', 'a0a00000-0000-4000-8000-00000000000a', 'a-fl') $q$,
    'P0001', 'peer_unavailable', 'no ticket from a Max to a colleague''s Fluck');
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-old', 'a0a00000-0000-4000-8000-00000000000a', 'a-max') $q$,
    'P0001', 'peer_unavailable', 'no ticket from an instance with no kind');
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('a-max', 'a0a00000-0000-4000-8000-00000000000a', 'a-max') $q$,
    'P0001', 'peer_unavailable', 'a calling instance that is not the caller''s gets no kind and no org ticket');
SELECT lives_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-fl', 'a0a00000-0000-4000-8000-00000000000a', 'a-fl') $q$,
    'Fluck to a colleague''s Fluck is allowed');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((SELECT row(user_id, instance_id, same_user, source)::text FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'bob-alice'), 'a-max')),
    '(a0a00000-0000-4000-8000-00000000000b,b-max,f,org)', 'alice redeems it and learns it is an org colleague');

-- ---- the member side: my orgs, opt out ----
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
INSERT INTO t SELECT 'bob-carol', public.fluck_a2a_mint_ticket('b-max', 'a0a00000-0000-4000-8000-00000000000c', 'c-max');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000c', true);
SELECT is((SELECT row(org_name, auto_connect, opted_out, colleagues)::text FROM public.fluck_a2a_orgs()), '(Acme,t,f,2)', 'carol sees her org with the policy on');
SELECT lives_ok($q$ SELECT public.fluck_a2a_set_org_opt_out((SELECT org FROM ids), true) $q$, 'carol opts out');
SELECT is((SELECT opted_out FROM public.fluck_a2a_orgs()), true, 'carol''s opt-out is recorded');
SELECT throws_ok($q$ SELECT * FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'bob-carol'), 'c-max') $q$,
    'P0001', 'ticket_invalid', 'a ticket minted before the opt-out is refused');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('c-max') WHERE NOT same_user), 0, 'an opted-out member lists no colleagues');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT is((SELECT array_agg(instance_id ORDER BY instance_id) FROM public.fluck_peer_list('b-max') WHERE NOT same_user),
    ARRAY['a-max'], 'and colleagues no longer list her');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000d', true);
SELECT throws_ok($q$ SELECT public.fluck_a2a_set_org_opt_out((SELECT org FROM ids), true) $q$, 'P0001', 'org_invalid', 'a non-member cannot opt out of an org');

-- ---- the admin sees who opted out, a member does not ----
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((public.org_a2a_policy((SELECT org FROM ids)) -> 'opted_out' -> 0 ->> 'email'), 'org-carol@acme.test', 'the admin sees carol in the opted-out list');
SELECT is((public.org_a2a_policy((SELECT org FROM ids)) ->> 'members')::int, 3, 'and the member count');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT is((public.org_a2a_policy((SELECT org FROM ids)) ->> 'error'), 'Permission denied', 'a member cannot read the opt-out list');
RESET ROLE;
SELECT is((SELECT array_agg(action ORDER BY id) FROM public.agent_audit_log WHERE org_id = (SELECT org FROM ids) AND action LIKE 'org.a2a_opt%'),
    ARRAY['org.a2a_opt_out'], 'the opt-out is audited');
SET LOCAL ROLE authenticated;

-- ---- blocking one colleague ----
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT lives_ok($q$ SELECT public.fluck_peer_revoke('a0a00000-0000-4000-8000-00000000000a') $q$, 'bob blocks alice, with no link row between them');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('b-max') WHERE NOT same_user), 0, 'bob no longer lists alice');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('a-max') WHERE NOT same_user), 0, 'and alice no longer lists bob');
SELECT lives_ok($q$ SELECT public.fluck_peer_unblock('a0a00000-0000-4000-8000-00000000000b') $q$, 'alice tries to lift bob''s block');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('a-max') WHERE NOT same_user), 0, 'only the one who blocked can lift it');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT lives_ok($q$ SELECT public.fluck_peer_unblock('a0a00000-0000-4000-8000-00000000000a') $q$, 'bob lifts his block');
SELECT is((SELECT array_agg(instance_id ORDER BY instance_id) FROM public.fluck_peer_list('b-max') WHERE NOT same_user),
    ARRAY['a-max'], 'alice is a colleague again');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000d', true);
SELECT lives_ok($q$ SELECT public.fluck_peer_revoke('a0a00000-0000-4000-8000-00000000000a') $q$, 'revoking a stranger is a silent no-op');
RESET ROLE;
SELECT is((SELECT count(*)::int FROM public.fluck_peer_links WHERE requester_id = 'a0a00000-0000-4000-8000-00000000000d'), 0, 'and writes nothing');

-- ---- explicit links are unchanged: any product, and they outrank the org grant ----
INSERT INTO public.fluck_peer_links (requester_id, recipient_id, status, responded_at)
    VALUES ('a0a00000-0000-4000-8000-00000000000d', 'a0a00000-0000-4000-8000-00000000000a', 'accepted', now());
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000d', true);
SELECT is((SELECT array_agg(instance_id || ':' || source ORDER BY instance_id) FROM public.fluck_peer_list('d-max') WHERE NOT same_user),
    ARRAY['a-fl:link','a-max:link'], 'a linked peer sees every product, as before');

-- ---- the system org never connects anyone ----
RESET ROLE;
UPDATE public.organisations SET a2a_auto_connect = true WHERE is_system;
INSERT INTO public.organisation_members (org_id, user_id, status, joined_at, join_source)
SELECT o.id, u, 'active', now(), 'seed' FROM public.organisations o,
    unnest(ARRAY['a0a00000-0000-4000-8000-00000000000d', 'a0a00000-0000-4000-8000-00000000000e']::uuid[]) u
WHERE o.is_system ON CONFLICT DO NOTHING;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000e', true);
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('e-max') WHERE NOT same_user), 0, 'the system org connects nobody, even if flagged');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((public.org_set_a2a_auto_connect((SELECT id FROM public.organisations WHERE is_system LIMIT 1), true)) ->> 'success', 'false',
    'the system org cannot be turned on');

-- ---- the one-time notice ----
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT is((SELECT row(org_name, colleagues)::text FROM public.fluck_a2a_org_notices()), '(Acme,1)', 'bob has a notice for Acme (carol opted out)');
SELECT is(public.fluck_a2a_claim_org_notice((SELECT org FROM ids)), true, 'one of bob''s agents claims it');
SELECT is(public.fluck_a2a_claim_org_notice((SELECT org FROM ids)), false, 'no other agent can');
SELECT is((SELECT count(*)::int FROM public.fluck_a2a_org_notices()), 0, 'the notice is gone');

-- ---- kill switch, leaving, policy off ----
RESET ROLE;
UPDATE public.platform_flags SET value = 'false'::jsonb WHERE key = 'a2a_org_auto_connect';
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('b-max') WHERE NOT same_user), 0, 'the platform kill switch ends every org grant');
RESET ROLE;
UPDATE public.platform_flags SET value = 'true'::jsonb WHERE key = 'a2a_org_auto_connect';
UPDATE public.organisation_members SET status = 'pending' WHERE user_id = 'a0a00000-0000-4000-8000-00000000000b' AND org_id = (SELECT org FROM ids);
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('b-max') WHERE NOT same_user), 0, 'a member who is no longer active loses colleagues');
RESET ROLE;
UPDATE public.organisation_members SET status = 'active' WHERE user_id = 'a0a00000-0000-4000-8000-00000000000b' AND org_id = (SELECT org FROM ids);
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((public.org_set_a2a_auto_connect((SELECT org FROM ids), false)) ->> 'success', 'true', 'the admin turns it off');
SELECT is((SELECT array_agg(source) FROM public.fluck_peer_list('a-max') WHERE NOT same_user), ARRAY['link'], 'only the explicit link remains');

SELECT * FROM finish();
ROLLBACK;
