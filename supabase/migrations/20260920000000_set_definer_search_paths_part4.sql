-- Close the search_path on the seven fixed-path SECURITY DEFINER functions
-- the part-1/part-2/part-3 family never reached (part 4).
--
-- The family so far pinned every function whose proconfig was NULL:
-- 20260916130000 (#773, the passkey trio), 20260916140000 (#772,
-- find_user_by_email, handle_user_email_update, safe_decrypt_recovery_codes)
-- and 20260919000000 (#968, the five plugin-store RPCs). A full-catalog audit
-- of current dev shows one last stratum those NULL-config audits passed over:
-- seven SECURITY DEFINER functions that DO carry a SET search_path, but a
-- fixed NON-EMPTY one, so they still SEARCH mutable schemas at run time:
--
--   decrypt_text(text)            SET search_path TO 'public, pg_catalog, extensions'
--   encrypt_text(text)            SET search_path TO 'public, pg_catalog, extensions'
--   get_encryption_key()          SET search_path TO 'public, vault'
--   get_user_roles_for_hook(uuid)  SET search_path TO 'public'
--   org_is_vetted(uuid)           SET search_path TO 'public'
--   org_visible_users()           SET search_path TO 'public'
--   user_display_name(uuid)       SET search_path TO 'public'
--
-- These are not caller-influenced, which is why the earlier parts and the
-- Supabase advisor's function_search_path_mutable lint (ten findings against
-- a migrated database: five definer in #968, five invoker in #993) never
-- counted them: the SET clause replaces the session path, so a hostile
-- session cannot add a schema to it. The residual exposure is the drift class
-- the family exists for, in its weaker form. Each searched schema is mutable
-- by its own DDL holders, and public and extensions are exactly the schemas a
-- future GRANT CREATE or a restored PUBLIC default would hand to a client
-- role. A role with CREATE on public could then shadow organisations,
-- organisation_members, user_roles and roles against org_is_vetted,
-- org_visible_users and get_user_roles_for_hook, and the crypto pair would
-- gain the same shape against extensions, where pgcrypto lives. vault is
-- searched by get_encryption_key but creatable by no role today, not even
-- postgres, so that one is uniformity rather than exposure.
--
-- Verified live: anon, authenticated and service_role all lack CREATE on
-- public, extensions and vault, so nothing here is a live exploit; the value
-- is closing the class before a future CREATE grant converts any of these
-- into escalation primitives, exactly the framing 20260916130000 used.
--
-- Every reference in all seven bodies is already schema-qualified
-- (public.*, extensions.*, vault.*, pg_catalog.*, auth.*), and the two
-- substring(x FROM y) forms in decrypt_text are resolved by the parser to
-- pg_catalog.substring, as that body's own comment documents, so tightening
-- to '' changes where these functions look without changing what they find.
--
-- 20260916140000 left a dependency on decrypt_text's old path: its header
-- says safe_decrypt_recovery_codes depends on public.decrypt_text "keeping
-- its own SET search_path TO 'public, pg_catalog, extensions'" because a
-- callee's SET clause overrides the caller's empty one. That dependency is
-- preserved, not broken: what part-2 actually needs is for decrypt_text to
-- keep resolving extensions.decrypt, extensions.decrypt_iv, pg_catalog.decode
-- and pg_catalog.convert_from, and every one of those references is
-- schema-qualified in the live body (20260914000000), so they resolve the
-- same way under ''. The part-2 worry, the WHEN OTHERS handler converting a
-- 42883 into a silent empty array, is about the clause being removed
-- (proconfig NULL), which is the opposite of what this migration does.
-- secret_encryption_iv_randomization_test.sql and the roundtrip in
-- definer_search_path_part4_test.sql pin that the crypto pair still works.
--
-- After this migration the whole-schema invariant holds: every SECURITY
-- DEFINER function the repo's own migrations define carries
-- SET search_path TO ''. The definer functions still outside that shape
-- belong to the platform's extensions, not to this repo: graphql (pg_graphql,
-- NULL proconfig), net (pg_net, search_path=net), supabase_functions
-- (search_path=its own schema), pgbouncer and vault (already ''). Those are
-- managed by their extensions and would be overwritten on the next extension
-- upgrade, so a migration pinning them would not stick; they are out of scope.
--
-- Each body below is the live pg_proc definition verbatim with one mechanical
-- change: the SET clause is now TO ''. Signatures, return types, volatility,
-- logic, comments, owners and grants are all unchanged (CREATE OR REPLACE
-- preserves the ACL). In particular get_user_roles_for_hook keeps exactly the
-- grants #994's revoke is in flight for, and org_is_vetted and
-- org_visible_users keep their deliberate authenticated EXECUTE
-- (20260908010000: an RLS policy expression is evaluated as the querying
-- role), while the crypto pair and user_display_name stay client-revoked
-- (20260909120000, 20260908010000).

