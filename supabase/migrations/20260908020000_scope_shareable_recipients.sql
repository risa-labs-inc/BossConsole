-- Scope the secret-sharing recipient picker to vetted organisations.
--
-- list_shareable_recipients was already org-scoped, which is why it looked
-- right: it joins the caller's own memberships. But it accepted ANY shared
-- organisation, and every account joins the `boss` org on signup. Verified
-- 2026-09-08: an account belonging only to that org enumerated 152 users with
-- full email addresses, and a search for "risalabs" returned 82 of them.
--
-- This is the same rule asked in a weaker form at one more site - the pattern
-- fixed across the Arcade and poker in the migrations either side of this one.
-- The fix is one join condition routing it through public.org_is_vetted, plus
-- the display-name expression routed through public.user_display_name
-- (20260908010000_org_visibility.sql) rather than restating what an
-- organisation means.
--
-- The body is otherwise byte-identical to the function as deployed before this
-- migration: it was read back with pg_get_functiondef, and only the join
-- condition and the display-name call below were changed.

CREATE OR REPLACE FUNCTION public.list_shareable_recipients(p_query text DEFAULT NULL::text, p_limit integer DEFAULT 50)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
    v_actor UUID := auth.uid();
    v_rows JSONB;
    v_pattern TEXT;
BEGIN
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- % and _ are wildcards to ILIKE, so a user typing either would silently
    -- widen their own search (a lone '%' matching everyone). Not an injection
    -- risk -- p_query is a parameter, never concatenated into SQL text -- but
    -- the escaping is what makes the search mean what the user typed.
    IF p_query IS NOT NULL THEN
        v_pattern := '%' || replace(replace(replace(p_query, '\', '\\'), '%', '\%'), '_', '\_') || '%';
    END IF;

    -- The DISTINCT ON collapse has to be ordered by (u.id, ...) to pick one row
    -- per user, so ordering and truncation for the CALLER both have to happen at
    -- an outer level. Doing it inline meant LIMIT took an arbitrary
    -- uuid-ordered slice, and jsonb_agg then emitted it in that same
    -- meaningless order -- so in an org above the limit the picker showed a
    -- random subset in a random order.
    -- The ORDER BY belongs inside jsonb_agg: a subquery's ORDER BY happens to
    -- survive into the aggregate today but nothing guarantees it, and the inner
    -- LIMIT still needs its own ordering to pick the right rows.
    SELECT COALESCE(jsonb_agg(row_to_json(t)::jsonb ORDER BY t.display_name, t.email), '[]'::jsonb)
      INTO v_rows
      FROM (
        SELECT * FROM (
            SELECT DISTINCT ON (u.id)
                   u.id AS user_id, u.email,
                   -- The tenth copy of the name rule, routed through the one
                   -- function. The inline version here was missing the
                   -- 'display_name' metadata key that three other sites
                   -- accepted, so the same person could appear under one name
                   -- in this picker and another on a leaderboard. The join to
                   -- auth.users stays: u.email is part of this function's
                   -- contract and the search below matches on it.
                   public.user_display_name(u.id) AS display_name,
                   om.org_id, o.name AS org_name
            FROM public.organisation_members om
            JOIN public.organisation_members mine
              ON mine.org_id = om.org_id
             AND mine.user_id = v_actor
             AND mine.status = 'active'
             -- Any shared org was the weaker question: every account joins the
             -- catch-all `boss` org on signup, so this returned all 152 other
             -- users WITH their full email addresses to anyone who registered.
             -- Same rule as everywhere else, from the same one place.
             AND public.org_is_vetted(mine.org_id)
            JOIN auth.users u ON u.id = om.user_id
            JOIN public.organisations o ON o.id = om.org_id
            WHERE om.status = 'active'
              AND om.user_id <> v_actor
              -- Matches what the picker actually displays, not just the email:
              -- searching for the name on screen used to return nothing.
              AND (
                  v_pattern IS NULL
                  OR u.email ILIKE v_pattern
                  OR COALESCE(u.raw_user_meta_data ->> 'full_name', '') ILIKE v_pattern
                  OR COALESCE(u.raw_user_meta_data ->> 'display_name', '') ILIKE v_pattern
                  OR COALESCE(u.raw_user_meta_data ->> 'name', '') ILIKE v_pattern
              )
            ORDER BY u.id, o.name
        ) d
        ORDER BY d.display_name, d.email
        LIMIT GREATEST(LEAST(COALESCE(p_limit, 50), 200), 1)
      ) t;

    RETURN jsonb_build_object('success', true, 'data', v_rows);
END;
$function$
;

revoke all on function public.list_shareable_recipients(text, integer) from public, anon;
grant execute on function public.list_shareable_recipients(text, integer) to authenticated;
