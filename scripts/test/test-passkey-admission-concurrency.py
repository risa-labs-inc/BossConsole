#!/usr/bin/env python3
"""Run after pgTAP, only against the CLI's disposable local Docker database."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import argparse
import json
import re
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', help='Explicit disposable Supabase database container')
    args = parser.parse_args()
    project = re.search(r'^project_id\s*=\s*"([a-zA-Z0-9_-]+)"',
                        Path('supabase/config.toml').read_text(), re.MULTILINE)
    if project is None:
        raise RuntimeError('Cannot identify the local test project')
    container = args.container or 'supabase_db_' + project.group(1)
    if not re.fullmatch(r'supabase_db_[a-zA-Z0-9_-]+', container):
        raise ValueError('Only a named local Supabase database container is supported')
    command = ['docker', 'exec', '-i', container, 'psql', '-U', 'postgres', '-d', 'postgres',
               '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=verbose']

    def sql(statement, expected_error=None):
        result = subprocess.run(command, input=statement, text=True, capture_output=True, timeout=30)
        if expected_error is not None and result.returncode != 0:
            if expected_error not in result.stderr:
                raise RuntimeError(result.stderr)
            return None
        if result.returncode != 0:
            raise RuntimeError(result.stderr)
        return result.stdout.strip()

    if sql("select count(*) from public.passkey_challenges where expires_at > clock_timestamp();") != '0':
        raise RuntimeError('The disposable fixture requires no active challenges; refusing to overwrite them')
    previous = json.loads(sql("select json_agg(row_to_json(t)) from public.passkey_challenge_admission t;"))
    prefix = 'concurrency-' + uuid.uuid4().hex
    try:
        sql("update public.passkey_challenge_admission set used=299, window_started_at=clock_timestamp() "
            "where type='authentication';")
        with ThreadPoolExecutor(max_workers=16) as pool:
            admissions = list(pool.map(lambda _: sql("set role service_role; select allowed from "
                                                    "public.admit_passkey_challenge('authentication');"), range(16)))
        assert admissions.count('t') == 1, admissions
        assert admissions.count('f') == 15, admissions
        print('Concurrent admission: exactly one of 16 sessions acquired the final budget slot')

        sql("set role service_role; insert into public.passkey_challenges(challenge,type,expires_at) "
            f"select '{prefix}-' || n, 'authentication', clock_timestamp()+interval '5 minutes' "
            "from generate_series(1,2047) n;")
        with ThreadPoolExecutor(max_workers=16) as pool:
            inserts = list(pool.map(lambda n: sql("set role service_role; insert into public.passkey_challenges "
                                                  "(challenge,type,expires_at) values "
                                                  f"('{prefix}-race-{n}','authentication',"
                                                  "clock_timestamp()+interval '5 minutes'); select 'inserted';",
                                                  expected_error='53300'), range(16)))
        assert inserts.count('inserted') == 1, inserts
        assert inserts.count(None) == 15, inserts
        assert sql(f"select count(*) from public.passkey_challenges where challenge like '{prefix}%';") == '2048'
        print('Concurrent insertion: exactly one of 16 sessions acquired the final outstanding slot')

        sql(f"update public.passkey_challenges set expires_at=clock_timestamp()-interval '1 second' "
            f"where challenge like '{prefix}%';")
        sql('set role service_role; select public.clean_expired_passkey_challenges();')
        assert sql(f"select count(*) from public.passkey_challenges where challenge like '{prefix}%';") == '1792'
        sql("update public.passkey_challenge_admission set window_started_at=clock_timestamp()-interval '61 seconds' "
            "where type='authentication';")
        assert sql("set role service_role; select allowed from public.admit_passkey_challenge('authentication');") == 't'
        print('Cleanup removed one bounded batch, and legitimate retry succeeded after expiry')
    finally:
        sql(f"delete from public.passkey_challenges where challenge like '{prefix}%';")
        for row in previous:
            if row['type'] not in ('authentication', 'registration') or not isinstance(row['used'], int):
                raise RuntimeError('Invalid saved admission state')
            # PostgreSQL emitted this timestamptz; JSON literal quoting is not SQL escaping.
            stamp = row['window_started_at'].replace("'", "''")
            sql("update public.passkey_challenge_admission set "
                f"window_started_at='{stamp}'::timestamptz, used={row['used']} where type='{row['type']}';")


if __name__ == '__main__':
    main()
