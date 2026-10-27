import { PGlite } from "npm:@electric-sql/pglite@0.3.14"
import { assert, assertEquals, assertRejects } from "@std/assert"

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

Deno.test("voice: pooled seconds, per-kind reservation, catalog kind and pool guard", async () => {
  const db = new PGlite()
  try {
    await db.exec(`
      CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role;
      CREATE SCHEMA auth;
      CREATE TABLE auth.users(id uuid PRIMARY KEY, banned_until timestamptz, is_anonymous boolean NOT NULL DEFAULT false);
      CREATE TABLE public.permissions(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text UNIQUE, description text, is_system boolean);
      CREATE TABLE public.roles(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text UNIQUE);
      CREATE TABLE public.role_permissions(role_id uuid, permission_id uuid, UNIQUE(role_id,permission_id));
      CREATE TABLE public.test_user_permissions(user_id uuid, permission_name text);
      CREATE FUNCTION public.user_has_permission(p_user_id uuid,p_permission text) RETURNS boolean LANGUAGE sql AS
        'SELECT EXISTS(SELECT 1 FROM public.test_user_permissions WHERE user_id=p_user_id AND permission_name=p_permission)';
      INSERT INTO public.roles(name) VALUES('user');
    `)
    for (
      const file of [
        "20260912000000_boss_ai.sql",
        "20260912001000_boss_ai_hardening.sql",
        "20260912002000_boss_ai_validation.sql",
        "20260912003000_boss_ai_allowance_preflight.sql",
        "20260912004000_boss_ai_exchange_tickets.sql",
        "20260924231000_boss_ai_token_eligibility.sql",
        "20261007000000_boss_ai_optimist_permission.sql",
        "20261008000000_boss_ai_model_provider_routing.sql",
        "20261008100000_boss_ai_reserve_ai_grants.sql",
        "20261015000000_boss_ai_voice.sql",
        "20261023090000_boss_ai_voice_admission.sql",
      ]
    ) {
      await db.exec(
        await Deno.readTextFile(new URL(`../../../migrations/${file}`, import.meta.url)),
      )
    }
    const user = "00000000-0000-0000-0000-000000000001"
    await db.exec(`
      INSERT INTO auth.users(id) VALUES('${user}');
      INSERT INTO public.test_user_permissions VALUES('${user}','ai.use');
      INSERT INTO public.boss_ai_connections VALUES('c','https://example.com/v1','BOSS_AI_TEST','openai_chat',true);
      INSERT INTO public.boss_ai_models(id,display_name,connection_id,upstream_model,kind,voice,context_length,max_output_tokens,published)
        VALUES('max-stt','Max STT','c','stt','transcription',NULL,1024,120,true),
              ('max-tts','Max TTS','c','tts','speech','sarah',1024,60,true),
              ('chat','Chat','c','chat','chat',NULL,1024,128,true);
      UPDATE public.boss_ai_models SET allowance_model_id='max-stt' WHERE id='max-tts';
      INSERT INTO public.boss_ai_allowances VALUES('max-stt','ai.use',400,400,400,2),('chat','ai.use',5000,5000,5000,2);
    `)
    const scalar = async (sql: string, params: unknown[] = []) =>
      (await db.query<{ value: any }>(sql, params)).rows[0].value
    const claim = () => scalar("SELECT public.boss_ai_claim_voice_start($1) AS value", [user])
    assertEquals(await claim(), true)
    assertEquals(await claim(), false)
    await db.exec(
      "UPDATE public.boss_ai_voice_starts SET started_at=clock_timestamp()-interval '31 seconds'",
    )
    assertEquals(await claim(), true)
    await db.exec("SET ROLE authenticated")
    await assertRejects(() => claim())
    await assertRejects(() => db.query("SELECT * FROM public.boss_ai_voice_starts"))
    await db.exec("RESET ROLE")
    await db.exec("DELETE FROM public.test_user_permissions")
    await db.exec(
      "UPDATE public.boss_ai_voice_starts SET started_at=clock_timestamp()-interval '31 seconds'",
    )
    assertEquals(await claim(), false)
    await db.exec(`INSERT INTO public.test_user_permissions VALUES('${user}','ai.use')`)
    const reserve = (model: string, id = crypto.randomUUID()) =>
      scalar("SELECT public.boss_ai_reserve($1,$2,$3) AS value", [user, model, id]).then((
        r,
      ) => ({ id, ...r }))
    const usage = (model: string) =>
      scalar("SELECT public.boss_ai_usage($1,$2) AS value", [user, model])
    const charged = (id: string) =>
      scalar("SELECT reserved_tokens::int AS value FROM public.boss_ai_requests WHERE id=$1", [id])

    // Audio reserves max_output_tokens, not context_length; the TTS draws from the STT pool.
    const tts = await reserve("max-tts")
    assertEquals(tts.model.voice, "sarah")
    assertEquals(await charged(tts.id), 60)
    await db.query("SELECT public.boss_ai_settle($1,5)", [tts.id])
    const stt = await reserve("max-stt")
    assertEquals(await charged(stt.id), 120)
    await db.query("SELECT public.boss_ai_settle($1,30)", [stt.id])
    for (const model of ["max-stt", "max-tts"]) {
      assertEquals((await usage(model)).month.used, 35)
      assertEquals((await usage(model)).month.limit, 400)
    }
    // The ledger keeps the actual model.
    assertEquals(
      await scalar(
        "SELECT string_agg(model_id,',' ORDER BY model_id) AS value FROM public.boss_ai_requests",
      ),
      "max-stt,max-tts",
    )
    // A chat model's pool is separate.
    assertEquals((await usage("chat")).month.used, 0)
    // Pool concurrency applies across both models.
    const a = await reserve("max-stt")
    const b = await reserve("max-tts")
    assertEquals((await reserve("max-tts")).error, "concurrency_exceeded")
    // Settlement is capped at the reservation: 35 + 120 + 60 = 215 used.
    await db.query("SELECT public.boss_ai_settle($1,1000)", [a.id])
    await db.query("SELECT public.boss_ai_settle($1,1000)", [b.id])
    for (const model of ["max-stt", "max-tts"]) {
      const next = await reserve(model)
      await db.query("SELECT public.boss_ai_settle($1,1000)", [next.id])
    }
    // 5 s left: a 60 s reservation exceeds it and carries usage for the voice UI.
    const denied = await reserve("max-tts")
    assertEquals(denied.error, "allowance_exceeded")
    assertEquals(denied.usage.month, {
      limit: 400,
      used: 395,
      remaining: 5,
      resets_at: denied.usage.month.resets_at,
    })
    const exceeded = await reserve("max-stt")
    assertEquals(exceeded.error, "allowance_exceeded")
    // Preflight compares the audio allowance with max_output_tokens, not context (1024).
    assertEquals(
      await scalar("SELECT public.boss_ai_lookup($1,'max-tts')->>'error' AS value", [user]),
      null,
    )
    await db.exec(
      "UPDATE public.boss_ai_allowances SET tokens_per_day=50 WHERE model_id='max-stt'",
    )
    assertEquals(
      await scalar("SELECT public.boss_ai_lookup($1,'max-tts')->>'error' AS value", [user]),
      "misconfigured_allowance",
    )
    await db.exec(
      "UPDATE public.boss_ai_allowances SET tokens_per_day=400 WHERE model_id='max-stt'",
    )
    // Catalog carries kind and the pool's allowance.
    const catalog = await scalar("SELECT public.boss_ai_catalog($1) AS value", [user])
    const kinds = Object.fromEntries(catalog.map((m: any) => [m.id, m.kind]))
    assertEquals(kinds, { chat: "chat", "max-stt": "transcription", "max-tts": "speech" })
    assertEquals(
      catalog.find((m: any) => m.id === "max-tts").allowance.month.used,
      395,
    )
    // Pools are one level: no self, chains, or re-pooling a target.
    for (
      const sql of [
        "UPDATE public.boss_ai_models SET allowance_model_id='max-tts' WHERE id='chat'",
        "UPDATE public.boss_ai_models SET allowance_model_id='max-stt' WHERE id='max-stt'",
        "UPDATE public.boss_ai_models SET allowance_model_id='chat' WHERE id='max-stt'",
        "UPDATE public.boss_ai_models SET allowance_model_id='nope' WHERE id='chat'",
      ]
    ) await assertRejects(() => db.query(sql))
    // A pooled model is forbidden when it is itself unpublished, even if the pool is live.
    await db.exec("UPDATE public.boss_ai_models SET published=false WHERE id='max-tts'")
    assertEquals((await reserve("max-tts")).error, "forbidden")
    await db.exec("RESET ROLE; SET ROLE authenticated")
    await assertRejects(() => db.query("SELECT public.boss_ai_lookup($1,'max-tts')", [user]))
    await assertRejects(() => db.query("SELECT public.boss_ai_usage($1,'max-tts')", [user]))
    await db.exec("RESET ROLE")
  } finally {
    await db.close()
  }
})

