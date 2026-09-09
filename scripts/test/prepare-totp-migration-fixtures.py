#!/usr/bin/env python3
"""Copy real migrations into pg_prove's tests-only mount for upgrade regression.

Run before `supabase test db`. The .inc extension keeps pg_prove from treating
these includes as standalone tests. No database connection is made here.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
target = root / 'supabase/tests/.migration-fixtures'
target.mkdir(exist_ok=True)
for name in ('20260907000000_secrets_paging_tiebreaker', '20260909000000_encrypt_totp'):
    (target / (name + '.inc')).write_bytes(
        (root / 'supabase/migrations' / (name + '.sql')).read_bytes()
    )
