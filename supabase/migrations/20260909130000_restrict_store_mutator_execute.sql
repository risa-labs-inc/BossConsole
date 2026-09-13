-- Supersedes the mutator/hook exceptions documented in 20260908030000.
-- Browse does not require direct client access to identity-taking mutators.
-- plugin-store/index.ts injects a service-role client, and routes/rating.ts
-- authenticates the caller before services/ratings.ts supplies p_user_id.
-- Letting anon/authenticated call that SECURITY DEFINER RPC directly bypasses
-- the route and lets the caller choose whose rating to overwrite. Downloads
-- similarly receive the verified identity and IP hash from the edge route.
revoke all on function public.upsert_plugin_rating(uuid, uuid, integer, text)
  from public, anon, authenticated;
revoke all on function public.record_plugin_download(uuid, uuid, uuid, text)
  from public, anon, authenticated;
grant execute on function public.upsert_plugin_rating(uuid, uuid, integer, text)
  to service_role;
grant execute on function public.record_plugin_download(uuid, uuid, uuid, text)
  to service_role;

-- GoTrue invokes the hook as supabase_auth_admin; a client is not the issuer.
revoke all on function public.custom_access_token_hook(jsonb) from public, anon, authenticated;
grant execute on function public.custom_access_token_hook(jsonb) to supabase_auth_admin;
