"""Two independent PostgreSQL sessions race one BOSS AI exchange ticket.

Redemption is a DELETE ... RETURNING, so a ticket must remain spendable at most
once even when two sessions race it. The racing session stays parked on the
first session's lock until pg_stat_activity proves the block; no timing sleep is
used as evidence of serialization. Both interleavings are pinned: a committed
redemption denies the racer, and an aborted redemption leaves exactly one
spendable attempt. Concurrent issuance is pinned against the pending-ticket cap.
"""
import subprocess
import sys
import time
import tomllib
from pathlib import Path

with (Path(__file__).resolve().parents[2] / "supabase/config.toml").open("rb") as config:
    project_id = tomllib.load(config)["project_id"]
PSQL = ["docker", "exec", "-i", f"supabase_db_{project_id}", "psql", "-XqAt",
        "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1"]
USER = "bf000000-0000-4000-8000-000000000001"


def query(sql):
    return subprocess.check_output(PSQL + ["-c", sql], text=True, timeout=15).strip()


def open_session():
    return subprocess.Popen(PSQL, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True, bufsize=1)


def send(proc, sql):
    proc.stdin.write(sql + "\n")
    proc.stdin.flush()


def ask(proc, sql):
    send(proc, sql)
    return proc.stdout.readline().rstrip("\n")


def issue(user):
    out = subprocess.check_output(PSQL + ["-c",
        f"SET ROLE authenticated; SELECT set_config('request.jwt.claim.sub','{user}',false);"
        f" SELECT public.boss_ai_create_exchange_ticket()->>'ticket';"], text=True, timeout=15)
    return out.strip().splitlines()[-1].strip()


def wait_blocked(proc, pid, event, desc):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        state = query(
            "SELECT wait_event_type||':'||coalesce(wait_event,'')"
            f" FROM pg_stat_activity WHERE pid={pid}")
        if state == f"Lock:{event}" or proc.poll() is not None:
            break
        time.sleep(0.05)
    assert state == f"Lock:{event}", f"{desc}: racing session did not block on the {event} lock"


def close(proc):
    _, error = proc.communicate("\\q\n", timeout=15)
    assert proc.returncode == 0, error


