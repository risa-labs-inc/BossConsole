-- The signing scheme changed from the bare digest to the canonical anchor
-- pluginId|version|sha256 before any host shipped verification; correct the
-- column comment already applied by 20260716200000 so it doesn't misdirect a
-- future signer/verifier implementation.

comment on column plugin_versions.signature is
  'Base64 RSASSA-PKCS1-v1_5/SHA-256 signature over the canonical anchor pluginId|version|sha256 (store signing key)';
