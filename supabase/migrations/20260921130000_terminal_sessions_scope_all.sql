-- ShareScope has three values (TAB, WINDOW, ALL); the original check allowed two, so an
-- "all windows" share was rejected with 23514 on every heartbeat. Measured on the first real
-- publish from a dev build.
--
-- 20260921120000 in this repo already carries the three-value check, so on a fresh database
-- this file is a no-op. It exists because production ran the two-value version of 120000
-- before that file was corrected; a migration that has been applied is never edited in place.
ALTER TABLE public.terminal_sessions DROP CONSTRAINT IF EXISTS terminal_sessions_scope_check;
ALTER TABLE public.terminal_sessions
    ADD CONSTRAINT terminal_sessions_scope_check CHECK (scope IN ('TAB', 'WINDOW', 'ALL'));
