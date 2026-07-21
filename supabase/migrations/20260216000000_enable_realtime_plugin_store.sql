-- Enable Realtime on plugin store tables
ALTER PUBLICATION supabase_realtime ADD TABLE plugins;
ALTER PUBLICATION supabase_realtime ADD TABLE plugin_versions;
