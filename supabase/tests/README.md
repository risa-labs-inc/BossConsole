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
