import { PGlite } from "npm:@electric-sql/pglite@0.3.14"
import { assertEquals, assertRejects } from "@std/assert"

// Runs the real migration in PostgreSQL/WASM; only the surrounding auth/RBAC
// schema is a fixture. No Supabase project or Docker daemon is required.
Deno.test("database policy, reservations, settlement, revocation and grants", async () => {
  const db = new PGlite()
  try {
    await db.exec(`
      CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role;
      CREATE SCHEMA auth;
      CREATE TABLE auth.users(id uuid PRIMARY KEY, banned_until timestamptz);
      CREATE TABLE public.permissions(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text UNIQUE, description text, is_system boolean);
      CREATE TABLE public.roles(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text UNIQUE);
      CREATE TABLE public.role_permissions(role_id uuid, permission_id uuid, UNIQUE(role_id,permission_id));
      CREATE TABLE public.test_user_permissions(user_id uuid, permission_name text);
      CREATE FUNCTION public.user_has_permission(p_user_id uuid,p_permission text) RETURNS boolean LANGUAGE sql AS
        'SELECT EXISTS(SELECT 1 FROM public.test_user_permissions WHERE user_id=p_user_id AND permission_name=p_permission)';
      INSERT INTO public.roles(name) VALUES('user');
    `)
    await db.exec(
      await Deno.readTextFile(
        new URL("../../../migrations/20260912000000_boss_ai.sql", import.meta.url),
      ),
    )
    await db.exec(
      await Deno.readTextFile(
        new URL("../../../migrations/20260912001000_boss_ai_hardening.sql", import.meta.url),
      ),
    )
    const user = "00000000-0000-0000-0000-000000000001"
    await db.exec(
      await Deno.readTextFile(
        new URL("../../../migrations/20260912002000_boss_ai_validation.sql", import.meta.url),
      ),
    )
    const other = "00000000-0000-0000-0000-000000000002"
    await db.exec(
      await Deno.readTextFile(
        new URL(
          "../../../migrations/20260912003000_boss_ai_allowance_preflight.sql",
          import.meta.url,
        ),
      ),
    )
    await db.exec(`
      INSERT INTO auth.users(id) VALUES('${user}'),('${other}');
      INSERT INTO public.permissions(name) VALUES('ai.extended');
      INSERT INTO public.test_user_permissions VALUES('${user}','ai.use'),('${user}','ai.extended'),('${other}','ai.use');
      INSERT INTO public.boss_ai_connections VALUES('test','https://example.com/v1','BOSS_AI_TEST','openai_chat',true);
      INSERT INTO public.boss_ai_models(id,display_name,connection_id,upstream_model,context_length,max_output_tokens,published,is_default)
        VALUES('test','Test','test','private',1024,128,true,true);
      INSERT INTO public.boss_ai_allowances VALUES('test','ai.use',2048,4096,8192,2),('test','ai.extended',4096,8192,16384,3);
    `)
    const scalar = async (sql: string, params: unknown[] = []) =>
      (await db.query<{ value: any }>(sql, params)).rows[0].value
    const policy = () => scalar("SELECT public.boss_ai_policy($1,'test') AS value", [user])
    assertEquals(await policy(), { day: 4096, week: 8192, month: 16384, concurrent: 3 })
    await db.exec(
      "BEGIN; UPDATE public.boss_ai_allowances SET tokens_per_day=1 WHERE model_id='test'",
    )
    assertEquals(
      await scalar("SELECT public.boss_ai_lookup($1,'test')->>'error' AS value", [user]),
      "misconfigured_allowance",
    )
    assertEquals(await scalar("SELECT count(*)::int AS value FROM public.boss_ai_requests"), 0)
    await db.exec("ROLLBACK")
    const reserve = (id: string, who = user) =>
      scalar("SELECT public.boss_ai_reserve($1,'test',$2) AS value", [who, id])
    const ids = Array.from({ length: 5 }, () => crypto.randomUUID())
    const first = await reserve(ids[0])
    assertEquals(first.model.upstream_model, "private")
    assertEquals((await reserve(ids[0])).error, "duplicate")
    await reserve(ids[1])
    await reserve(ids[2])
    assertEquals((await reserve(ids[3])).error, "concurrency_exceeded")
    // Unknown outcome remains fully charged. Settlement is idempotent.
    await db.query("SELECT public.boss_ai_settle($1,NULL)", [ids[0]])
    await db.query("SELECT public.boss_ai_settle($1,0)", [ids[0]])
    assertEquals(
      await scalar("SELECT charged_tokens AS value FROM public.boss_ai_requests WHERE id=$1", [
        ids[0],
      ]),
      1024,
    )
    await reserve(ids[3])
    assertEquals((await reserve(ids[4])).error, "allowance_exceeded")
    // Another user has a separate ledger.
    assertEquals((await reserve(crypto.randomUUID(), other)).error, undefined)
    // Release excess based on actual upstream accounting.
    await db.query("SELECT public.boss_ai_settle($1,10)", [ids[1]])
    const u = await scalar("SELECT public.boss_ai_usage($1,'test') AS value", [user])
    assertEquals(u.day.used, 3082)
    assertEquals(u.week.used, 3082)
    assertEquals(u.day.remaining, 1014)
    // Dropping a permission changes the allowance immediately, without resetting usage.
    await db.query(
      "DELETE FROM public.test_user_permissions WHERE user_id=$1 AND permission_name='ai.extended'",
      [user],
    )
    assertEquals((await policy()).day, 2048)
    assertEquals((await reserve(ids[4])).error, "allowance_exceeded")
    await db.query("DELETE FROM public.test_user_permissions WHERE user_id=$1", [user])
    assertEquals(await policy(), null)
    assertEquals(await scalar("SELECT public.boss_ai_catalog($1) AS value", [user]), [])
    assertEquals((await reserve(ids[4])).error, "forbidden")
    await db.exec("UPDATE public.boss_ai_models SET published=false")
    assertEquals((await reserve(crypto.randomUUID(), other)).error, "forbidden")
    // No anonymous or authenticated RPC/table access to a service-role impersonation API.
    await db.exec("SET ROLE authenticated")
    await assertRejects(() => db.query("SELECT * FROM public.boss_ai_connections"))
    await assertRejects(() => db.query("SELECT public.boss_ai_catalog($1)", [other]))
    await assertRejects(() => db.query("SELECT public.boss_ai_settle($1,0)", [ids[2]]))
    await db.exec("RESET ROLE")
  } finally {
    await db.close()
  }
})
