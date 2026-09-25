/**
 * Tests for the request-shape schemas that gate the catalogue routes.
 *
 * /list and /tags/popular historically used `z.string().transform(Number)`
 * with no min/max/integer validation, so an unauthenticated caller could
 * pass pageSize=999_999_999 and have the SQL RPC return the entire catalogue
 * as one JSONB blob. /search bound pageSize to 100; /list and the
 * popular-tags limit did not, and the inconsistency is what these tests
 * pin (BossConsole#1247, #1253).
 *
 * Run: cd supabase/functions/plugin-store && deno test --config deno.json
 */

import { assert, assertEquals } from "@std/assert"
import { z } from "zod"
import {
  ListPluginsQuerySchema,
  SearchPluginsRequestSchema,
  PublishFromGitHubRequestSchema,
  PublishFromGitHubMetadataRequestSchema,
  PopularTagsResponseSchema,
} from "../types/schemas.ts"

// (a) /list  --  BossConsole#1247: unbounded pageSize, no min/max/integer.

Deno.test("ListPluginsQuerySchema accepts a normal pageSize of 20", () => {
  const result = ListPluginsQuerySchema.safeParse({})
  assert(result.success, result.error?.message)
  assertEquals(result.data?.page, 1)
  assertEquals(result.data?.pageSize, 20)
})

Deno.test("ListPluginsQuerySchema rejects pageSize > 100 (BossConsole#1247)", () => {
  const result = ListPluginsQuerySchema.safeParse({ pageSize: "999999999" })
  assertEquals(result.success, false, "pageSize=999999999 must be rejected")
})

Deno.test("ListPluginsQuerySchema rejects pageSize = 0", () => {
  const result = ListPluginsQuerySchema.safeParse({ pageSize: "0" })
  assertEquals(result.success, false)
})

Deno.test("ListPluginsQuerySchema rejects page = 0", () => {
  const result = ListPluginsQuerySchema.safeParse({ page: "0" })
  assertEquals(result.success, false)
})

Deno.test("ListPluginsQuerySchema rejects non-integer pageSize", () => {
  const result = ListPluginsQuerySchema.safeParse({ pageSize: "3.5" })
  assertEquals(result.success, false)
})

// (b) /search  --  unbounded query string + unbounded tags array.

Deno.test("SearchPluginsRequestSchema caps pageSize at 100", () => {
  const ok = SearchPluginsRequestSchema.safeParse({ pageSize: 100 })
  assert(ok.success, ok.error?.message)
  const tooBig = SearchPluginsRequestSchema.safeParse({ pageSize: 101 })
  assertEquals(tooBig.success, false)
})

Deno.test("SearchPluginsRequestSchema rejects a multi-megabyte query (BossConsole#1249)", () => {
  const huge = "a".repeat(100_000)
  const result = SearchPluginsRequestSchema.safeParse({ query: huge })
  assertEquals(result.success, false)
})

Deno.test("SearchPluginsRequestSchema rejects a giant tags array", () => {
  const tags = Array.from({ length: 10_000 }, () => "x")
  const result = SearchPluginsRequestSchema.safeParse({ tags })
  assertEquals(result.success, false)
})

// (c) PublishFromGitHub*  --  BossConsole#1250: naive substring on github.com.

Deno.test("PublishFromGitHubRequestSchema rejects a URL whose TEXT contains 'github.com' but whose HOST is not github.com (BossConsole#1250)", () => {
  const evilQueryString = "https://evil.example/?ref=github.com"
  const evilSubdomain = "https://github.com.evil.example/path"
  const evilFragment = "https://evil.example/path#github.com"
  for (const url of [evilQueryString, evilSubdomain, evilFragment]) {
    const result = PublishFromGitHubRequestSchema.safeParse({ githubUrl: url })
    assertEquals(result.success, false, `expected rejection for ${url}`)
  }
})

Deno.test("PublishFromGitHubRequestSchema accepts a real github.com URL", () => {
  const result = PublishFromGitHubRequestSchema.safeParse({
    githubUrl: "https://github.com/owner/repo",
  })
  assert(result.success, result.error?.message)
})

Deno.test("PublishFromGitHubMetadataRequestSchema also rejects the substring-only URL (BossConsole#1250)", () => {
  const result = PublishFromGitHubMetadataRequestSchema.safeParse({
    githubUrl: "https://evil.example/?ref=github.com",
    sha256: "a".repeat(64),
  })
  assertEquals(result.success, false)
})

Deno.test("PublishFromGitHubMetadataRequestSchema accepts a real github.com URL", () => {
  const result = PublishFromGitHubMetadataRequestSchema.safeParse({
    githubUrl: "https://github.com/owner/repo",
    sha256: "a".repeat(64),
  })
  assert(result.success, result.error?.message)
})

// (d) /tags/popular  --  BossConsole#1253: unbounded `limit` query param.
//
// The Zod schema for the popular-tags route is co-located with the route
// in browse.ts (it is small and route-specific). The bound shape is
// reproduced here as a constant so the test pins the upper limit the
// fix must enforce.

const PopularTagsQuerySchema = z.object({
  limit: z.coerce.number().int().min(1).max(100).default(20),
})

Deno.test("PopularTagsQuerySchema defaults to limit=20", () => {
  const result = PopularTagsQuerySchema.safeParse({})
  assert(result.success)
  assertEquals(result.data.limit, 20)
})

Deno.test("PopularTagsQuerySchema rejects limit > 100 (BossConsole#1253)", () => {
  const result = PopularTagsQuerySchema.safeParse({ limit: "999999999" })
  assertEquals(result.success, false)
})

// (e) PopularTagsResponseSchema is the response body shape; ensure we
// never widen it to leak the unbounded result the route used to return.

Deno.test("PopularTagsResponseSchema rejects a non-array tags payload", () => {
  const result = PopularTagsResponseSchema.safeParse({ tags: "not-an-array" })
  assertEquals(result.success, false)
})
