"""Two independent PostgreSQL sessions race one plugin rating submission.

upsert_plugin_rating must keep exactly one rating row per (plugin, user)
even when two sessions submit the same user's first rating concurrently.
The racing session stays parked on the winner's speculative insertion until
pg_stat_activity proves the block; no timing sleep is used as evidence of
serialization. Both interleavings are pinned: a committed winner lets the
racer take the update arm (created=false, one row), and an aborted winner
lets the racer's insertion stand (created=true, one row).
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
PLUGIN = "af200000-0000-4000-8000-000000000001"
AUTHOR = "af100000-0000-4000-8000-000000000001"
ALICE = "af100000-0000-4000-8000-000000000002"


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


def wait_blocked(proc, pid, desc):
    """Park until pg_stat_activity proves the racer holds on a lock."""
    deadline = time.monotonic() + 5
    state = ""
    while time.monotonic() < deadline:
        state = query(
            "SELECT wait_event_type||':'||coalesce(wait_event,'')"
            f" FROM pg_stat_activity WHERE pid={pid}")
        if state.startswith("Lock:") or proc.poll() is not None:
            break
        time.sleep(0.05)
    assert state.startswith("Lock:"), \
        f"{desc}: racing session never blocked on a lock (last state {state!r})"
    return state.split(":", 1)[1]


def close(proc):
    _, error = proc.communicate("\\q\n", timeout=15)
    assert proc.returncode == 0, error


def reset_rows():
    """Both races exercise a user's FIRST rating; start each from no rows."""
    query(f"DELETE FROM public.plugin_ratings WHERE plugin_id='{PLUGIN}'")


def rows_summary():
    return query(
        f"SELECT count(*)||':'||min(rating) FROM public.plugin_ratings"
        f" WHERE plugin_id='{PLUGIN}'")


holder = racer = None
try:
    query(f"DELETE FROM public.plugin_ratings WHERE plugin_id='{PLUGIN}'; "
          f"DELETE FROM public.plugins WHERE id='{PLUGIN}'; "
          f"DELETE FROM auth.users WHERE id IN ('{AUTHOR}','{ALICE}'); "
          f"INSERT INTO auth.users(id,email) VALUES"
          f" ('{AUTHOR}','rating-race-author@pgtap.test'),"
          f" ('{ALICE}','rating-race-alice@pgtap.test'); "
          f"INSERT INTO public.plugins(id,plugin_id,display_name,author_id,"
          f" author_name,published) VALUES('{PLUGIN}',"
          f" 'com.pgtap.rating-race','Rating Race','{AUTHOR}','Rating Author',true)")

    # A committed first submission lets the racer take the update arm.
    holder = open_session()
    send(holder, "BEGIN;")
    first = ask(holder,
        f"SELECT * FROM public.upsert_plugin_rating('{PLUGIN}','{ALICE}',5,'holder');")
    assert first.endswith("|t"), f"holder: expected the insertion arm, got {first!r}"
    racer = open_session()
    racer_pid = int(ask(racer, "SELECT pg_backend_pid();"))
    send(racer,
        f"SELECT * FROM public.upsert_plugin_rating('{PLUGIN}','{ALICE}',4,'racer');")
    event = wait_blocked(racer, racer_pid, "first-rating race")
    _, error = holder.communicate("COMMIT;\n\\q\n", timeout=15)
    assert holder.returncode == 0, error
    holder = None
    second = racer.stdout.readline().rstrip("\n")
    assert second.endswith("|f") and second != "", \
        f"a committed first rating must let the racer update in place, got {second!r}"
    close(racer)
    racer = None
    assert rows_summary() == "1:4", \
        f"racing first submissions must leave one row with the last write, got {rows_summary()!r}"
    assert second.split("|")[0] in query(
        f"SELECT id FROM public.plugin_ratings WHERE plugin_id='{PLUGIN}'"), \
        "the racer must report the same row the winner created"
    print(f"PASS: racing first submissions serialize; the loser takes the update arm (blocked on {event})")

    # An aborted first submission leaves the racer's insertion standing.
    reset_rows()
    holder = open_session()
    send(holder, "BEGIN;")
    ask(holder,
        f"SELECT * FROM public.upsert_plugin_rating('{PLUGIN}','{ALICE}',5,'aborted');")
    racer = open_session()
    racer_pid = int(ask(racer, "SELECT pg_backend_pid();"))
    send(racer,
        f"SELECT * FROM public.upsert_plugin_rating('{PLUGIN}','{ALICE}',2,'racer');")
    event = wait_blocked(racer, racer_pid, "aborted-winner race")
    _, error = holder.communicate("ROLLBACK;\n\\q\n", timeout=15)
    assert holder.returncode == 0, error
    holder = None
    second = racer.stdout.readline().rstrip("\n")
    assert second.endswith("|t") and second != "", \
        f"an aborted first rating must leave the racer's insertion standing, got {second!r}"
    close(racer)
    racer = None
    assert rows_summary() == "1:2", \
        f"an aborted winner must leave exactly the racer's row, got {rows_summary()!r}"
    print(f"PASS: an aborted winner leaves exactly the racer's row (blocked on {event})")
finally:
    original_failure = sys.exc_info()[0] is not None
    try:
        for process in [holder, racer]:
            if process and process.poll() is None:
                process.kill()
                process.communicate(timeout=15)
        query(f"""
      SET statement_timeout='5s'; SET lock_timeout='3s';
      DELETE FROM public.plugin_ratings WHERE plugin_id='{PLUGIN}';
      DELETE FROM public.plugins WHERE id='{PLUGIN}';
      DELETE FROM auth.users WHERE id IN ('{AUTHOR}','{ALICE}');
""")
    except Exception:
        if not original_failure:
            raise
        print("Cleanup also failed; preserving the original test failure. Discard this disposable DB.",
              file=sys.stderr)
