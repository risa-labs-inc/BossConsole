-- web.<agents base domain> (agents contract v5 §1): a person's own BOSS desktop in the browser.
--
-- web_pod_self_serve: whether signing in there sets up a BOSS pod for someone who has none. Off by default: the pod
-- comes from joining an agent, and people without one get "Request a BOSS". Platform admins flip it like the other
-- platform flags (PATCH platform_flags); chat reads it with the public key through web_pod_self_serve(), which says
-- nothing else.
--
-- "web" becomes a reserved agent slug: web.<base> is the web console's host, never an agent's.

INSERT INTO public.platform_flags (key, value) VALUES ('web_pod_self_serve', 'false'::jsonb)
    ON CONFLICT (key) DO NOTHING;

INSERT INTO public.agent_reserved_slugs (slug) VALUES ('web') ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION public.web_pod_self_serve()
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path TO ''
AS $$
    SELECT COALESCE((SELECT f.value = 'true'::jsonb FROM public.platform_flags f WHERE f.key = 'web_pod_self_serve'), false)
$$;

REVOKE ALL ON FUNCTION public.web_pod_self_serve() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.web_pod_self_serve() TO anon, authenticated, service_role;

COMMENT ON FUNCTION public.web_pod_self_serve() IS
    'True when web.<base> sets up a BOSS pod at sign-in for people who have none (platform flag web_pod_self_serve). Public: reveals only this flag.';
