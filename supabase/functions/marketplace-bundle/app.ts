/**
 * marketplace-bundle: zip bundles for the marketplace.
 *   POST /marketplace-bundle/upload    raw zip (application/zip), ?skip_review=1  -> marketplace_submit_bundle
 *   POST /marketplace-bundle/download  {slug, agent}                              -> marketplace_install_v2 + signed URL
 *   POST /marketplace-bundle/review    {version_id}                               -> signed URL for the creator or a platform reader
 * Every answer is JSON. Errors are {error, message} with a 4xx/5xx; zip contents and stack traces are never echoed.
 */
import { validateBundle } from "./zip.ts";
import { BundleError } from "./manifest.ts";

export const MAX_BYTES = 10 * 1024 * 1024;
const SLUG = /^[a-z0-9][a-z0-9-]{1,46}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface SubmitArgs {
  userId: string;
  manifest: unknown;
  path: string;
  sha256: string;
  size: number;
  skipReview: boolean;
}

/** Everything the function needs from the outside world, so the handler is testable without a stack. */
export interface Deps {
  /** The caller's user id from their JWT, or null. */
  user(jwt: string): Promise<string | null>;
  /** marketplace_flags() (public). */
  flags(): Promise<{ bundles: boolean }>;
  /** Stores bundles/<sha>.zip in the private bucket without overwriting. */
  put(path: string, bytes: Uint8Array): Promise<"created" | "exists">;
  /** marketplace_submit_bundle as the service role. Returns the RPC's JSON. */
  submit(a: SubmitArgs): Promise<Record<string, unknown>>;
  /** marketplace_install_v2 as the user (anon key + the user's JWT). */
  install(jwt: string, slug: string, agent: string | null): Promise<Record<string, unknown>>;
  /** A 120 second download URL for a stored bundle. */
  sign(path: string): Promise<string>;
  /** marketplace_bundle_access as the service role: the path, or null. */
  access(userId: string, versionId: string): Promise<string | null>;
}

/** The caller has no right to the thing they asked for. */
export class Unauthorized extends Error {}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
};

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}
const fail = (status: number, error: string, message: string) => json(status, { error, message });

const MESSAGES: Record<string, string> = {
  bundles_disabled: "Bundles are turned off.",
  http_connectors_disabled: "HTTP connectors are turned off.",
  script_connectors_disabled: "Script connectors are turned off.",
  restricted_disabled: "Listings restricted to agents are turned off.",
  unknown_agent: "A listed agent does not exist.",
  agent_restriction_not_allowed: "You may not restrict a listing to one of those agents.",
  agent_not_allowed: "This listing cannot be installed in this agent.",
  review_required: "Script connectors always wait for review.",
  review_skip_not_allowed: "You may not skip review.",
  not_found: "No such listing.",
  slug_taken: "That name belongs to another publisher.",
  kind_mismatch: "That name is already used by a listing of the other kind.",
  listing_suspended: "That listing is suspended.",
  submission_rate_limited: "Too many submissions today. Try again tomorrow.",
  invalid_bundle: "The bundle is not valid.",
  invalid_manifest: "boss.json is not valid.",
  bundle_too_large: "The bundle is too large.",
  invalid_category: "That category does not exist.",
  invalid_tag: "A tag is not valid.",
  too_many_tags: "At most 8 tags.",
  connector_requires_public_https: "Connector hosts must be public https hosts.",
  invalid_connector: "The connector settings are not valid.",
};
const message = (code: string) => MESSAGES[code] ?? "The request was refused.";

/** HTTP status for a refusal code from the database. */
export function statusFor(code: string): number {
  if (["slug_taken", "kind_mismatch", "listing_suspended"].includes(code)) return 409;
  if (code === "submission_rate_limited") return 429;
  if (code === "not_found") return 404;
  if (code === "agent_not_allowed" || code.endsWith("_disabled")) return 403;
  if (
    ["review_skip_not_allowed", "agent_restriction_not_allowed", "review_required"].includes(code)
  ) return 403;
  if (code === "bundle_too_large") return 413;
  return 400;
}

function bearer(req: Request): string | null {
  const m = /^Bearer\s+([A-Za-z0-9._~+/-]+=*)$/.exec(req.headers.get("authorization") ?? "");
  return m ? m[1] : null;
}

