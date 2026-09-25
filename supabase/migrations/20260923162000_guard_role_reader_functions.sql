-- Stop the role-reading RPCs answering for users the caller cannot see.
--
-- Three SECURITY DEFINER functions take a user id and return that user's roles,
-- and none of them checks who is asking:
--
--   get_user_roles(check_user_id uuid)              -> SETOF text
--   get_user_roles_with_names(target_user_id uuid)  -> jsonb, with assigned_by and assigned_at
--   user_has_role(check_user_id uuid, text)         -> boolean
--
-- `authenticated` can execute all three, so any signed-in user could read any
-- other user's roles, including who assigned them. `user_roles` itself refuses
-- that: its policies give an ordinary reader their own rows only. SECURITY
-- DEFINER is what got past it, because these run as their owner.
--
-- WHO MAY READ ANOTHER USER'S ROLES, and why it is not "admins only". The rule
-- has to match who can already list other users, or it breaks a working screen:
--
--   * the only caller is RoleService.getUserRoles -> get_user_roles_with_names,
--     which UserService calls once per user while building the user list and
--     the email search, both exposed to plugins by UserManagementProviderImpl;
--   * `users` lets a reader list everyone when they are an admin OR hold
--     `role.read` ("Privileged users can read all users");
--   * `role.read` is seeded on `boss_admin`, which sits below `admin` and is not
--     named `admin`, so is_user_admin() is false for it.
--
-- An admins-only check would therefore have emptied the role column for every
-- BOSS administrator. The rule used here is the caller's own id, the service
-- role, or `public.authorize('role.read')`. authorize() already passes admins,
-- reads the tables rather than the token's claims, and is the same check
-- get_all_roles, get_all_permissions and get_role_permissions make before
-- answering. It is looser than `user_roles`' own SELECT policy, which admits
-- admins but not other `role.read` holders; that is deliberate here, to leave
-- today's legitimate readers exactly where they are, and it is recorded in the PR.
--
-- ON DENIAL each function returns what a direct read of `user_roles` gives that
-- caller: no rows, `[]` and `false`. Nothing changes shape, so the client
-- decodes the same types, and a caller who cannot see a user sees them as
-- having no roles, which is what the table already tells them.
--
-- That makes a false from user_has_role mean either "no" or "you may not ask",
-- so the three function comments now say so: NOT user_has_role(other, 'banned')
-- is not a deny check for another user.
--
-- The rule lives in one function, can_read_user_roles(uuid), so the three cannot
-- drift apart. It must never return NULL, because it would otherwise FAIL OPEN:
-- with no session, `p_user_id = auth.uid()` is NULL, and `IF NOT NULL THEN` does
-- not take the branch, so the function would carry on and answer. It is a CASE,
-- not an OR chain: a branch whose test is NULL falls through to the next, the
-- last one is coalesce(authorize(...), false), and Postgres promises the order a
-- CASE is evaluated in, which it does not for OR, so authorize() only runs when
-- the caller is neither the subject nor the service role. The tests pin the
-- no-subject case.
--
-- Bodies, signatures, return types, volatility, security mode and search_path
-- are otherwise unchanged. CREATE OR REPLACE preserves the ACL, so
-- `authenticated` keeps EXECUTE on all three and the client path still works.
-- `anon` has had none of the three since 20260908030000's sweep, and the
-- enforce_explicit_anon_grants event trigger (20260908000000, 20260912120000)
-- would revoke it again on these CREATE OR REPLACE statements anyway. The tests
-- pin that anon stays without them.
--
-- Not covered: get_user_api_key_count(p_user_id uuid) has the same shape, but it
-- returns a count rather than an identity, its only caller is plugin-store with
-- the service role, and #1171 rewrites its body, so it is left for after that.

CREATE FUNCTION "public"."can_read_user_roles"("p_user_id" "uuid")
    RETURNS boolean
    LANGUAGE "sql" STABLE
    SET "search_path" TO ''
    AS $$
    SELECT CASE
        WHEN p_user_id = (SELECT auth.uid()) THEN true
        WHEN (SELECT auth.jwt() ->> 'role') = 'service_role' THEN true
        ELSE COALESCE(public.authorize('role.read'), false)
    END;