Deno.test("fallbacks: validated shape, existing connections, resolved by lookup and reserve", async () => {
  const db = new PGlite()
  try {
    await db.exec(`
      CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role;
      CREATE SCHEMA auth;
      CREATE TABLE auth.users(id uuid PRIMARY KEY, banned_until timestamptz, is_anonymous boolean NOT NULL DEFAULT false);
      CREATE TABLE public.permissions(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text UNIQUE, description text, is_system boolean);
      CREATE TABLE public.roles(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text UNIQUE);
      CREATE TABLE public.role_permissions(role_id uuid, permission_id uuid, UNIQUE(role_id,permission_id));
      CREATE TABLE public.test_user_permissions(user_id uuid, permission_name text);
      CREATE FUNCTION public.user_has_permission(p_user_id uuid,p_permission text) RETURNS boolean LANGUAGE sql AS
        'SELECT EXISTS(SELECT 1 FROM public.test_user_permissions WHERE user_id=p_user_id AND permission_name=p_permission)';
      INSERT INTO public.roles(name) VALUES('user');
    `)
    for (
      const file of [
        "20260912000000_boss_ai.sql",
        "20260912001000_boss_ai_hardening.sql",
        "20260912002000_boss_ai_validation.sql",
        "20260912003000_boss_ai_allowance_preflight.sql",
        "20260912004000_boss_ai_exchange_tickets.sql",
        "20260924231000_boss_ai_token_eligibility.sql",
        "20261007000000_boss_ai_optimist_permission.sql",
        "20261008000000_boss_ai_model_provider_routing.sql",
        "20261008100000_boss_ai_reserve_ai_grants.sql",
        "20261015000000_boss_ai_voice.sql",
        "20261023090000_boss_ai_voice_admission.sql",
        "20261027120000_boss_ai_anthropic_fallbacks.sql",
      ]
    ) {
      await db.exec(
        await Deno.readTextFile(new URL(`../../../migrations/${file}`, import.meta.url)),
      )
    }
    const user = "00000000-0000-0000-0000-000000000001"
    await db.exec(`
      INSERT INTO auth.users(id) VALUES('${user}');
      INSERT INTO public.test_user_permissions VALUES('${user}','ai.use');
      INSERT INTO public.boss_ai_connections VALUES
        ('anthropic','https://api.anthropic.com/v1','BOSS_AI_ANTHROPIC','anthropic_messages',true),
        ('or','https://openrouter.ai/api/v1','BOSS_AI_OR','openai_chat',true),
        ('off','https://example.com/v1','BOSS_AI_OFF','openai_chat',false);
      INSERT INTO public.boss_ai_models(id,display_name,connection_id,upstream_model,context_length,max_output_tokens,published)
        VALUES('m','M','anthropic','claude-opus-5-5',1024,128,true);
      INSERT INTO public.boss_ai_allowances VALUES('m','ai.use',5000,5000,5000,2);
    `)
    const scalar = async (sql: string, params: unknown[] = []) =>
      (await db.query<{ value: any }>(sql, params)).rows[0].value
    const set = (value: string) =>
      db.query("UPDATE public.boss_ai_models SET fallbacks=$1::jsonb WHERE id='m'", [value])
    assertEquals(
      await scalar("SELECT fallbacks AS value FROM public.boss_ai_models WHERE id='m'"),
      [],
    )
    await assertRejects(() =>
      db.query(
        "INSERT INTO public.boss_ai_connections VALUES('x','https://x.example/v1','BOSS_AI_X','nope',true)",
      )
    )
    const entry = (extra = "") =>
      `{"connection_id":"or","upstream_model":"anthropic/claude-opus-5.5"${extra}}`
    await set(
      `[${
        entry(',"provider_routing":{"order":["anthropic"],"allow_fallbacks":true}')
      },{"connection_id":"off","upstream_model":"x"},{"connection_id":"or","upstream_model":"anthropic/claude-haiku-5.5"}]`,
    )
    const lookup = await scalar("SELECT public.boss_ai_lookup($1,'m') AS value", [user])
    // Disabled connections are dropped; order and per-entry routing survive; no key material.
    assertEquals(lookup.fallbacks.map((f: any) => f.upstream_model), [
      "anthropic/claude-opus-5.5",
      "anthropic/claude-haiku-5.5",
    ])
    assertEquals(lookup.fallbacks[0].provider_routing, {
      order: ["anthropic"],
      allow_fallbacks: true,
    })
    assertEquals(lookup.fallbacks[0].connection.api_type, "openai_chat")
    assertEquals(lookup.fallbacks[0].connection.api_key_secret, "BOSS_AI_OR")
    assertEquals(lookup.connection.api_type, "anthropic_messages")
    const reserved = await scalar("SELECT public.boss_ai_reserve($1,'m',$2) AS value", [
      user,
      crypto.randomUUID(),
    ])
    assertEquals(reserved.fallbacks.length, 2)
    for (
      const bad of [
        "{}",
        "null",
        `[${entry()},${entry()},${entry()},${entry()},${entry()}]`,
        `[${entry(',"extra":1')}]`,
        '[{"connection_id":"or"}]',
        '[{"upstream_model":"x"}]',
        '[{"connection_id":"","upstream_model":"x"}]',
        '[{"connection_id":1,"upstream_model":"x"}]',
        '[{"connection_id":"or","upstream_model":""}]',
        "[1]",
        `[${entry(',"provider_routing":{"nope":true}')}]`,
        `[${entry(',"provider_routing":null')}]`,
        '[{"connection_id":"missing","upstream_model":"x"}]',
      ]
    ) await assertRejects(() => set(bad))
    await set(`[${entry()},${entry()},${entry()},${entry()}]`)
    await assertRejects(() => db.query("DELETE FROM public.boss_ai_connections WHERE id='or'"))
    await assertRejects(() =>
      db.query("UPDATE public.boss_ai_connections SET id='or2' WHERE id='or'")
    )
    await set("[]")
    await db.query("DELETE FROM public.boss_ai_connections WHERE id='off'")
    await db.exec("SET ROLE authenticated")
    await assertRejects(() => db.query("SELECT public.boss_ai_resolve_fallbacks('[]')"))
    await db.exec("RESET ROLE")
  } finally {
    await db.close()
  }
})

