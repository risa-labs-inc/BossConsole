/**
 * Agent adapter plugins: publishing is `agents.publish` in the agent's organisation, nothing else.
 * Authorship, publish_policy and `plugins.create` neither grant it nor are needed, and the plugin
 * always lands on the agent's organisation.
 */

import { assert, assertEquals, assertFalse } from "@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import {
  AGENT_PLUGIN_PREFIX,
  authorizeNewPluginPublish,
  authorizeVersionPublish,
} from "../services/publish-authz.ts"
import type { PublishAuthz } from "../services/publish-authz.ts"
import type { AuthResult } from "../utils/auth.ts"

const USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const AUTHOR = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const AGENT_ORG = "11111111-1111-4111-8111-111111111111"
const ORG_OTHER = "22222222-2222-4222-8222-222222222222"
const PLUGIN = `${AGENT_PLUGIN_PREFIX}acme`
const NOT_OWNER = "Not authorized to publish to this plugin"

interface Answer {
  org_id?: string | null
  allowed?: boolean
}

function stub(answer: Answer | "error", other: { canPublish?: boolean; orgs?: string[] } = {}) {
  const calls: Array<{ fn: string; args: Record<string, unknown> }> = []
  const client = {
    rpc: (fn: string, args: Record<string, unknown>) => {
      calls.push({ fn, args })
      if (fn === "agent_plugin_publish_authz") {
        if (answer === "error") return Promise.resolve({ data: null, error: { message: "boom" } })
        const inNamespace = String(args.p_plugin_id).startsWith(AGENT_PLUGIN_PREFIX)
        return Promise.resolve({
          data: inNamespace
            ? { agent: true, org_id: answer.org_id ?? null, allowed: answer.allowed === true }
            : { agent: false },
          error: null,
        })
      }
      if (fn === "user_can_publish_org_plugin") {
        return Promise.resolve({ data: other.canPublish === true, error: null })
      }
      if (fn === "get_my_organisations") {
        const rows = (other.orgs ?? []).map((id) => ({ id, is_system: false, status: "active" }))
        return Promise.resolve({ data: { success: true, data: rows }, error: null })
      }
      return Promise.resolve({ data: null, error: { message: `unexpected rpc ${fn}` } })
    },
  }
  return { client: client as unknown as SupabaseClient, calls }
}

const user = (extra: Partial<AuthResult> = {}): AuthResult => ({
  userId: USER,
  email: "a@b.test",
  isAdmin: false,
  jwtPermissions: [],
  ...extra,
})
const creator = (extra: Partial<AuthResult> = {}) => user({ jwtPermissions: ["plugins.create"], ...extra })

function allowed(result: PublishAuthz) {
  if (!result.ok) throw new Error(`expected success, got ${result.status}: ${result.error}`)
  return { orgId: result.orgId, orgScoped: result.orgScoped }
}
function refused(result: PublishAuthz) {
  if (result.ok) throw new Error(`expected a refusal, got orgId=${result.orgId}`)
  return { status: result.status, error: result.error }
}

Deno.test("a version: agents.publish in the agent's org is enough, without being the author or an org admin", async () => {
  const { client, calls } = stub({ org_id: AGENT_ORG, allowed: true })
  const result = await authorizeVersionPublish(client, user(), AUTHOR, AGENT_ORG, NOT_OWNER, PLUGIN)
  assertEquals(allowed(result), { orgId: AGENT_ORG, orgScoped: true })
  assertEquals(calls[0].args, { p_user_id: USER, p_plugin_id: PLUGIN })
  assertFalse(calls.some((c) => c.fn === "user_can_publish_org_plugin"), "publish_policy is not consulted")
})

Deno.test("a version: the author without agents.publish is refused (revocation applies at once)", async () => {
  const { client } = stub({ org_id: AGENT_ORG, allowed: false }, { canPublish: true, orgs: [AGENT_ORG] })
  const result = refused(await authorizeVersionPublish(client, user({ userId: AUTHOR }), AUTHOR, AGENT_ORG, NOT_OWNER, PLUGIN))
  assertEquals(result.status, 403)
  assert(result.error.includes("agents.publish"), "the refusal names the permission")
})

