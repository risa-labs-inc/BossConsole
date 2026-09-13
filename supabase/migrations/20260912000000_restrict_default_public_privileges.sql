-- Restrict default privileges for objects created by postgres in public.
--
-- 20251023000014_grants.sql configured broad default grants for anon and
-- authenticated. That made every subsequently-created public table/function
-- client-accessible unless a later migration explicitly revoked those grants.
--
-- New public objects should be private by default and opt in to client access
-- with explicit GRANT statements.

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public"
    REVOKE ALL ON TABLES FROM "anon";

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public"
    REVOKE ALL ON TABLES FROM "authenticated";

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public"
    REVOKE ALL ON SEQUENCES FROM "anon";

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public"
    REVOKE ALL ON SEQUENCES FROM "authenticated";

-- PUBLIC EXECUTE in public is removed by enforce_explicit_anon_grants.
-- Keep the built-in global default: revoking it would also change future
-- extension functions outside public, contrary to #423's scoped guard.
-- Future client-facing objects need explicit GRANTs after creation.

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public"
    REVOKE ALL ON FUNCTIONS FROM "anon";

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public"
    REVOKE ALL ON FUNCTIONS FROM "authenticated";

-- handle_new_user() is an internal SECURITY DEFINER trigger function.
-- The explicit grants in 20251023000014_grants.sql exposed its ACL to client roles.
-- Clients must not be able to invoke it directly.

REVOKE EXECUTE ON FUNCTION public.handle_new_user()
FROM PUBLIC, anon, authenticated;

-- Retain the existing explicit server grant; this is not a new caller.
GRANT EXECUTE ON FUNCTION public.handle_new_user()
TO service_role;
