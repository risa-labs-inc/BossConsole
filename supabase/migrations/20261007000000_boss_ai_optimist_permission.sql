-- Permission gate for the Optimist BOSS AI model.
--
-- boss_ai_allowances already scopes a model to a permission (boss_ai_policy). This adds the
-- permission only. The model row, its allowance and the "optimist" organisation (whose user-kind
-- role carries ai.optimist) are operator data, applied with the rest of the BOSS AI configuration.
--
-- 'ai' is not a reserved domain in is_org_grantable_permission, so an organisation role may hold
-- it. is_system keeps delete_permission() from dropping it while allowances reference it.
BEGIN;
INSERT INTO public.permissions(name, description, is_system)
VALUES ('ai.optimist', 'Use the Optimist BOSS AI model within its allowance', true)
ON CONFLICT (name) DO NOTHING;
COMMIT;
