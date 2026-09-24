-- ============================================================================
-- BOSS Database Schema: Organisation invite consume-time lifecycle
-- ============================================================================
-- File: 20260923173000_organisation_invite_lifecycle.sql
-- Description:
--   redeem_organisation_invite re-checks, at CONSUME time, everything that was
--   true when the link was minted. Mint-time checks alone are a TOCTOU over the
--   whole life of a link: an invite is a standing grant that outlives the moment
--   it was authorised, and everything it was authorised AGAINST can change
--   underneath it.
--
--   Three consume-time properties are added to the existing revoked / expired /
--   exhausted re-checks (which already existed and are unchanged):
--
--   1. THE INVITER'S AUTHORITY IS RE-CHECKED. Mint requires
--      user_is_org_admin; redeem now requires it AGAIN for the stored creator.
--      Without this, an admin who is demoted or removed keeps every link they
--      ever minted: the org's own invariant -- remove_organisation_member
--      deletes "every user_roles row for this organisation's roles --
--      otherwise a removed member keeps organisation permissions and secret
--      access" -- was silently contradicted by invites minted by that same
--      removed member, which kept ADMITTING people (and granting roles,
--      attributed to the removed admin) for up to 30 days.
--      The check is LIVE, not a revocation flag: re-promoting the inviter
--      re-arms their links, demoting them again re-kills them.
--
--   2. THE STORED ROLE IS RE-VALIDATED. The grant still comes ONLY from the
--      stored invite row -- accept takes no role parameter, so a client cannot
--      request a role -- but a role that has since been unmapped from the
--      organisation, or that points at the admin kind, must not be granted.
--      A stale role_id is inert inside the org (organisation_roles.role_id is
--      globally UNIQUE, so no other org can adopt it), but the row it leaves in
--      user_roles is a standing global grant attributed to a role the org no
--      longer controls. Degrade to the default member role, mirroring
--      organisation_invites_role_fkey's ON DELETE SET NULL, and refuse the
--      admin-kind grant outright -- the exact refusal mint already makes.
--
--   3. CONSUMPTION IS A CONDITIONAL UPDATE ... RETURNING, FAIL-CLOSED ON NO
--      ROWS. The uses increment now carries its own capacity predicate
--      (max_uses IS NULL OR uses < max_uses) and a no-row outcome raises,
--      aborting the whole redemption. Under the existing FOR UPDATE this
--      branch is unreachable -- that is the point: it is a tripwire, so a
--      future refactor that loses the row lock fails CLOSED (transaction
--      rolled back, nothing granted) instead of silently over-issuing.
--
--   4. THE GATE IS ONE SHARED PREDICATE, NOT A LOCAL COPY. Everything above
--      that decides "does this link still admit a NEW redeemer right now"
--      lives in public.organisation_invite_is_live, and the OTHER two
--      surfaces that answer the same question -- the landing page's
--      get_organisation_invite_preview (the unauthenticated join page's
--      valid/not-valid answer) and list_organisation_invites' is_live
--      column (the admin page's live/expired pill) -- now call it too.
--      A check written only in redemption drifts from a check written only
--      in the preview: a demoted inviter's link was refused at consume time
--      while the landing page still advertised it, and the admin list still
--      showed "live". One function called from all three cannot drift.
--
--      The role re-validation is deliberately NOT in the predicate: a stale
--      or admin-kind role degrades the GRANT at consume time, it does not
--      kill the link -- the join still happens (mint's refusal message says
--      to assign the admin role explicitly "after they join"). The preview
--      and the list stay true to redemption, not stricter than it.
--
--   Unchanged on purpose: idempotency is still evaluated BEFORE validity (an
--   exhausted link's own redeemer still hears "already member"), every failure
--   mode still returns the ONE generic message (no enumeration oracle), and
--   the removal-then-re-click re-admit path still works.
--
-- Dependencies:
--   - 20260801040000_organisation_invites.sql (the functions being replaced)
--   - 20260801010000_organisation_permissions_and_guards.sql (user_is_org_admin)
--
-- Next migration: (none)
-- ============================================================================


-- organisation_invite_is_live: THE consume-time gate, written once.
--
-- One function answers "would redemption admit a NEW redeemer through this
-- link right now?" for every surface that must not diverge from redemption:
--
--   - redeem_organisation_invite, as its refusal gate (so a link is refused
--     with the one generic message exactly when this returns false);
--   - get_organisation_invite_preview, so the unauthenticated landing page
--     never calls a link good that redemption then rejects -- the demoted
--     inviter's link was exactly that: advertised on the page, dead at
--     consume time;
--   - list_organisation_invites' is_live column, so the admin page's
--     live/expired pill says not-live the same hour redemption starts
--     refusing.
--
-- The four arms, and what each one re-checks at CONSUME time rather than
-- trusting mint time:
--
--   revoked_at IS NULL / expires_at > now() -- the mint-time promises the row
--     always carried; unchanged in meaning, now shared by name instead of
--     copied by hand.
--   max_uses IS NULL OR uses < max_uses -- capacity, read live (the uses
--     column is the redemption counter).
--   user_is_org_admin(created_by, org_id) -- THE re-check this migration adds:
--     an invite is a standing grant of the inviter's authority for up to 30
--     days, and the authority can be withdrawn in between. A demoted or
--     removed admin is exactly the person whose links must stop admitting,
--     the same day. It is a LIVE predicate, not a snapshot or a revocation
--     flag: re-promote the inviter and their links re-arm without being
--     re-minted. The OWNER is covered (user_is_org_admin is true for the
--     owner), so an ownership handoff intentionally kills the previous
--     owner's links.
--
-- What is deliberately NOT here: the stored-role re-validation. A stale or
-- admin-kind role_id degrades the granted ROLE at consume time -- the join
-- still happens, mirroring organisation_invites_role_fkey's ON DELETE SET
-- NULL -- so the link stays live on every surface. Stricter here than
-- redemption is just a different drift.
--
-- Internal by design: no grant to authenticated, so it is not itself an
-- oracle (a caller probing rows it fabricated cannot learn anything, and the
-- three callers above all execute as their own SECURITY DEFINER owner).
CREATE OR REPLACE FUNCTION "public"."organisation_invite_is_live"(
    "p_inv" "public"."organisation_invites"
)
RETURNS boolean
    LANGUAGE "sql" STABLE
    SET "search_path" TO ''
    AS $$
    SELECT p_inv.revoked_at IS NULL
       AND p_inv.expires_at > now()
       AND (p_inv.max_uses IS NULL OR p_inv.uses < p_inv.max_uses)
       AND public.user_is_org_admin(p_inv.created_by, p_inv.org_id);
$$;

ALTER FUNCTION "public"."organisation_invite_is_live"("public"."organisation_invites") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."organisation_invite_is_live"("public"."organisation_invites") IS 'THE consume-time invite gate, shared by redemption, the landing-page preview and the admin live-list so the three cannot drift. True when the link is not revoked, not expired, under capacity, and its creator still holds admin over the org (a live re-check, not a snapshot: re-promoting the inviter re-arms their links). The stored-role re-validation is deliberately absent: a stale or admin-kind role degrades the granted role, it does not kill the link.';

REVOKE EXECUTE ON FUNCTION "public"."organisation_invite_is_live"("public"."organisation_invites") FROM PUBLIC, "anon", "authenticated";


CREATE OR REPLACE FUNCTION "public"."redeem_organisation_invite"("p_token" "text")
RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_hash TEXT;
    v_inv public.organisation_invites;
    v_slug TEXT;
    v_name TEXT;
    v_role_kind TEXT;
    v_uses_after integer;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF p_token IS NULL OR btrim(p_token) = '' THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite link is invalid or expired');
    END IF;

    v_hash := pg_catalog.encode(extensions.digest(btrim(p_token), 'sha256'), 'hex');

    -- FOR UPDATE is what makes max_uses race-free: two simultaneous redemptions
    -- of a single-use link serialize, and the second sees uses >= max_uses.
    SELECT * INTO v_inv
    FROM public.organisation_invites i
    WHERE i.token_hash = v_hash
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite link is invalid or expired');
    END IF;

    SELECT o.slug, o.name INTO v_slug, v_name
    FROM public.organisations o WHERE o.id = v_inv.org_id;

    -- IDEMPOTENCY IS CHECKED FIRST, BEFORE revoked/expired/exhausted, AND THE
    -- ORDER MATTERS.
    --
    -- The obvious order -- validate the invite, then dedupe -- is wrong for the
    -- common case. A single-use link (max_uses = 1) is exhausted the instant its
    -- one redeemer uses it, so when that same person clicks their own link again
    -- (a double-click, a re-opened tab, a mail client prefetch followed by a real
    -- click) a validity-first check answers "invalid or expired" -- about a link
    -- that worked perfectly and made them a member. A test caught exactly this.
    --
    -- Answering already_member here grants nothing: the row proves they redeemed
    -- it before, and no state changes. It is deliberately returned even for a
    -- revoked or expired invite, because revoking a link is not a mechanism for
    -- ejecting members who already used it -- remove_organisation_member is.
    --
    -- The inviter-authority re-check below follows the SAME reasoning: a demoted
    -- inviter's link is dead for NEW admissions, but a member who already used it
    -- while it was authorised is still a member, and their re-click still says
    -- so. Losing the inviter's admin status ejects nobody.
    --
    -- AND still a member. The redemption row outlives the membership -- there is
    -- no 'removed' status, remove_organisation_member deletes the row, and the
    -- redemption's foreign keys point at the invite and the user, not at the
    -- membership. Keying only on the redemption meant that someone removed from
    -- the organisation, or who left, clicking a still-live link they had used
    -- before was told "already a member" and NOT re-added: a dead end they could
    -- never escape through that link. Falling through re-admits them via the
    -- ON CONFLICT DO UPDATE below, and the redemptions insert's ON CONFLICT DO
    -- NOTHING keeps uses from double-counting.
    IF EXISTS (
        SELECT 1 FROM public.organisation_invite_redemptions red
        WHERE red.invite_id = v_inv.id AND red.user_id = v_user_id
    ) AND EXISTS (
        SELECT 1 FROM public.organisation_members m
        WHERE m.org_id = v_inv.org_id AND m.user_id = v_user_id AND m.status = 'active'
    ) THEN
        RETURN jsonb_build_object('success', true, 'already_member', true,
            'org_id', v_inv.org_id::text, 'slug', v_slug, 'name', v_name);
    END IF;

    -- THE GATE IS ONE SHARED PREDICATE, NOT A LOCAL COPY.
    --
    -- organisation_invite_is_live holds every arm that decides whether this
    -- link still admits a NEW redeemer right now -- not revoked, not expired,
    -- under capacity, and the inviter's authority RE-CHECKED at consume time
    -- (mint required user_is_org_admin; a link is a standing grant of that
    -- authority for up to 30 days, and the authority can be withdrawn in
    -- between -- a demoted or removed admin is exactly the person whose
    -- tokens must stop admitting, the same day, and the check is live, not a
    -- snapshot: re-promote the inviter and their links re-arm without being
    -- re-minted; the OWNER is covered too, so an ownership handoff
    -- intentionally kills the previous owner's links).
    --
    -- The same function gates the landing page's preview and the admin
    -- live-list's is_live column, so this refusal and their verdicts cannot
    -- drift. A check written only here was exactly how the demoted inviter's
    -- link stayed "valid" on the page that redemption then rejected.
    --
    -- Same message as "not found" on every arm. Any distinction -- "revoked",
    -- "the inviter lost admin" -- would confirm a token existed, and the
    -- enumeration oracle this function refuses to be would be reborn one
    -- layer up.
    IF NOT public.organisation_invite_is_live(v_inv) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite link is invalid or expired');
    END IF;

    INSERT INTO public.organisation_members (
        org_id, user_id, status, joined_at, join_source, invited_by, invited_at
    ) VALUES (
        v_inv.org_id, v_user_id, 'active', now(), 'invite', v_inv.created_by, v_inv.created_at
    )
    ON CONFLICT (org_id, user_id) DO UPDATE
        SET status = 'active',
            joined_at = COALESCE(organisation_members.joined_at, now()),
            updated_at = now();

    -- THE ROLE COMES FROM THE STORED INVITE ROW -- redeem takes no role
    -- parameter, so the redeemer cannot request one -- AND IS RE-VALIDATED
    -- AGAINST THE ORGANISATION'S CURRENT ROLE SET.
    --
    -- Mint checked the role belonged to this org and refused the admin kind.
    -- Both can drift: the mapping row can be deleted (the roles row survives,
    -- organisation_roles.role_id is globally UNIQUE but nothing else pins it),
    -- leaving a stale role_id pointing at a role the org no longer controls;
    -- and a directly-corrupted row (mint refuses admin-kind, but the row is
    -- writable by the DB owner) must never turn a link into an
    -- organisation-takeover primitive. In both cases the JOIN still happens
    -- -- mint's refusal message says to assign the admin role explicitly
    -- "after they join" -- but the grant degrades to the default member role,
    -- mirroring organisation_invites_role_fkey's ON DELETE SET NULL.
    IF v_inv.role_id IS NOT NULL THEN
        SELECT orl.kind INTO v_role_kind
        FROM public.organisation_roles orl
        WHERE orl.org_id = v_inv.org_id AND orl.role_id = v_inv.role_id;

        IF FOUND AND v_role_kind <> 'admin' THEN
            INSERT INTO public.user_roles (user_id, role_id, assigned_by, assigned_at)
            VALUES (v_user_id, v_inv.role_id, v_inv.created_by, now())
            ON CONFLICT (user_id, role_id) DO NOTHING;
        ELSE
            PERFORM public.assign_org_member_role_internal(v_inv.org_id, v_user_id);
        END IF;
    ELSE
        PERFORM public.assign_org_member_role_internal(v_inv.org_id, v_user_id);
    END IF;

    -- The increment is GUARDED on the redemption actually inserting.
    --
    -- The re-admit-after-removal fall-through is exactly the case that produced
    -- one redemption row and two increments while the UPDATE was unconditional
    -- - burning a use of a capped link on somebody who had already consumed one.
    INSERT INTO public.organisation_invite_redemptions (invite_id, user_id)
    VALUES (v_inv.id, v_user_id)
    ON CONFLICT (invite_id, user_id) DO NOTHING;

    IF FOUND THEN
        -- AND the increment is itself a CONDITIONAL single-use consume:
        -- capacity is re-asserted in the WHERE clause, not just in the read
        -- above. Under the FOR UPDATE this can never match zero rows - that is
        -- the design. It is here so that the property lives in the consume
        -- statement itself, and a future refactor that loses the row lock
        -- fails CLOSED: zero rows means capacity vanished between validate and
        -- consume, so the redemption - membership, role, redemption row - must
        -- not survive. RAISE aborts the transaction; returning a JSON error
        -- here would leave the member inserted and the use unconsumed, the
        -- exact over-issue this predicate exists to make impossible.
        UPDATE public.organisation_invites
           SET uses = uses + 1
         WHERE id = v_inv.id
           AND (max_uses IS NULL OR uses < max_uses)
        RETURNING uses INTO v_uses_after;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Invite link is invalid or expired';
        END IF;
    END IF;

    RETURN jsonb_build_object('success', true, 'org_id', v_inv.org_id::text,
        'slug', v_slug, 'name', v_name, 'status', 'active');
END;
$$;

ALTER FUNCTION "public"."redeem_organisation_invite"("text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."redeem_organisation_invite"("text") IS 'Redeems an invite link for the CURRENT user. authenticated-only, so the desktop app is what redeems -- which is why an email scanner prefetching the invite URL cannot consume it. Re-checks at consume time everything mint checked, through the shared organisation_invite_is_live gate (also used by the preview and the admin live-list, so the three cannot drift): revoked, expired, exhausted, and the inviter still holds admin. The stored role is re-validated against the org''s current role set: it degrades to the default member role when stale or admin-kind. All failure modes return one identical message so this is not a token oracle. The uses increment is a conditional UPDATE ... RETURNING that fails closed (transaction aborted) if capacity vanished mid-consume.';


-- get_organisation_invite_preview: the landing page's side of the SAME gate.
--
-- The unauthenticated join page calls this via the edge function, and before
-- it answered only the mint-time arms -- revoked, expired, exhausted -- so
-- it called a link good that redemption then refused: exactly the demoted
-- inviter's link, advertised on the page the same day consume-time rejection
-- began. The gate is now organisation_invite_is_live, called by redemption
-- and the admin live-list too, so the page's "valid" and redemption's answer
-- cannot drift apart again.
--
-- Unchanged in every other property: display-only, NEVER redeems and never
-- consumes a use (a link prefetch is harmless), service_role only, and one
-- identical valid = false shape for unknown, revoked, expired, exhausted and
-- now inviter-no-longer-admin tokens alike -- the page must stay a
-- non-oracle.
CREATE OR REPLACE FUNCTION "public"."get_organisation_invite_preview"("p_token" "text")
RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_hash TEXT;
    v_org_name TEXT;
    v_org_slug TEXT;
    v_org_description TEXT;
BEGIN
    IF p_token IS NULL OR btrim(p_token) = '' THEN
        RETURN jsonb_build_object('success', true, 'valid', false);
    END IF;

    v_hash := pg_catalog.encode(extensions.digest(btrim(p_token), 'sha256'), 'hex');

    SELECT o.name, o.slug, o.description
      INTO v_org_name, v_org_slug, v_org_description
      FROM public.organisation_invites i
      JOIN public.organisations o ON o.id = i.org_id
     WHERE i.token_hash = v_hash
       AND public.organisation_invite_is_live(i);

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', true, 'valid', false);
    END IF;

    RETURN jsonb_build_object('success', true, 'valid', true,
        'name', v_org_name, 'slug', v_org_slug, 'description', v_org_description);
END;
$$;

ALTER FUNCTION "public"."get_organisation_invite_preview"("text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_organisation_invite_preview"("text") IS 'Display-only preview of an invite for the unauthenticated web landing page. NEVER redeems and never consumes a use, so a link prefetch is harmless. Gated by the shared organisation_invite_is_live predicate -- the same gate redemption and the admin live-list apply -- so the page never calls a link good that redemption then rejects. Returns { valid: false } identically for unknown, expired, revoked, exhausted and inviter-no-longer-admin tokens.';


-- list_organisation_invites: the admin page's live/expired pill, on the SAME
-- gate.
--
-- is_live used to be computed here from the mint-time arms alone, so a
-- demoted inviter's link still showed "live" in the invite table while
-- redemption refused it -- the admin sees the pill, acts on nothing, and the
-- link keeps dead-ending every recipient it is sent to. The pill now reads
-- the shared organisation_invite_is_live predicate, so the table, the
-- landing page and redemption answer as one.
--
-- Projection and authorization unchanged: token_hash stays absent (a client
-- that could read the hash could confirm a guessed token offline),
-- organisation admins only.
CREATE OR REPLACE FUNCTION "public"."list_organisation_invites"(
    "p_org_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_rows JSONB;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    -- token_hash is deliberately absent from this projection. A client that could
    -- read the hash could confirm a guessed token offline, so the invite table is
    -- never selected directly by the desktop app either (no authenticated table
    -- grant -- see 20260801000000).
    SELECT COALESCE(jsonb_agg(row_to_json(t)::jsonb), '[]'::jsonb)
      INTO v_rows
      FROM (
        SELECT i.id AS invite_id, i.token_prefix, i.label,
               i.role_id, r.name AS role_name,
               i.max_uses, i.uses, i.expires_at, i.revoked_at, i.created_at,
               cu.email AS created_by_email,
               public.organisation_invite_is_live(i) AS is_live
        FROM public.organisation_invites i
        LEFT JOIN public.roles r ON r.id = i.role_id
        LEFT JOIN auth.users cu ON cu.id = i.created_by
        WHERE i.org_id = p_org_id
        ORDER BY i.created_at DESC
      ) t;

    RETURN jsonb_build_object('success', true, 'data', v_rows);
END;
$$;

ALTER FUNCTION "public"."list_organisation_invites"("uuid", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."list_organisation_invites"("uuid", "uuid") IS 'Invite links for an organisation, masked: token_prefix only, never token_hash. Organisation admins only. is_live reads the shared organisation_invite_is_live gate -- the same predicate redemption and the preview apply -- so the admin table and the consume-time answer cannot drift.';


-- ============================================================================
-- End of File: 20260923173000_organisation_invite_lifecycle.sql
-- ============================================================================