/** Reads the body up to MAX_BYTES; null when it is larger. */
async function readCapped(req: Request): Promise<Uint8Array | null> {
  const declared = Number(req.headers.get("content-length") ?? "");
  if (Number.isFinite(declared) && declared > MAX_BYTES) {
    await req.body?.cancel();
    return null;
  }
  if (!req.body) return new Uint8Array(0);
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let n = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    n += value.length;
    if (n > MAX_BYTES) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const out = new Uint8Array(n);
  let o = 0;
  for (const c of chunks) {
    out.set(c, o);
    o += c.length;
  }
  return out;
}

async function sha256Hex(b: Uint8Array): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", b as unknown as BufferSource));
  return [...d].map((x) => x.toString(16).padStart(2, "0")).join("");
}

async function readJson(req: Request): Promise<Record<string, unknown> | null> {
  const raw = await readCapped(req);
  if (!raw || raw.length > 4096) return null;
  try {
    const v = JSON.parse(new TextDecoder().decode(raw));
    return v && typeof v === "object" && !Array.isArray(v) ? v as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

function refusal(r: Record<string, unknown>): Response | null {
  if (typeof r.error !== "string") return null;
  return fail(statusFor(r.error), r.error, message(r.error));
}

export function createApp(deps: Deps): (req: Request) => Promise<Response> {
  async function upload(req: Request, jwt: string, url: URL): Promise<Response> {
    const userId = await deps.user(jwt);
    if (!userId) return fail(401, "unauthorized", "Sign in again.");
    if (!(req.headers.get("content-type") ?? "").toLowerCase().startsWith("application/zip")) {
      return fail(415, "invalid_bundle", "Send the bundle as application/zip.");
    }
    // Refuse before storing anything when bundles are off.
    if (!(await deps.flags()).bundles) {
      return fail(403, "bundles_disabled", message("bundles_disabled"));
    }
    const bytes = await readCapped(req);
    if (!bytes) return fail(413, "bundle_too_large", message("bundle_too_large"));
    let manifest: unknown;
    try {
      manifest = validateBundle(bytes).manifest;
    } catch (e) {
      if (e instanceof BundleError) return fail(statusFor(e.code), e.code, e.message);
      return fail(400, "invalid_bundle", message("invalid_bundle"));
    }
    const sha = await sha256Hex(bytes);
    const path = `bundles/${sha}.zip`;
    await deps.put(path, bytes);
    const r = await deps.submit({
      userId,
      manifest,
      path,
      sha256: sha,
      size: bytes.length,
      skipReview: url.searchParams.get("skip_review") === "1",
    });
    return refusal(r) ?? json(200, r);
  }

  async function download(req: Request, jwt: string): Promise<Response> {
    const body = await readJson(req);
    const slug = body?.slug;
    const agent = body?.agent ?? null;
    if (
      typeof slug !== "string" || !SLUG.test(slug) ||
      (agent !== null && (typeof agent !== "string" || agent.length > 128))
    ) {
      return fail(400, "invalid_request", "slug and agent are required.");
    }
    const r = await deps.install(jwt, slug, agent as string | null);
    const refused = refusal(r);
    if (refused) return refused;
    const path = typeof r.bundle_path === "string" ? r.bundle_path : null;
    return json(200, path ? { ...r, url: await deps.sign(path) } : r);
  }

  async function review(req: Request, jwt: string): Promise<Response> {
    const userId = await deps.user(jwt);
    if (!userId) return fail(401, "unauthorized", "Sign in again.");
    const body = await readJson(req);
    const vid = body?.version_id;
    if (typeof vid !== "string" || !UUID.test(vid)) {
      return fail(400, "invalid_request", "version_id is required.");
    }
    const path = await deps.access(userId, vid);
    if (!path) return fail(403, "forbidden", "You cannot download that bundle.");
    return json(200, { url: await deps.sign(path) });
  }

  return async (req: Request): Promise<Response> => {
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const url = new URL(req.url);
    const parts = url.pathname.split("/").filter(Boolean);
    const at = parts.lastIndexOf("marketplace-bundle");
    const route = at >= 0 ? parts.slice(at + 1).join("/") : "";
    if (req.method !== "POST" || !["upload", "download", "review"].includes(route)) {
      return fail(404, "not_found", "No such route.");
    }
    const jwt = bearer(req);
    if (!jwt) return fail(401, "unauthorized", "Sign in again.");
    try {
      if (route === "upload") return await upload(req, jwt, url);
      if (route === "download") return await download(req, jwt);
      return await review(req, jwt);
    } catch (e) {
      if (e instanceof Unauthorized) return fail(401, "unauthorized", "Sign in again.");
      console.error("marketplace-bundle", route, e instanceof Error ? e.name : "error");
      return fail(502, "service_unavailable", "Try again in a moment.");
    }
  };
}
