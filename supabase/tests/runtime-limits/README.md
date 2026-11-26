Run `bun install --frozen-lockfile && bun run test` from this directory.

This is an isolated PostgreSQL (PGlite) check of the runtime-limits migration:
read-only client grants, anonymous reads, non-admin rejection, administrator writes,
optimistic concurrency, invalid values, history records, and an invitation blocked
then unblocked by a config change. It needs no production access or credentials.

The fixture provides minimal auth and invitation tables. Function-body checking is
disabled while installing replacements for existing organisation and marketplace
functions, whose full schemas are absent here. The invitation gate and all new
configuration functions execute with body checking enabled. This does not replace
the full-schema pgTAP suite (`supabase test db`), including
`platform_runtime_limits_test.sql` and the existing organisation, invitation and
marketplace tests.
