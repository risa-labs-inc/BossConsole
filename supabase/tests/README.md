# Database regression tests

From the repository root, run both commands on a disposable local Supabase database:

```sh
python3 scripts/test/prepare-db-access-tests.py
supabase test db
```

Always regenerate immediately before testing. The rotation and audit suites embed
current operational SQL because Supabase CLI mounts test files in isolation.
Running only `supabase test db` can omit these suites on a clean checkout or use
stale generated SQL. The generated `.sql` files are ignored; edit `.sql.in` inputs
and the operational source instead. CI executes the same preparation step.

The audit's exact ACL checks fail CI. Identity checks 2, 3, 3b and 3c are advisory
heuristics: tests prove they detect synthetic ungated readers and tables, but do
not assert that all existing identity surfaces have been authorized correctly.
Those findings need human review of the actual callers and policies.

`client_grant_audit.sql` is the companion that asks who can WRITE rather than who
can read. Both checks gate CI. G2 is a structural lint, not proof that a client
can perform a write: table grants and other restrictive policies still apply:

* **G1** names any `public` table a client role can reach that does not enable
  RLS. There is no allowlist, because every table in the schema enables RLS
  today.
* **G2** names any PERMISSIVE `INSERT`, `UPDATE`, `DELETE` or `ALL` policy reachable by
  `anon`, `authenticated` or PUBLIC whose predicate is the literal `true`. It
  keys on the predicate, not on a missing `TO` clause: most policies here omit
  `TO`, so that alone would report most of the schema.

G2 is asserted as a subset rather than as HEALTHY while the two policies #538
removes are still on `dev`. Once that merges, empty the exception array in
`client_grant_audit_test.sql.in` and the assertion becomes a plain HEALTHY.

Limits: G1 checks table-wide grants, not column-only grants or views. G2 is
grant-blind and checks direct client/PUBLIC policy roles, not inherited policy
roles. It catches the literal-true shape in #488/#538, not #536's always-true
`session_id IS NOT NULL` predicate. Dedicated table suites remain necessary.

After #538 lands, its `audit_log_insert_rls_test.sql` independently rejects any
recreated secret_access_log_insert policy and pins the plugin log INSERT policy
to service_role. Recreating either exception name therefore fails that suite;
the subset here allows either merge order without requiring a synchronized edit.
