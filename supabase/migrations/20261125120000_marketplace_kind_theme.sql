-- Marketplace: a third bundle kind, `theme` (a Max colour theme: theme.json plus optional art.svg and woff2 fonts).
--
-- In its own file because a value added to an enum cannot be used in the transaction that added it, and the next
-- migration (20261125120100) both compares against it and is exercised by tests in the same database. Nothing here
-- uses the value; the manifest rules and the legacy-path refusal for it live in 20261125120100.
--
-- IF NOT EXISTS keeps a re-run harmless. Existing rows are untouched: every listing stays skill or connector.
ALTER TYPE public.marketplace_kind ADD VALUE IF NOT EXISTS 'theme';
