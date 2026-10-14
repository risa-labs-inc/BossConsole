-- pgTAP tests for the part-2 SECURITY DEFINER search_path hardening
-- (20260916140000, BossConsole#772).
--
-- 20260916130000 (BossConsole#773) pins the three passkey lifecycle
-- functions; a live catalog audit showed share_secret / unshare_secret /
-- get_secret_shares / handle_new_user were already hardened by later
-- migrations. These assertions pin the three that remained, so a future
-- CREATE OR REPLACE cannot silently drop the clause again.

begin;
select plan(6);

-- 1-3: each hardened function pins an empty search_path in pg_proc.proconfig.
-- Array containment (@>) instead of proconfig[1] so a later-added SET clause
-- cannot push the pin out of position without tripping the assertion.
select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
      from pg_proc p
     where p.oid = 'public.find_user_by_email(text)'::regprocedure),
    true,
    'find_user_by_email pins search_path to empty'
);

select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
      from pg_proc p
     where p.oid = 'public.handle_user_email_update()'::regprocedure),
    true,
    'handle_user_email_update pins search_path to empty'
);

select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
      from pg_proc p
     where p.oid = 'public.safe_decrypt_recovery_codes(text)'::regprocedure),
    true,
    'safe_decrypt_recovery_codes pins search_path to empty'
);

-- 4: the auth.users read still executes under the closed search_path.
select is_empty(
    $$ select * from public.find_user_by_email('nobody@pgtap.invalid') $$,
    'find_user_by_email still executes against auth.users under the closed search_path'
);

-- 5: the documented NULL guard still returns the empty array.
select is(
    public.safe_decrypt_recovery_codes(NULL::text),
    '[]'::jsonb,
    'safe_decrypt_recovery_codes(NULL) still returns the documented empty array'
);

-- 6: a genuine decrypt under the closed search_path. Assertion 5 alone proves
-- nothing about the decrypt path: the NULL guard returns before
-- public.decrypt_text is ever called, and the WHEN OTHERS handler would turn
-- a broken path (e.g. 42883) into a silent '[]' for every user. Only a real
-- round trip proves it. The vault key fixture mirrors
-- fail_soft_secret_listing_decryption_test.sql and rolls back with us.
DO $fixture$
DECLARE existing uuid;
BEGIN
    SELECT id INTO existing FROM vault.secrets WHERE name = 'master_encryption_key';
    IF existing IS NULL THEN
        PERFORM vault.create_secret('definer_part2_pgtap_key_0123456789', 'master_encryption_key', 'pgTAP only');
    ELSE
        PERFORM vault.update_secret(existing, 'definer_part2_pgtap_key_0123456789', 'master_encryption_key', 'pgTAP only');
    END IF;
END;
$fixture$;

select is(
    public.safe_decrypt_recovery_codes(public.encrypt_text('["code-a","code-b"]')),
    '["code-a","code-b"]'::jsonb,
    'safe_decrypt_recovery_codes round-trips a genuinely encrypted value under the closed search_path'
);

select * from finish();
rollback;
