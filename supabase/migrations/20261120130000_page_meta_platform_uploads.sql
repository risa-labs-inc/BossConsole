-- Page metadata share images for Optimist and Max: platform admins upload them to the public agent-brand bucket under
-- platform/<optimist|max>/<file>. Agent folders (<orgId>/<agentId|org>/) keep their own policies.

CREATE OR REPLACE FUNCTION public.is_platform_brand_path(p_name text) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT pg_catalog.cardinality(storage.foldername(p_name)) = 2
       AND (storage.foldername(p_name))[1] = 'platform'
       AND (storage.foldername(p_name))[2] IN ('optimist', 'max')
       AND pg_catalog.split_part(p_name, '/', 3) ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'
       AND auth.uid() IS NOT NULL AND public.is_user_admin(auth.uid());
$$;
REVOKE ALL ON FUNCTION public.is_platform_brand_path(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_platform_brand_path(text) TO authenticated, service_role;

DROP POLICY IF EXISTS "agent-brand platform select" ON storage.objects;
CREATE POLICY "agent-brand platform select" ON storage.objects FOR SELECT TO authenticated
    USING (bucket_id = 'agent-brand' AND public.is_platform_brand_path(name));
DROP POLICY IF EXISTS "agent-brand platform insert" ON storage.objects;
CREATE POLICY "agent-brand platform insert" ON storage.objects FOR INSERT TO authenticated
    WITH CHECK (bucket_id = 'agent-brand' AND public.is_platform_brand_path(name));
DROP POLICY IF EXISTS "agent-brand platform delete" ON storage.objects;
CREATE POLICY "agent-brand platform delete" ON storage.objects FOR DELETE TO authenticated
    USING (bucket_id = 'agent-brand' AND public.is_platform_brand_path(name));
