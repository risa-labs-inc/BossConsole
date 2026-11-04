-- pgTAP tests for A2A org auto-connect (20261104120000).
-- Run with: supabase test db
--
-- Weighted towards what must be REFUSED: an org grant that leaks across products, past an
-- opt-out, past a block, after leaving, through the system org, or with the policy off.
BEGIN;
SELECT plan(100);

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
SELECT public.fluck_web_upsert_instance('a-sup', 'Alice Support', 'Support', 'https://a-sup.example.test', NULL, 'agent:acme-support');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT public.fluck_web_upsert_instance('b-max', 'Bob Mac', 'Max', 'https://b-max.example.test', NULL, 'max');
SELECT public.fluck_web_upsert_instance('b-fl', 'Bob Fluck', 'Fluck', 'https://b-fl.example.test', NULL, 'fluck');
SELECT public.fluck_web_upsert_instance('b-old', 'Bob Old', 'Max', 'https://b-old.example.test', NULL);
SELECT public.fluck_web_upsert_instance('b-sup', 'Bob Support', 'Support', 'https://b-sup.example.test', NULL, 'agent:acme-support');
SELECT public.fluck_web_upsert_instance('b-hr', 'Bob HR', 'HR', 'https://b-hr.example.test', NULL, 'agent:acme-hr');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000c', true);
SELECT public.fluck_web_upsert_instance('c-max', 'Carol Mac', 'Max', 'https://c-max.example.test', NULL, 'max');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000d', true);
SELECT public.fluck_web_upsert_instance('d-max', 'Dave Mac', 'Max', 'https://d-max.example.test', NULL, 'max');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000e', true);
SELECT public.fluck_web_upsert_instance('e-max', 'Erin Mac', 'Max', 'https://e-max.example.test', NULL, 'max');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('e-x', 'X', 'X', 'https://x.example.test', NULL, 'other') $q$,
    '22023', NULL, 'an unknown kind is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('e-x', 'X', 'X', 'https://x.example.test', NULL, 'agent:Bad Slug') $q$,
    '22023', NULL, 'a malformed managed-agent kind is refused');

-- ---- policy off: colleagues are strangers ----
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT is((SELECT array_agg(instance_id ORDER BY instance_id) FROM public.fluck_peer_list('b-max')),
    ARRAY['b-fl','b-hr','b-max','b-old','b-sup'], 'policy off: bob lists only his own instances');
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
SELECT lives_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-old', 'a0a00000-0000-4000-8000-00000000000b', 'b-max') $q$,
    'an instance with no kind still reaches its owner''s own instances');
SELECT public.fluck_web_upsert_instance('b-max', 'Bob Mac', 'Max', 'https://b-max.example.test', NULL);
SELECT is((SELECT agent_kind FROM public.fluck_web_instances WHERE instance_id = 'b-max'), NULL,
    'a kindless heartbeat (a downgraded client) clears the kind, so it gets no org tickets');
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-max', 'a0a00000-0000-4000-8000-00000000000a', 'a-max') $q$,
    'P0001', 'peer_unavailable', 'and cannot mint one');
SELECT public.fluck_web_upsert_instance('b-max', 'Bob Mac', 'Max', 'https://b-max.example.test', NULL, 'max');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('a-max') WHERE NOT same_user), 0, 'someone else''s instance name selects no colleagues');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('b-max') WHERE instance_id IN ('d-max','e-max')), 0, 'non-members are not listed');

INSERT INTO t SELECT 'bob-alice', public.fluck_a2a_mint_ticket('b-max', 'a0a00000-0000-4000-8000-00000000000a', 'a-max');
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-max', 'a0a00000-0000-4000-8000-00000000000a', 'a-fl') $q$,
    'P0001', 'peer_unavailable', 'no ticket from a Max to a colleague''s Fluck');
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-old', 'a0a00000-0000-4000-8000-00000000000a', 'a-max') $q$,
    'P0001', 'peer_unavailable', 'no ticket from an instance with no kind');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('a-max', 'a0a00000-0000-4000-8000-00000000000b', 'b-old') $q$,
    'P0001', 'peer_unavailable', 'and never to one: an older build is never the target of an org ticket');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('a-max', 'a0a00000-0000-4000-8000-00000000000a', 'a-max') $q$,
    'P0001', 'peer_unavailable', 'a calling instance that is not the caller''s gets no kind and no org ticket');
SELECT lives_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-fl', 'a0a00000-0000-4000-8000-00000000000a', 'a-fl') $q$,
    'Fluck to a colleague''s Fluck is allowed');
