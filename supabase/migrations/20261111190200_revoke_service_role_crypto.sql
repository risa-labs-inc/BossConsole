-- service_role loses the raw crypto helpers. Every caller in this repository is
-- a SECURITY DEFINER function owned by postgres (create_secret, update_secret,
-- the listings, fluck_oauth_store_secret, the seal trigger), and no edge
-- function in supabase/functions calls them directly.
--
-- Out-of-repo writers: the QBO and Google token brokers keep qbo_token_state /
-- google_token_state under this key (see ops/rotate_master_encryption_key.sql).
-- If they call encrypt_text/decrypt_text with the service key, this breaks
-- them. Confirm before applying in production; it is its own migration so it
-- can be held back.
REVOKE EXECUTE ON FUNCTION public.encrypt_text(text) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.decrypt_text(text) FROM service_role;
REVOKE EXECUTE ON FUNCTION public.get_encryption_key() FROM service_role;
REVOKE EXECUTE ON FUNCTION public.safe_decrypt_recovery_codes(text) FROM service_role;
