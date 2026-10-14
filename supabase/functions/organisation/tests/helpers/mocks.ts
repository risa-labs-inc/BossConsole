/**
 * Test doubles.
 *
 * The service client is replaced wholesale rather than intercepting fetch: the
 * thing under test is which RPC a route calls and with what actor, and a fake
 * that records `(fn, params)` states that directly.
 */

import { setServiceClientForTests } from "../../utils/org-rpc.ts"
import type { SupabaseClient } from "@supabase/supabase-js"

/** A 32+ character key, the minimum requireSessionSecrets accepts. */
export const TEST_SECRET = "test-secret-that-is-long-enough-0123456789"

export interface RpcCall {
  fn: string
  params: Record<string, unknown>
}

export interface RpcStub {
  calls: RpcCall[]
  /** Responses by function name. A missing entry answers `{ success: true }`. */
  responses: Map<string, unknown>
  /** Errors by function name, as the supabase-js client would report them. */
  errors: Map<string, string>
}

/**
 * Install a fake service client and return the recorder.
 *
 * Call `restoreServiceClient()` in the test's teardown, or the next test
 * inherits this one's stub.
 */
export function stubServiceClient(): RpcStub {
  const stub: RpcStub = { calls: [], responses: new Map(), errors: new Map() }

  const fake = {
    rpc(fn: string, params: Record<string, unknown>) {
      stub.calls.push({ fn, params })
      const error = stub.errors.get(fn)
      if (error) return Promise.resolve({ data: null, error: { message: error } })
      const data = stub.responses.has(fn) ? stub.responses.get(fn) : { success: true }
      return Promise.resolve({ data, error: null })
    },
  }

  setServiceClientForTests(fake as unknown as SupabaseClient)
  return stub
}

export function restoreServiceClient(): void {
  setServiceClientForTests(null)
}

/** Set the environment the function expects. Returns a teardown. */
export function withTestEnv(): () => void {
  const previous = {
    secret: Deno.env.get("ORG_SESSION_SECRET"),
    prev: Deno.env.get("ORG_SESSION_SECRET_PREV"),
    base: Deno.env.get("ORG_PUBLIC_BASE_PATH"),
    baseUrl: Deno.env.get("ORG_PUBLIC_BASE_URL"),
  }

  Deno.env.set("ORG_SESSION_SECRET", TEST_SECRET)
  Deno.env.delete("ORG_SESSION_SECRET_PREV")
  Deno.env.set("ORG_PUBLIC_BASE_PATH", "/functions/v1/organisation")
  // The configured origin absolute invite links are minted from -- the one
  // source publicBaseUrl trusts. Tests of the UNCONFIGURED behaviour delete it
  // again inside the test; the teardown puts back whatever was here first.
  Deno.env.set("ORG_PUBLIC_BASE_URL", "https://boss.example")

  return () => {
    if (previous.secret === undefined) Deno.env.delete("ORG_SESSION_SECRET")
    else Deno.env.set("ORG_SESSION_SECRET", previous.secret)
    if (previous.prev === undefined) Deno.env.delete("ORG_SESSION_SECRET_PREV")
    else Deno.env.set("ORG_SESSION_SECRET_PREV", previous.prev)
    if (previous.base === undefined) Deno.env.delete("ORG_PUBLIC_BASE_PATH")
    else Deno.env.set("ORG_PUBLIC_BASE_PATH", previous.base)
    if (previous.baseUrl === undefined) Deno.env.delete("ORG_PUBLIC_BASE_URL")
    else Deno.env.set("ORG_PUBLIC_BASE_URL", previous.baseUrl)
  }
}

export const FIXTURE = {
  userId: "11111111-1111-4111-8111-111111111111",
  orgId: "22222222-2222-4222-8222-222222222222",
  slug: "acme",
  otherOrgId: "33333333-3333-4333-8333-333333333333",
  memberId: "44444444-4444-4444-8444-444444444444",
  roleId: "55555555-5555-4555-8555-555555555555",
  domainId: "66666666-6666-4666-8666-666666666666",
}

/** A get_organisation_detail payload for an admin viewer. */
export function orgDetailResponse(overrides: Record<string, unknown> = {}) {
  return {
    success: true,
    data: {
      id: FIXTURE.orgId,
      slug: FIXTURE.slug,
      name: "Acme",
      description: "A fixture",
      visibility: "private",
      join_policy: "invite_only",
      is_system: false,
      owner_email: "owner@example.com",
      member_count: 2,
      pending_count: 0,
      primary_domain: null,
      is_owner: true,
      is_admin: true,
      can_publish: true,
      publish_policy: "owner_only",
      publish_role_id: null,
      publish_role_name: null,
      auto_assign_member_role: true,
      max_custom_roles: 10,
      custom_role_count: 0,
      plugin_count: 0,
      ...overrides,
    },
  }
}

/** Standard request headers for a same-origin browser POST. */
export function formHeaders(cookie: string): Headers {
  return new Headers({
    "content-type": "application/x-www-form-urlencoded",
    "host": "api.risaboss.com",
    "origin": "https://api.risaboss.com",
    "sec-fetch-site": "same-origin",
    "sec-fetch-dest": "document",
    "x-forwarded-proto": "https",
    cookie,
  })
}