SELECT is((SELECT array_agg(instance_id ORDER BY instance_id) FROM public.fluck_peer_list('b-sup') WHERE NOT same_user),
    ARRAY['a-sup'], 'a managed agent lists the same managed agent of colleagues only');
SELECT lives_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-sup', 'a0a00000-0000-4000-8000-00000000000a', 'a-sup') $q$,
    'a managed agent reaches a colleague''s same managed agent');
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-hr', 'a0a00000-0000-4000-8000-00000000000a', 'a-sup') $q$,
    'P0001', 'peer_unavailable', 'but not another managed agent');
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
SELECT is(public.fluck_a2a_claim_org_notice((SELECT org FROM ids)), false, 'an opted-out member cannot burn the notice');
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
SELECT is((public.org_a2a_policy((SELECT org FROM ids), 'a0a00000-0000-4000-8000-00000000000a') ->> 'error'), 'Permission denied',
    'nor by naming an admin as the actor');
SELECT is((public.org_set_a2a_auto_connect((SELECT org FROM ids), false, 'a0a00000-0000-4000-8000-00000000000a') ->> 'error'), 'Permission denied',
    'nor change the policy that way');
RESET ROLE;
SELECT is((SELECT array_agg(action ORDER BY id) FROM public.agent_audit_log WHERE org_id = (SELECT org FROM ids) AND action LIKE 'org.a2a_opt%'),
    ARRAY['org.a2a_opt_out'], 'the opt-out is audited');
SET LOCAL ROLE authenticated;

-- ---- blocking one colleague ----
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT lives_ok($q$ SELECT public.fluck_peer_revoke('a0a00000-0000-4000-8000-00000000000a') $q$, 'bob blocks alice, with no link row between them');
SELECT is((SELECT status FROM public.fluck_peer_links() WHERE peer_id = 'a0a00000-0000-4000-8000-00000000000a'), 'revoked', 'bob sees his own block');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((SELECT count(*)::int FROM public.fluck_peer_links() WHERE peer_id = 'a0a00000-0000-4000-8000-00000000000b'), 0, 'alice is not shown that bob blocked her');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT lives_ok($q$ SELECT public.fluck_peer_revoke('a0a00000-0000-4000-8000-00000000000a') $q$, 'bob blocks alice again');
RESET ROLE;
SELECT is((SELECT count(*)::int FROM public.fluck_peer_links WHERE least(requester_id, recipient_id) = 'a0a00000-0000-4000-8000-00000000000a'::uuid
           AND greatest(requester_id, recipient_id) = 'a0a00000-0000-4000-8000-00000000000b'::uuid), 1, 'a repeated block writes no second row');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('b-max') WHERE NOT same_user), 0, 'bob no longer lists alice');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('a-max') WHERE NOT same_user), 0, 'and alice no longer lists bob');
SELECT lives_ok($q$ SELECT public.fluck_peer_unblock('a0a00000-0000-4000-8000-00000000000b') $q$, 'alice tries to lift bob''s block');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('a-max') WHERE NOT same_user), 0, 'only the one who blocked can lift it');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT lives_ok($q$ SELECT public.fluck_peer_unblock('a0a00000-0000-4000-8000-00000000000a') $q$, 'bob lifts his block');
SELECT is((SELECT array_agg(instance_id ORDER BY instance_id) FROM public.fluck_peer_list('b-max') WHERE NOT same_user),
    ARRAY['a-max'], 'alice is a colleague again');
RESET ROLE;
INSERT INTO public.fluck_peer_links (requester_id, recipient_id, status, responded_at)
    VALUES ('a0a00000-0000-4000-8000-00000000000a', 'a0a00000-0000-4000-8000-00000000000b', 'revoked', now());
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('a-max') WHERE NOT same_user), 0, 'a revoke from before this migration blocks');
SELECT lives_ok($q$ SELECT public.fluck_peer_unblock('a0a00000-0000-4000-8000-00000000000b') $q$, 'alice tries to lift a revoke whose author is unknown');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('a-max') WHERE NOT same_user), 0, 'a legacy revoke is not lifted (fails closed)');
RESET ROLE;
DELETE FROM public.fluck_peer_links WHERE least(requester_id, recipient_id) = 'a0a00000-0000-4000-8000-00000000000a'::uuid AND greatest(requester_id, recipient_id) = 'a0a00000-0000-4000-8000-00000000000b'::uuid;
INSERT INTO public.fluck_peer_links (requester_id, recipient_id, status, responded_at)
    VALUES ('a0a00000-0000-4000-8000-00000000000a', 'a0a00000-0000-4000-8000-00000000000b', 'declined', now());
