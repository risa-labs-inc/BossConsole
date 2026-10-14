-- ============================================================================
-- terminal_sessions: live BossTerm share registry per BOSS account
-- ============================================================================
--
-- A signed-in BossTerm publishes one row per active terminal share and
-- heartbeats it every ~30 s; the `live-sessions` edge function lists the rows
-- whose last_seen_at is fresh and lets the owner open the share-viewer from any
-- browser after a magic-link sign-in.
--
-- What is stored is the LINK, not the terminal. No output, no input, no
-- transcript ever reaches this table - only the URL, device/session names and
-- timestamps. The link does include the end-to-end secret (`#k=`), so a row is
-- a bearer credential for its own session and is readable by its owner only.
--
-- Writes are performed by the desktop app AS THE USER (PostgREST with the
-- user's JWT), never by service_role, so RLS is the whole access model.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.terminal_sessions (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    -- Stable per share for the life of the process: sha256(viewToken) prefix.
    -- The desktop upserts on (user_id, share_id) so heartbeats never duplicate.
    share_id      text NOT NULL CHECK (share_id ~ '^[0-9a-f]{16,64}$'),
    device_name   text NOT NULL CHECK (char_length(device_name) BETWEEN 1 AND 120),
    session_name  text CHECK (char_length(session_name) <= 120),
    scope         text NOT NULL CHECK (scope IN ('TAB', 'WINDOW', 'ALL')),  -- ShareScope enum names
    -- Read-only viewer link and the account (auto-admit, control) link. Both
    -- carry the E2E secret in the fragment.
    view_url      text NOT NULL CHECK (char_length(view_url) <= 2048),
    control_url   text NOT NULL CHECK (char_length(control_url) <= 2048),
    secure        boolean NOT NULL DEFAULT false,
    e2e_code      text CHECK (e2e_code ~ '^[0-9a-f]{8}$'),
    app_version   text CHECK (char_length(app_version) <= 40),
    started_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, share_id)
);

COMMENT ON TABLE public.terminal_sessions IS
    'Live BossTerm shares per account. One row per share, heartbeated by the desktop app; a row is live when last_seen_at is within ~90 s. Holds links only, never terminal content.';
COMMENT ON COLUMN public.terminal_sessions.control_url IS
    'Account link: auto-admitted, control-capable, includes the #k= E2E secret. A bearer credential for that one session; owner-readable only via RLS.';

CREATE INDEX IF NOT EXISTS idx_terminal_sessions_user_seen
    ON public.terminal_sessions (user_id, last_seen_at DESC);

-- ----------------------------------------------------------------------------
-- Server-side heartbeat stamp. The client sends NO timestamps: any UPDATE
-- (the heartbeat is an upsert that changes nothing else) refreshes
-- last_seen_at from the database clock, so client clock skew cannot make a
-- dead session look live or a live one look dead.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.terminal_sessions_touch() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = ''
    AS $$
BEGIN
    NEW.last_seen_at := now();
    -- started_at is set once; an upsert must not let the client move it.
    IF TG_OP = 'UPDATE' THEN
        NEW.started_at := OLD.started_at;
        NEW.user_id := OLD.user_id;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS terminal_sessions_touch_on_write ON public.terminal_sessions;
CREATE TRIGGER terminal_sessions_touch_on_write
    BEFORE INSERT OR UPDATE ON public.terminal_sessions
    FOR EACH ROW EXECUTE FUNCTION public.terminal_sessions_touch();

-- ----------------------------------------------------------------------------
-- Probabilistic cleanup (pattern: organisation_handoff_tokens). A crashed or
-- offline desktop never deletes its row; anything not heartbeated for 15 min is
-- dead by any definition and gets swept on ~10% of writes (inserts and heartbeats).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.trigger_cleanup_stale_terminal_sessions() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = ''
    AS $$
BEGIN
    IF random() < 0.1 THEN
        DELETE FROM public.terminal_sessions
        WHERE last_seen_at < now() - interval '15 minutes';
    END IF;
    RETURN NEW;
END;
$$;

-- INSERT OR UPDATE: the heartbeat is an upsert that lands on the UPDATE path, and an
-- INSERT-only trigger would sweep only when a brand-new share appears.
DROP TRIGGER IF EXISTS trigger_cleanup_stale_terminal_sessions_on_write ON public.terminal_sessions;
CREATE TRIGGER trigger_cleanup_stale_terminal_sessions_on_write
    AFTER INSERT OR UPDATE ON public.terminal_sessions
    FOR EACH ROW EXECUTE FUNCTION public.trigger_cleanup_stale_terminal_sessions();

-- ----------------------------------------------------------------------------
-- RLS: owner only. No anon access at all.
-- ----------------------------------------------------------------------------
ALTER TABLE public.terminal_sessions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "terminal_sessions owner select" ON public.terminal_sessions;
CREATE POLICY "terminal_sessions owner select" ON public.terminal_sessions
    FOR SELECT TO authenticated USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "terminal_sessions owner insert" ON public.terminal_sessions;
CREATE POLICY "terminal_sessions owner insert" ON public.terminal_sessions
    FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "terminal_sessions owner update" ON public.terminal_sessions;
CREATE POLICY "terminal_sessions owner update" ON public.terminal_sessions
    FOR UPDATE TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "terminal_sessions owner delete" ON public.terminal_sessions;
CREATE POLICY "terminal_sessions owner delete" ON public.terminal_sessions
    FOR DELETE TO authenticated USING (auth.uid() = user_id);

REVOKE ALL ON public.terminal_sessions FROM PUBLIC, anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.terminal_sessions TO authenticated;
GRANT ALL ON public.terminal_sessions TO service_role;

-- ----------------------------------------------------------------------------
-- Realtime: lets the web page (phase 2) and other BossTerm instances react to
-- a share appearing/disappearing without polling. RLS applies to the stream.
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'terminal_sessions'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.terminal_sessions;
    END IF;
END $$;