-- 1. The AES encrypt half (20260914000000 body).
CREATE OR REPLACE FUNCTION public.encrypt_text(plaintext text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
AS $$
DECLARE
    encryption_key TEXT;
    iv bytea;
    ciphertext bytea;
BEGIN
    encryption_key := public.get_encryption_key();
    -- 16 bytes: pgcrypto's AES block size, and what decrypt below expects to
    -- split back off the front of the stored envelope.
    iv := extensions.gen_random_bytes(16);
    ciphertext := extensions.encrypt_iv(
        pg_catalog.convert_to(plaintext, 'utf8'),
        encryption_key::bytea,
        iv,
        'aes'::text
    );
    RETURN 'v2:' || pg_catalog.encode(iv || ciphertext, 'base64'::text);
END;
$$;

-- 2. The AES decrypt half, v2 envelope first, legacy zero-IV fallback after.
CREATE OR REPLACE FUNCTION public.decrypt_text(ciphertext text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
AS $$
DECLARE
    encryption_key TEXT;
    envelope bytea;
    iv bytea;
    body bytea;
BEGIN
    IF ciphertext IS NULL THEN
        RETURN NULL;
    END IF;

    encryption_key := public.get_encryption_key();

    IF ciphertext LIKE 'v2:%' THEN
        -- substring(x FROM y [FOR z]) is SQL-standard trailing syntax the parser recognizes only
        -- on the bare function name - schema-qualifying it (pg_catalog.substring(...)) makes FROM
        -- a syntax error, since it is then parsed as an ordinary call instead. The parser
        -- resolves this SQL-standard form to pg_catalog.substring; it does not rely
        -- on search_path (20260909000000_encrypt_totp.sql does the same).
        envelope := pg_catalog.decode(substring(ciphertext from 4), 'base64'::text);
        iv := substring(envelope from 1 for 16);
        body := substring(envelope from 17);
        RETURN pg_catalog.convert_from(
            extensions.decrypt_iv(body, encryption_key::bytea, iv, 'aes'::text),
            'utf8'::name
        );
    END IF;

    -- Legacy path, unchanged: a zero-IV ciphertext written before this
    -- migration, or any row the backfill below has not reached yet.
    RETURN pg_catalog.convert_from(
        extensions.decrypt(
            pg_catalog.decode(ciphertext, 'base64'::text),
            encryption_key::bytea,
            'aes'::text
        ),
        'utf8'::name
    );
END;
$$;

-- 3. The vault-key read the pair resolves through (20251023000005 body).
CREATE OR REPLACE FUNCTION public.get_encryption_key()
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
AS $$
DECLARE
    encryption_key TEXT;
BEGIN
    -- Retrieve encryption key from Supabase Vault
    -- This ensures the key is never hardcoded in the codebase
    SELECT decrypted_secret INTO encryption_key
    FROM vault.decrypted_secrets
    WHERE name = 'master_encryption_key';

    IF encryption_key IS NULL THEN
        RAISE EXCEPTION 'Encryption key not found in vault. Please run: SELECT vault.create_secret(''<your-key>'', ''master_encryption_key'', ''Master key for encrypting user secrets'');';
    END IF;

    RETURN encryption_key;
END;
$$;

-- 4. The token-hook role helper (20251023000007 body; grants are #994's to
-- change, not this migration's).
CREATE OR REPLACE FUNCTION public.get_user_roles_for_hook(check_user_id uuid)
 RETURNS text[]
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
AS $$
BEGIN
    -- Query user_roles and JOIN with roles table
    -- ARRAY_AGG aggregates role names into PostgreSQL array
    -- Ordered by assigned_at to preserve assignment chronology
    RETURN (
        SELECT ARRAY_AGG(r.name ORDER BY ur.assigned_at)
        FROM public.user_roles ur
        JOIN public.roles r ON r.id = ur.role_id
        WHERE ur.user_id = check_user_id
    );
END;
$$;

-- 5. The org-vetting leg of the one visibility rule (20260908010000 body).
CREATE OR REPLACE FUNCTION public.org_is_vetted(p_org uuid)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER SET search_path TO ''
AS $$
  select exists (
    select 1
    from public.organisations o
    where o.id = p_org
      and not o.is_system
      and o.join_policy <> 'open'
  );
$$;

-- 6. The visible-user set itself.
CREATE OR REPLACE FUNCTION public.org_visible_users()
 RETURNS setof uuid
 LANGUAGE sql
 STABLE SECURITY DEFINER SET search_path TO ''
AS $$
  select auth.uid() where auth.uid() is not null
  union
  select theirs.user_id
  from public.organisation_members mine
  join public.organisation_members theirs on theirs.org_id = mine.org_id
  where mine.user_id = auth.uid()
    and mine.status = 'active'
    and theirs.status = 'active'
    and public.org_is_vetted(mine.org_id);
$$;

-- 7. The one display-name rule (auth.users is qualified, so '' is safe).
CREATE OR REPLACE FUNCTION public.user_display_name(p_user uuid)
 RETURNS text
 LANGUAGE sql
 STABLE SECURITY DEFINER SET search_path TO ''
AS $$
  select coalesce(
    u.raw_user_meta_data ->> 'full_name',
    u.raw_user_meta_data ->> 'display_name',
    u.raw_user_meta_data ->> 'name',
    split_part(u.email, '@', 1)
  )
  from auth.users u
  where u.id = p_user;
$$;
