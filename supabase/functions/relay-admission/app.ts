const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const TOKEN = /^[-_A-Za-z0-9]{43}$/;
const encoder = new TextEncoder();
const MAX_BODY = 256;
const MAX_BODY_READ_MS = 2000;
type Consume = (room: string, token: string) => Promise<unknown>;

function response(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    },
  });
}

// Bound streaming bodies too: Content-Length is optional and untrusted.
async function readBody(request: Request): Promise<Uint8Array<ArrayBuffer>> {
  const reader = request.body?.getReader();
  if (!reader) throw new Error("body");
  const chunks: Uint8Array[] = [];
  let length = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(
      () => reject(new Error("body timeout")),
      MAX_BODY_READ_MS,
    );
  });
  try {
    for (;;) {
      const { done, value } = await Promise.race([reader.read(), deadline]);
      if (done) break;
      length += value.byteLength;
      if (length > MAX_BODY || chunks.length >= MAX_BODY) {
        throw new Error("body");
      }
      chunks.push(value);
    }
  } finally {
    clearTimeout(timer);
    // Cancellation itself must not extend the unauthenticated body's deadline.
    void reader.cancel().catch(() => {});
    reader.releaseLock();
  }
  const body = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

function databaseConsumer(): Consume {
  return async (room, token) => {
    const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const r = await fetch(
      `${
        Deno.env.get("SUPABASE_URL")
      }/rest/v1/rpc/consume_terminal_relay_ticket`,
      {
        method: "POST",
        headers: {
          apikey: key,
          Authorization: `Bearer ${key}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ p_room_id: room, p_token: token }),
        signal: AbortSignal.timeout(4000),
      },
    );
    if (!r.ok) throw new Error("admission");
    return await r.json();
  };
}

/** A single-purpose gateway: the public relay never holds a database credential. */
export function createApp(
  options: { secret?: string; consume?: Consume } = {},
) {
  const secret = options.secret ?? Deno.env.get("RELAY_ADMISSION_KEY") ?? "";
  const consume = options.consume ?? databaseConsumer();
  const key = secret.length >= 32
    ? crypto.subtle.importKey(
      "raw",
      encoder.encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["verify"],
    )
    : null;
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") {
      return response(405, { error: "Method not allowed" });
    }
    if (!key) return response(503, { error: "Admission unavailable" });
    const signature = request.headers.get("X-Relay-Signature") ?? "";
    if (!/^[a-f0-9]{64}$/.test(signature)) {
      return response(401, { error: "Unauthorized" });
    }
    let body: Uint8Array<ArrayBuffer>;
    try {
      body = await readBody(request);
    } catch {
      return response(400, { error: "Invalid request" });
    }
    const bytes = Uint8Array.from(
      signature.match(/../g)!,
      (pair) => parseInt(pair, 16),
    );
    if (!await crypto.subtle.verify("HMAC", await key, bytes, body)) {
      return response(401, { error: "Unauthorized" });
    }
    let room: string;
    let token: string;
    try {
      const value = JSON.parse(
        new TextDecoder("utf-8", { fatal: true }).decode(body),
      );
      if (
        !value || typeof value !== "object" || Array.isArray(value) ||
        Object.keys(value).length !== 2 ||
        typeof value.p_room_id !== "string" || !UUID.test(value.p_room_id) ||
        typeof value.p_token !== "string" || !TOKEN.test(value.p_token)
      ) throw new Error("body");
      room = value.p_room_id.toLowerCase();
      token = value.p_token;
    } catch {
      return response(400, { error: "Invalid request" });
    }
    try {
      const result = await consume(room, token) as { role?: unknown } | null;
      if (result?.role !== "host" && result?.role !== "account") {
        throw new Error("role");
      }
      // Never forward database details, owner identifiers, credentials or errors.
      return response(200, { role: result.role });
    } catch {
      return response(403, { error: "Admission refused" });
    }
  };
}