holder = racer = None
try:
    query(f"DELETE FROM auth.users WHERE id='{USER}'; "
          f"INSERT INTO auth.users(id,email) VALUES('{USER}','boss-ai-ticket-race@pgtap.test')")
    # Restore the boss_ai migration's baseline seed when a shared dev database has
    # lost it. Idempotent, and a no-op in CI where migrations apply it fresh.
    query("""
      INSERT INTO public.permissions(name, description, is_system)
      VALUES ('ai.use', 'Use the included BOSS AI models within their allowance', true)
      ON CONFLICT (name) DO NOTHING;
      INSERT INTO public.role_permissions(role_id, permission_id)
      SELECT r.id, p.id FROM public.roles r CROSS JOIN public.permissions p
      WHERE r.name = 'user' AND p.name = 'ai.use'
      ON CONFLICT (role_id, permission_id) DO NOTHING;
    """)
    assert query(f"SELECT public.user_has_permission('{USER}','ai.use')") == "t", \
        "Baseline AI role grant missing"

    # A committed redemption denies the racing session; the ticket row is gone.
    ticket = issue(USER)
    holder = open_session()
    send(holder, "BEGIN;")
    assert ask(holder, f"SELECT public.boss_ai_consume_exchange_ticket('{ticket}');") == USER
    racer = open_session()
    racer_pid = int(ask(racer, "SELECT pg_backend_pid();"))
    send(racer, f"SELECT public.boss_ai_consume_exchange_ticket('{ticket}');")
    wait_blocked(racer, racer_pid, "transactionid", "double-spend")
    _, error = holder.communicate("COMMIT;\n\\q\n", timeout=15)
    assert holder.returncode == 0, error
    holder = None
    assert racer.stdout.readline().rstrip("\n") == "", "racing session spent a committed ticket"
    close(racer)
    racer = None
    assert query(f"SELECT count(*) FROM public.boss_ai_exchange_tickets WHERE user_id='{USER}'") == "0"
    assert query(f"SELECT public.boss_ai_consume_exchange_ticket('{ticket}')") == ""
    print("PASS: racing sessions redeem a committed ticket at most once and the loser gets NULL")

    # An aborted redemption leaves exactly one spendable attempt.
    ticket = issue(USER)
    holder = open_session()
    send(holder, "BEGIN;")
    assert ask(holder, f"SELECT public.boss_ai_consume_exchange_ticket('{ticket}');") == USER
    racer = open_session()
    racer_pid = int(ask(racer, "SELECT pg_backend_pid();"))
    send(racer, f"SELECT public.boss_ai_consume_exchange_ticket('{ticket}');")
    wait_blocked(racer, racer_pid, "transactionid", "abort-retry")
    _, error = holder.communicate("ROLLBACK;\n\\q\n", timeout=15)
    assert holder.returncode == 0, error
    holder = None
    assert racer.stdout.readline().rstrip("\n") == USER, \
        "an aborted redemption did not leave the ticket spendable"
    close(racer)
    racer = None
    assert query(f"SELECT count(*) FROM public.boss_ai_exchange_tickets WHERE user_id='{USER}'") == "0"
    assert query(f"SELECT public.boss_ai_consume_exchange_ticket('{ticket}')") == ""
    print("PASS: an aborted redemption does not burn the ticket; one later attempt still wins")

    # Concurrent issuance serializes on the per-user advisory lock and cannot
    # exceed the pending cap: the ninth exchange raises while the eighth lives.
    for _ in range(7):
        issue(USER)
    assert query(f"SELECT count(*) FROM public.boss_ai_exchange_tickets WHERE user_id='{USER}'") == "7"
    holder = open_session()
    send(holder, "BEGIN;")
    send(holder, "SET ROLE authenticated;")
    assert ask(holder, f"SELECT set_config('request.jwt.claim.sub','{USER}',false);") == USER
    eighth = ask(holder, "SELECT public.boss_ai_create_exchange_ticket()->>'ticket';")
    assert len(eighth) == 64, eighth
    racer = open_session()
    racer_pid = int(ask(racer, "SELECT pg_backend_pid();"))
    send(racer, "SET ROLE authenticated;")
    ask(racer, f"SELECT set_config('request.jwt.claim.sub','{USER}',false);")
    send(racer, "SELECT public.boss_ai_create_exchange_ticket()->>'ticket';")
    wait_blocked(racer, racer_pid, "advisory", "issuance")
    _, error = holder.communicate("COMMIT;\n\\q\n", timeout=15)
    assert holder.returncode == 0, error
    holder = None
    _, error = racer.communicate(timeout=15)
    assert racer.returncode != 0 and "Too many pending" in error, error
    racer = None
    assert query(f"SELECT count(*) FROM public.boss_ai_exchange_tickets WHERE user_id='{USER}'") == "8"
    assert query(f"SELECT public.boss_ai_consume_exchange_ticket('{eighth}')") == USER
    print("PASS: concurrent issuance caps pending tickets at eight and the winner's ticket is real")
finally:
    original_failure = sys.exc_info()[0] is not None
    try:
        for process in [holder, racer]:
            if process and process.poll() is None:
                process.kill()
                process.communicate(timeout=15)
        query(f"""
      SET statement_timeout='5s'; SET lock_timeout='3s';
      DELETE FROM public.boss_ai_exchange_tickets WHERE user_id='{USER}';
      DELETE FROM auth.users WHERE id='{USER}';
        """)
    except Exception:
        if not original_failure:
            raise
        print("Cleanup also failed; preserving the original test failure. Discard this disposable DB.",
              file=sys.stderr)