Deno.test("a version: plugins.create does not reach an agent's plugin", async () => {
  const { client } = stub({ org_id: AGENT_ORG, allowed: false })
  assertEquals(refused(await authorizeVersionPublish(client, creator(), AUTHOR, AGENT_ORG, NOT_OWNER, PLUGIN)).status, 403)
  assertEquals(refused(await authorizeVersionPublish(client, creator({ userId: AUTHOR }), AUTHOR, AGENT_ORG, NOT_OWNER, PLUGIN)).status, 403)
})

Deno.test("a version: a row recorded against another organisation is not the agent's", async () => {
  const { client } = stub({ org_id: AGENT_ORG, allowed: true })
  const result = refused(await authorizeVersionPublish(client, user(), AUTHOR, ORG_OTHER, NOT_OWNER, PLUGIN))
  assertEquals(result.status, 403)
  assert(result.error.includes("agent's own organisation"))
  assertEquals(refused(await authorizeVersionPublish(client, user(), AUTHOR, null, NOT_OWNER, PLUGIN)).status, 403)
})

Deno.test("a new agent plugin lands on the agent's organisation, org-scoped", async () => {
  const { client } = stub({ org_id: AGENT_ORG, allowed: true })
  assertEquals(allowed(await authorizeNewPluginPublish(client, user(), null, PLUGIN)), { orgId: AGENT_ORG, orgScoped: true })
  assertEquals(allowed(await authorizeNewPluginPublish(client, user(), AGENT_ORG, PLUGIN)), { orgId: AGENT_ORG, orgScoped: true })
})

Deno.test("a new agent plugin: naming another organisation is refused", async () => {
  const { client } = stub({ org_id: AGENT_ORG, allowed: true })
  assertEquals(refused(await authorizeNewPluginPublish(client, user(), ORG_OTHER, PLUGIN)).status, 403)
})

Deno.test("a new agent plugin: no agent uses the id, so nobody may claim it", async () => {
  const { client } = stub({ org_id: null, allowed: false })
  const result = refused(await authorizeNewPluginPublish(client, creator({ isAdmin: true }), null, PLUGIN))
  assertEquals(result.status, 403)
  assert(result.error.includes("Create the agent"))
})

Deno.test("a new agent plugin: no agents.publish, no publish, plugins.create or not", async () => {
  const { client } = stub({ org_id: AGENT_ORG, allowed: false }, { canPublish: true, orgs: [AGENT_ORG] })
  assertEquals(refused(await authorizeNewPluginPublish(client, user(), AGENT_ORG, PLUGIN)).status, 403)
  assertEquals(refused(await authorizeNewPluginPublish(client, creator(), null, PLUGIN)).status, 403)
})

Deno.test("an unreadable agent check fails closed with a 500", async () => {
  const { client } = stub("error")
  assertEquals(refused(await authorizeNewPluginPublish(client, creator(), null, PLUGIN)).status, 500)
  assertEquals(refused(await authorizeVersionPublish(client, user({ userId: AUTHOR }), AUTHOR, AGENT_ORG, NOT_OWNER, PLUGIN)).status, 500)
})

Deno.test("ids outside the agent namespace never reach the agent check (the template included)", async () => {
  const { client, calls } = stub({ org_id: AGENT_ORG, allowed: true }, { canPublish: true, orgs: [AGENT_ORG] })
  await authorizeVersionPublish(client, user({ userId: AUTHOR }), AUTHOR, AGENT_ORG, NOT_OWNER, "ai.rever.boss.plugin.dynamic.agentadaptertemplate")
  await authorizeNewPluginPublish(client, user(), AGENT_ORG, "ai.rever.boss.plugin.dynamic.max")
  assertFalse(calls.some((c) => c.fn === "agent_plugin_publish_authz"))
})

Deno.test("every publish site passes the plugin id to its gate", () => {
  const src = Deno.readTextFileSync(new URL("../routes/publish.ts", import.meta.url))
  const versionCalls = [...src.matchAll(/authorizeVersionPublish\(([\s\S]*?)\n\s*\)/g)].map((m) => m[1])
  assertEquals(versionCalls.length, 4)
  for (const args of versionCalls) assert(/pluginId\s*$/.test(args.trim()), `version gate without a plugin id: ${args}`)
  const newCalls = [...src.matchAll(/authorizeNewPluginPublish\(([^)]*)\)/g)].map((m) => m[1])
  assertEquals(newCalls.length, 3)
  for (const args of newCalls) assert(/pluginId$/.test(args.trim()), `new-plugin gate without a plugin id: ${args}`)
})
