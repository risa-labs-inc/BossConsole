type JsonObject = Record<string, unknown>;

const PROVIDER_ORIGIN = "https://rtc.live.cloudflare.com";
export const TURN_TTL_SECONDS = 86_400;
const UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const INSTANCE = /^[A-Za-z0-9._:-]{1,128}$/;
const TICKET = /^[A-Za-z0-9_-]{43}$/;
const TURN_URL =
  /^(turn|turns):turn\.cloudflare\.com:([0-9]{1,5})\?transport=(udp|tcp)$/;

export class ApiError extends Error {
  constructor(public status: number, public code: string) {
    super(code);
    this.name = "ApiError";
  }
}

export interface Dependencies {
  consume(
    ticket: string,
    instanceId: string,
    attemptId: string,
  ): Promise<boolean>;
  mint(ttl: number): Promise<Response>;
  now(): Date;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
    },
  });
}

async function requestJson(request: Request): Promise<JsonObject> {
  if (!request.headers.get("content-type")?.startsWith("application/json")) {
    throw new ApiError(415, "invalid_request");
  }
  const length = Number(request.headers.get("content-length") ?? 0);
  if (length > 4096) throw new ApiError(413, "invalid_request");
  const bytes = await boundedBody(request.body, 4096, 413, "invalid_request");
  try {
    const value = JSON.parse(
      new TextDecoder("utf-8", { fatal: true }).decode(bytes),
    );
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new Error();
    }
    return value;
  } catch {
    throw new ApiError(400, "invalid_request");
  }
}

async function boundedBody(
  body: ReadableStream<Uint8Array> | null,
  limit: number,
  status: number,
  code: string,
): Promise<Uint8Array> {
  const reader = body?.getReader();
  if (!reader) throw new ApiError(status, code);
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > limit || chunks.length > 128) {
        throw new ApiError(status, code);
      }
      chunks.push(value);
    }
  } finally {
    void reader.cancel().catch(() => {});
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  return bytes;
}

function iceServers(value: unknown): JsonObject[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 4) {
    throw new ApiError(502, "upstream_unavailable");
  }
  const servers = value.map((item) => {
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      throw new ApiError(502, "upstream_unavailable");
    }
    const row = item as JsonObject;
    if (
      Object.keys(row).some((key) =>
        !["urls", "username", "credential"].includes(key)
      )
    ) {
      throw new ApiError(502, "upstream_unavailable");
    }
    const urls = typeof row.urls === "string" ? [row.urls] : row.urls;
    if (
      !Array.isArray(urls) || urls.length < 1 || urls.length > 8 ||
      urls.some((url) => {
        if (url === "stun:stun.cloudflare.com:3478") return false;
        if (typeof url !== "string") return true;
        const match = TURN_URL.exec(url);
        if (!match) return true;
        const port = Number(match[2]);
        return port < 1 || port > 65_535 ||
          (match[1] === "turns" && match[3] !== "tcp");
      })
    ) {
      throw new ApiError(502, "upstream_unavailable");
    }
    const username = row.username;
    const credential = row.credential;
    if (
      (username !== undefined &&
        (typeof username !== "string" || username.length < 1 ||
          username.length > 512)) ||
      (credential !== undefined &&
        (typeof credential !== "string" || credential.length < 1 ||
          credential.length > 512))
    ) {
      throw new ApiError(502, "upstream_unavailable");
    }
    const turn = urls.some((url) => (url as string).startsWith("turn"));
    if (
      turn !== (typeof username === "string" && typeof credential === "string")
    ) {
      throw new ApiError(502, "upstream_unavailable");
    }
    return {
      urls,
      ...(username === undefined ? {} : { username }),
      ...(credential === undefined ? {} : { credential }),
    };
  });
  if (
    !servers.some((server) =>
      (server.urls as string[]).some((url) =>
        url.startsWith("turn:") || url.startsWith("turns:")
      )
    )
  ) throw new ApiError(502, "upstream_unavailable");
  return servers;
}

