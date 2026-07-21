-- Public Storage bucket for desktop-app release binaries (DMG/MSI/DEB/RPM/JAR).
--
-- Objects are laid out as <app>/<version>/<asset> and uploaded by CI using the
-- service-role key (which bypasses RLS). public = true makes them world-readable at
-- the /storage/v1/object/public/app-releases/... path so update checks and downloads
-- work without authentication. Idempotent so re-running is safe.
INSERT INTO storage.buckets (id, name, public)
VALUES ('app-releases', 'app-releases', true)
ON CONFLICT (id) DO UPDATE SET public = EXCLUDED.public;
