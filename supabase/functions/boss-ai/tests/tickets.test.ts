import { PGlite } from "npm:@electric-sql/pglite@0.3.14"
import { assertEquals, assertRejects } from "@std/assert"

Deno.test("exchange tickets bind identity, expire, are single-use and service-only", async () => {
  const db = new PGlite()
  const user = "00000000-0000-0000-0000-000000000001"
  const other = "00000000-0000-0000-0000-000000000002"
  try {
    await db.exec(`
      CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role;
      CREATE SCHEMA auth;
      CREATE TABLE auth.users(id uuid PRIMARY KEY, banned_until timestamptz, is_anonymous boolean DEFAULT false);
      CREATE TABLE public.test_permissions(user_id uuid);
      CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql AS
        'SELECT nullif(current_setting(''request.jwt.claim.sub'',true),'''')::uuid';
      CREATE FUNCTION public.user_has_permission(p_user_id uuid,p_permission text) RETURNS boolean LANGUAGE sql AS
        'SELECT EXISTS(SELECT 1 FROM public.test_permissions WHERE user_id=p_user_id)';
      INSERT INTO auth.users(id) VALUES('${user}'),('${other}');
      INSERT INTO public.test_permissions VALUES('${user}'),('${other}');
    `)
    await db.exec(
      await Deno.readTextFile(
        new URL("../../../migrations/20260912004000_boss_ai_exchange_tickets.sql", import.meta.url),
      ),
    )
    const scalar = async (sql: string, params: unknown[] = []) =>
      (await db.query<{ value: any }>(sql, params)).rows[0].value
    const issue = () => scalar("SELECT public.boss_ai_create_exchange_ticket()->>'ticket' AS value")
    const consume = (ticket: string) =>
      scalar("SELECT public.boss_ai_consume_exchange_ticket($1) AS value", [ticket])
    await db.exec("SET ROLE authenticated")
    await assertRejects(issue)
    await db.query("SELECT set_config('request.jwt.claim.sub',$1,false)", [user])
    const ticket = await issue()
    assertEquals(ticket.length, 64)
    await assertRejects(() => db.query("SELECT * FROM public.boss_ai_exchange_tickets"))
    await assertRejects(() => consume(ticket))
    await db.exec("RESET ROLE")
    assertEquals(
      await scalar("SELECT count(*)::int AS value FROM public.boss_ai_exchange_tickets"),
      1,
    )
    await db.exec("SET ROLE service_role")
    assertEquals(await consume(ticket), user)
    assertEquals(await consume(ticket), null)
    assertEquals(await consume("0".repeat(64)), null)
    await db.exec("RESET ROLE")

    const expired = await issue()
    await db.exec("UPDATE public.boss_ai_exchange_tickets SET expires_at=now()-interval '1 second'")
    assertEquals(await consume(expired), null)
    const revoked = await issue()
    await db.exec(`DELETE FROM public.test_permissions WHERE user_id='${user}'`)
    assertEquals(await consume(revoked), null)
    await assertRejects(issue)
    await db.exec(`INSERT INTO public.test_permissions VALUES('${user}')`)
    const banned = await issue()
    await db.exec(`UPDATE auth.users SET banned_until=now()+interval '1 hour' WHERE id='${user}'`)
    assertEquals(await consume(banned), null)
    await assertRejects(issue)
    await db.exec(`UPDATE auth.users SET banned_until=NULL, is_anonymous=true WHERE id='${user}'`)
    await assertRejects(issue)
    await db.exec(`UPDATE auth.users SET is_anonymous=false WHERE id='${user}'`)
    for (let i = 0; i < 8; i++) await issue()
    await assertRejects(issue)
    await db.query("SELECT set_config('request.jwt.claim.sub',$1,false)", [other])
    assertEquals(await consume(await issue()), other)
    await db.exec("SET ROLE anon")
    await assertRejects(issue)
    await assertRejects(() => consume(ticket))
  } finally {
    await db.close()
  }
})