async function boundedProviderJson(response: Response): Promise<JsonObject> {
  if (response.status !== 201) {
    await response.body?.cancel().catch(() => {});
    throw new ApiError(502, "upstream_unavailable");
  }
  const bytes = await boundedBody(
    response.body,
    32_768,
    502,
    "upstream_unavailable",
  );
  try {
    const value = JSON.parse(
      new TextDecoder("utf-8", { fatal: true }).decode(bytes),
    );
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw new Error();
    }
    return value;
  } catch {
    throw new ApiError(502, "upstream_unavailable");
  }
}

export function createHandler(
  deps: Dependencies,
): (request: Request) => Promise<Response> {
  return async (request) => {
    try {
      if (request.method !== "POST") {
        return json({ error: "method_not_allowed" }, 405);
      }
      const input = await requestJson(request);
      if (
        Object.keys(input).some((key) =>
          !["ticket", "instance_id", "attempt_id"].includes(key)
        ) ||
        typeof input.ticket !== "string" || !TICKET.test(input.ticket) ||
        typeof input.instance_id !== "string" ||
        !INSTANCE.test(input.instance_id) ||
        typeof input.attempt_id !== "string" || !UUID.test(input.attempt_id)
      ) {
        throw new ApiError(400, "invalid_request");
      }
      if (
        !await deps.consume(input.ticket, input.instance_id, input.attempt_id)
      ) {
        throw new ApiError(401, "unauthorized");
      }
      const minted = await boundedProviderJson(
        await deps.mint(TURN_TTL_SECONDS),
      );
      const servers = iceServers(minted.iceServers);
      return json({
        ice_servers: servers,
        expires_at: new Date(deps.now().getTime() + TURN_TTL_SECONDS * 1000)
          .toISOString(),
      });
    } catch (error) {
      const failure = error instanceof ApiError
        ? error
        : new ApiError(503, "unavailable");
      return json({ error: failure.code }, failure.status);
    }
  };
}

export function productionDependencies(): Dependencies {
  const base = Deno.env.get("SUPABASE_URL") ?? "";
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const keyId = Deno.env.get("WATCH_BOSS_TURN_KEY_ID") ?? "";
  const apiToken = Deno.env.get("WATCH_BOSS_TURN_KEY_API_TOKEN") ?? "";
  return {
    now: () => new Date(),
    consume: async (ticket, instanceId, attemptId) => {
      if (!base || !serviceKey) throw new ApiError(503, "not_configured");
      const response = await fetch(
        `${base}/rest/v1/rpc/fluck_web_consume_turn_ticket`,
        {
          method: "POST",
          redirect: "error",
          signal: AbortSignal.timeout(5_000),
          headers: {
            apikey: serviceKey,
            Authorization: `Bearer ${serviceKey}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            p_ticket: ticket,
            p_instance_id: instanceId,
            p_attempt_id: attemptId,
          }),
        },
      );
      if (!response.ok) {
        await response.body?.cancel().catch(() => {});
        if (response.status === 400 || response.status === 404) return false;
        throw new ApiError(503, "unavailable");
      }
      const body = await boundedBody(response.body, 1024, 503, "unavailable");
      try {
        return typeof JSON.parse(
          new TextDecoder("utf-8", { fatal: true }).decode(body),
        ) === "string";
      } catch {
        throw new ApiError(503, "unavailable");
      }
    },
    mint: async (ttl) => {
      if (!/^[A-Za-z0-9_-]{1,128}$/.test(keyId) || apiToken.length < 16) {
        throw new ApiError(503, "not_configured");
      }
      return await fetch(
        `${PROVIDER_ORIGIN}/v1/turn/keys/${
          encodeURIComponent(keyId)
        }/credentials/generate-ice-servers`,
        {
          method: "POST",
          redirect: "error",
          signal: AbortSignal.timeout(5_000),
          headers: {
            Authorization: `Bearer ${apiToken}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ ttl }),
        },
      );
    },
  };
}
