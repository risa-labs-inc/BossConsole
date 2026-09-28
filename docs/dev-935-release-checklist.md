# Dev integration #935: release checks

These are rollout checks, not completed production verification.

- Verify enabled `system_plugins` rows against the IDs and repository pins in `SystemPluginManifestService.FALLBACK`. IDs must match exactly; repository matching is case-insensitive, with no surrounding whitespace. Unknown or retargeted rows fail closed.
- Before applying `20260918000000_revoke_client_ciphertext_writes.sql`, audit effective client grants, including PUBLIC and inherited roles. The migration deliberately aborts if protected ciphertext columns remain writable through inherited grants.
- Verify supported Secret Manager plugin versions create/update secrets through the protected RPCs rather than direct writes to ciphertext columns. This migration affects existing clients too. Future table columns need explicit client grants where appropriate.
- Verify primary and GitHub fallback Chromium downloads publish identical artifact bytes matching the catalog checksum. A different archive must be rejected, even if its unpacked contents look equivalent.
- An engine version is installable only when the Supabase catalog pins a checksum for the current platform archive. Hashless GitHub-only versions are not offered in the picker; a direct install attempt refuses before download.
- Exercise login under expected shared-egress traffic. The passkey challenge limit is per client IP and isolate; repeated discovery/authentication requests share its budget. Any limiter redesign must preserve the pre-lookup enumeration defense.

Database regressions are already wired into `.github/workflows/build.yml` through `supabase test db`, including `client_ciphertext_write_test.sql` and `passkey_definer_search_path_test.sql`. They are not manual-only tests. Disposable CI success does not verify production grants, plugin versions, or artifact parity.

## Separate follow-up scope

- Persisted MCP tool rules are now stored per plugin (`providerToolRules`). Rules written before that stay in the name-only `rules` map and keep answering for every plugin. There is no automatic migration of those rules to a plugin, because nothing records who they were meant for.
- Shutdown flush currently waits for persistence. A reliable bounded shutdown needs to account for blocking filesystem I/O; a coroutine timeout alone cannot guarantee that a blocked write stops.
- Snapshot reads reject invalid or escaping process directories. Callers must handle the documented exception.
