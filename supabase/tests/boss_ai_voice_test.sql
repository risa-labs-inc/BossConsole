-- Audio models: pooled seconds, per-kind reservation, catalog kind, one-level pools.
BEGIN;
SELECT no_plan();
INSERT INTO auth.users(id,email) VALUES ('be000000-0000-4000-8000-000000000001','boss-ai-voice@pgtap.test');
INSERT INTO public.boss_ai_connections(id,base_url,api_key_secret,api_type,enabled) VALUES
 ('pgtap-voice','https://example.com/v1','BOSS_AI_TEST','openai_chat',true);
INSERT INTO public.boss_ai_models
 (id,display_name,connection_id,upstream_model,kind,voice,context_length,max_output_tokens,published) VALUES
 ('pgtap-stt','STT','pgtap-voice','stt','transcription',NULL,1024,120,true),
 ('pgtap-tts','TTS','pgtap-voice','tts','speech','sarah',1024,60,true);
UPDATE public.boss_ai_models SET allowance_model_id='pgtap-stt' WHERE id='pgtap-tts';
INSERT INTO public.boss_ai_allowances(model_id,permission_name,tokens_per_day,tokens_per_week,tokens_per_month,max_concurrent)
 VALUES('pgtap-stt','ai.use',3600,3600,3600,6);

SELECT throws_ok($$UPDATE public.boss_ai_models SET allowance_model_id='pgtap-tts' WHERE id='pgtap-stt'$$,
 '23514',NULL,'a pool target cannot itself draw from another pool');
SELECT throws_ok($$UPDATE public.boss_ai_models SET allowance_model_id='pgtap-tts' WHERE id='pgtap-tts'$$,
 '23514',NULL,'a model cannot pool into itself');
SELECT throws_ok($$UPDATE public.boss_ai_models SET kind='video' WHERE id='pgtap-tts'$$,
 '23514',NULL,'kind is a closed set');

SET LOCAL ROLE service_role;
SELECT is(public.boss_ai_lookup('be000000-0000-4000-8000-000000000001','pgtap-tts')->'model'->>'voice','sarah',
 'lookup carries the speech voice');
SELECT is((public.boss_ai_reserve('be000000-0000-4000-8000-000000000001','pgtap-tts',
 'bf000000-0000-4000-8000-000000000001')->'model'->>'kind'),'speech','reservation returns the kind');
SELECT is((SELECT reserved_tokens FROM public.boss_ai_requests WHERE id='bf000000-0000-4000-8000-000000000001'),
 60::bigint,'audio reserves max_output_tokens, not context_length');
SELECT public.boss_ai_settle('bf000000-0000-4000-8000-000000000001',7);
SELECT is(public.boss_ai_usage('be000000-0000-4000-8000-000000000001','pgtap-stt')->'month'->>'used','7',
 'TTS seconds are charged into the STT pool');
SELECT is(public.boss_ai_usage('be000000-0000-4000-8000-000000000001','pgtap-tts')->'month'->>'limit','3600',
 'the pooled model reports the pool limit');
SELECT is((SELECT array_agg(e->>'kind' ORDER BY e->>'id')
 FROM jsonb_array_elements(public.boss_ai_catalog('be000000-0000-4000-8000-000000000001')) e),
 ARRAY['transcription','speech'],'catalog lists the kind of each model');
RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