SET LOCAL ROLE authenticated;
SELECT lives_ok($q$ SELECT public.fluck_peer_unblock('a0a00000-0000-4000-8000-00000000000b') $q$, 'alice tries to lift bob''s decline of her request');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('a-max') WHERE NOT same_user), 0, 'a decline is not the requester''s to lift');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT lives_ok($q$ SELECT public.fluck_peer_unblock('a0a00000-0000-4000-8000-00000000000a') $q$, 'bob lifts his decline');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('b-max') WHERE NOT same_user), 1, 'and alice is a colleague again');
SELECT lives_ok($q$ SELECT public.fluck_peer_unblock(NULL) $q$, 'unblocking nobody is a no-op');
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
    ARRAY['a-fl:link','a-max:link','a-sup:link'], 'a linked peer sees every product, as before');

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
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000d', true);
SELECT is(public.fluck_a2a_claim_org_notice((SELECT org FROM ids)), false, 'a non-member claims nothing');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);

-- ---- an accepted link outranks the org grant, in the list and at redemption ----
RESET ROLE;
INSERT INTO public.fluck_peer_links (requester_id, recipient_id, status, responded_at)
    VALUES ('a0a00000-0000-4000-8000-00000000000b', 'a0a00000-0000-4000-8000-00000000000a', 'accepted', now());
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT is((SELECT source FROM public.fluck_peer_list('b-max') WHERE instance_id = 'a-max'), 'link', 'a linked colleague is listed as a link');
INSERT INTO t SELECT 'bob-alice-link', public.fluck_a2a_mint_ticket('b-max', 'a0a00000-0000-4000-8000-00000000000a', 'a-max');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((SELECT source FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'bob-alice-link'), 'a-max')), 'link',
    'and redeemed as a link, so the receiver does not run it read-only');
RESET ROLE;
DELETE FROM public.fluck_peer_links WHERE requester_id = 'a0a00000-0000-4000-8000-00000000000b' AND recipient_id = 'a0a00000-0000-4000-8000-00000000000a';
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);

-- ---- kill switch, leaving, policy off ----
RESET ROLE;
UPDATE public.platform_flags SET value = 'false'::jsonb WHERE key = 'a2a_org_auto_connect';
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('b-max') WHERE NOT same_user), 0, 'the platform kill switch ends every org grant');
SELECT is((SELECT auto_connect FROM public.fluck_a2a_orgs() WHERE org_id = (SELECT org FROM ids)), false,
    'and members are told it is off');
SELECT is(public.fluck_a2a_claim_org_notice((SELECT org FROM ids)), false, 'and no notice can be burned while it is paused');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((public.org_a2a_policy((SELECT org FROM ids)) ->> 'platform_enabled'), 'false', 'and so is the admin');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
RESET ROLE;
DELETE FROM public.platform_flags WHERE key = 'a2a_org_auto_connect';
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('b-max') WHERE NOT same_user), 0, 'a missing kill-switch row fails closed');
RESET ROLE;
INSERT INTO public.platform_flags (key, value) VALUES ('a2a_org_auto_connect', 'false'::jsonb);
RESET ROLE;
UPDATE public.platform_flags SET value = 'true'::jsonb WHERE key = 'a2a_org_auto_connect';
UPDATE public.organisation_members SET status = 'pending' WHERE user_id = 'a0a00000-0000-4000-8000-00000000000b' AND org_id = (SELECT org FROM ids);
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list('b-max') WHERE NOT same_user), 0, 'a member who is no longer active loses colleagues');
RESET ROLE;
UPDATE public.organisation_members SET status = 'active' WHERE user_id = 'a0a00000-0000-4000-8000-00000000000b' AND org_id = (SELECT org FROM ids);
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
INSERT INTO t SELECT 'alice-bob-off', public.fluck_a2a_mint_ticket('a-max', 'a0a00000-0000-4000-8000-00000000000b', 'b-max');
SELECT is((public.org_set_a2a_auto_connect((SELECT org FROM ids), false)) ->> 'success', 'true', 'the admin turns it off');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT throws_ok($q$ SELECT * FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'alice-bob-off'), 'b-max') $q$,
    'P0001', 'ticket_invalid', 'a ticket minted before the policy was turned off is refused');
SELECT lives_ok($q$ SELECT public.fluck_peer_revoke('a0a00000-0000-4000-8000-00000000000c') $q$, 'blocking a colleague while the policy is off');
RESET ROLE;
SELECT is((SELECT count(*)::int FROM public.fluck_peer_links WHERE requester_id = 'a0a00000-0000-4000-8000-00000000000b'::uuid
           AND recipient_id = 'a0a00000-0000-4000-8000-00000000000c'), 0, 'writes nothing: there is no connection to block');
