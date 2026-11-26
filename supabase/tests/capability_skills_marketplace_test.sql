BEGIN;
SELECT plan(5);
SELECT ok(public.agent_capabilities_valid('{"skills_marketplace":true}'::jsonb), 'skills_marketplace is a valid capability');
SELECT ok(public.agent_capabilities_valid('{"evolve":true,"own_connectors":false,"skills_marketplace":false,"tabs":{"skills":true,"invite":false}}'::jsonb),
    'skills_marketplace combines with the other switches');
SELECT ok(NOT public.agent_capabilities_valid('{"skills_marketplace":"yes"}'::jsonb), 'skills_marketplace must be boolean');
SELECT ok(NOT public.agent_capabilities_valid('{"tabs":{"skills_marketplace":true}}'::jsonb), 'it is not a tab');
SELECT ok(public.agent_capabilities_valid('{}'::jsonb), 'unset is still valid (off)');
SELECT * FROM finish();
ROLLBACK;
