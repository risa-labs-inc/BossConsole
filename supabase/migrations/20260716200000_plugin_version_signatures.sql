-- Store-side artifact signatures for plugin versions.
--
-- NOTE: this file is preserved exactly as it was applied to prod (including
-- the now-outdated bare-digest wording below) — never retro-edit an applied
-- migration. The signing scheme changed to the canonical anchor
-- pluginId|version|sha256 before any host shipped verification;
-- 20260716210000_fix_signature_column_comment.sql is the sole correction.
--
-- `signature` holds a base64 RSASSA-PKCS1-v1_5/SHA-256 signature produced by
-- the plugin-store publish function over the UTF-8 bytes of the lowercase hex
-- `sha256` of the version's JAR. The BOSS host pins the matching public key
-- (PluginStoreTrust) and verifies signature -> hash -> bytes at install time.
-- Nullable: versions published before signing shipped have no signature until
-- the backfill script signs them.

alter table plugin_versions
  add column if not exists signature text;

comment on column plugin_versions.signature is
  'Base64 RSASSA-PKCS1-v1_5/SHA-256 signature over the lowercase hex sha256 (store signing key)';