SELECT set_config('request.jwt.claims', '{"role":"service_role"}', true);
SELECT set_config('request.jwt.claim.sub', '', true);
SELECT is((public.org_a2a_policy((SELECT org FROM ids), 'a0a00000-0000-4000-8000-00000000000a') ->> 'success'), 'true', 'the service role reads the policy as the named admin (the BossConsole page)');
SELECT set_config('request.jwt.claims', '', true);
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000c', true);
SELECT lives_ok($q$ SELECT public.fluck_a2a_set_org_opt_out((SELECT org FROM ids), false) $q$, 'carol opts back in');
RESET ROLE;
SELECT is((SELECT array_agg(action ORDER BY id) FROM public.agent_audit_log WHERE org_id = (SELECT org FROM ids) AND action LIKE 'org.a2a_opt%'),
    ARRAY['org.a2a_opt_out', 'org.a2a_opt_in'], 'opting back in is audited too');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT is((SELECT array_agg(source) FROM public.fluck_peer_list('a-max') WHERE NOT same_user), ARRAY['link'], 'only the explicit link remains');
SELECT is((public.org_set_a2a_auto_connect((SELECT org FROM ids), NULL)) ->> 'error', 'enabled is required', 'the policy needs a value');

-- ---- turned on again: every member is told again ----
RESET ROLE;
UPDATE public.organisation_a2a_notices SET notified_at = now() - interval '1 hour'
    WHERE org_id = (SELECT org FROM ids) AND user_id = 'a0a00000-0000-4000-8000-00000000000b';
UPDATE public.organisations SET a2a_auto_connect_at = now() - interval '2 hours' WHERE id = (SELECT org FROM ids);
SET LOCAL ROLE authenticated;
SELECT is((public.org_set_a2a_auto_connect((SELECT org FROM ids), true)) ->> 'success', 'true', 'the admin turns it back on');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT is(public.fluck_a2a_claim_org_notice((SELECT org FROM ids)), true, 'bob is told again after it was turned back on');

-- ---- a heartbeat through the rewritten upsert keeps an instance live (the touch trigger stamps last_seen_at) ----
RESET ROLE;
ALTER TABLE public.fluck_web_instances DISABLE TRIGGER fluck_web_instances_touch_on_write;
UPDATE public.fluck_web_instances SET last_seen_at = now() - interval '10 minutes' WHERE instance_id = 'a-max';
ALTER TABLE public.fluck_web_instances ENABLE TRIGGER fluck_web_instances_touch_on_write;
SET LOCAL ROLE authenticated;
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-max', 'a0a00000-0000-4000-8000-00000000000a', 'a-max') $q$,
    'P0001', 'peer_unavailable', 'an instance that stopped heartbeating cannot be reached');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT public.fluck_web_upsert_instance('a-max', 'Alice Mac', 'Max', 'https://a-max.example.test', NULL, 'max');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT lives_ok($q$ SELECT public.fluck_a2a_mint_ticket('b-max', 'a0a00000-0000-4000-8000-00000000000a', 'a-max') $q$,
    'a heartbeat on an existing row makes it reachable again');

-- ---- a colleague block does not stop a later explicit link (the request flow's cool-down applies) ----
SELECT lives_ok($q$ SELECT public.fluck_peer_revoke('a0a00000-0000-4000-8000-00000000000a') $q$, 'bob blocks alice');
RESET ROLE;
UPDATE public.fluck_peer_links SET responded_at = now() - interval '2 days'
    WHERE least(requester_id, recipient_id) = 'a0a00000-0000-4000-8000-00000000000a'::uuid AND greatest(requester_id, recipient_id) = 'a0a00000-0000-4000-8000-00000000000b'::uuid;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000a', true);
SELECT lives_ok($q$ SELECT public.fluck_peer_request('org-bob@acme.test') $q$, 'alice asks bob for a link after the cool-down');
SELECT set_config('request.jwt.claim.sub', 'a0a00000-0000-4000-8000-00000000000b', true);
SELECT lives_ok($q$ SELECT public.fluck_peer_respond((SELECT id FROM public.fluck_peer_links
    WHERE least(requester_id, recipient_id) = 'a0a00000-0000-4000-8000-00000000000a'::uuid AND greatest(requester_id, recipient_id) = 'a0a00000-0000-4000-8000-00000000000b'::uuid), true) $q$,
    'bob accepts');
SELECT is((SELECT source FROM public.fluck_peer_list('b-max') WHERE instance_id = 'a-max'), 'link', 'and they are linked');

SELECT * FROM finish();
ROLLBACK;
