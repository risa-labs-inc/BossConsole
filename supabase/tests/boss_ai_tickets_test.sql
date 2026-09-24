-- BOSS AI exchange tickets under the real RBAC and grant stack. Issuance binds
-- the calling user, redemption is single-use and permission-checked, no role
-- below service_role can touch the ledger, and pending issuance is capped.
-- Raw ticket material crosses role switches through session custom GUCs, because
-- temporary tables created by one role cannot be read by another.
BEGIN;
SELECT no_plan();
INSERT INTO auth.users(id,email) VALUES
 ('b9000000-0000-4000-8000-000000000001','boss-ai-ticket-one@pgtap.test'),
 ('b9000000-0000-4000-8000-000000000002','boss-ai-ticket-two@pgtap.test');
SELECT ok(public.user_has_permission('b9000000-0000-4000-8000-000000000001','ai.use'),
 'new users inherit the baseline AI permission through real RBAC');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','b9000000-0000-4000-8000-000000000001',true);
SELECT set_config('boss_ai.ticket.one',
 public.boss_ai_create_exchange_ticket()->>'ticket', false);
SELECT ok(current_setting('boss_ai.ticket.one') ~ '^[a-f0-9]{64}$',
 'issued exchange ticket is 64 lowercase hex characters');
SELECT throws_ok($$SELECT public.boss_ai_consume_exchange_ticket(
 current_setting('boss_ai.ticket.one'))$$, '42501', NULL,
 'authenticated callers cannot redeem tickets');
SELECT throws_ok($$SELECT * FROM public.boss_ai_exchange_tickets$$, '42501', NULL,
 'authenticated callers cannot read the ticket ledger');
SET LOCAL ROLE anon;
SELECT throws_ok($$SELECT public.boss_ai_create_exchange_ticket()$$, '42501', NULL,
 'anonymous sessions cannot issue tickets');
RESET ROLE;
SELECT is((SELECT count(*) FROM public.boss_ai_exchange_tickets
 WHERE user_id='b9000000-0000-4000-8000-000000000001'), 1::bigint,
 'issuance stores exactly one pending ticket');
SELECT ok(NOT EXISTS (SELECT 1 FROM public.boss_ai_exchange_tickets
 WHERE ticket_hash = convert_to(current_setting('boss_ai.ticket.one'),'UTF8')),
 'raw ticket material is never stored');
SELECT ok(EXISTS (SELECT 1 FROM public.boss_ai_exchange_tickets
 WHERE ticket_hash = sha256(convert_to(current_setting('boss_ai.ticket.one'),'UTF8'))),
 'only the SHA-256 digest of the ticket is stored');

-- Single-spend: the first redemption returns the issuing user and destroys the row.
SET LOCAL ROLE service_role;
SELECT is(public.boss_ai_consume_exchange_ticket(current_setting('boss_ai.ticket.one')),
 'b9000000-0000-4000-8000-000000000001'::uuid,
 'a ticket redeems exactly its issuing user');
SELECT is(public.boss_ai_consume_exchange_ticket(current_setting('boss_ai.ticket.one')),
 NULL::uuid, 'a redeemed ticket cannot be spent a second time');
SELECT is(public.boss_ai_consume_exchange_ticket(repeat('0',64)),
 NULL::uuid, 'unknown ticket values fail closed');
SELECT is((SELECT count(*) FROM public.boss_ai_exchange_tickets
 WHERE user_id='b9000000-0000-4000-8000-000000000001'), 0::bigint,
 'redemption removes the ticket row');

-- Banning between issuance and redemption burns the ticket instead of redeeming it.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','b9000000-0000-4000-8000-000000000002',true);
SELECT set_config('boss_ai.ticket.banned',
 public.boss_ai_create_exchange_ticket()->>'ticket', false);
RESET ROLE;
UPDATE auth.users SET banned_until=now()+interval '1 hour'
 WHERE id='b9000000-0000-4000-8000-000000000002';
SET LOCAL ROLE service_role;
SELECT is(public.boss_ai_consume_exchange_ticket(current_setting('boss_ai.ticket.banned')),
 NULL::uuid, 'a banned user cannot redeem an already-issued ticket');
SELECT is((SELECT count(*) FROM public.boss_ai_exchange_tickets
 WHERE user_id='b9000000-0000-4000-8000-000000000002'), 0::bigint,
 'a refused redemption burns the ticket row');
RESET ROLE;
UPDATE auth.users SET banned_until=NULL
 WHERE id='b9000000-0000-4000-8000-000000000002';

-- Anonymous accounts can neither issue nor redeem.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','b9000000-0000-4000-8000-000000000002',true);
SELECT set_config('boss_ai.ticket.anon',
 public.boss_ai_create_exchange_ticket()->>'ticket', false);
RESET ROLE;
UPDATE auth.users SET is_anonymous=true
 WHERE id='b9000000-0000-4000-8000-000000000002';
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','b9000000-0000-4000-8000-000000000002',true);
SELECT throws_ok($$SELECT public.boss_ai_create_exchange_ticket()$$, '42501', NULL,
 'anonymous users cannot issue tickets');
RESET ROLE;
SET LOCAL ROLE service_role;
SELECT is(public.boss_ai_consume_exchange_ticket(current_setting('boss_ai.ticket.anon')),
 NULL::uuid, 'an anonymous user cannot redeem an already-issued ticket');
RESET ROLE;
UPDATE auth.users SET is_anonymous=false
 WHERE id='b9000000-0000-4000-8000-000000000002';

-- The pending-exchange cap: eight live, the ninth refused.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','b9000000-0000-4000-8000-000000000002',true);
SELECT lives_ok($test$ DO $body$
 BEGIN
  FOR i IN 1..8 LOOP PERFORM public.boss_ai_create_exchange_ticket(); END LOOP;
 END $body$ $test$, 'eight pending exchanges are admitted');
SELECT throws_ok($$SELECT public.boss_ai_create_exchange_ticket()$$, '54000', NULL,
 'a ninth pending exchange is refused');
RESET ROLE;
SELECT is((SELECT count(*) FROM public.boss_ai_exchange_tickets
 WHERE user_id='b9000000-0000-4000-8000-000000000002'), 8::bigint,
 'the pending cap holds exactly eight tickets');

-- Revoking the baseline grant between issuance and redemption burns the ticket.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','b9000000-0000-4000-8000-000000000001',true);
SELECT set_config('boss_ai.ticket.revoked',
 public.boss_ai_create_exchange_ticket()->>'ticket', false);
RESET ROLE;
DELETE FROM public.role_permissions
 WHERE permission_id=(SELECT id FROM public.permissions WHERE name='ai.use');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','b9000000-0000-4000-8000-000000000001',true);
SELECT throws_ok($$SELECT public.boss_ai_create_exchange_ticket()$$, '42501', NULL,
 'revoked callers cannot issue tickets');
RESET ROLE;
SET LOCAL ROLE service_role;
SELECT is(public.boss_ai_consume_exchange_ticket(current_setting('boss_ai.ticket.revoked')),
 NULL::uuid, 'revocation between issuance and redemption burns the ticket');
SELECT is((SELECT count(*) FROM public.boss_ai_exchange_tickets
 WHERE user_id='b9000000-0000-4000-8000-000000000001'), 0::bigint,
 'a burned revoked ticket leaves no redeemable row');
RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