$$;

COMMENT ON FUNCTION "public"."can_read_user_roles"("p_user_id" "uuid") IS 'Whether the current caller may read p_user_id''s role assignments: their own, the service role, or anyone authorize(''role.read'') accepts. Never NULL.';

-- Only the three functions below call it. They are SECURITY DEFINER and owned by
-- postgres, so they call it as postgres, and it is owned by postgres too: with
-- PUBLIC revoked, a different owner would lock them out of it and every call
-- would fail. A client gains nothing from calling it directly, so it is not
-- offered one.
ALTER FUNCTION "public"."can_read_user_roles"("p_user_id" "uuid") OWNER TO "postgres";
REVOKE ALL ON FUNCTION "public"."can_read_user_roles"("p_user_id" "uuid") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."can_read_user_roles"("p_user_id" "uuid") FROM "anon";
REVOKE ALL ON FUNCTION "public"."can_read_user_roles"("p_user_id" "uuid") FROM "authenticated";

-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."get_user_roles"("check_user_id" "uuid")
    RETURNS SETOF "text"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    IF NOT public.can_read_user_roles(check_user_id) THEN
        RETURN;
    END IF;

    -- Query user_roles and JOIN with roles table
    -- Returns role names (not UUIDs) for easier consumption
    -- Ordered by assigned_at to show assignment chronology
    RETURN QUERY
    SELECT r.name FROM public.user_roles ur
    JOIN public.roles r ON r.id = ur.role_id
    WHERE ur.user_id = check_user_id
    ORDER BY ur.assigned_at;
END;
$$;

-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."get_user_roles_with_names"("target_user_id" "uuid")
    RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_roles JSONB;
BEGIN
    IF NOT public.can_read_user_roles(target_user_id) THEN
        RETURN '[]'::jsonb;
    END IF;

    -- Query user_roles table and JOIN with roles to get role names
    -- Build detailed JSONB objects with all assignment metadata
    -- UUIDs cast to text for client compatibility
    SELECT jsonb_agg(
        jsonb_build_object(
            'id', ur.id::text,
            'user_id', ur.user_id::text,
            'role', r.name,
            'assigned_by', ur.assigned_by::text,
            'assigned_at', ur.assigned_at::text,
            'created_at', ur.created_at::text
        )
        ORDER BY ur.assigned_at  -- Chronological order
    ) INTO v_roles
    FROM public.user_roles ur
    JOIN public.roles r ON r.id = ur.role_id
    WHERE ur.user_id = target_user_id;

    -- Return array (empty [] if no roles assigned)
    RETURN COALESCE(v_roles, '[]'::jsonb);
END;
$$;

-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."user_has_role"("check_user_id" "uuid", "check_role" "text")
    RETURNS boolean
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    IF NOT public.can_read_user_roles(check_user_id) THEN
        RETURN false;
    END IF;

    -- Check if user has specified role
    -- EXISTS is efficient - stops at first match
    -- Returns true if user has role, false otherwise
    RETURN EXISTS (
        SELECT 1 FROM public.user_roles ur
        JOIN public.roles r ON r.id = ur.role_id
        WHERE ur.user_id = check_user_id AND r.name = check_role
    );
END;
$$;

-- ---------------------------------------------------------------------------
-- CREATE OR REPLACE keeps the old comments, which describe an answer for anyone.
-- ---------------------------------------------------------------------------
COMMENT ON FUNCTION "public"."get_user_roles"("check_user_id" "uuid") IS 'Returns all role names assigned to a user using table-based schema. Returns no rows when the caller may not read that user''s roles (can_read_user_roles), so no rows does not mean the user has none.';

COMMENT ON FUNCTION "public"."get_user_roles_with_names"("target_user_id" "uuid") IS 'Returns user roles with role names (not UUIDs) for backward compatibility with RoleService.kt. Returns [] when the caller may not read that user''s roles (can_read_user_roles).';

COMMENT ON FUNCTION "public"."user_has_role"("check_user_id" "uuid", "check_role" "text") IS 'Check if a user has a specific role using table-based schema. Returns false both when the user lacks the role and when the caller may not read that user''s roles (can_read_user_roles), so NOT user_has_role(...) is not a deny check for another user.';
