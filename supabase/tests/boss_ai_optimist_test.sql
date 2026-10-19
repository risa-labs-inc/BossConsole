-- An organisation-scoped BOSS AI model (20261007000000_boss_ai_optimist_permission.sql), end to end
-- through real RBAC: the organisation's user-kind role carries ai.optimist, an organisation admin
-- mints a single-use invite, the person redeems it with their own session, and only then does the
-- catalog list the model. All fixtures roll back.
BEGIN;
SELECT no_plan();

INSERT INTO auth.users(id,email,email_confirmed_at) VALUES
 ('0e000000-0000-4000-8000-000000000001','opt-bot@pgtap.test',now()),
 ('0e000000-0000-4000-8000-000000000002','opt-member@pgtap.test',now()),
 ('0e000000-0000-4000-8000-000000000003','opt-outsider@pgtap.test',now());

SELECT ok(EXISTS(SELECT 1 FROM public.permissions WHERE name='ai.optimist' AND is_system),
 'ai.optimist exists and is a system permission');

SELECT is(public.create_organisation_internal(
 p_slug=>'pgtopt', p_name=>'PGTap Optimist',
 p_owner_id=>'0e000000-0000-4000-8000-000000000001',
 p_visibility=>'private', p_join_policy=>'invite_only')->>'success','true',
 'an invite-only organisation is created with the bot as owner');

-- Operator step: the organisation member role carries the model permission.
SELECT lives_ok($$INSERT INTO public.role_permissions(role_id,permission_id)
 SELECT orl.role_id, p.id FROM public.organisation_roles orl
 JOIN public.organisations o ON o.id=orl.org_id AND o.slug='pgtopt'
 CROSS JOIN public.permissions p WHERE orl.kind='user' AND p.name='ai.optimist'$$,
 'ai.optimist may be granted to an organisation role');

INSERT INTO public.boss_ai_connections(id,base_url,api_key_secret,api_type) VALUES
 ('pgtopt','https://example.com/v1','BOSS_AI_TEST','openai_chat');
INSERT INTO public.boss_ai_models
 (id,display_name,connection_id,upstream_model,capabilities,context_length,max_output_tokens,published)
 VALUES('pgtopt','Optimist','pgtopt','vendor/model-a',ARRAY['text','tools'],2048,1024,true),
       ('pgtopt-base','Base','pgtopt','vendor/model-b',ARRAY['text'],1024,128,true);
INSERT INTO public.boss_ai_allowances(model_id,permission_name,tokens_per_day,tokens_per_week,tokens_per_month,max_concurrent)
 VALUES('pgtopt','ai.optimist',20480,40960,81920,4),
       ('pgtopt-base','ai.use',10240,20480,40960,2);

-- Before enrollment: baseline only.
SELECT ok(NOT public.user_has_permission('0e000000-0000-4000-8000-000000000002','ai.optimist'),
 'a new user does not hold ai.optimist');
SELECT ok(public.boss_ai_token_eligible('0e000000-0000-4000-8000-000000000002'),
 'a new user can still mint a BOSS AI token through ai.use');
SELECT is(public.boss_ai_policy('0e000000-0000-4000-8000-000000000002','pgtopt'),NULL::jsonb,
 'a new user has no policy for the organisation model');

-- An outsider cannot mint an invite.
SELECT set_config('request.jwt.claims','{"sub":"0e000000-0000-4000-8000-000000000003","role":"authenticated"}',true);
SET LOCAL ROLE authenticated;
SELECT is(public.create_organisation_invite((SELECT id FROM public.organisations WHERE slug='pgtopt'),
 NULL,'enroll',1,1)->>'error','Permission denied','a non-admin cannot mint an enrollment invite');
RESET ROLE;

-- The bot mints a single-use, one-hour invite with its own session.
CREATE TEMP TABLE t_opt_inv(token text);
GRANT ALL ON t_opt_inv TO authenticated;
SELECT set_config('request.jwt.claims','{"sub":"0e000000-0000-4000-8000-000000000001","role":"authenticated"}',true);
SET LOCAL ROLE authenticated;
INSERT INTO t_opt_inv SELECT public.create_organisation_invite(
 (SELECT id FROM public.organisations WHERE slug='pgtopt'),NULL,'enroll',1,1)->>'token';
RESET ROLE;
SELECT matches((SELECT token FROM t_opt_inv),'^boss_inv_','the bot mints an invite');

-- The person redeems it with their own session.
SELECT set_config('request.jwt.claims','{"sub":"0e000000-0000-4000-8000-000000000002","role":"authenticated"}',true);
SET LOCAL ROLE authenticated;
SELECT is(public.redeem_organisation_invite((SELECT token FROM t_opt_inv))->>'success','true',
 'the person redeems the invite');
SELECT is(public.redeem_organisation_invite((SELECT token FROM t_opt_inv))->>'already_member','true',
 're-redeeming the same invite is idempotent for that person');
RESET ROLE;

SELECT set_config('request.jwt.claims','{"sub":"0e000000-0000-4000-8000-000000000003","role":"authenticated"}',true);
SET LOCAL ROLE authenticated;
SELECT is(public.redeem_organisation_invite((SELECT token FROM t_opt_inv))->>'success','false',
 'a used single-use invite admits nobody else');
