/**
 * The fluck.ai deployment reuses the portal without changing the legacy deployment's
 * cookies, OAuth callbacks, or CSRF origin. Each Edge Function has its own isolate.
 */
import { app } from "../fluck-web/app.ts"
import { configurePortalDeployment } from "../fluck-web/utils/config.ts"

configurePortalDeployment("https://fluck.ai", "/")

export function handleRequest(request: Request): Response | Promise<Response> {
  const url = new URL(request.url)
  if (!/^\/fluck-ai(?:\/|$)/.test(url.pathname)) {
    return new Response("Not found", { status: 404 })
  }
  url.pathname = url.pathname.replace(/^\/fluck-ai(?=\/|$)/, "/fluck-web")
  return app.fetch(new Request(url, request))
}
