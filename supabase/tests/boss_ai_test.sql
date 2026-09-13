-- Real RBAC/auth schema integration; all fixtures roll back with the test.
BEGIN;
SELECT no_plan();
INSERT INTO auth.users(id,email) VALUES
 ('ba000000-0000-4000-8000-000000000001','boss-ai-one@pgtap.test'),
 ('ba000000-0000-4000-8000-000000000002','boss-ai-two@pgtap.test');
INSERT INTO public.boss_ai_connections(id,base_url,api_key_secret,api_type,enabled) VALUES
 ('pgtap','https://example.com/v1','BOSS_AI_TEST','openai_chat',true);
INSERT INTO public.boss_ai_models
 (id,display_name,connection_id,upstream_model,context_length,max_output_tokens,published)
 VALUES('pgtap','Test','pgtap','private',1024,128,true);
INSERT INTO public.boss_ai_allowances(model_id,permission_name,tokens_per_day,tokens_per_week,tokens_per_month,max_concurrent)
 VALUES('pgtap','ai.use',10240,20480,40960,2);

SELECT ok(public.user_has_permission('ba000000-0000-4000-8000-000000000001','ai.use'),
 'new users inherit the baseline AI permission through real RBAC');
SELECT is((public.boss_ai_policy('ba000000-0000-4000-8000-000000000001','pgtap')->>'day')::bigint,
 10240::bigint,'baseline permission selects the allowance');
SELECT throws_ok($$UPDATE public.boss_ai_connections SET api_key_secret='BOSS_AI_SIGNING_SECRET'
 WHERE id='pgtap'$$, '23514', NULL, 'signing secret cannot become an upstream key');

SET LOCAL ROLE service_role;
SELECT ok(public.boss_ai_lookup('ba000000-0000-4000-8000-000000000001','pgtap') ? 'model',
 'authorized service-role preflight returns configuration');
SELECT is((SELECT count(*) FROM public.boss_ai_requests WHERE model_id='pgtap'),0::bigint,
 'preflight creates no accounting rows');
UPDATE public.boss_ai_allowances SET tokens_per_day=1 WHERE model_id='pgtap';
SELECT is(public.boss_ai_lookup('ba000000-0000-4000-8000-000000000001','pgtap')->>'error',
 'misconfigured_allowance','an allowance smaller than one context is a configuration fault');
UPDATE public.boss_ai_allowances SET tokens_per_day=10240 WHERE model_id='pgtap';
SELECT is((SELECT count(*) FROM public.boss_ai_connections WHERE id='pgtap'),1::bigint,
 'real service role can read RLS-protected routing');
SELECT ok(public.boss_ai_reserve('ba000000-0000-4000-8000-000000000001','pgtap',
 'bb000000-0000-4000-8000-000000000001') ? 'model','service role can reserve through real RBAC');
SELECT is(public.boss_ai_reserve('ba000000-0000-4000-8000-000000000001','pgtap',
 'bb000000-0000-4000-8000-000000000001')->>'error','duplicate','duplicate requests are refused');
SELECT ok(public.boss_ai_reserve('ba000000-0000-4000-8000-000000000001','pgtap',
 'bb000000-0000-4000-8000-000000000002') ? 'model','second concurrency slot admitted');
SELECT is(public.boss_ai_reserve('ba000000-0000-4000-8000-000000000001','pgtap',
 'bb000000-0000-4000-8000-000000000003')->>'error','concurrency_exceeded','slot cap enforced');
SELECT ok(public.boss_ai_reserve('ba000000-0000-4000-8000-000000000002','pgtap',
 'bb000000-0000-4000-8000-000000000004') ? 'model','other users have separate slots and usage');
UPDATE public.boss_ai_requests SET lease_until=now()-interval '1 second'
 WHERE id='bb000000-0000-4000-8000-000000000001';
SELECT ok(public.boss_ai_reserve('ba000000-0000-4000-8000-000000000001','pgtap',
 'bb000000-0000-4000-8000-000000000003') ? 'model','expired lease releases its slot');
SELECT is((SELECT charged_tokens FROM public.boss_ai_requests
 WHERE id='bb000000-0000-4000-8000-000000000001'),1024::bigint,'expired lease keeps unknown usage charged');
SELECT public.boss_ai_settle('bb000000-0000-4000-8000-000000000001',99999999999);
SELECT is((SELECT charged_tokens FROM public.boss_ai_requests
 WHERE id='bb000000-0000-4000-8000-000000000001'),1024::bigint,'upstream usage cannot exceed the admitted bound');