RESET ROLE;
SELECT set_config('request.jwt.claims','',true);

SELECT ok(public.user_has_permission('0e000000-0000-4000-8000-000000000002','ai.optimist'),
 'membership grants ai.optimist through the organisation user role');
SELECT ok(NOT public.user_has_permission('0e000000-0000-4000-8000-000000000003','ai.optimist'),
 'the outsider does not hold ai.optimist');

-- Another organisation's admin cannot extend the model to their organisation through the org-admin
-- RPC (20261008100000), even when they hold ai.optimist themselves.
SELECT is(public.create_organisation_internal(p_slug=>'pgtopt2', p_name=>'PGTap Other',
 p_owner_id=>'0e000000-0000-4000-8000-000000000002')->>'success','true',
 'the member owns a second organisation');
SELECT is(public.create_organisation_internal(p_slug=>'pgtopt3', p_name=>'PGTap Third',
 p_owner_id=>'0e000000-0000-4000-8000-000000000003')->>'success','true',
 'the outsider owns a third organisation');
CREATE TEMP TABLE t_opt_roles AS SELECT o.slug, o.id AS org_id, orl.role_id
 FROM public.organisations o JOIN public.organisation_roles orl ON orl.org_id=o.id AND orl.kind='user'
 WHERE o.slug IN ('pgtopt2','pgtopt3');
GRANT SELECT ON t_opt_roles TO authenticated;

SELECT set_config('request.jwt.claims','{"sub":"0e000000-0000-4000-8000-000000000002","role":"authenticated"}',true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT public.grant_organisation_role_permission(org_id, role_id, 'ai.optimist')->>'success'
 FROM t_opt_roles WHERE slug='pgtopt2'),'false',
 'an organisation admin holding ai.optimist cannot grant it to another organisation');
RESET ROLE;
SELECT set_config('request.jwt.claims','{"sub":"0e000000-0000-4000-8000-000000000003","role":"authenticated"}',true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT public.grant_organisation_role_permission(org_id, role_id, 'ai.optimist')->>'success'
 FROM t_opt_roles WHERE slug='pgtopt3'),'false',
 'an organisation admin without ai.optimist cannot grant it');
RESET ROLE;
SELECT set_config('request.jwt.claims','',true);
SELECT ok(NOT EXISTS(SELECT 1 FROM public.role_permissions rp JOIN t_opt_roles t ON t.role_id=rp.role_id
 JOIN public.permissions p ON p.id=rp.permission_id AND p.name='ai.optimist'),
 'neither organisation role carries ai.optimist');

SET LOCAL ROLE service_role;
SELECT ok(public.boss_ai_catalog('0e000000-0000-4000-8000-000000000002') @> '[{"id":"pgtopt"}]'::jsonb,
 'the member catalog lists the organisation model');
SELECT ok(public.boss_ai_catalog('0e000000-0000-4000-8000-000000000002') @> '[{"id":"pgtopt-base"}]'::jsonb,
 'the member keeps the baseline model');
SELECT ok(NOT public.boss_ai_catalog('0e000000-0000-4000-8000-000000000003') @> '[{"id":"pgtopt"}]'::jsonb,
 'the outsider catalog omits the organisation model');
SELECT ok(public.boss_ai_catalog('0e000000-0000-4000-8000-000000000003') @> '[{"id":"pgtopt-base"}]'::jsonb,
 'the outsider keeps the baseline model');
SELECT is(public.boss_ai_lookup('0e000000-0000-4000-8000-000000000003','pgtopt'),NULL::jsonb,
 'inference preflight refuses the outsider');
SELECT is(public.boss_ai_reserve('0e000000-0000-4000-8000-000000000003','pgtopt',
 'bf000000-0000-4000-8000-000000000001')->>'error','forbidden','admission refuses the outsider');
SELECT is(public.boss_ai_lookup('0e000000-0000-4000-8000-000000000002','pgtopt')->'model'->>'upstream_model',
 'vendor/model-a','the member is routed to the configured upstream model');
SELECT ok(public.boss_ai_reserve('0e000000-0000-4000-8000-000000000002','pgtopt',
 'bf000000-0000-4000-8000-000000000002') ? 'model','admission accepts the member');
RESET ROLE;

-- Switching the upstream is a row edit; the client-facing id is unchanged.
UPDATE public.boss_ai_models SET upstream_model='vendor/model-c' WHERE id='pgtopt';
SET LOCAL ROLE service_role;
SELECT is(public.boss_ai_lookup('0e000000-0000-4000-8000-000000000002','pgtopt')->'model'->>'upstream_model',
 'vendor/model-c','an upstream switch takes effect on the next request');
RESET ROLE;

-- Removing the member role revokes access immediately.
DELETE FROM public.user_roles ur USING public.organisation_roles orl, public.organisations o
 WHERE ur.user_id='0e000000-0000-4000-8000-000000000002' AND ur.role_id=orl.role_id
   AND orl.org_id=o.id AND o.slug='pgtopt' AND orl.kind='user';
SET LOCAL ROLE service_role;
SELECT ok(NOT public.boss_ai_catalog('0e000000-0000-4000-8000-000000000002') @> '[{"id":"pgtopt"}]'::jsonb,
 'removing the organisation role removes the model at once');
RESET ROLE;

SELECT * FROM finish();
ROLLBACK;