Deno.test("user quotas: overrides replace limits, downgrade chain serves the target", async () => {
  const db = new PGlite()
  try {
    await db.exec(`
      CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role;
      CREATE SCHEMA auth;
      CREATE TABLE auth.users(id uuid PRIMARY KEY, banned_until timestamptz, is_anonymous boolean NOT NULL DEFAULT false);
      CREATE TABLE public.permissions(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text UNIQUE, description text, is_system boolean);
      CREATE TABLE public.roles(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text UNIQUE);
      CREATE TABLE public.role_permissions(role_id uuid, permission_id uuid, UNIQUE(role_id,permission_id));
      CREATE TABLE public.test_user_permissions(user_id uuid, permission_name text);
      CREATE FUNCTION public.user_has_permission(p_user_id uuid,p_permission text) RETURNS boolean LANGUAGE sql AS
        'SELECT EXISTS(SELECT 1 FROM public.test_user_permissions WHERE user_id=p_user_id AND permission_name=p_permission)';
      INSERT INTO public.roles(name) VALUES('user');
    `)
    for (
      const file of [
        "20260912000000_boss_ai.sql",
        "20260912001000_boss_ai_hardening.sql",
        "20260912002000_boss_ai_validation.sql",
        "20260912003000_boss_ai_allowance_preflight.sql",
        "20260912004000_boss_ai_exchange_tickets.sql",
        "20260924231000_boss_ai_token_eligibility.sql",
        "20261007000000_boss_ai_optimist_permission.sql",
        "20261008000000_boss_ai_model_provider_routing.sql",
        "20261008100000_boss_ai_reserve_ai_grants.sql",
        "20261015000000_boss_ai_voice.sql",
        "20261023090000_boss_ai_voice_admission.sql",
        "20261027120000_boss_ai_anthropic_fallbacks.sql",
        "20261027130000_boss_ai_user_quota_downgrade.sql",
      ]
    ) {
      await db.exec(
        await Deno.readTextFile(new URL(`../../../migrations/${file}`, import.meta.url)),
      )
    }
    const u1 = "00000000-0000-0000-0000-000000000001"
    const u2 = "00000000-0000-0000-0000-000000000002"
    const u3 = "00000000-0000-0000-0000-000000000003"
    await db.exec(`
      INSERT INTO auth.users(id) VALUES('${u1}'),('${u2}'),('${u3}');
      INSERT INTO public.permissions(name) VALUES('ai.lite');
      INSERT INTO public.test_user_permissions VALUES('${u1}','ai.use'),('${u1}','ai.lite'),('${u2}','ai.use'),('${u3}','ai.lite');
      INSERT INTO public.boss_ai_connections VALUES('c','https://example.com/v1','BOSS_AI_TEST','openai_chat',true);
      INSERT INTO public.boss_ai_models(id,display_name,connection_id,upstream_model,kind,context_length,max_output_tokens,published) VALUES
        ('big','Big','c','big','chat',1024,100,true),
        ('mid','Mid','c','mid','chat',1024,100,true),
        ('lite','Lite','c','lite','chat',1024,100,true),
        ('tiny','Tiny','c','tiny','chat',1024,100,true),
        ('voice','Voice','c','v','speech',1024,10,true);
      INSERT INTO public.boss_ai_allowances VALUES
        ('big','ai.use',2048,2048,2048,2),
        ('mid','ai.use',100000,100000,100000,2),
        ('lite','ai.lite',100000,100000,100000,2),
        ('tiny','ai.use',100000,100000,100000,2);
    `)
    const scalar = async (sql: string, params: unknown[] = []) =>
      (await db.query<{ value: any }>(sql, params)).rows[0].value
    const policy = (u: string, m = "big") =>
      scalar("SELECT public.boss_ai_policy($1,$2) AS value", [u, m])
    const reserve = (u: string, m = "big") =>
      scalar("SELECT public.boss_ai_reserve($1,$2,$3) AS value", [u, m, crypto.randomUUID()])
    const serve = async (u: string, m = "big") => {
      const id = crypto.randomUUID()
      const r = await scalar("SELECT public.boss_ai_reserve($1,$2,$3) AS value", [u, m, id])
      return { r, id }
    }
    const row = (id: string) =>
      scalar("SELECT model_id AS value FROM public.boss_ai_requests WHERE id=$1", [id])

    // Overrides replace per field, can raise or lower, and null falls through.
    assertEquals(await policy(u1), { day: 2048, week: 2048, month: 2048, concurrent: 2 })
    await db.exec(
      `INSERT INTO public.boss_ai_user_allowances(user_id,model_id,tokens_per_month,max_concurrent)
      VALUES('${u1}','big',50000,7)`,
    )
    assertEquals(await policy(u1), { day: 2048, week: 2048, month: 50000, concurrent: 7 })
    await db.exec(
      `UPDATE public.boss_ai_user_allowances SET tokens_per_month=500, max_concurrent=NULL`,
    )
    assertEquals(await policy(u1), { day: 2048, week: 2048, month: 500, concurrent: 2 })
    // An override never grants access: u3 has no permission for big.
    await db.exec(`INSERT INTO public.boss_ai_user_allowances(user_id,model_id,tokens_per_day)
      VALUES('${u3}','big',999999)`)
    assertEquals(await policy(u3), null)
    assertEquals((await reserve(u3)).error, "forbidden")
    assertEquals(await scalar("SELECT public.boss_ai_lookup($1,'big') AS value", [u3]), null)
    // Per-user data stays per user.
    assertEquals((await policy(u2)).month, 2048)
    // Checks and no client access.
    await assertRejects(() =>
      db.exec(
        `INSERT INTO public.boss_ai_user_allowances(user_id,model_id,tokens_per_day) VALUES('${u2}','big',0)`,
      )
    )
    await assertRejects(() =>
      db.exec(
        `INSERT INTO public.boss_ai_user_allowances(user_id,model_id,max_concurrent) VALUES('${u2}','big',101)`,
      )
    )
    await assertRejects(() =>
      db.exec(
        `INSERT INTO public.boss_ai_user_allowances(user_id,model_id,over_allowance_model_id) VALUES('${u2}','big','big')`,
      )
    )
    await db.exec("SET ROLE authenticated")
    await assertRejects(() => db.query("SELECT * FROM public.boss_ai_user_allowances"))
    await assertRejects(() => db.query("SELECT public.boss_ai_next_model($1,'big')", [u1]))
    await db.exec("RESET ROLE")
    await db.exec(`DELETE FROM public.boss_ai_user_allowances WHERE user_id='${u3}'`)

    // Exhausted with no target: the existing allowance_exceeded; nothing is charged.
    const a = await serve(u1)
    assertEquals(a.r.error, "allowance_exceeded")
    assertEquals(await scalar("SELECT count(*)::int AS value FROM public.boss_ai_requests"), 0)
    await db.exec(`DELETE FROM public.boss_ai_user_allowances WHERE user_id='${u1}'`)

    // One hop: big -> mid. First request fits big; the next two exhaust it and are served by mid.
    await db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id='mid' WHERE id='big'")
    const first = await serve(u1)
    assertEquals(first.r.model.id, "big")
    assertEquals(first.r.downgraded_from, undefined)
    await db.query("SELECT public.boss_ai_settle($1,1024)", [first.id])
    const second = await serve(u1)
    assertEquals(second.r.model.id, "big")
    await db.query("SELECT public.boss_ai_settle($1,1024)", [second.id])
    const down = await serve(u1)
    assertEquals(down.r.model.id, "mid")
    assertEquals(down.r.downgraded_from, "big")
    assertEquals(down.r.model.upstream_model, "mid")
    assertEquals(down.r.connection.api_type, "openai_chat")
    assert(Array.isArray(down.r.fallbacks))
    // The ledger row and usage belong to the served model; settlement charges the target.
    assertEquals(await row(down.id), "mid")
    await db.query("SELECT public.boss_ai_settle($1,40)", [down.id])
    assertEquals(
      (await scalar("SELECT public.boss_ai_usage($1,'mid') AS value", [u1])).day.used,
      40,
    )
    assertEquals(
      (await scalar("SELECT public.boss_ai_usage($1,'big') AS value", [u1])).day.used,
      2048,
    )
    // Duplicate and concurrency semantics survive on the target.
    assertEquals(
      (await scalar("SELECT public.boss_ai_reserve($1,'big',$2) AS value", [u1, down.id])).error,
      "duplicate",
    )
    await serve(u1)
    await serve(u1)
    assertEquals((await reserve(u1)).error, "concurrency_exceeded")
    await db.exec("DELETE FROM public.boss_ai_requests WHERE NOT settled")
    // Another user is unaffected by u1's usage.
    assertEquals((await serve(u2)).r.model.id, "big")
    await db.exec("DELETE FROM public.boss_ai_requests WHERE user_id = '" + u2 + "'")

    // Catalog and usage: effective target and downgraded state with resets_at, own data only.
    const cat = (await scalar("SELECT public.boss_ai_catalog($1) AS value", [u1])).find((m: any) =>
      m.id === "big"
    )
    assertEquals(cat.over_allowance_model_id, "mid")
    assertEquals(cat.downgraded, true)
    assert(typeof cat.downgrade_resets_at === "string")
    const mid = (await scalar("SELECT public.boss_ai_catalog($1) AS value", [u1])).find((m: any) =>
      m.id === "mid"
    )
    assertEquals([mid.over_allowance_model_id, mid.downgraded, mid.downgrade_resets_at], [
      null,
      false,
      null,
    ])
    const other = (await scalar("SELECT public.boss_ai_catalog($1) AS value", [u2])).find((
      m: any,
    ) => m.id === "big")
    assertEquals(other.downgraded, false)

    // Two hops: mid -> lite requires ai.lite; a user without it gets allowance_exceeded.
    await db.exec(
      `UPDATE public.boss_ai_allowances SET tokens_per_day=1024,tokens_per_week=1024,tokens_per_month=1024 WHERE model_id='mid'`,
    )
    await db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id='lite' WHERE id='mid'")
    const hop2 = await serve(u1)
    assertEquals([hop2.r.model.id, hop2.r.downgraded_from], ["lite", "big"])
    assertEquals(await row(hop2.id), "lite")
    const noPerm = await serve(u2)
    assertEquals(noPerm.r.model.id, "big")
    await db.exec(
      `INSERT INTO public.boss_ai_requests(id,user_id,model_id,reserved_tokens,charged_tokens)
      VALUES(gen_random_uuid(),'${u2}','big',2048,2048)`,
    )
    // u2: big exhausted -> mid (permitted, 1024 fits one request) ...
    assertEquals((await serve(u2)).r.model.id, "mid")
    await db.exec(
      `INSERT INTO public.boss_ai_requests(id,user_id,model_id,reserved_tokens,charged_tokens)
      VALUES(gen_random_uuid(),'${u2}','mid',1024,1024)`,
    )
    // ... then mid exhausted -> lite, which u2 may not use: allowance_exceeded with big's usage.
    const stop = await serve(u2)
    assertEquals(stop.r.error, "allowance_exceeded")
    assertEquals(stop.r.usage.day.limit, 2048)
    // Target also exhausted and no further target.
    await db.exec(
      `INSERT INTO public.boss_ai_requests(id,user_id,model_id,reserved_tokens,charged_tokens)
      VALUES(gen_random_uuid(),'${u1}','lite',100000,100000)`,
    )
    assertEquals((await serve(u1)).r.error, "allowance_exceeded")

    // A per-user target overrides the model's; a per-user lowered limit forces a downgrade.
    await db.exec("DELETE FROM public.boss_ai_requests")
    await db.exec(
      `INSERT INTO public.boss_ai_user_allowances(user_id,model_id,tokens_per_day,over_allowance_model_id)
      VALUES('${u2}','big',10,'tiny')`,
    )
    assertEquals(
      await scalar("SELECT public.boss_ai_lookup($1,'big')->>'error' AS value", [u2]),
      null,
    )
    const forced = await serve(u2)
    assertEquals([forced.r.model.id, forced.r.downgraded_from], ["tiny", "big"])
    // Without any target a limit below one request stays a configuration fault.
    await db.exec(
      `UPDATE public.boss_ai_models SET over_allowance_model_id=NULL WHERE id IN ('big','mid')`,
    )
    assertEquals(
      await scalar("SELECT public.boss_ai_lookup($1,'big')->>'error' AS value", [u1]),
      null,
    )
    await db.exec(
      `INSERT INTO public.boss_ai_user_allowances(user_id,model_id,tokens_per_day) VALUES('${u1}','big',10)`,
    )
    assertEquals(
      await scalar("SELECT public.boss_ai_lookup($1,'big')->>'error' AS value", [u1]),
      "misconfigured_allowance",
    )
    await db.exec(`DELETE FROM public.boss_ai_user_allowances WHERE user_id='${u1}'`)

    // Cycles, depth and kind mismatches are refused at write time, model-level and user-level.
    await db.exec(`DELETE FROM public.boss_ai_user_allowances`)
    await db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id='mid' WHERE id='big'")
    await db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id='lite' WHERE id='mid'")
    await assertRejects(() =>
      db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id='big' WHERE id='lite'")
    )
    await assertRejects(() =>
      db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id='mid' WHERE id='lite'")
    )
    await db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id='tiny' WHERE id='lite'")
    // big -> mid -> lite -> tiny is 3 hops: allowed. A fourth is not.
    await assertRejects(
      () =>
        db.exec(
          "INSERT INTO public.boss_ai_models(id,display_name,connection_id,upstream_model,context_length,max_output_tokens,over_allowance_model_id) VALUES('x','X','c','x',1024,10,'big')",
        ),
      undefined,
    )
    await db.exec(
      "INSERT INTO public.boss_ai_models(id,display_name,connection_id,upstream_model,context_length,max_output_tokens) VALUES('x','X','c','x',1024,10)",
    )
    await assertRejects(() =>
      db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id='x' WHERE id='tiny'")
    )
    await db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id=NULL WHERE id='lite'")
    // User-level overrides combine with model-level chains.
    await assertRejects(() =>
      db.exec(
        `INSERT INTO public.boss_ai_user_allowances(user_id,model_id,over_allowance_model_id) VALUES('${u1}','lite','big')`,
      )
    )
    await db.exec(
      `INSERT INTO public.boss_ai_user_allowances(user_id,model_id,over_allowance_model_id) VALUES('${u1}','tiny','x')`,
    )
    // That makes big -> mid -> lite is fine, but a model-level edge closing a loop for u1 is caught.
    await assertRejects(() =>
      db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id='big' WHERE id='x'")
    )
    await assertRejects(() =>
      db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id='voice' WHERE id='big'")
    )
    // Deleting a user removes their overrides.
    await db.exec(`DELETE FROM auth.users WHERE id='${u1}'`)
    assertEquals(
      await scalar("SELECT count(*)::int AS value FROM public.boss_ai_user_allowances"),
      0,
    )
  } finally {
    await db.close()
  }
})

