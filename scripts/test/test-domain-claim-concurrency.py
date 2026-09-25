"""Exercise domain quota admission with two real PostgreSQL sessions.

Runs only against the disposable Supabase Docker database, like the BOSS AI
concurrency suite. An observed lock wait, not a sleep, proves serialization.
"""
import json
import subprocess
import sys
import time
import tomllib
import uuid
from pathlib import Path

with (Path(__file__).resolve().parents[2] / "supabase/config.toml").open("rb") as config:
    project_id = tomllib.load(config)["project_id"]
PSQL = ["docker", "exec", "-i", f"supabase_db_{project_id}", "psql", "-XqAt",
        "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1"]
USER = str(uuid.uuid4())
# Reused as a DNS label below: letters/digits satisfy both the organisation
# slug grammar (no hyphens) and the domain grammar (no underscores).
SLUG = "quota" + uuid.uuid4().hex[:16]
SECOND_APP = "domain-quota-" + uuid.uuid4().hex
AUTH = "SET LOCAL request.jwt.claims = '{\"role\":\"service_role\"}';"


def query(sql):
    return subprocess.check_output(PSQL + ["-c", sql], text=True, timeout=15).strip()


first = second = None
created = False
try:
    query(f"INSERT INTO auth.users(id,email) VALUES('{USER}','{SLUG}@quota.test');")
    created = True
    setup = query(f"""BEGIN; {AUTH}
      SELECT public.create_organisation_internal(
        p_slug => '{SLUG}', p_name => 'Concurrent domain quota', p_description => NULL,
        p_owner_id => '{USER}', p_domain => NULL, p_visibility => 'private',
        p_join_policy => 'invite_only'); COMMIT;""")
    assert json.loads(setup).get("success") is True, f"Organisation setup failed: {setup}"
    org = query(f"SELECT id FROM public.organisations WHERE slug='{SLUG}'")
    assert org, "Organisation fixture missing"

    def claim(number):
        return f"SELECT public.add_organisation_domain('{org}','d{number}.{SLUG}.test',false,'{USER}');"

    for number in range(4):
        result = query(f"BEGIN; {AUTH} {claim(number)} COMMIT;")
        assert json.loads(result)["success"], result

    # A fixed transaction snapshot could still observe four after waiting for the
    # organisation lock. These modes must refuse rather than bypass admission.
    for isolation in ["REPEATABLE READ", "SERIALIZABLE"]:
        result = subprocess.run(PSQL + ["-c",
            f"BEGIN ISOLATION LEVEL {isolation}; {AUTH} {claim(4)}"],
            capture_output=True, text=True, timeout=15)
        assert result.returncode != 0 and "requires READ COMMITTED" in result.stderr, result.stderr

    first = subprocess.Popen(PSQL, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True, bufsize=1)
    first.stdin.write(f"BEGIN; SET LOCAL statement_timeout='10s'; "
                      f"SET LOCAL idle_in_transaction_session_timeout='15s'; {AUTH}\n{claim(4)}\n")
    first.stdin.flush()
    assert json.loads(first.stdout.readline())["success"], "Fifth claim was refused"

    second = subprocess.Popen(PSQL + ["-c",
        f"BEGIN; SET LOCAL application_name='{SECOND_APP}'; SET LOCAL statement_timeout='10s'; "
        f"{AUTH} {claim(5)} COMMIT;"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    deadline = time.monotonic() + 5
    blocked = False
    while time.monotonic() < deadline:
        blocked = query(f"""SELECT EXISTS(SELECT 1 FROM pg_stat_activity
          WHERE application_name='{SECOND_APP}' AND wait_event_type='Lock')""") == "t"
        if blocked or second.poll() is not None:
            break
        time.sleep(0.05)
    assert blocked, "Second claim did not wait on the organisation lock"
    _, error = first.communicate("COMMIT;\n\\q\n", timeout=15)
    assert first.returncode == 0, error
    output, error = second.communicate(timeout=15)
    assert second.returncode == 0, error
    result = json.loads(output)
    assert result.get("success") is False, result
    assert result["error"] == "This organisation has reached its limit of 5 unverified domain claims", result
    assert query(f"SELECT count(*) FROM public.organisation_domains WHERE org_id='{org}' AND NOT verified") == "5"
    print("PASS: concurrent domain claims serialize; the sixth claim is refused")
finally:
    original_failure = sys.exc_info()[0] is not None
    try:
        for process in [first, second]:
            if process and process.poll() is None:
                process.kill()
                process.communicate(timeout=15)
        if created:
            query(f"""SET statement_timeout='10s'; SET lock_timeout='5s';
              DELETE FROM public.organisations WHERE slug='{SLUG}' AND owner_id='{USER}';
              DELETE FROM auth.users WHERE id='{USER}';""")
    except Exception:
        if not original_failure:
            raise
        print("Cleanup also failed; discard this disposable DB.", file=sys.stderr)
