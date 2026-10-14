-- provider_routing: NULL default, allow-listed shape, reaches the service-role lookup only.
BEGIN;
SELECT no_plan();
INSERT INTO auth.users(id,email) VALUES ('bc000000-0000-4000-8000-000000000001','boss-ai-routing@pgtap.test');
INSERT INTO public.boss_ai_connections(id,base_url,api_key_secret,api_type,enabled) VALUES
 ('pgtap-routing','https://example.com/v1','BOSS_AI_TEST','openai_chat',true);
INSERT INTO public.boss_ai_models
 (id,display_name,connection_id,upstream_model,context_length,max_output_tokens,published)
 VALUES('pgtap-routing','Test','pgtap-routing','private',1024,128,true);
INSERT INTO public.boss_ai_allowances(model_id,permission_name,tokens_per_day,tokens_per_week,tokens_per_month,max_concurrent)
 VALUES('pgtap-routing','ai.use',10240,20480,40960,2);

SELECT is((SELECT provider_routing FROM public.boss_ai_models WHERE id='pgtap-routing'),NULL::jsonb,
 'existing and new models default to no routing');
SET LOCAL ROLE service_role;
SELECT ok(public.boss_ai_lookup('bc000000-0000-4000-8000-000000000001','pgtap-routing')->'model'->'provider_routing'
 = 'null'::jsonb,'lookup carries a NULL routing as JSON null');
RESET ROLE;

SELECT lives_ok($$UPDATE public.boss_ai_models SET provider_routing=
 '{"order":["cerebras","coreweave"],"allow_fallbacks":true,"ignore":["groq","amazon-bedrock"],
   "only":["cerebras"],"require_parameters":false,"sort":"throughput"}' WHERE id='pgtap-routing'$$,
 'every allow-listed key is accepted');
SET LOCAL ROLE service_role;
SELECT is(public.boss_ai_lookup('bc000000-0000-4000-8000-000000000001','pgtap-routing')->'model'->'provider_routing'->'order',
 '["cerebras","coreweave"]'::jsonb,'lookup returns the routing to the edge function');
SELECT is(public.boss_ai_reserve('bc000000-0000-4000-8000-000000000001','pgtap-routing',
 'bd000000-0000-4000-8000-000000000001')->'model'->'provider_routing'->>'allow_fallbacks','true',
 'reservation returns the same routing');
RESET ROLE;

SELECT throws_ok($$UPDATE public.boss_ai_models SET provider_routing='[]' WHERE id='pgtap-routing'$$,
 '23514',NULL,'routing must be an object');
SELECT throws_ok($$UPDATE public.boss_ai_models SET provider_routing='"cerebras"' WHERE id='pgtap-routing'$$,
 '23514',NULL,'a scalar routing is a check violation, not an operator error');
SELECT throws_ok($$UPDATE public.boss_ai_models SET provider_routing='{"data_collection":"deny"}' WHERE id='pgtap-routing'$$,
 '23514',NULL,'keys outside the allow-list are refused');
SELECT throws_ok($$UPDATE public.boss_ai_models SET provider_routing='{"order":"cerebras"}' WHERE id='pgtap-routing'$$,
 '23514',NULL,'lists must be arrays');
SELECT throws_ok($$UPDATE public.boss_ai_models SET provider_routing='{"allow_fallbacks":"yes"}' WHERE id='pgtap-routing'$$,
 '23514',NULL,'flags must be booleans');
SELECT throws_ok($$UPDATE public.boss_ai_models SET provider_routing='{"sort":"fastest"}' WHERE id='pgtap-routing'$$,
 '23514',NULL,'sort must be a known strategy');
SELECT throws_ok($$UPDATE public.boss_ai_models SET provider_routing='{"sort":null}' WHERE id='pgtap-routing'$$,
 '23514',NULL,'a JSON null value is refused like the function refuses it');
SELECT throws_ok($$UPDATE public.boss_ai_models SET provider_routing='{"only":[]}' WHERE id='pgtap-routing'$$,
 '23514',NULL,'empty provider lists are refused');
SELECT throws_ok($$UPDATE public.boss_ai_models SET provider_routing='{"order":["Cerebras"]}' WHERE id='pgtap-routing'$$,
 '23514',NULL,'slugs must be lowercase provider ids');
SELECT throws_ok($$UPDATE public.boss_ai_models SET provider_routing='{"order":[1]}' WHERE id='pgtap-routing'$$,
 '23514',NULL,'slugs must be strings');
SELECT throws_ok($$UPDATE public.boss_ai_models SET provider_routing=
 jsonb_build_object('ignore',(SELECT jsonb_agg('p'||g) FROM generate_series(1,33) g)) WHERE id='pgtap-routing'$$,
 '23514',NULL,'lists are bounded at 32 providers');

SELECT ok(NOT EXISTS (SELECT 1 FROM jsonb_array_elements(public.boss_ai_catalog('bc000000-0000-4000-8000-000000000001')) e
 WHERE e ? 'provider_routing'),'the user catalog does not expose routing');
SET LOCAL ROLE authenticated;
SELECT throws_ok($$SELECT provider_routing FROM public.boss_ai_models$$,'42501',NULL,
 'signed-in users cannot read model routing');
RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
