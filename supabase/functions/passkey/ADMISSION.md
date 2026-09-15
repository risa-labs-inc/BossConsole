# Passkey admission and rollout

The database limits are storage/CPU safety valves, not fair per-client rate limiting.
Authentication permits 300 attempts per minute across the deployment; registration has
its own 60-attempt budget. Sustained anonymous traffic can exhaust the authentication
budget and temporarily refuse legitimate users. Do not describe this as DoS prevention.

Before rollout, the deployment owner must configure and test admission at a trusted
gateway, including legitimate-client availability during sustained anonymous traffic.
This code does not trust caller-supplied IP or forwarding headers. Removing the global
storage bound or guessing which header is trustworthy is not a substitute for that policy.

Admission and insertion serialize per challenge type. Lock waits are capped at 250 ms,
so a held authentication transaction does not indefinitely queue work or block registration.
Storage contention is retryable; clients must honor Retry-After. Expiry cleanup skips
locked records and deletes at most 256 records per invocation.

Validate database migrations with pgTAP and `scripts/test/test-passkey-admission-concurrency.py`
against the disposable local Supabase database. The latter checks both final-slot races
and a held authentication lock while a registration proceeds. Unit tests alone do not
prove deployed gateway fairness or availability.
