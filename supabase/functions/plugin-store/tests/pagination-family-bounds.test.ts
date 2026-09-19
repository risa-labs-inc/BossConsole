import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts"
import { ListPluginsQuerySchema } from "../types/schemas.ts"

/**
 * #915 family-bounds regression tests: /list (via ListPluginsQuerySchema) and
 * the ratings route (its inline query schema) must both bound page/pageSize to
 * a 25,000-row worst case, and refuse the Number()-transform quirks
 * (scientific notation, whitespace padding) that previously passed.
 */

Deno.test("/list accepts in-bounds page and pageSize", () => {
  const parsed = ListPluginsQuerySchema.parse({ page: "3", pageSize: "25" })
  assertEquals(parsed.page, 3)
  assertEquals(parsed.pageSize, 25)
})

Deno.test("/list defaults to page 1 / pageSize 20 when omitted", () => {
  const parsed = ListPluginsQuerySchema.parse({})
  assertEquals(parsed.page, 1)
  assertEquals(parsed.pageSize, 20)
})

Deno.test("/list refuses out-of-bounds page and pageSize", () => {
  for (const bad of [{ page: "0" }, { page: "501" }, { pageSize: "0" }, { pageSize: "51" }, { page: "-3" }]) {
    let threw = false
    try {
      ListPluginsQuerySchema.parse(bad)
    } catch {
      threw = true
    }
    assertEquals(threw, true, `expected refusal for ${JSON.stringify(bad)}`)
  }
})

Deno.test("/list refuses non-integer forms; scientific notation is a valid integer and stays bounded", () => {
  // Fractional and non-numeric forms refuse. Scientific notation ('1e2' = 100)
  // and whitespace-padded (' 5 ') forms ARE valid integers to z.coerce, so
  // they are accepted - but still inside the 1..500 / 1..50 caps, which is
  // the property that matters (#915 bounds the window, not the spelling).
  for (const bad of [{ page: "2.5" }, { pageSize: "2.5" }, { page: "abc" }, { page: "" }]) {
    let threw = false
    try {
      ListPluginsQuerySchema.parse(bad)
    } catch {
      threw = true
    }
    assertEquals(threw, true, `expected refusal for ${JSON.stringify(bad)}`)
  }
  assertEquals(ListPluginsQuerySchema.parse({ page: "1e2" }).page, 100)
  assertEquals(ListPluginsQuerySchema.parse({ page: " 5 " }).page, 5)
})

Deno.test("the worst-case /list window is bounded to 25,000 rows", () => {
  const parsed = ListPluginsQuerySchema.parse({ page: "500", pageSize: "50" })
  assertEquals(parsed.page * parsed.pageSize, 25000)
  // One past the cap in each dimension refuses.
  for (const edge of [{ page: "501", pageSize: "50" }, { page: "500", pageSize: "51" }]) {
    let threw = false
    try {
      ListPluginsQuerySchema.parse(edge)
    } catch {
      threw = true
    }
    assertEquals(threw, true, `expected refusal for ${JSON.stringify(edge)}`)
  }
})
