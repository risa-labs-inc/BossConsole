-- ============================================================================
-- app_releases: desktop-app release catalog + Realtime push channel
-- ============================================================================
--
-- Source of truth for BossConsole / BossTerm desktop self-update.
-- Binaries (DMG/MSI/DEB/RPM/JAR) live in Supabase Storage; this table holds the
-- catalog (version + per-platform asset URLs/sha256) and, via the
-- supabase_realtime publication, pushes new releases to running clients so they
-- check for updates instantly instead of polling GitHub.
--
-- Writes are performed by CI using the service_role key (bypasses RLS).
-- Reads are public (anon) so update checks work before/without login.
-- ============================================================================

CREATE TABLE IF NOT EXISTS app_releases (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app           TEXT NOT NULL,                    -- 'boss' | 'bossterm'
    version       TEXT NOT NULL,                    -- e.g. '9.2.17'
    channel       TEXT NOT NULL DEFAULT 'stable',   -- stable | alpha | beta | rc
    prerelease    BOOLEAN NOT NULL DEFAULT false,
    release_notes TEXT DEFAULT '',
    -- Array of {name, url, size, sha256} — one entry per platform asset.
    assets        JSONB NOT NULL DEFAULT '[]'::jsonb,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(app, version)
);

-- Newest-first lookups per app (the client queries order=published_at.desc).
CREATE INDEX IF NOT EXISTS idx_app_releases_app_published
    ON app_releases (app, published_at DESC);

-- ----------------------------------------------------------------------------
-- Row Level Security: public read, service-role-only writes
-- ----------------------------------------------------------------------------
ALTER TABLE app_releases ENABLE ROW LEVEL SECURITY;

-- Anyone (anon or authenticated) can read the release catalog.
CREATE POLICY "App releases are viewable by everyone"
    ON app_releases FOR SELECT
    USING (true);

-- No INSERT/UPDATE/DELETE policies: only service_role (CI) can write, and it
-- bypasses RLS. This prevents end users from forging releases.

GRANT SELECT ON app_releases TO anon, authenticated;
GRANT ALL ON app_releases TO service_role;

-- ----------------------------------------------------------------------------
-- Realtime: push INSERT/UPDATE events to subscribed clients
-- ----------------------------------------------------------------------------
-- Idempotent: ADD TABLE errors if it's already a member (re-run / already added).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'app_releases'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE app_releases;
    END IF;
END $$;
