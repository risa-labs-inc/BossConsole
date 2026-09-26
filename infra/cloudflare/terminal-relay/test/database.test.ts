import { test } from "node:test";
import { PGlite } from "@electric-sql/pglite";
import { pgcrypto } from "@electric-sql/pglite/contrib/pgcrypto";
import { readFile } from "node:fs/promises";
import assert from "node:assert/strict";
test("PostgreSQL enforces account isolation, one-use admission, expiry and capacity", async () => {
const db = new PGlite({ extensions: { pgcrypto } });
await db.exec(
  `CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role;
CREATE SCHEMA auth; CREATE SCHEMA extensions; CREATE EXTENSION pgcrypto WITH SCHEMA extensions;
CREATE TABLE auth.users(id uuid PRIMARY KEY);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
CREATE FUNCTION auth.role() RETURNS text LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('request.jwt.claim.role',true),'') $$;
GRANT USAGE ON SCHEMA auth TO authenticated,anon,service_role;`,
);
const root = new URL("../../../../supabase/migrations/", import.meta.url);
for (
  const file of [
    "20260927000000_user_terminal_preferences.sql",
    "20260927010000_terminal_relay_tickets.sql",
    "20260927020000_terminal_relay_cleanup.sql",
    "20260927030000_terminal_preferences_conflict_status.sql",
  ]
) await db.exec(await readFile(new URL(file, root), "utf8"));
const a = "11111111-1111-4111-8111-111111111111",
  b = "22222222-2222-4222-8222-222222222222",
  room = "33333333-3333-4333-8333-333333333333";
await db.query("INSERT INTO auth.users VALUES($1),($2)", [a, b]);
const as = async (id: string, role = "authenticated") => {
  await db.exec("RESET ROLE; SET ROLE " + role);
  await db.query(
    "SELECT set_config('request.jwt.claim.sub',$1,false),set_config('request.jwt.claim.role',$2,false)",
    [id, role],
  );
};
type Preferences = {unfocused_mode: string; unfocused_fps: number; revision: number};
const scalar = async <T = number>(sql: string, args: unknown[] = []): Promise<T> =>
  (await db.query<{value: T}>(sql, args)).rows[0].value;
const hasCode = (error: unknown, code: string) => error instanceof Error && "code" in error && error.code === code;
await as(a);
assert.deepEqual(await scalar<Preferences>("SELECT get_user_terminal_preferences() value"), {
  unfocused_mode: "batch",
  unfocused_fps: 4,
  revision: 0,
});
assert.equal(
  (await scalar<Preferences>("SELECT set_user_terminal_preferences('preview',10,0) value"))
    .revision,
  1,
);
await assert.rejects(
  db.query("SELECT set_user_terminal_preferences('batch',4,0)"),
  (e) => hasCode(e, "PT409"),
);
await assert.rejects(
  db.query("SELECT set_user_terminal_preferences('batch',4,99)"),
  (e) => hasCode(e, "PT409"),
);
assert.equal((await scalar<Preferences>("SELECT get_user_terminal_preferences() value")).revision, 1);
await assert.rejects(
  db.query("SELECT set_user_terminal_preferences('batch',31,1)"),
  (e) => hasCode(e, "22023"),
);
await assert.rejects(
  db.query("SELECT get_user_terminal_preferences($1)", [b]),
  (e) => hasCode(e, "42501"),
);
await assert.rejects(
  db.query("SELECT get_user_terminal_preferences(NULL,$1)", [b]),
  (e) => hasCode(e, "42501"),
);
const handoff = await scalar<{token: string}>("SELECT mint_user_settings_handoff($1) value", [
  a,
]);
assert.match(handoff.token, /^[\w-]{43}$/);
await assert.rejects(
  db.query("SELECT consume_user_settings_handoff($1)", [handoff.token]),
  (e) => hasCode(e, "42501"),
);
await assert.rejects(
  db.query("SELECT mint_terminal_relay_ticket($1,'host',$2)", [room, b]),
  (e) => hasCode(e, "42501"),
);
const ticket = await scalar<{ticket: string}>(
  "SELECT mint_terminal_relay_ticket($1,'host') value",
  [room],
);
assert.match(ticket.ticket, /^[\w-]{43}$/);
await as(b);
assert.equal(
  (await scalar<Preferences>("SELECT get_user_terminal_preferences() value")).revision,
  0,
);
assert.equal(
  (await db.query("SELECT * FROM user_terminal_preferences")).rows.length,
  0,
);
await assert.rejects(
  db.query("SELECT mint_terminal_relay_ticket($1,'account')", [room]),
  (e) => hasCode(e, "42501"),
);
await assert.rejects(
  db.query("SELECT mint_terminal_relay_ticket($1,'host')", [room]),
  (e) => hasCode(e, "42501"),
);
await as("", "service_role");
assert.equal(
  (await scalar<{user_id: string}>("SELECT consume_user_settings_handoff($1) value", [
    handoff.token,
  ])).user_id,
  a,
);
await assert.rejects(
  db.query("SELECT consume_user_settings_handoff($1)", [handoff.token]),
  (e) => hasCode(e, "42501"),
);
assert.equal(
  (await scalar<{role: string}>("SELECT consume_terminal_relay_ticket($1,$2) value", [
    ticket.ticket,
    room,
  ])).role,
  "host",
);
await assert.rejects(
  db.query("SELECT consume_terminal_relay_ticket($1,$2)", [
    ticket.ticket,
    room,
  ]),
  (e) => hasCode(e, "42501"),
);
assert.equal(
  (await scalar<Preferences>("SELECT get_user_terminal_preferences($1) value", [a]))
    .unfocused_fps,
  10,
);
// Expired cleanup is owner/room-scoped, and expires_at == now is reclaimable.
// Seed over the usual pending limits to prove expiration frees capacity before counting.
await db.exec("RESET ROLE");
await db.query("DELETE FROM terminal_relay_rooms");
await db.query(`INSERT INTO terminal_relay_rooms
 SELECT md5('expired-own-room-' || n)::uuid, $1, now() - interval '1 minute'
 FROM generate_series(1,20) n`, [a]);
const foreignRoom = "44444444-4444-4444-8444-444444444444";
await db.query("INSERT INTO terminal_relay_rooms VALUES($1,$2,now()-interval '1 minute')", [foreignRoom,b]);
await db.query(`INSERT INTO terminal_relay_tickets VALUES
 ('foreign-expired-ticket',$1,'host',now()-interval '1 minute'),
 ('own-cascade-ticket',md5('expired-own-room-1')::uuid,'host',now()+interval '1 minute')`, [foreignRoom]);
await as(a);
await scalar<{ticket: string}>("SELECT mint_terminal_relay_ticket($1,'host') value", [room]);
await db.exec("RESET ROLE");
assert.equal(await scalar("SELECT count(*)::int value FROM terminal_relay_rooms WHERE user_id=$1", [a]),1);
assert.equal(await scalar("SELECT count(*)::int value FROM terminal_relay_rooms WHERE id=$1", [foreignRoom]),1);
assert.equal(await scalar("SELECT count(*)::int value FROM terminal_relay_tickets WHERE token_hash='own-cascade-ticket'"),0);
assert.equal(await scalar("SELECT count(*)::int value FROM terminal_relay_tickets WHERE token_hash='foreign-expired-ticket'"),1);
// The caller cannot reuse another owner's expired room or collect that owner's tickets.
await as(a);
await assert.rejects(db.query("SELECT mint_terminal_relay_ticket($1,'host')", [foreignRoom]), e => hasCode(e, "42501"));
await db.exec("RESET ROLE");
await db.query(`INSERT INTO terminal_relay_tickets
 SELECT 'own-pending-' || n, $1, 'account', now()+interval '1 minute'
 FROM generate_series(1,31) n`, [room]);
await as(a);
await assert.rejects(db.query("SELECT mint_terminal_relay_ticket($1,'account')", [room]), e => hasCode(e, "54000"));
await db.exec("RESET ROLE");
await db.query("UPDATE terminal_relay_tickets SET expires_at=now() WHERE room_id=$1", [room]);
await as(a);
const renewed = await scalar<{ticket: string}>("SELECT mint_terminal_relay_ticket($1,'account') value", [room]);
await db.exec("RESET ROLE");
assert.equal(await scalar("SELECT count(*)::int value FROM terminal_relay_tickets WHERE room_id=$1", [room]),1);
assert.equal(await scalar("SELECT count(*)::int value FROM terminal_relay_tickets WHERE room_id=$1", [foreignRoom]),1);
// A ticket cannot extend an expired room lease, even if its own 60s TTL remains valid.
await db.query("UPDATE terminal_relay_rooms SET expires_at=now() WHERE id=$1", [room]);
await as("", "service_role");
await assert.rejects(db.query("SELECT consume_terminal_relay_ticket($1,$2)", [renewed.ticket,room]), e => hasCode(e, "42501"));
await as(a);
await scalar<{ticket: string}>("SELECT mint_terminal_relay_ticket($1,'host') value", [room]);

// Room limits are per owner; renewing an existing room at the cap is permitted.
await db.exec("RESET ROLE");
await db.query(`INSERT INTO terminal_relay_rooms
 SELECT md5('active-own-room-' || n)::uuid, $1, now()+interval '1 hour'
 FROM generate_series(1,19) n`, [a]);
await as(a);
await assert.rejects(db.query("SELECT mint_terminal_relay_ticket($1,'host')", ["55555555-5555-4555-8555-555555555555"]), e => hasCode(e, "54000"));
await scalar<{ticket: string}>("SELECT mint_terminal_relay_ticket($1,'host') value", [room]);

await db.exec("RESET ROLE");
await db.query(`INSERT INTO user_settings_handoffs
 SELECT 'expired-handoff-' || n, CASE WHEN n<=10 THEN $1::uuid ELSE $2::uuid END,
 now()-interval '1 minute' FROM generate_series(1,20) n`, [a,b]);
await as(a);
await scalar<{token: string}>("SELECT mint_user_settings_handoff() value");
await db.exec("RESET ROLE");
assert.equal(await scalar("SELECT count(*)::int value FROM user_settings_handoffs WHERE user_id=$1", [a]),1);
assert.equal(await scalar("SELECT count(*)::int value FROM user_settings_handoffs WHERE user_id=$1", [b]),10);
await db.query(`INSERT INTO user_settings_handoffs
 SELECT 'active-handoff-' || n, $1, now()+interval '1 minute' FROM generate_series(1,9) n`, [a]);
await as(a);
await assert.rejects(db.query("SELECT mint_user_settings_handoff()"), e => hasCode(e, "54000"));
await db.exec("RESET ROLE");
await db.query("UPDATE user_settings_handoffs SET expires_at=now() WHERE user_id=$1", [a]);
await as(a);
await scalar<{token: string}>("SELECT mint_user_settings_handoff() value");
await db.exec("RESET ROLE");
assert.equal(await scalar("SELECT count(*)::int value FROM user_settings_handoffs WHERE user_id=$1", [a]),1);
assert.equal(await scalar("SELECT count(*)::int value FROM user_settings_handoffs WHERE user_id=$1", [b]),10);
// Maintenance removes only old expired records, in bounded batches, with service-only authority.
await db.exec("RESET ROLE");
await db.exec("DELETE FROM terminal_relay_rooms; DELETE FROM user_settings_handoffs;");
await db.query(`INSERT INTO terminal_relay_rooms
 SELECT md5('cleanup-room-' || n)::uuid, $1,
 CASE WHEN n<=5 THEN now()-interval '10 minutes' WHEN n=6 THEN now()-interval '1 minute' ELSE now()+interval '1 hour' END
 FROM generate_series(1,7) n`, [a]);
await db.query(`INSERT INTO terminal_relay_tickets
 SELECT 'cleanup-ticket-' || n, md5('cleanup-room-7')::uuid, 'account',
 CASE WHEN n<=5 THEN now()-interval '10 minutes' WHEN n=6 THEN now()-interval '1 minute' ELSE now()+interval '1 minute' END
 FROM generate_series(1,7) n`);
await db.query(`INSERT INTO user_settings_handoffs
 SELECT 'cleanup-handoff-' || n, $1,
 CASE WHEN n<=5 THEN now()-interval '10 minutes' WHEN n=6 THEN now()-interval '1 minute' ELSE now()+interval '1 minute' END
 FROM generate_series(1,7) n`, [a]);
await as(a);
await assert.rejects(db.query("SELECT cleanup_expired_terminal_relay_records(2)"), e => hasCode(e, "42501"));
await as("", "service_role");
await assert.rejects(db.query("SELECT cleanup_expired_terminal_relay_records(1001)"), e => hasCode(e, "22023"));
assert.deepEqual(await scalar<{tickets: number; rooms: number; handoffs: number}>("SELECT cleanup_expired_terminal_relay_records(2) value"), {tickets:2,rooms:2,handoffs:2});
assert.deepEqual(await scalar<{tickets: number; rooms: number; handoffs: number}>("SELECT cleanup_expired_terminal_relay_records(100) value"), {tickets:3,rooms:3,handoffs:3});
assert.deepEqual(await scalar<{tickets: number; rooms: number; handoffs: number}>("SELECT cleanup_expired_terminal_relay_records(100) value"), {tickets:0,rooms:0,handoffs:0});
await db.exec("RESET ROLE");
for (const table of ["terminal_relay_rooms", "terminal_relay_tickets", "user_settings_handoffs"]) {
 assert.equal(await scalar(`SELECT count(*)::int value FROM ${table}`), 2, "recently expired and live rows remain");
}
await as("", "anon");
await assert.rejects(db.query("SELECT cleanup_expired_terminal_relay_records()"), e => hasCode(e, "42501"));
await assert.rejects(
  db.query("SELECT get_user_terminal_preferences()"),
  (e) => hasCode(e, "42501"),
);
await db.close();
});
