import { validCloseResponse } from "../_shared/app-sharing/close-response.ts";
// Authenticated application signaling only. SFU credentials and locators never come from clients.
type ObjectValue = Record<string, unknown>;
function validTrackName(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_.:-]{1,135}$/.test(value)) {
    throw new ApiError(502, "upstream_unavailable");
  }
  return value;
}
export type Rpc = (
  action: string,
  body: ObjectValue,
  actor: string,
) => Promise<unknown>;
export interface Dependencies {
  authenticate: (jwt: string) => Promise<string | null>;
  authorizeGrant: (
    grant: string,
    session: string,
    generation: string,
    action: string,
    instance?: string,
  ) => Promise<string | null>;
  rpc: Rpc;
  preferences: (actor: string, body?: ObjectValue) => Promise<unknown>;
  sfu: (
    path: string,
    method: string,
    body?: ObjectValue,
  ) => Promise<ObjectValue>;
}
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}
const id = /^[A-Za-z0-9_-]{1,128}$/;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function object(value: unknown): ObjectValue {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ApiError(400, "invalid_request");
  }
  return value as ObjectValue;
}
function validId(value: unknown): string {
  if (typeof value !== "string" || !id.test(value)) {
    throw new ApiError(502, "upstream_unavailable");
  }
  return value;
}
function description(value: unknown, type: string): ObjectValue {
  const d = object(value);
  if (
    d.type !== type || typeof d.sdp !== "string" || d.sdp.length < 1 ||
    d.sdp.length > 100000
  ) throw new ApiError(400, "invalid_request");
  return { type, sdp: d.sdp };
}
async function body(request: Request): Promise<ObjectValue> {
  if (!request.headers.get("content-type")?.startsWith("application/json")) {
    throw new ApiError(415, "invalid_request");
  }
  const reader = request.body?.getReader();
  if (!reader) throw new ApiError(400, "invalid_request");
  let length = 0;
  const chunks: Uint8Array[] = [];
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(
      () => reject(new ApiError(408, "invalid_request")),
      3000,
    );
  });
  try {
    for (;;) {
      const { done, value } = await Promise.race([reader.read(), timeout]);
      if (done) break;
      length += value.length;
      if (length > 131072 || chunks.length > 4096) {
        throw new ApiError(413, "invalid_request");
      }
      chunks.push(value);
    }
  } finally {
    clearTimeout(timer);
    void reader.cancel().catch(() => {});
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const part of chunks) {
    bytes.set(part, offset);
    offset += part.length;
  }
  try {
    return object(
      JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)),
    );
  } catch {
    throw new ApiError(400, "invalid_request");
  }
}
const actions = new Set([
  "register",
  "revoke",
  "cleanup",
  "heartbeat",
  "mediaDemand",
  "peerHeartbeat",
  "list",
  "stop",
  "admit",
  "consume",
  "controlAcquire",
  "controlRelease",
  "controlRenew",
  "controlPoll",
  "preferencesGet",
  "preferencesSet",
  "mediaCreate",
  "mediaPublish",
  "mediaSubscribe",
  "mediaRenegotiate",
  "mediaClose",
  "dataEstablish",
  "dataPublish",
  "dataSubscribe",
  "dataRevoke",
]);
const grantActions = new Set([
  "register",
  "heartbeat",
  "stop",
  "admit",
  "consume",
  "peerHeartbeat",
  "revoke",
  "mediaDemand",
  "mediaCreate",
  "mediaPublish",
  "mediaSubscribe",
  "mediaRenegotiate",
  "mediaClose",
  "dataEstablish",
  "dataPublish",
  "dataSubscribe",
  "dataRevoke",
  "controlAcquire",
  "controlRenew",
  "controlRelease",
  "controlPoll",
]);
// Satisfies the legacy non-null descriptor shape without carrying a media root.
// It is never returned to either bridge clients or native session discovery.
const BRIDGE_VIEWER_URL = "https://bridge.invalid/#k=" + "A".repeat(43);
function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
}
export function createApp(deps: Dependencies) {
  return async (request: Request): Promise<Response> => {
    try {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname.endsWith("/viewer")) {
        const session = url.searchParams.get("session");
        if (!session || !uuid.test(session)) {
          return json({ error: "invalid_request" }, 400);
        }
        const configured = Deno.env.get("LIVE_SESSIONS_PUBLIC_BASE_URL");
        if (!configured) return json({ error: "not_configured" }, 503);
        const base = new URL(configured);
        if (
          base.protocol !== "https:" || base.username || base.password ||
          base.search || base.hash
        ) return json({ error: "not_configured" }, 503);
        const prefix = (Deno.env.get("LIVE_SESSIONS_PUBLIC_BASE_PATH") ??
          "/functions/v1/live-sessions").replace(/\/$/, "");
        base.pathname = prefix + "/app-viewer/";
        base.searchParams.set("session", session);
        // No fragment in Location: browsers preserve the incoming fragment. It is not trusted as a key by the viewer bootstrap.
        return new Response(null, {
          status: 302,
          headers: {
            Location: base.toString(),
            "Cache-Control": "no-store",
            "Referrer-Policy": "no-referrer",
          },
        });
      }
      if (request.method !== "POST") {
        return json({ error: "method_not_allowed" }, 405);
      }
      const input = await body(request);
      const action = input.action;
      if (typeof action !== "string" || !actions.has(action)) {
        throw new ApiError(400, "invalid_request");
      }
      const { action: _action, ...args } = input;
      const auth = request.headers.get("authorization");
      const grant = request.headers.get("x-fluck-app-sharing-grant");
      if ((auth !== null) === (grant !== null)) {
        throw new ApiError(401, "unauthorized");
      }
      let actor: string | null;
      if (grant !== null) {
        if (!/^[A-Za-z0-9_-]{43}$/.test(grant) || !grantActions.has(action)) {
          throw new ApiError(401, "unauthorized");
        }
        const session = args.session_id, generation = args.generation;
        if (
          typeof session !== "string" || !uuid.test(session) ||
          typeof generation !== "string" || !uuid.test(generation)
        ) {
          throw new ApiError(400, "invalid_request");
        }
        actor = await deps.authorizeGrant(
          grant,
          session,
          generation,
          action,
          typeof args.instance_id === "string" ? args.instance_id : undefined,
        );
      } else {
        if (!auth?.startsWith("Bearer ") || auth.length > 16384) {
          throw new ApiError(401, "unauthorized");
        }
        actor = await deps.authenticate(auth.slice(7));
      }
      if (!actor || !uuid.test(actor)) throw new ApiError(401, "unauthorized");
      if (grant !== null && action === "register") {
        if (args.viewer_url !== undefined && args.viewer_url !== null) {
          throw new ApiError(400, "invalid_request");
        }
        args.viewer_url = BRIDGE_VIEWER_URL;
      }
      if (action === "preferencesGet") {
        return json(await deps.preferences(actor));
      }
      if (action === "preferencesSet") {
        if (
          typeof args.auto_admit !== "boolean" ||
          typeof args.auto_control !== "boolean" ||
          !Number.isSafeInteger(args.revision) || Number(args.revision) < 0
        ) throw new ApiError(400, "invalid_request");
        return json(await deps.preferences(actor, args));
      }
      const call = async (op: string, extra: ObjectValue = {}) => {
        const value = await deps.rpc(op, { ...args, ...extra }, actor);
        if (
          value && typeof value === "object" && !Array.isArray(value) &&
          object(value).generation_revoked
        ) throw new ApiError(409, "session_retired");
        return value;
      };
      if (
        action === "mediaDemand" ||
        (!action.startsWith("media") && !action.startsWith("data"))
      ) {
        const result = action === "cleanup" ? {} : object(await call(action));
        if (result.generation_revoked) {
          throw new ApiError(409, "session_retired");
        }
        if (grant !== null && result.viewer_url === BRIDGE_VIEWER_URL) {
          result.viewer_url = null;
        }
        if (["stop", "register", "revoke", "cleanup"].includes(action)) {
          let pending = false;
          const jobs = await call("_cleanupList");
          if (Array.isArray(jobs)) {
            await Promise.all(
              jobs.slice(0, 8).map(async (job) => {
                const record = object(job), m = object(record.descriptor);
                try {
                  const path = `/sessions/${validId(m.sfu_id)}`;
                  if (m.mid) {
                    await deps.sfu(path + "/tracks/close", "PUT", {
                      tracks: [{ mid: m.mid }],
                      force: true,
                    });
                  }
                  const channels = [m.channel_id, m.transport_channel_id]
                    .filter(
                      Number.isInteger,
                    );
                  if (channels.length) {
                    await deps.sfu(path + "/datachannels/close", "PUT", {
                      dataChannels: channels.map((id) => ({ id })),
                    });
                  }
                  await call("_cleanupAck", { cleanup_id: record.id });
                } catch {
                  pending = true;
                }
              }),
            );
          }
          return json({ ...result, cleanup_pending: pending });
        }
        if (
          grant === null && action === "list" && Array.isArray(result.sessions)
        ) {
          result.sessions = result.sessions.filter((session) =>
            object(session).viewer_url !== BRIDGE_VIEWER_URL
          );
        }
        return json(result);
      }
      // Serialize all SFU mutations for this peer/window across concurrent Edge invocations.
      const operation = crypto.randomUUID();
      await call("_mediaLock", { operation });
      try {
        const state = object(await call("_mediaGet"));
        const media = state.media === null ? null : object(state.media);
        const publisher = state.publisher === true;
        if (action === "mediaCreate") {
          if (media) throw new ApiError(409, "conflict");
          const created = await deps.sfu("/sessions/new", "POST");
          const sfu_id = validId(created.sessionId);
          await call("_mediaBind", { sfu_id });
          return json({
            peer_id: args.peer_id,
            window_id: args.window_id,
            ice_servers: [],
          });
        }
        if (!media) throw new ApiError(409, "media_not_created");
        const path = `/sessions/${validId(media.sfu_id)}`;
        if (action === "mediaClose") {
          if (media.mid) {
            await deps.sfu(path + "/tracks/close", "PUT", {
              tracks: [{ mid: media.mid }],
              force: true,
            });
          }
          const channels = [media.channel_id, media.transport_channel_id]
            .filter((v) => Number.isInteger(v));
          if (channels.length) {
            await deps.sfu(path + "/datachannels/close", "PUT", {
              dataChannels: channels.map((id) => ({ id })),
            });
          }
          await call("_mediaDelete");
          return json({ closed: true });
        }
        if (action === "mediaRenegotiate") {
          await deps.sfu(path + "/renegotiate", "PUT", {
            sessionDescription: description(args.session_description, "answer"),
          });
          await call("_mediaGet");
          return json({ accepted: true });
        }
        if (action === "mediaPublish" || action === "mediaSubscribe") {
          if (media.mid) throw new ApiError(409, "conflict");
          let request: ObjectValue;
          const name = `window-${args.window_id}`;
          if (action === "mediaPublish") {
            if (!publisher) throw new ApiError(403, "unauthorized");
            const mid = validId(args.mid);
            request = {
              sessionDescription: description(
                args.session_description,
                "offer",
              ),
              tracks: [{ location: "local", mid, trackName: name }],
            };
          } else {
            if (publisher) throw new ApiError(403, "unauthorized");
            if (!state.publication) {
              throw new ApiError(409, "publication_pending");
            }
            const source = object(state.publication);
            request = {
              tracks: [{
                location: "remote",
                sessionId: validId(source.sfu_id),
                trackName: validTrackName(source.track_name),
              }],
            };
          }
          const result = await deps.sfu(path + "/tracks/new", "POST", request);
          const tracks = result.tracks;
          if (
            !Array.isArray(tracks) || tracks.length !== 1 ||
            object(tracks[0]).errorCode
          ) throw new ApiError(502, "upstream_unavailable");
          const mid = validId(object(tracks[0]).mid);
          try {
            await call("_mediaTrack", {
              mid,
              track_name: publisher ? name : null,
            });
          } catch (error) {
            await deps.sfu(path + "/tracks/close", "PUT", {
              tracks: [{ mid }],
              force: true,
            }).catch(() => {});
            throw error;
          }
          return json({
            session_description: result.sessionDescription,
            mid,
            track: {
              window_id: args.window_id,
              track_name: publisher
                ? name
                : object(state.publication).track_name,
            },
          });
        }
        if (action === "dataEstablish") {
          if (media.transport_channel_id !== null) {
            throw new ApiError(409, "conflict");
          }
          const result = await deps.sfu(
            path + "/datachannels/establish",
            "POST",
            {
              dataChannel: {
                location: "remote",
                dataChannelName: "server-events",
              },
            },
          );
          const channel = object(result.dataChannel);
          if (!Number.isInteger(channel.id) || channel.errorCode) {
            throw new ApiError(502, "upstream_unavailable");
          }
          await call("_dataBind", { channel_id: channel.id, transport: true });
          return json({
            session_description: result.sessionDescription,
            channel_id: channel.id,
          });
        }
        const name = "controls";
        if (action === "dataPublish") {
          if (!publisher || media.channel_id !== null) {
            throw new ApiError(403, "unauthorized");
          }
          const result = await deps.sfu(path + "/datachannels/new", "POST", {
            dataChannels: [{ location: "local", dataChannelName: name }],
          });
          const channels = result.dataChannels;
          if (
            !Array.isArray(channels) || channels.length !== 1 ||
            !Number.isInteger(object(channels[0]).id) ||
            object(channels[0]).errorCode
          ) throw new ApiError(502, "upstream_unavailable");
          const channel_id = object(channels[0]).id;
          await call("_dataBind", { channel_id, transport: false });
          return json({ channel_id });
        }
        if (publisher) throw new ApiError(403, "unauthorized");
        if (!state.control_publication) {
          throw new ApiError(409, "publication_pending");
        }
        const source = object(state.control_publication);
        const canReply = action === "dataSubscribe" && args.can_reply === true;
        if (canReply) await call("_controlAuthorize");
        const record = {
          location: "remote",
          sessionId: validId(source.sfu_id),
          dataChannelName: name,
          canReply,
        };
        const update = media.channel_id !== null;
        const result = await deps.sfu(
          path + (update ? "/datachannels/update" : "/datachannels/new"),
          update ? "PUT" : "POST",
          { dataChannels: [record] },
        );
        if (canReply) {
          try {
            await call("_controlAuthorize");
          } catch (error) {
            await deps.sfu(path + "/datachannels/update", "PUT", {
              dataChannels: [{ ...record, canReply: false }],
            }).catch(() => {});
            throw error;
          }
        }
        if (
          !Array.isArray(result.dataChannels) ||
          result.dataChannels.length !== 1 ||
          object(result.dataChannels[0]).errorCode
        ) throw new ApiError(502, "upstream_unavailable");
        const channel_id = update
          ? media.channel_id
          : object(result.dataChannels[0]).id;
        if (!Number.isInteger(channel_id)) {
          throw new ApiError(502, "upstream_unavailable");
        }
        await call("_dataBind", { channel_id, transport: false });
        return json({ channel_id, can_reply: canReply });
      } finally {
        await call("_mediaUnlock", { operation }).catch(() => {});
      }
    } catch (error) {
      return error instanceof ApiError
        ? json({ error: error.message }, error.status)
        : json({ error: "upstream_unavailable" }, 503);
    }
  };
}
export function productionDependencies(): Dependencies {
  const base = Deno.env.get("SUPABASE_URL") ?? "";
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const rpc = async (name: string, args: ObjectValue) => {
    const response = await fetch(`${base}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers: {
        apikey: key,
        Authorization: `Bearer ${key}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(args),
      signal: AbortSignal.timeout(8000),
    });
    const value = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (
        response.status === 408 || response.status === 429 ||
        response.status >= 500
      ) {
        throw new ApiError(
          response.status === 429 ? 429 : 503,
          "upstream_unavailable",
        );
      }
      const code = object(value).code;
      throw new ApiError(
        code === "PT409"
          ? 409
          : code === "PT429"
          ? 429
          : code === "22023" || code === "22P02"
          ? 400
          : 403,
        code === "PT409"
          ? "conflict"
          : code === "PT429"
          ? "capacity"
          : code === "PT403"
          ? "approval_required"
          : code === "22023" || code === "22P02"
          ? "invalid_request"
          : "unauthorized",
      );
    }
    return value;
  };
  return {
    authenticate: async (jwt) => {
      const response = await fetch(`${base}/auth/v1/user`, {
        headers: { apikey: key, Authorization: `Bearer ${jwt}` },
        signal: AbortSignal.timeout(5000),
      });
      if (
        response.status === 408 || response.status === 429 ||
        response.status >= 500
      ) {
        throw new ApiError(
          response.status === 429 ? 429 : 503,
          "upstream_unavailable",
        );
      }
      if (!response.ok) return null;
      const user = await response.json();
      return typeof user.id === "string" ? user.id : null;
    },
    authorizeGrant: async (grant, session, generation, action, instance) => {
      const value = await rpc("fluck_web_authorize_app_sharing", {
        p_grant: grant,
        p_session_id: session,
        p_generation: generation,
        p_action: action,
        p_instance_id: instance ?? null,
      });
      return typeof value === "string" ? value : null;
    },
    rpc: (action, body, actor) =>
      rpc("app_sharing_command", {
        p_action: action,
        p_body: body,
        p_actor_id: actor,
      }),
    preferences: (actor, body) =>
      rpc(
        body
          ? "set_user_app_sharing_preferences"
          : "get_user_app_sharing_preferences",
        body
          ? {
            p_actor_id: actor,
            p_auto_admit: body.auto_admit,
            p_auto_control: body.auto_control,
            p_revision: body.revision,
          }
          : { p_actor_id: actor },
      ),
    sfu: async (path, method, body) => {
      const app = Deno.env.get("APP_SHARING_SFU_APP_ID") ?? "",
        secret = Deno.env.get("APP_SHARING_SFU_SECRET") ?? "";
      if (!id.test(app) || !secret) {
        throw new ApiError(503, "sfu_not_configured");
      }
      const response = await fetch(
        `https://rtc.live.cloudflare.com/v1/apps/${app}${path}`,
        {
          method,
          headers: {
            Authorization: `Bearer ${secret}`,
            "Content-Type": "application/json",
          },
          ...(body ? { body: JSON.stringify(body) } : {}),
          signal: AbortSignal.timeout(10000),
        },
      );
      const value = await response.json().catch(() => null);
      if (!response.ok || !value || value.errorCode) {
        throw new ApiError(502, "upstream_unavailable");
      }
      if (
        path.endsWith("/tracks/close") || path.endsWith("/datachannels/close")
      ) {
        if (!validCloseResponse(path, body ?? {}, value)) {
          throw new ApiError(502, "upstream_unavailable");
        }
        return object(value);
      }
      for (const field of ["tracks", "dataChannels"]) {
        if (
          Array.isArray(value[field]) && value[field].some((entry: unknown) => {
            const record = object(entry);
            return typeof record.errorCode === "string" &&
              record.errorCode.length > 0;
          })
        ) throw new ApiError(502, "upstream_unavailable");
      }
      if (value.dataChannel && object(value.dataChannel).errorCode) {
        throw new ApiError(502, "upstream_unavailable");
      }
      return object(value);
    },
  };
}
