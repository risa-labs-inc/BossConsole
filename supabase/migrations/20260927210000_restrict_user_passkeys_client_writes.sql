-- ============================================================================
-- BOSS Database Schema: clients may not write user_passkeys at all
-- ============================================================================
-- File: 20260927210000_restrict_user_passkeys_client_writes.sql
--
-- `authenticated` and `anon` hold table-level INSERT and UPDATE on
-- public.user_passkeys (20251023000014_grants.sql), and the row policies
-- ("Users can insert their own passkeys", "Users can update their own
-- passkeys") let an account write its own rows. A table-level grant covers
-- every column, and those policies gate the ROW, not the columns within it,
-- so anyone holding a user's bearer token can write the columns the `passkey`
-- edge function later trusts for verification:
--
--   * UPDATE public_key preserving credential_id - replaces the enrolled key
--     with an attacker key under the existing credential id, giving durable
--     passkey access to the account with no enrollment ceremony.
--   * INSERT a fresh row with an attacker public_key - the same outcome
--     without touching the existing credential: the INSERT policy checks only
--     auth.uid() = user_id, which a stolen bearer satisfies.
--   * UPDATE sign_count to 0 - resets the clone-detection counter
--     (20260725000000), so a cloned authenticator no longer trips the
--     non-increase rule.
--   * UPDATE public_key_alg or rp_id - mis-describes the stored key or
--     re-pins the credential to another relying party.
--
-- A stolen bearer already authorizes the function's /register/challenge
-- route, so the gap closed here is specifically the bypass of the ceremony
-- and attestation path: direct table writes create or alter trust material
-- the verification route then relies on.
--
-- The same GRANT ALL also hands client roles TRUNCATE, REFERENCES and
-- TRIGGER on the table. TRUNCATE is not subject to row-level security at
-- all: where it is reachable (a SQL-capable client or an RPC; ordinary
-- PostgREST does not expose it) it erases EVERY user's passkeys, which is
-- worse than the trust-column hole. REFERENCES lets a client pin the table
-- with a foreign key from their own table, and TRIGGER lets a client attach
-- triggers. 20260911010000 revoked exactly this trio from the log tables;
-- they are revoked here for the same reason.
--
-- Fix: no client role may INSERT or UPDATE public.user_passkeys at all.
-- Unlike 20260927120000 (plugins), where authors legitimately edit ordinary
-- columns and the fix is column-level, every legitimate writer of this table
-- is the `passkey` edge function, which connects with the service-role key
-- (index.ts) and so bypasses RLS and these grants: enrollment inserts,
-- sign_count/last_used_at updates on assertion, display_name renames, and
-- active=false soft deletes all run as service_role. No client in this
-- repository writes the table directly, so there is no column list to
-- preserve and the whole privilege is withdrawn.
--
-- The auto-updatable view public.active_user_passkeys (security_invoker =
-- on, GRANT ALL in 20251023000014) is a second write path into the same
-- table: it exposes credential_id and last_used_at, and with the view grant
-- intact a client could still UPDATE those base columns through it. Its
-- INSERT, UPDATE and DELETE are withdrawn for the same client roles, along
-- with REFERENCES and TRIGGER (both valid on views; a client TRIGGER grant
-- would allow attaching INSTEAD OF triggers). SELECT stays, because listing
-- one's own active passkeys through this view is the intended client read
-- path.
--
-- SELECT and DELETE on the base table are unchanged. SELECT is how clients
-- read their own rows; DELETE can only remove the caller's own passkey
-- (self-denial, no trust material created or altered), and removing a row
-- cannot be combined with a re-INSERT now that INSERT is closed.
--
-- How: mirror 20260927120000. A table-level grant cannot be narrowed by
-- revoking one column, so where a client role holds table-level INSERT or
-- UPDATE the grant is revoked wholesale and nothing is re-granted. A
-- deployment that carries direct column-level grants without the table-level
-- one has those revoked individually as well. The block fails closed only if
-- a privilege survives through PUBLIC or an inherited role, which revoking
-- the client's own ACL entries cannot remove.
--
-- Known limit, deliberately not fixed here: 20251023000014 also sets
-- ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT ALL ON
-- TABLES to anon and authenticated. If user_passkeys or the view is ever
-- DROPPED and recreated (CREATE OR REPLACE VIEW keeps grants; DROP + CREATE
-- does not), the default privileges restore the full grant and reopen every
-- hole this migration closes. Narrowing those defaults is the right
-- fail-closed posture, but it changes what every FUTURE table in this schema
-- gets at creation, so it is a repository-wide policy change left for a
-- maintainer decision rather than folded into a user_passkeys migration.
--
-- Consequence for later migrations: passkey management clients must keep
-- going through the `passkey` edge function; a future direct client write
-- path needs its own migration re-granting the specific columns it needs,
-- and user_passkeys_client_writes_test.sql pins the closed set until then.
-- ============================================================================

DO $restrict_user_passkeys_client_writes$
DECLARE
    passkeys_oid oid := pg_catalog.to_regclass('public.user_passkeys');
    view_oid oid := pg_catalog.to_regclass('public.active_user_passkeys');
    client_role text;
    privilege text;
    exposed name;
    direct_grant record;
BEGIN
    IF passkeys_oid IS NULL THEN
        RAISE EXCEPTION 'public.user_passkeys is missing; this migration expects the passkey schema';
    END IF;

    FOREACH client_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        CONTINUE WHEN pg_catalog.to_regrole(client_role) IS NULL;

        -- Write privileges on the base table, wholesale: no legitimate
        -- client writer exists, so nothing is re-granted.
        FOREACH privilege IN ARRAY ARRAY['INSERT', 'UPDATE'] LOOP
            -- has_table_privilege is true only for a table-level grant (or
            -- ownership), never because of column-level grants, so this
            -- touches exactly the grants being replaced.
            IF pg_catalog.has_table_privilege(client_role, passkeys_oid, privilege) THEN
                EXECUTE pg_catalog.format(
                    'REVOKE %s ON TABLE public.user_passkeys FROM %I', privilege, client_role);
            END IF;

            -- A deployment carrying direct column-level grants without the
            -- table-level one loses those too, rather than failing here.
            FOR direct_grant IN
                SELECT column_name
                FROM information_schema.column_privileges
                WHERE table_schema = 'public'
                  AND table_name = 'user_passkeys'
                  AND grantee = client_role
                  AND privilege_type = privilege
            LOOP
                EXECUTE pg_catalog.format(
                    'REVOKE %s (%I) ON TABLE public.user_passkeys FROM %I',
                    privilege, direct_grant.column_name, client_role);
            END LOOP;

            -- Fail closed only when a privilege survives through PUBLIC or
            -- an inherited role, which revoking the client's own ACL entries
            -- cannot remove.
            SELECT a.attname INTO exposed
            FROM pg_catalog.pg_attribute a
            WHERE a.attrelid = passkeys_oid
              AND a.attnum > 0
              AND NOT a.attisdropped
              AND pg_catalog.has_column_privilege(client_role, passkeys_oid, a.attname, privilege)
            LIMIT 1;

            IF exposed IS NOT NULL THEN
                RAISE EXCEPTION USING
                    errcode = '42501',
                    message = pg_catalog.format(
                        'client %s retains %s on public.user_passkeys.%I',
                        client_role, privilege, exposed),
                    hint = 'Revoke the privilege from PUBLIC or the inherited role.';
            END IF;
        END LOOP;

        -- The non-row privileges from GRANT ALL. TRUNCATE ignores RLS, so a
        -- reachable client TRUNCATE erases every user's passkeys.
        FOREACH privilege IN ARRAY ARRAY['TRUNCATE', 'REFERENCES', 'TRIGGER'] LOOP
            IF pg_catalog.has_table_privilege(client_role, passkeys_oid, privilege) THEN
                EXECUTE pg_catalog.format(
                    'REVOKE %s ON TABLE public.user_passkeys FROM %I', privilege, client_role);
            END IF;

            IF pg_catalog.has_table_privilege(client_role, passkeys_oid, privilege) THEN
                RAISE EXCEPTION USING
                    errcode = '42501',
                    message = pg_catalog.format(
                        'client %s retains %s on public.user_passkeys', client_role, privilege),
                    hint = 'Revoke the privilege from PUBLIC or the inherited role.';
            END IF;
        END LOOP;

        -- The view is auto-updatable; without this, its GRANT ALL would keep
        -- credential_id and last_used_at client-writable into the same table.
        -- REFERENCES and TRIGGER are valid privileges on views too (TRUNCATE is
        -- not), and GRANT ALL handed them over, so they are revoked here as
        -- well: a client TRIGGER grant would allow attaching INSTEAD OF
        -- triggers to the view.
        IF view_oid IS NOT NULL THEN
            FOREACH privilege IN ARRAY ARRAY['INSERT', 'UPDATE', 'DELETE', 'REFERENCES', 'TRIGGER'] LOOP
                IF pg_catalog.has_table_privilege(client_role, view_oid, privilege) THEN
                    EXECUTE pg_catalog.format(
                        'REVOKE %s ON TABLE public.active_user_passkeys FROM %I',
                        privilege, client_role);
                END IF;

                IF pg_catalog.has_table_privilege(client_role, view_oid, privilege) THEN
                    RAISE EXCEPTION USING
                        errcode = '42501',
                        message = pg_catalog.format(
                            'client %s retains %s on public.active_user_passkeys',
                            client_role, privilege),
                        hint = 'Revoke the privilege from PUBLIC or the inherited role.';
                END IF;
            END LOOP;
        END IF;
    END LOOP;
END;
$restrict_user_passkeys_client_writes$;
