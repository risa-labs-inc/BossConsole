-- Add 'service' and 'mixed' to the allowed plugin types.
-- 'service' is for system plugins like the microkernel runtime that have no UI.
-- 'mixed' aligns with the PluginType enum in PluginManifest.kt.
ALTER TABLE plugins DROP CONSTRAINT IF EXISTS plugins_type_check;
ALTER TABLE plugins ADD CONSTRAINT plugins_type_check
  CHECK (type IN ('panel', 'tab', 'hybrid', 'mixed', 'service'));
