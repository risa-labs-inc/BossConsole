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
        "20261027140000_boss_ai_router_budget.sql",
        "20261027150000_boss_ai_decider_api.sql",
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
        "20261027140000_boss_ai_router_budget.sql",
        "20261027150000_boss_ai_decider_api.sql",
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

Deno.test("router: config, budget state, routed reservation, settlement and sticky decisions", async () => {
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
        "20261027140000_boss_ai_router_budget.sql",
        "20261027150000_boss_ai_decider_api.sql",
      ]
    ) {
      await db.exec(
        await Deno.readTextFile(new URL(`../../../migrations/${file}`, import.meta.url)),
      )
    }
    const u1 = "00000000-0000-0000-0000-000000000001"
    const u2 = "00000000-0000-0000-0000-000000000002"
    await db.exec(`
      INSERT INTO auth.users(id) VALUES('${u1}'),('${u2}');
      INSERT INTO public.permissions(name) VALUES('ai.optimist') ON CONFLICT DO NOTHING;
      INSERT INTO public.test_user_permissions VALUES('${u1}','ai.optimist'),('${u2}','ai.use');
      INSERT INTO public.boss_ai_connections VALUES
        ('c','https://example.com/v1','BOSS_AI_TEST','openai_chat',true),
        ('risa_qwen_internal','https://litellm.example','BOSS_AI_RISA_QWEN','openai_chat',true);
      INSERT INTO public.boss_ai_models(id,display_name,connection_id,upstream_model,context_length,max_output_tokens,published,
          usd_per_mtok_input,usd_per_mtok_output,usd_per_mtok_cache_read,usd_per_mtok_cache_write) VALUES
        ('optimist','Optimist','c','unused',200000,8192,true,NULL,NULL,NULL,NULL),
        ('optimist-fast','Fast','c','haiku',200000,8192,false,0.1,0.5,0.01,0.125),
        ('optimist-standard','Standard','c','sonnet',200000,8192,false,2,10,0.2,2.5),
        ('optimist-strong','Strong','c','opus',200000,8192,false,4,20,0.2,5);
      INSERT INTO public.boss_ai_allowances VALUES('optimist','ai.optimist',100000000,100000000,100000000,2);
      INSERT INTO public.boss_ai_routers(model_id,decider_connection_id,fast_model_id,standard_model_id,strong_model_id)
        VALUES('optimist','risa_qwen_internal','optimist-fast','optimist-standard','optimist-strong');
    `)
    const scalar = async (sql: string, params: unknown[] = []) =>
      (await db.query<{ value: any }>(sql, params)).rows[0].value
    const ctx = (u = u1) =>
      scalar("SELECT public.boss_ai_route_context($1,'optimist') AS value", [u])
    const reserve = (
      u: string,
      served: string,
      est = 0.01,
      tier = "strong",
      source = "decider",
    ) => {
      const id = crypto.randomUUID()
      return scalar(
        "SELECT public.boss_ai_reserve_routed($1,'optimist',$2,$3,$4,$5,$6) AS value",
        [u, served, id, tier, source, est],
      ).then((r: any) => ({ r, id }))
    }
    const spend = (usd: number, u = u1, model = "optimist-strong") =>
      db.query(
        `INSERT INTO public.boss_ai_requests(id,user_id,model_id,reserved_tokens,charged_tokens,settled,cost_usd,routed_from,tier,route_source)
         VALUES(gen_random_uuid(),$1,$2,1000,1000,true,$3,'optimist','strong','decider')`,
        [u, model, usd],
      )

    // Defaults: the editable decider config and a $100 budget with the specced thresholds.
    const c0 = await ctx()
    assertEquals(c0.router.decider_model, "risa-pplx-decider-v1-27b")
    assertEquals([
      c0.router.decider_timeout_ms,
      c0.router.state_char_budget,
      c0.router.min_confidence,
    ], [2500, 12000, 0.55])
    assertEquals([
      c0.router.default_tier,
      c0.router.monthly_usd_limit,
      c0.router.strong_cap_at,
      c0.router.fast_only_at,
    ], ["standard", 100, 0.6, 0.85])
    assertEquals(c0.router.hard_stop_at, null)
    assertEquals(c0.router.choices.map((c: any) => c.tier), ["fast", "standard", "strong"])
    assertEquals(c0.models.exhausted.id, "optimist-fast")
    // Regression: every tier is the full configured model row (a CTE column once shadowed the
    // standard alias and returned the month-start timestamp, so standard calls were forbidden).
    for (
      const [tier, id, upstream] of [
        ["fast", "optimist-fast", "haiku"],
        ["standard", "optimist-standard", "sonnet"],
        ["strong", "optimist-strong", "opus"],
        ["exhausted", "optimist-fast", "haiku"],
      ]
    ) {
      assertEquals(typeof c0.models[tier], "object", tier)
      assertEquals(c0.models[tier].id, id, tier)
      assertEquals(c0.models[tier].upstream_model, upstream, tier)
      assertEquals(c0.models[tier].connection_id, "c", tier)
      assert("usd_per_mtok_input" in c0.models[tier], tier)
    }
    assertEquals(Object.keys(c0.models).sort(), ["exhausted", "fast", "standard", "strong"])
    // The router object carries config only: no helper columns leak into it.
    for (const leaked of ["dc", "fm", "sm", "gm", "xm", "month_start", "lim", "spent"]) {
      assert(!(leaked in c0.router), leaked)
    }
    // Each reservation on a tier serves exactly that configured model.
    for (const served of ["optimist-fast", "optimist-standard", "optimist-strong"]) {
      const t = await reserve(u1, served, 0.001, "standard", "default")
      assertEquals(t.r.model.id, served)
      await db.query("SELECT public.boss_ai_settle($1,1)", [t.id])
    }
    await db.exec("DELETE FROM public.boss_ai_requests")
    assertEquals(c0.decider_connection.base_url, "https://litellm.example")
    assertEquals([c0.budget.monthly_usd_limit, c0.budget.spent_usd, c0.budget.state], [
      100,
      0,
      "normal",
    ])
    assert(
      String(c0.budget.resets_at).startsWith(
        new Date(Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth() + 1, 1))
          .toISOString().slice(0, 10),
      ),
    )
    assertEquals(
      await scalar("SELECT public.boss_ai_route_context($1,'optimist-fast') AS value", [u1]),
      null,
    )
    // Lookup carries the router; tier models are unpublished: hidden from the catalog and lookup.
    assertEquals(
      (await scalar("SELECT public.boss_ai_lookup($1,'optimist') AS value", [u1])).router.router
        .model_id,
      "optimist",
    )
    assertEquals(
      await scalar("SELECT public.boss_ai_lookup($1,'optimist-strong') AS value", [u1]),
      null,
    )
    const catalog = await scalar("SELECT public.boss_ai_catalog($1) AS value", [u1])
    assertEquals(catalog.map((m: any) => m.id), ["optimist"])
    assertEquals(catalog[0].budget.state, "normal")
    // No permission, no routed call; a user override raises the limit for one user only.
    assertEquals((await reserve(u2, "optimist-fast")).r.error, "forbidden")
    assertEquals((await reserve(u1, "not-a-tier")).r.error, "forbidden")

    // Routed reservation: ledger row on the served model, routed_from/tier/source, estimate recorded.
    const a = await reserve(u1, "optimist-strong", 0.5)
    assertEquals(
      [a.r.model.id, a.r.routed_from, a.r.tier, a.r.route_source, a.r.budget_exhausted],
      ["optimist-strong", "optimist", "strong", "decider", false],
    )
    const row =
      (await db.query<any>("SELECT * FROM public.boss_ai_requests WHERE id=$1", [a.id])).rows[0]
    assertEquals([
      row.model_id,
      row.routed_from,
      row.tier,
      row.route_source,
      Number(row.est_cost_usd),
    ], ["optimist-strong", "optimist", "strong", "decider", 0.5])
    // In-flight estimate counts toward spend; duplicate and concurrency semantics hold.
    assertEquals((await ctx()).budget.spent_usd, 0.5)
    assertEquals(
      (await scalar(
        "SELECT public.boss_ai_reserve_routed($1,'optimist','optimist-fast',$2,'fast','decider',0) AS value",
        [u1, a.id],
      )).error,
      "duplicate",
    )
    await reserve(u1, "optimist-standard", 0.1)
    assertEquals((await reserve(u1, "optimist-standard", 0.1)).r.error, "concurrency_exceeded")
    // Settlement fills the breakdown and cost; the cost replaces the estimate in spend.
    await db.query("SELECT public.boss_ai_settle($1,1300,1000,100,200,0,0.0076)", [a.id])
    const done =
      (await db.query<any>("SELECT * FROM public.boss_ai_requests WHERE id=$1", [a.id])).rows[0]
    assertEquals([
      done.input_tokens,
      done.output_tokens,
      done.cache_read_tokens,
      done.cache_write_tokens,
      Number(done.cost_usd),
      done.settled,
    ], [1000, 100, 200, 0, 0.0076, true])
    assertEquals(Number((await ctx()).budget.spent_usd), 0.1076)
    await assertRejects(() =>
      db.query("SELECT public.boss_ai_settle($1,1,-1,0,0,0,0)", [crypto.randomUUID()])
    )
    await db.exec("DELETE FROM public.boss_ai_requests")

    // Budget state follows spend: 60% caps strong, 85% fast only, 100% exhausted.
    const state = async () => (await ctx()).budget.state
    await spend(59)
    assertEquals(await state(), "normal")
    await spend(1)
    assertEquals(await state(), "strong_capped")
    await spend(25)
    assertEquals(await state(), "fast_only")
    await spend(15)
    assertEquals(await state(), "exhausted (serving fast model)")
    // Over budget is served on the exhausted model (default: fast), never refused.
    const over = await reserve(u1, "optimist-strong", 0.2)
    assertEquals([over.r.model.id, over.r.tier, over.r.route_source, over.r.budget_exhausted], [
      "optimist-fast",
      "fast",
      "budget",
      true,
    ])
    // A per-user budget override and a router limit change apply immediately.
    await db.exec(
      `INSERT INTO public.boss_ai_user_budgets(user_id,router_model_id,monthly_usd_limit,note) VALUES('${u1}','optimist',200,'vip')`,
    )
    assertEquals(await state(), "normal")
    assertEquals((await ctx()).budget.remaining_usd, 100 - 0.2)
    await db.exec(`DELETE FROM public.boss_ai_user_budgets`)
    await db.exec("DELETE FROM public.boss_ai_requests")
    // A call that no longer fits under the lock is substituted by the exhausted model.
    await spend(99.9)
    const fit = await reserve(u1, "optimist-strong", 0.5)
    assertEquals([fit.r.model.id, fit.r.route_source, fit.r.budget_exhausted], [
      "optimist-fast",
      "budget",
      true,
    ])
    await db.exec("DELETE FROM public.boss_ai_requests")
    // A separate exhausted model, and only hard_stop_at refuses.
    await db.exec(
      `INSERT INTO public.boss_ai_models(id,display_name,connection_id,upstream_model,context_length,max_output_tokens,published)
      VALUES('optimist-free','Free','c','free',200000,8192,false);
      UPDATE public.boss_ai_routers SET exhausted_model_id='optimist-free', hard_stop_at=1.5 WHERE model_id='optimist'`,
    )
    assertEquals((await ctx()).models.exhausted.id, "optimist-free")
    await spend(120)
    assertEquals((await reserve(u1, "optimist-strong")).r.model.id, "optimist-free")
    assertEquals(await state(), "exhausted (serving fast model)")
    await spend(40)
    assertEquals(await state(), "hard_stopped")
    const stop = (await reserve(u1, "optimist-strong")).r
    assertEquals(stop.error, "budget_exhausted")
    assertEquals(stop.budget.monthly_usd_limit, 100)
    assert(typeof stop.budget.resets_at === "string")
    await db.exec(
      "DELETE FROM public.boss_ai_requests; UPDATE public.boss_ai_routers SET exhausted_model_id=NULL, hard_stop_at=NULL",
    )

    // Token allowances on a tier model still apply, with its over-allowance downgrade.
    await db.exec(`UPDATE public.boss_ai_models SET published=true WHERE id='optimist-strong';
      INSERT INTO public.boss_ai_allowances VALUES('optimist-strong','ai.optimist',200000,200000,200000,2);
      UPDATE public.boss_ai_models SET published=false, over_allowance_model_id='optimist-standard' WHERE id='optimist-strong'`)
    // Unpublished means no policy for the tier, so its allowance does not bind (access is the router's).
    assertEquals((await reserve(u1, "optimist-strong")).r.model.id, "optimist-strong")
    await db.exec("DELETE FROM public.boss_ai_requests")
    await db.exec("UPDATE public.boss_ai_models SET published=true WHERE id='optimist-strong'")
    const q1 = await reserve(u1, "optimist-strong")
    assertEquals(q1.r.model.id, "optimist-strong")
    await db.query("SELECT public.boss_ai_settle($1,200000)", [q1.id])
    const q2 = await reserve(u1, "optimist-strong")
    assertEquals([q2.r.model.id, q2.r.downgraded_from], ["optimist-standard", "optimist-strong"])
    await db.exec(
      "DELETE FROM public.boss_ai_requests; DELETE FROM public.boss_ai_allowances WHERE model_id='optimist-strong'; UPDATE public.boss_ai_models SET published=false WHERE id='optimist-strong'",
    )

    // Sticky decisions: stored per key, expire after the TTL, scoped by key.
    await db.query("SELECT public.boss_ai_route_put('k1',$1,'optimist','strong')", [u1])
    assertEquals(await scalar("SELECT public.boss_ai_route_get('k1') AS value"), "strong")
    assertEquals(await scalar("SELECT public.boss_ai_route_get('k2') AS value"), null)
    await db.query("SELECT public.boss_ai_route_put('k1',$1,'optimist','fast')", [u1])
    assertEquals(await scalar("SELECT public.boss_ai_route_get('k1') AS value"), "fast")
    await db.exec(
      "UPDATE public.boss_ai_route_decisions SET created_at = now() - interval '31 minutes'",
    )
    assertEquals(await scalar("SELECT public.boss_ai_route_get('k1') AS value"), null)
    assertEquals(await scalar("SELECT public.boss_ai_route_get('k1', 60) AS value"), "fast")

    // Validation and access.
    const bad = (sql: string) => assertRejects(() => db.exec(sql))
    await bad("UPDATE public.boss_ai_routers SET strong_cap_at=0.9, fast_only_at=0.8")
    await bad("UPDATE public.boss_ai_routers SET monthly_usd_limit=0")
    await bad("UPDATE public.boss_ai_routers SET hard_stop_at=0")
    await bad("UPDATE public.boss_ai_routers SET default_tier='huge'")
    await bad("UPDATE public.boss_ai_routers SET decider_timeout_ms=5")
    await bad("UPDATE public.boss_ai_routers SET fast_model_id='optimist'")
    await bad("UPDATE public.boss_ai_routers SET choices='[]'")
    await bad(
      `UPDATE public.boss_ai_routers SET choices='[{"tier":"fast","label":"a","description":"b"}]'`,
    )
    await bad(
      `UPDATE public.boss_ai_routers SET choices='[{"tier":"x","label":"a","description":"b"},{"tier":"fast","label":"c","description":"d"}]'`,
    )
    await bad(
      `UPDATE public.boss_ai_routers SET choices='[{"tier":"fast","label":"a","description":"b"},{"tier":"fast","label":"a","description":"d"}]'`,
    )
    await bad(
      `UPDATE public.boss_ai_routers SET choices='[{"tier":"fast","label":"a","description":"b","x":1},{"tier":"fast","label":"c","description":"d"}]'`,
    )
    await bad(`INSERT INTO public.boss_ai_user_budgets VALUES('${u1}','optimist',0)`)
    await bad(`INSERT INTO public.boss_ai_user_budgets VALUES('${u1}','optimist-fast',5)`)
    // decider_api defaults to the hosted decider; systemone is allowed, anything else is not.
    assertEquals((await ctx()).router.decider_api, "litellm_embeddings")
    await db.exec(
      "UPDATE public.boss_ai_routers SET decider_api='systemone', decider_model='decider:4b'",
    )
    assertEquals((await ctx()).router.decider_api, "systemone")
    await bad("UPDATE public.boss_ai_routers SET decider_api='other'")
    await db.exec(
      "UPDATE public.boss_ai_routers SET decider_api='litellm_embeddings', decider_model='risa-pplx-decider-v1-27b'",
    )
    // Connections may point at local http hosts; other http URLs stay refused.
    await db.exec(
      "INSERT INTO public.boss_ai_connections VALUES('ollaya-local','http://host.docker.internal:11437','BOSS_AI_OLLAYA','openai_chat',true),('l2','http://localhost/v1','BOSS_AI_L2','openai_chat',true),('l3','http://127.0.0.1:4000','BOSS_AI_L3','openai_chat',true)",
    )
    await bad(
      "INSERT INTO public.boss_ai_connections VALUES('x1','http://example.com/v1','BOSS_AI_X1','openai_chat',true)",
    )
    await bad(
      "INSERT INTO public.boss_ai_connections VALUES('x2','http://localhost.evil.com/v1','BOSS_AI_X2','openai_chat',true)",
    )
    await bad(
      "INSERT INTO public.boss_ai_connections VALUES('x3','ftp://localhost','BOSS_AI_X3','openai_chat',true)",
    )
    await db.exec("UPDATE public.boss_ai_routers SET enabled=false")
    assertEquals(
      (await scalar("SELECT public.boss_ai_lookup($1,'optimist') AS value", [u1])).router,
      null,
    )
    assertEquals((await reserve(u1, "optimist-fast")).r.error, "forbidden")
    await db.exec("UPDATE public.boss_ai_routers SET enabled=true")
    await db.exec("SET ROLE authenticated")
    await assertRejects(() => db.query("SELECT * FROM public.boss_ai_routers"))
    await assertRejects(() => db.query("SELECT * FROM public.boss_ai_user_budgets"))
    await assertRejects(() => db.query("SELECT * FROM public.boss_ai_route_decisions"))
    await assertRejects(() => db.query("SELECT public.boss_ai_route_context($1,'optimist')", [u1]))
    await assertRejects(() =>
      db.query(
        "SELECT public.boss_ai_reserve_routed($1,'optimist','optimist-fast',gen_random_uuid(),'fast','decider',0)",
        [u1],
      )
    )
    await db.exec("RESET ROLE")
  } finally {
    await db.close()
  }
})
