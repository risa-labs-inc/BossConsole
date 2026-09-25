-- ============================================================================
-- BOSS Database Schema: clients may not write secret ciphertext directly
-- ============================================================================
-- File: 20260918000000_revoke_client_ciphertext_writes.sql
--
-- `authenticated` holds table-level INSERT and UPDATE on public.secrets and
-- public.secret_metadata (20251023000014_grants.sql), and the row policies let
-- an owner update their own rows (20260802000000_secrets_org_ownership.sql).
-- So a signed-in user could PATCH any ciphertext into their own
-- `password_encrypted` or `recovery_codes_encrypted` through PostgREST, then
-- call get_user_secrets, which decrypts it with the one global key and hands
-- back the plaintext. Every stored ciphertext is under that key, so the user
-- can decrypt any ciphertext they can get hold of - another user's row from a
-- leaked dump or backup, a support export, a log line - by importing it into a
-- row they own.
--
-- That is the attack 20260909000000_encrypt_totp.sql already closes for
-- `twofa_secret` ("never let a caller import another row's ciphertext and
-- decrypt it through an otherwise authorized RPC on their own row"): its
-- trigger rejects a stored envelope as input. The password and the recovery
-- codes, which are read through the same RPCs, were left open.
--
-- It also turns an owned row into an oracle against the CBC ciphertext itself
-- (BossConsole#618 defect 3: no MAC). A tampered ciphertext written into an
-- owned row comes back from the fail-soft listing (20260914010000) as either a
-- value or a blank, which is the one bit a padding-oracle attack needs.
--
-- Fix: no client role may write those two columns. Every legitimate writer
-- (create_secret, update_secret, and the backfill and rotation procedures) is
-- SECURITY DEFINER, runs as the table owner, and is unaffected; no client in
-- this repository writes either column directly. Client writes to every other
-- column keep working exactly as the row policies allow.
--
-- How: a table-level grant covers every column, so it cannot be narrowed by
-- revoking one column. Where a client role holds table-level INSERT or UPDATE,
-- that grant is replaced by a column-level grant of the same privilege on every
-- current column except the protected ones. The column list is read from the
-- catalog rather than written out, so a deployment whose tables carry an extra
-- column keeps its behaviour for that column. A privilege a role does not hold
-- today is never granted. `anon` keeps nothing it does not already hold, and
-- `service_role` (server-side only) is deliberately left as it is.
--
-- Consequence for later migrations: a new column added to either table is not
-- client-writable until a migration grants it. That is the fail-closed
-- direction, and it is the price of a column-level grant.
--
-- `twofa_secret` stays client-writable on purpose: its trigger encrypts
-- plaintext input and rejects envelopes, and PostgREST writers are its
-- documented input path.
-- ============================================================================

DO $revoke_ciphertext_writes$
DECLARE
    target record;
    client_role text;
    privilege text;
    writable text;
BEGIN
    FOR target IN
        SELECT *
        FROM (VALUES
            ('secrets', ARRAY['password_encrypted']::name[]),
            ('secret_metadata', ARRAY['recovery_codes_encrypted']::name[])
        ) AS protected(tbl, cols)
    LOOP
        IF pg_catalog.to_regclass(pg_catalog.format('public.%I', target.tbl)) IS NULL THEN
            RAISE EXCEPTION 'public.% is missing; this migration expects the secret tables', target.tbl;
        END IF;

        SELECT pg_catalog.string_agg(pg_catalog.quote_ident(a.attname), ', ' ORDER BY a.attnum)
        INTO writable
        FROM pg_catalog.pg_attribute a
        WHERE a.attrelid = pg_catalog.to_regclass(pg_catalog.format('public.%I', target.tbl))
          AND a.attnum > 0
          AND NOT a.attisdropped
          AND a.attname <> ALL (target.cols);

        FOREACH client_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
            CONTINUE WHEN pg_catalog.to_regrole(client_role) IS NULL;

            FOREACH privilege IN ARRAY ARRAY['INSERT', 'UPDATE'] LOOP
                -- has_table_privilege is true only for a table-level grant (or
                -- ownership), never because of column-level grants, so this
                -- touches exactly the grants that expose the protected columns.
                IF pg_catalog.has_table_privilege(
                    client_role,
                    pg_catalog.format('public.%I', target.tbl),
                    privilege
                ) THEN
                    EXECUTE pg_catalog.format(
                        'REVOKE %s ON TABLE public.%I FROM %I',
                        privilege, target.tbl, client_role);
                    EXECUTE pg_catalog.format(
                        'GRANT %s (%s) ON TABLE public.%I TO %I',
                        privilege, writable, target.tbl, client_role);
                END IF;

                -- Fail closed if access survived through PUBLIC or an
                -- inherited role. Revoking the client's direct ACL entry
                -- cannot remove either source of effective privilege.
                IF pg_catalog.has_column_privilege(
                    client_role,
                    pg_catalog.format('public.%I', target.tbl),
                    target.cols[1],
                    privilege
                ) THEN
                    RAISE EXCEPTION USING
                        errcode = '42501',
                        message = pg_catalog.format(
                            'client %s retains %s on public.%s.%s',
                            client_role, privilege, target.tbl, target.cols[1]),
                        hint = 'Revoke the privilege from PUBLIC or the inherited role.';
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;
END;
$revoke_ciphertext_writes$;
