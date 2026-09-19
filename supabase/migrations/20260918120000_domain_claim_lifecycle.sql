-- Lifecycle for unverified organisation domain claims: a per-organisation cap
-- and a 7-day TTL for rows that are never verified (BossConsole#966).
--
-- Problem
-- -------
-- organisation_domains.domain is globally UNIQUE, and add_organisation_domain
-- (20260801030000) refused only reserved and already-claimed names. Nothing
-- bounded how many domains one organisation could claim at once, and nothing
-- ever removed a claim that was never verified -- although every other
-- credential-shaped row in this schema already has a lifetime:
-- organisation_invites.expires_at is NOT NULL and capped at 720 hours
-- (20260801040000), and passkey challenges are swept by trigger once past
-- their expires_at (trigger_cleanup_expired_challenges, 20251023000007).
-- The unverified domain claim was the one row that lived forever, so any org
-- admin could permanently squat any unclaimed domain -- a competitor's name, a
-- common word -- and the real owner was blocked until someone deleted the row
-- by hand.
--
-- Fix
-- ---
-- Two bounds, both mirroring shapes this schema already trusts:
--
--   * A CAP of 5 concurrent unverified claims per organisation, shaped exactly
--     like create_organisation_role's max_custom_roles cap: count the rows,
--     refuse with a formatted sentence, insert nothing. Verified domains do
--     not count against it -- a verified domain is a proven asset, while an
--     unverified one is only a claim parked on a name somebody else might own.
--
--   * A TTL: unverified rows older than 7 days are DELETED, through three
--     cooperating paths:
--       - cleanup_expired_unverified_organisation_domains(), the operational
--         path (service_role only), which also runs INLINE at the top of
--         add_organisation_domain, so an expired claim cannot survive the very
--         add it blocks -- the domain becomes claimable again by its real
--         owner the moment the claim ages out;
--       - an AFTER INSERT trigger in the probabilistic shape of
--         trigger_cleanup_expired_challenges (as hardened by 20260916130000),
--         so abandoned claims are swept even when nobody ever comes back for
--         the name;
--       - a one-time sweep in THIS migration, so domains already squatted
--         under the old rules are freed the moment it deploys.
--
-- Why DELETE rather than a flag: "expired" only means something where the
-- globally-unique bite is. A soft-expired row would still hold the domain, and
-- the real owner would still be refused.
--
-- Security notes: every function below follows the repo convention since
-- 20260802000000 -- SECURITY DEFINER plus SET search_path TO '' and
-- fully-qualified references -- and the trigger function is SECURITY DEFINER
-- for the reason 20260916130000 gives for the passkey trigger:
-- organisation_domains has no client DELETE policy (clients cannot INSERT
-- either), so an INVOKER trigger would silently clean nothing.

-- 1. Partial index: the cleanup scans unverified rows by created_at, mirroring
--    the indexed expires_at the passkey sweep relies on.
CREATE INDEX IF NOT EXISTS "idx_organisation_domains_unverified_created"
    ON "public"."organisation_domains" ("created_at") WHERE "verified" = false;

-- 2. The operational cleanup path.
--
-- Returns the number of rows removed so an operator -- or a pgTAP assertion --
-- can tell a no-op sweep from a real one.
CREATE OR REPLACE FUNCTION "public"."cleanup_expired_unverified_organisation_domains"()
    -- integer stays UNQUOTED deliberately: "integer" is a keyword alias the
    -- grammar resolves to pg_catalog.int4, so the quoted identifier would be
    -- looked up literally in pg_type, which has no row named "integer"
    -- (SQLSTATE 42704). The quoted house style is only safe for names that
    -- ARE literal pg_type rows (jsonb, text, uuid, trigger, void).
    RETURNS integer
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_deleted INTEGER;
BEGIN
    DELETE FROM public.organisation_domains
    WHERE verified = false
      AND created_at < now() - interval '7 days';

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

ALTER FUNCTION "public"."cleanup_expired_unverified_organisation_domains"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."cleanup_expired_unverified_organisation_domains"() IS 'Deletes unverified organisation domain claims older than 7 days and returns how many. service_role only; add_organisation_domain also calls it inline so an expired claim frees its domain, and the AFTER INSERT trigger sweep keeps abandoned claims from accumulating.';

-- No client has any business sweeping this table on demand; the in-database
-- callers are add_organisation_domain (definer) and the trigger below. Mirrors
-- the dead-grant revocation of clean_expired_passkey_challenges
-- (20260916130000): revoke client paths, keep service_role for operators.
REVOKE EXECUTE ON FUNCTION "public"."cleanup_expired_unverified_organisation_domains"() FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."cleanup_expired_unverified_organisation_domains"()  TO "service_role";

-- 3. The probabilistic insert sweep, SELF-CONTAINED in the trigger body (the
--    pattern trigger_cleanup_expired_challenges settled on in 20260916130000:
--    client roles intentionally cannot EXECUTE the cleanup RPC, while an
--    INSERT trigger must remain able to perform its owner-controlled cleanup).
CREATE OR REPLACE FUNCTION "public"."trigger_cleanup_expired_unverified_organisation_domains"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    IF pg_catalog.random() < 0.1 THEN
        DELETE FROM public.organisation_domains
        WHERE verified = false
          AND created_at < pg_catalog.now() - interval '7 days';
    END IF;

    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."trigger_cleanup_expired_unverified_organisation_domains"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."trigger_cleanup_expired_unverified_organisation_domains"() IS 'Trigger function: after each insert into organisation_domains, 10% of the time deletes unverified claims older than 7 days, mirroring trigger_cleanup_expired_challenges (20251023000007 / 20260916130000).';

CREATE TRIGGER "trigger_cleanup_expired_unverified_organisation_domains"
    AFTER INSERT ON "public"."organisation_domains"
    FOR EACH ROW EXECUTE FUNCTION "public"."trigger_cleanup_expired_unverified_organisation_domains"();

-- 4. add_organisation_domain, carried forward from 20260801030000 with two
--    additions in the middle: expire stale claims BEFORE the existence checks
--    and the cap read the table, and refuse at 5 concurrent unverified claims.
--    Everything else is verbatim.
CREATE OR REPLACE FUNCTION "public"."add_organisation_domain"(
    "p_org_id" "uuid",
    "p_domain" "text",
    "p_is_primary" boolean DEFAULT false,
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_domain TEXT;
    v_token TEXT;
    v_domain_id UUID;
    v_unverified_count INTEGER;
    v_max_unverified CONSTANT INTEGER := 5;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    v_domain := lower(btrim(COALESCE(p_domain, '')));
    IF v_domain = '' OR NOT (v_domain ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$') THEN
        RETURN jsonb_build_object('success', false, 'error', 'That is not a valid domain name');
    END IF;

    -- Expire FIRST, before the reserved/claimed checks and the cap below read
    -- the table: a claim that was never verified is only ever 7 days deep, so
    -- a squatted name becomes claimable again by its real owner without
    -- anyone deleting a row by hand. Same transaction, so a row removed here
    -- is already invisible to the checks that follow.
    PERFORM public.cleanup_expired_unverified_organisation_domains();

    IF EXISTS (SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = v_domain) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('"%s" is a reserved email domain and cannot be claimed by an organisation', v_domain));
    END IF;

    IF EXISTS (SELECT 1 FROM public.organisation_domains d WHERE d.domain = v_domain) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Domain "%s" is already claimed', v_domain));
    END IF;

    -- The cap, shaped exactly like create_organisation_role's max_custom_roles
    -- cap: count the rows, refuse with a formatted sentence, insert nothing.
    -- VERIFIED rows are deliberately excluded -- a verified domain is a proven
    -- asset, while an unverified one is only a claim parked on a name somebody
    -- else might own, and that is what is being bounded here. Cleanup has
    -- already run above, so only live claims are counted.
    SELECT count(*) INTO v_unverified_count
    FROM public.organisation_domains d
    WHERE d.org_id = p_org_id AND d.verified = false;

    IF v_unverified_count >= v_max_unverified THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('This organisation has reached its limit of %s unverified domain claims', v_max_unverified));
    END IF;

    v_token := translate(pg_catalog.encode(extensions.gen_random_bytes(24), 'base64'), '+/=', '-_');

    IF p_is_primary THEN
        UPDATE public.organisation_domains SET is_primary = false WHERE org_id = p_org_id;
    END IF;

    INSERT INTO public.organisation_domains (org_id, domain, is_primary, verification_token, created_by)
    VALUES (p_org_id, v_domain, COALESCE(p_is_primary, false), v_token, v_actor)
    RETURNING id INTO v_domain_id;

    RETURN jsonb_build_object(
        'success', true,
        'domain_id', v_domain_id::text,
        'domain', v_domain,
        'verified', false,
        -- Everything the admin needs to publish the proof. Verification itself is
        -- performed by the organisation edge function, the only caller of
        -- mark_organisation_domain_verified.
        'dns_record_type', 'TXT',
        'dns_record_name', '_boss-verify.' || v_domain,
        'dns_record_value', 'boss-org-verification=' || v_token);
END;
$$;

ALTER FUNCTION "public"."add_organisation_domain"("uuid", "text", boolean, "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."add_organisation_domain"("uuid", "text", boolean, "uuid") IS 'Claims an email domain for an organisation, UNVERIFIED, and returns the DNS TXT record to publish. Refuses reserved consumer mailboxes and already-claimed domains. Unverified claims older than 7 days are removed first, so an expired claim frees its domain, and at most 5 concurrent unverified claims per organisation are allowed.';

REVOKE EXECUTE ON FUNCTION "public"."add_organisation_domain"("uuid", "text", boolean, "uuid") FROM PUBLIC, "anon";
GRANT  EXECUTE ON FUNCTION "public"."add_organisation_domain"("uuid", "text", boolean, "uuid")  TO "authenticated", "service_role";

-- 5. Say so on the table itself, where the next reader of the unique index will
--    look for what a row's lifetime is.
COMMENT ON TABLE "public"."organisation_domains" IS 'Email domains claimed by an organisation. Only verified rows are honoured. domain is globally UNIQUE so domain-based discovery has exactly one answer. Unverified claims are bounded (5 per organisation, enforced by add_organisation_domain) and expire: rows never verified within 7 days are deleted by cleanup_expired_unverified_organisation_domains().';

-- 6. One-time sweep, so domains squatted under the old rules are freed the
--    moment this migration deploys rather than at the first 10%-trigger fire.
SELECT public.cleanup_expired_unverified_organisation_domains();
