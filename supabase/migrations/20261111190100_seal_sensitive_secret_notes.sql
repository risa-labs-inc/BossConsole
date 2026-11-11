-- Seal notes already stored in plaintext on card, cvv and address rows (and rows
-- tagged notes:encrypted). SET notes = notes re-fires secrets_seal_notes_trigger,
-- the same path every new write takes. Idempotent: sealed rows have notes NULL.
-- v1 listings read the sealed form back, so no client sees a change.
UPDATE public.secrets s
SET notes = s.notes
WHERE s.notes IS NOT NULL
  AND public.secret_notes_sensitive(s.id);
