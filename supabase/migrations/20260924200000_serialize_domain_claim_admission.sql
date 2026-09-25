-- Serialize concurrent domain claims; preserve the deployed lifecycle migration.
-- Permissions and SECURITY DEFINER search-path hardening are carried forward.

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

    -- A fixed transaction snapshot could still see the pre-lock count after waiting.
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'add_organisation_domain requires READ COMMITTED'
            USING ERRCODE = '25001';
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

    -- Serialize even an empty domain set. Cleanup precedes this organisation lock
    -- to avoid inverting lock order with its global sweep. At READ COMMITTED the
    -- count below sees claims committed by the preceding holder.
    PERFORM 1 FROM public.organisations WHERE id = p_org_id FOR UPDATE;

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
