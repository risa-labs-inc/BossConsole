# Download analytics configuration

Before enabling IP hashing, provision `PLUGIN_DOWNLOAD_IP_HASH_KEY` as an independent, randomly generated secret: 32-256 non-whitespace characters. For example, use a 32-byte random value encoded as hex (64 characters). Do not reuse the Supabase service-role key or a public configuration value.

The exact configured string is HMAC-SHA256 key material. Whitespace, including a trailing newline, is rejected; it is not silently trimmed. Longer values are deliberately rejected by the configuration contract, not because HMAC cannot accept them. Correct a rejected value before rollout. A missing or invalid key logs its variable name once per function isolate, never its value, and records a null IP hash. Downloads remain available. The database column is nullable and existing download-access restrictions remain in effect.

New rows contain a hexadecimal HMAC-SHA256 value. Historical rows used SHA-256 with a predictable service-role JWT prefix. Existing historical migration comments describe that old generation; do not interpret them as the current algorithm. No backfill is performed. Deployment and every key rotation break correlation with older hashes, so do not use distinct hashes across those boundaries as unique-client counts.

The input is the raw forwarded-IP header. It is not a verified or normalized client identity and can be influenced by callers. These hashes are indicative analytics, not an abuse-prevention or authorization control.

Validation before deployment: verify a test download succeeds with the key absent (null hash), verify a test-only configured key produces stable hashes, and verify rotation changes them. The local Kotlin tests prove reserved characters remain encoded on HTTP requests; the deployed gateway's handling of encoded slashes remains an operational check. No live-service rollout or gateway test is performed by the PR tests.