Deno.test("user quotas: downgrade serves from the target's own pool", async () => {
  const db = new PGlite()
  try {
    await db.exec(`
      CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role;
      CREATE SCHEMA auth;
      CREATE TABLE auth.users(id uuid PRIMARY KEY, banned_until timestamptz, is_anonymous boolean NOT NULL DEFAULT false);
      CREATE TABLE public.permissions(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text UNIQUE, description text, is_system boolean);
      CREATE TABLE public.roles(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), name text UNIQUE);
      CREATE TABLE public.role_permissions(role_id uuid, permission_id uuid, UNIQUE(role_id,permission_id));
      CREATE TABLE public.test_user_permissions(user_id uuid, permission_name text);
      CREATE FUNCTION public.user_has_permission(p_user_id uuid,p_permission text) RETURNS boolean LANGUAGE sql AS
        'SELECT EXISTS(SELECT 1 FROM public.test_user_permissions WHERE user_id=p_user_id AND permission_name=p_permission)';
      INSERT INTO public.roles(name) VALUES('user');
    `)
    for (
      const file of [
        "20260912000000_boss_ai.sql",
        "20260912001000_boss_ai_hardening.sql",
        "20260912002000_boss_ai_validation.sql",
        "20260912003000_boss_ai_allowance_preflight.sql",
        "20260912004000_boss_ai_exchange_tickets.sql",
        "20260924231000_boss_ai_token_eligibility.sql",
        "20261007000000_boss_ai_optimist_permission.sql",
        "20261008000000_boss_ai_model_provider_routing.sql",
        "20261008100000_boss_ai_reserve_ai_grants.sql",
        "20261015000000_boss_ai_voice.sql",
        "20261023090000_boss_ai_voice_admission.sql",
        "20261027120000_boss_ai_anthropic_fallbacks.sql",
        "20261027130000_boss_ai_user_quota_downgrade.sql",
      ]
    ) {
      await db.exec(
        await Deno.readTextFile(new URL(`../../../migrations/${file}`, import.meta.url)),
      )
    }
    const u = "00000000-0000-0000-0000-000000000001"
    await db.exec(`
      INSERT INTO auth.users(id) VALUES('${u}');
      INSERT INTO public.test_user_permissions VALUES('${u}','ai.use');
      INSERT INTO public.boss_ai_connections VALUES('c','https://example.com/v1','BOSS_AI_TEST','openai_chat',true);
      INSERT INTO public.boss_ai_models(id,display_name,connection_id,upstream_model,kind,context_length,max_output_tokens,published) VALUES
        ('stt','Stt','c','stt','transcription',1024,60,true),
        ('tts','Tts','c','tts','speech',1024,20,true),
        ('stt-lite','SttLite','c','stt2','transcription',1024,60,true);
      UPDATE public.boss_ai_models SET allowance_model_id='stt' WHERE id='tts';
      INSERT INTO public.boss_ai_allowances VALUES('stt','ai.use',100,100,100,2),('stt-lite','ai.use',1000,1000,1000,2);
    `)
    const scalar = async (sql: string, params: unknown[] = []) =>
      (await db.query<{ value: any }>(sql, params)).rows[0].value
    const reserve = (m: string) =>
      scalar("SELECT public.boss_ai_reserve($1,$2,$3) AS value", [u, m, crypto.randomUUID()])
    // Pooling still works: tts draws from stt's seconds, so a downgrade of stt sees tts usage too.
    await db.exec(
      "UPDATE public.boss_ai_models SET over_allowance_model_id='stt-lite' WHERE id='stt'",
    )
    const t1 = await reserve("tts")
    assertEquals(t1.model.id, "tts")
    await db.exec("UPDATE public.boss_ai_requests SET charged_tokens=60, settled=true")
    assertEquals((await scalar("SELECT public.boss_ai_usage($1,'stt') AS value", [u])).day.used, 60)
    // 40 seconds left < 60 needed: stt downgrades; tts needs only 20 and still fits.
    const s = await reserve("stt")
    assertEquals([s.model.id, s.downgraded_from], ["stt-lite", "stt"])
    assertEquals((await reserve("tts")).model.id, "tts")
    // A kind mismatch is refused.
    await db.exec(
      "INSERT INTO public.boss_ai_models(id,display_name,connection_id,upstream_model,context_length,max_output_tokens) VALUES('c1','C','c','c',1024,10)",
    )
    await assertRejects(() =>
      db.exec("UPDATE public.boss_ai_models SET over_allowance_model_id='c1' WHERE id='stt'")
    )
  } finally {
    await db.close()
  }
})
