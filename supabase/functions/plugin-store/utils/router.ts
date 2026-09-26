import { OpenAPIHono, z } from "@hono/zod-openapi"
import type { PluginStoreContext } from "../types/context.ts"

/**
 * Every plugin-store router is built here, so each one answers a request that fails its route's
 * schema the same way: a 400 carrying the ErrorResponseSchema `{ error }` its routes declare,
 * naming the first failing field. Without a hook the validator answers with its own body,
 * `{ success: false, error: <ZodError> }`, which is not the declared shape and hands the caller
 * the schema's internals.
 *
 * The hook has to be set on each router, not on the app in index.ts: it is bound into a route's
 * validators when `router.openapi(...)` registers the route, so an app-level hook never reaches a
 * sub-router's routes. A new router made with `new OpenAPIHono()` instead of this would silently go
 * back to the validator's body; `router-hook.test.ts` checks one route per router.
 */
export function newRouter() {
  return new OpenAPIHono<{ Variables: PluginStoreContext }>({
    defaultHook: (result, ctx) => {
      if (!result.success) {
        return ctx.json({ error: invalidRequestMessage(result.error) }, 400)
      }
    },
  })
}

/** The first schema issue, named by its field, as the one-line `error` of ErrorResponseSchema. */
export function invalidRequestMessage(error: z.ZodError): string {
  const issue = error.issues[0]
  if (!issue) return "Invalid request"
  const field = issue.path.join(".")
  return field ? `Invalid ${field}: ${issue.message}` : `Invalid request: ${issue.message}`
}
