-- The stale sweep deletes across all users (SECURITY DEFINER, by design: a dead row belongs to
-- a desktop that is gone and cannot delete it). Two costs the first cut carried, both from review:
--   1. FOR EACH ROW fired one sweep per written row; a multi-row upsert ran N sweeps.
--   2. The predicate is on last_seen_at alone and the only index was (user_id, last_seen_at),
--      so every sweep was a sequential scan.
CREATE INDEX IF NOT EXISTS idx_terminal_sessions_last_seen
    ON public.terminal_sessions (last_seen_at);

DROP TRIGGER IF EXISTS trigger_cleanup_stale_terminal_sessions_on_write ON public.terminal_sessions;
CREATE TRIGGER trigger_cleanup_stale_terminal_sessions_on_write
    AFTER INSERT OR UPDATE ON public.terminal_sessions
    FOR EACH STATEMENT EXECUTE FUNCTION public.trigger_cleanup_stale_terminal_sessions();

COMMENT ON FUNCTION public.trigger_cleanup_stale_terminal_sessions() IS
    'Sweeps rows not heartbeated for 15 minutes on ~10% of writes. SECURITY DEFINER on purpose: it deletes OTHER users'' dead rows, which RLS would otherwise forbid; the predicate makes it reach only rows no live desktop is maintaining.';