SELECT public.boss_ai_settle('bb000000-0000-4000-8000-000000000002',7);
SELECT throws_ok($$SELECT public.boss_ai_settle('bb000000-0000-4000-8000-000000000002',-1)$$,
 'P0001','Invalid usage','negative settlement is rejected');
SELECT public.boss_ai_settle('bb000000-0000-4000-8000-000000000002',0);
SELECT is((SELECT charged_tokens FROM public.boss_ai_requests
 WHERE id='bb000000-0000-4000-8000-000000000002'),7::bigint,'settlement is first-writer-wins');
RESET ROLE;

UPDATE auth.users SET banned_until=now()+interval '1 hour'
 WHERE id='ba000000-0000-4000-8000-000000000001';
SELECT is(public.boss_ai_policy('ba000000-0000-4000-8000-000000000001','pgtap'),NULL::jsonb,
 'banned users lose model permission');
UPDATE auth.users SET banned_until=NULL WHERE id='ba000000-0000-4000-8000-000000000001';
UPDATE public.boss_ai_connections SET enabled=false WHERE id='pgtap';
SELECT is(public.boss_ai_catalog('ba000000-0000-4000-8000-000000000001'),'[]'::jsonb,
 'disabled connections disappear from the catalog');
UPDATE public.boss_ai_connections SET enabled=true WHERE id='pgtap';
UPDATE public.boss_ai_models SET published=false WHERE id='pgtap';
SELECT is(public.boss_ai_policy('ba000000-0000-4000-8000-000000000001','pgtap'),NULL::jsonb,
 'unpublished models deny admission');
UPDATE public.boss_ai_models SET published=true WHERE id='pgtap';

-- Exercise the actual usage function on both sides of every current UTC boundary,
-- with a non-UTC database timezone. Separate windows are isolated by deleting fixtures.
SET LOCAL timezone='Pacific/Honolulu';
SELECT lives_ok($test$
DO $body$
DECLARE period text; boundary timestamptz; usage jsonb;
BEGIN
 FOREACH period IN ARRAY ARRAY['day','week','month'] LOOP
  DELETE FROM public.boss_ai_requests WHERE model_id='pgtap';
  boundary := date_trunc(period,now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
  INSERT INTO public.boss_ai_requests(id,user_id,model_id,started_at,reserved_tokens,charged_tokens,settled)
  VALUES(gen_random_uuid(),'ba000000-0000-4000-8000-000000000001','pgtap',boundary-interval '1 microsecond',1024,30,true),
        (gen_random_uuid(),'ba000000-0000-4000-8000-000000000001','pgtap',boundary,1024,7,true);
  usage := public.boss_ai_usage('ba000000-0000-4000-8000-000000000001','pgtap')->period;
  IF (usage->>'used')::bigint <> 7 THEN RAISE EXCEPTION 'bad % rollover',period; END IF;
  IF (usage->>'resets_at')::timestamptz <>
    ((boundary AT TIME ZONE 'UTC')+('1 '||period)::interval) AT TIME ZONE 'UTC'
  THEN RAISE EXCEPTION 'bad % reset',period; END IF;
 END LOOP;
END $body$;
$test$, 'UTC day, Monday-week and month boundaries exclude the preceding window');

DELETE FROM public.role_permissions WHERE permission_id=(SELECT id FROM public.permissions WHERE name='ai.use');
SELECT is(public.boss_ai_policy('ba000000-0000-4000-8000-000000000001','pgtap'),NULL::jsonb,
 'revoking the real baseline grant takes effect immediately');
SET LOCAL ROLE authenticated;
SELECT throws_ok($$SELECT public.boss_ai_lookup('ba000000-0000-4000-8000-000000000001','pgtap')$$,
 '42501',NULL,'authenticated cannot read upstream config through preflight');
SELECT throws_ok('SELECT * FROM public.boss_ai_connections','42501',NULL,'authenticated cannot read keys/routing');
SELECT throws_ok($$SELECT public.boss_ai_catalog('ba000000-0000-4000-8000-000000000001')$$,
 '42501',NULL,'authenticated cannot impersonate a catalog user');
SET LOCAL ROLE anon;
SELECT throws_ok('SELECT * FROM public.boss_ai_requests','42501',NULL,'anonymous cannot read accounting');
SELECT throws_ok($$SELECT public.boss_ai_settle('bb000000-0000-4000-8000-000000000001',0)$$,
 '42501',NULL,'anonymous cannot refund requests');
RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
