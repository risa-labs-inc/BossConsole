BEGIN;
SELECT plan(3);
SELECT ok(public.agent_capabilities_valid('{"tabs":{"invite":false}}'::jsonb), 'tabs.invite is a valid capability');
SELECT ok(public.agent_capabilities_valid('{"tabs":{"invite":true,"memory":false}}'::jsonb), 'tabs.invite combines with other tabs');
SELECT ok(NOT public.agent_capabilities_valid('{"tabs":{"invite":"no"}}'::jsonb), 'tabs.invite must be boolean');
SELECT * FROM finish();
ROLLBACK;
