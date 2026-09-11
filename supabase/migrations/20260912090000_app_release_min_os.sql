-- Platform-specific minimum OS versions for pre-download update filtering.
-- Missing keys deliberately mean unknown so legacy publishers remain compatible.
ALTER TABLE app_releases
    ADD COLUMN IF NOT EXISTS min_os JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN app_releases.min_os IS
    'Minimum supported OS versions keyed by macos, windows, or linux';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'app_releases_min_os_object'
          AND conrelid = 'app_releases'::regclass
    ) THEN
        ALTER TABLE app_releases
            ADD CONSTRAINT app_releases_min_os_object
            CHECK (jsonb_typeof(min_os) = 'object');
    END IF;
END $$;

-- BOSS 9.4.0 raised the macOS floor to 13.0 with JxBrowser 9.4.0. Backfill
-- released rows so currently installed clients benefit before the next publish.
UPDATE app_releases
SET min_os = jsonb_set(min_os, '{macos}', '"13.0"'::jsonb, true)
WHERE app = 'boss'
  AND version ~ '^[0-9]+\.[0-9]+(\.|$)'
  AND (
      split_part(version, '.', 1)::integer > 9
      OR (
          split_part(version, '.', 1)::integer = 9
          AND split_part(version, '.', 2)::integer >= 4
      )
  );
