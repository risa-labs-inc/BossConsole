-- ============================================================================
-- BOSS Database Schema: a plugin's author may not write its trust columns
-- ============================================================================
-- File: 20260927120000_restrict_plugin_trust_columns.sql
--
-- `authenticated` and `anon` hold table-level INSERT and UPDATE on
-- public.plugins (20251023000014_grants.sql), and the row policies
-- ("Authorised publishers can create plugins", "Authors and organisation
-- admins can update plugins") let an author write their own plugin row. A
-- table-level grant covers every column, and those policies gate the ROW, not
-- the columns within it, so an author can set two columns they must not own:
--
--   * `verified` - the trust checkmark. It is meant to be admin-only: the only
--     legitimate writer is the plugin-store edge function's admin route, gated
--     on `plugins.admin.publish` (routes/admin.ts), and the INSERT policy never
--     checks it. A signed-in user can `PATCH /plugins?id=eq.<own>` with
--     `{"verified": true}`, or INSERT a row with `verified = true`, and their
--     plugin shows the verified badge and passes a `verifiedOnly` store filter.
--
--   * `org_id` - the organisation a plugin is attributed to. The UPDATE policy's
--     check is `auth.uid() = author_id OR (org_id IS NOT NULL AND
--     is_org_admin(org_id))`; the author clause short-circuits the OR, so an
--     author can move their own plugin under ANY organisation, including one
--     they do not administer, and it is then attributed to that organisation.
--     (INSERT is different: the INSERT policy validates org_id with
--     `can_publish_org_plugin`, so a fresh row cannot claim a foreign org. Only
--     the UPDATE path is open, so only UPDATE(org_id) is withdrawn here.)
--
-- Reproduced on unpatched `dev` as the `authenticated` role with a JWT claim,
-- the privileges PostgREST applies: the UPDATE and INSERT of `verified` both
-- land, and the org_id UPDATE re-attributes the plugin. This is the same class
-- as 20260918000000 (a client writing a column an authorized read then trusts),
-- one table over.
--
-- Fix: no client role may write `verified`, and none may change `org_id` on an
-- existing row. Every legitimate writer is the plugin-store edge function,
-- which uses the service-role key and so bypasses these grants; no client in
-- this repository writes the plugins table directly. Client writes to every
-- other column keep working exactly as the row policies allow, and INSERT of
-- `org_id` keeps working under its own policy check.
--
-- The row policy "Users with plugins.admin.publish can update any plugin"
-- still gives an admin's own session row access for UPDATE, but only to the
-- columns granted here. An admin tool outside this repository that sets
-- `verified` or moves `org_id` through PostgREST as `authenticated` now gets
-- 42501, and has to go through the edge function's admin route
-- (plugin-store routes/admin.ts), which is the sanctioned writer.
--
-- How: a table-level grant cannot be narrowed by revoking one column, so where
-- a client role holds table-level INSERT or UPDATE, that grant is replaced by a
-- column-level grant of the same privilege on every current column except the
-- ones protected for that privilege. The column list is read from the catalog,
-- so a deployment carrying an extra column keeps its behaviour for that column.
-- A privilege a role does not hold today is never granted, and the block fails
-- closed if the protected privilege survives through PUBLIC or an inherited
-- role.
--
-- Consequence for later migrations, as in 20260918000000: a new column added to
-- public.plugins is not client-writable until a migration grants it. That is
-- the fail-closed direction and the price of a column-level grant, and
-- plugin_trust_columns_test.sql pins the exact set of closed columns, so such a
-- column fails the suite until someone decides whether clients may write it.
--
-- Do not copy this block to protect a third column. It acts only on a
-- table-level grant, and after it has run none remains, so has_table_privilege
-- is false and the block would do nothing. With the grants per-column, a later
-- migration closes a column directly: REVOKE UPDATE (<column>) ON public.plugins
-- FROM anon, authenticated (and INSERT likewise if needed).
-- ============================================================================

DO $restrict_plugin_trust_columns$
DECLARE
    plugins_oid oid := pg_catalog.to_regclass('public.plugins');
    client_role text;
    privilege text;
    protected name[];
    writable text;
BEGIN
    IF plugins_oid IS NULL THEN
        RAISE EXCEPTION 'public.plugins is missing; this migration expects the plugin store schema';
    END IF;

    FOREACH client_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        CONTINUE WHEN pg_catalog.to_regrole(client_role) IS NULL;

        FOREACH privilege IN ARRAY ARRAY['INSERT', 'UPDATE'] LOOP
            -- `verified` is never client-writable; `org_id` may be set at INSERT
            -- (the INSERT policy validates it with can_publish_org_plugin) but
            -- never changed by a client afterwards.
            protected := CASE privilege
                WHEN 'UPDATE' THEN ARRAY['verified', 'org_id']::name[]
                ELSE ARRAY['verified']::name[]
            END;

            -- has_table_privilege is true only for a table-level grant (or
            -- ownership), never because of column-level grants, so this touches
            -- exactly the grants that expose the protected columns.
            IF pg_catalog.has_table_privilege(client_role, plugins_oid, privilege) THEN
                SELECT pg_catalog.string_agg(pg_catalog.quote_ident(a.attname), ', ' ORDER BY a.attnum)
                INTO writable
                FROM pg_catalog.pg_attribute a
                WHERE a.attrelid = plugins_oid
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                  AND a.attname <> ALL (protected);

                EXECUTE pg_catalog.format(
                    'REVOKE %s ON TABLE public.plugins FROM %I', privilege, client_role);
                EXECUTE pg_catalog.format(
                    'GRANT %s (%s) ON TABLE public.plugins TO %I', privilege, writable, client_role);
            END IF;

            -- Fail closed if access survived through PUBLIC or an inherited role:
            -- revoking the client's direct ACL entry cannot remove either source.
            IF pg_catalog.has_column_privilege(client_role, plugins_oid, 'verified', privilege) THEN
                RAISE EXCEPTION USING
                    errcode = '42501',
                    message = pg_catalog.format(
                        'client %s retains %s on public.plugins.verified', client_role, privilege),
                    hint = 'Revoke the privilege from PUBLIC or the inherited role.';
            END IF;
        END LOOP;

        -- org_id: only UPDATE is withdrawn, so assert only that.
        IF pg_catalog.has_column_privilege(client_role, plugins_oid, 'org_id', 'UPDATE') THEN
            RAISE EXCEPTION USING
                errcode = '42501',
                message = pg_catalog.format(
                    'client %s retains UPDATE on public.plugins.org_id', client_role),
                hint = 'Revoke the privilege from PUBLIC or the inherited role.';
        END IF;
    END LOOP;
END;
$restrict_plugin_trust_columns$;
