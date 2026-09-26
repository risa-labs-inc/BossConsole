import { DurableObject } from "cloudflare:workers";
import { type Peer, Router } from "./router";
interface Env {
  ROOMS: DurableObjectNamespace<TerminalRoom>;
  SUPABASE_URL: string;
  RELAY_ADMISSION_KEY: string;
}
type Attachment = Peer | { pendingUntil: number };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const u = new URL(request.url),
      id = u.pathname.match(/^\/v1\/rooms\/([^/]+)$/)?.[1];
    if (!id || !UUID.test(id) || u.search) {
      return new Response("Not found", { status: 404 });
    }
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket required", { status: 426 });
    }
    return env.ROOMS.get(env.ROOMS.idFromName(id.toLowerCase())).fetch(request);
  },
} satisfies ExportedHandler<Env>;
export class TerminalRoom extends DurableObject<Env> {
  private router: Router;
  private sockets = new Map<string, WebSocket>();
  private admitting = new WeakSet<WebSocket>();
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.router = new Router(
      (peer) => this.sockets.get(peer.id)?.serializeAttachment(peer),
      Date.now,
      (id) => { this.sockets.delete(id); },
    );
    // Persist only routing and delivery credits, never terminal payloads. Pending
    // batching timers are volatile; restored live/batch subscriptions request a fresh boundary.
    const restored = ctx.getWebSockets().map((ws) => ({
      ws,
      peer: ws.deserializeAttachment() as Attachment | null,
    }));
    restored.sort((a, b) =>
      Number(b.peer && "role" in b.peer && b.peer.role === "host") -
      Number(a.peer && "role" in a.peer && a.peer.role === "host")
    );
    for (const { ws, peer } of restored) {
      // Server-initiated closes have no webSocketClose callback and must not resurrect on wake.
      if (ws.readyState !== WebSocket.OPEN) continue;
      if (peer && "pendingUntil" in peer) {
        if (peer.pendingUntil <= Date.now()) {
          ws.close(1008, "Admission timeout");
        }
        continue;
      }
      if (!peer || peer.expires <= Date.now()) {
        ws.close(1008, "Session expired");
        continue;
      }
      this.sockets.set(peer.id, ws);
      try {
        this.router.add(peer, ws, true);
      } catch {
        this.sockets.delete(peer.id);
        ws.close(1008, "Invalid session");
      }
    }
  }
  async fetch(_request: Request): Promise<Response> {
    if (this.ctx.getWebSockets().length >= 160) {
      return new Response("Room full", { status: 429 });
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    const pendingUntil = Date.now() + 10000;
    server.serializeAttachment({ pendingUntil });
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null || alarm > pendingUntil) {
      await this.ctx.storage.setAlarm(pendingUntil);
    }
    return new Response(null, { status: 101, webSocket: client });
  }
  async webSocketMessage(ws: WebSocket, data: string | ArrayBuffer) {
    if (typeof data !== "string" || data.length > 1024 * 1024) {
      this.closeSocket(ws, 1009, "Message too large");
      return;
    }
    const peer = ws.deserializeAttachment() as Attachment | null;
    if (peer && "id" in peer) {
      this.router.receive(peer.id, data);
      return;
    }
    // Reject untrusted hello bytes before JSON parsing or contacting the admission service.
    if (data.length > 4096 || new TextEncoder().encode(data).byteLength > 4096) {
      this.closeSocket(ws, 1009, "Hello too large");
      return;
    }
    if (!peer || !("pendingUntil" in peer) || peer.pendingUntil <= Date.now()) {
      this.closeSocket(ws, 1008, "Admission timeout");
      return;
    }
    if (this.admitting.has(ws)) {
      this.closeSocket(ws, 1008, "Admission in progress");
      return;
    }
    this.admitting.add(ws);
    try {
      const hello = JSON.parse(data);
      if (
        hello.op !== "hello" || hello.v !== 1 || typeof hello.room !== "string" || !UUID.test(hello.room)
      ) throw new Error("hello");
      if (
        !this.ctx.id.equals(this.env.ROOMS.idFromName(hello.room.toLowerCase()))
      ) {
        throw new Error("room");
      }
      let role: Peer["role"] = "guest";
      if (hello.ticket) {
        if (
          typeof hello.ticket !== "string" ||
          !/^[-_A-Za-z0-9]{43}$/.test(hello.ticket)
        ) throw new Error("ticket");
        // The ticket's role is confidential until redemption. Reject only if neither role
        // could fit; a fresh owner host ticket may replace its still-connected predecessor.
        if (!this.router.canAdmit("host", true) && !this.router.canAdmit("account")) {
          throw new Error("capacity");
        }
        const body = JSON.stringify({p_token: hello.ticket, p_room_id: hello.room});
        if (!this.env.RELAY_ADMISSION_KEY || this.env.RELAY_ADMISSION_KEY.length < 32) throw new Error("configuration");
        const encoder = new TextEncoder();
        const key = await crypto.subtle.importKey("raw", encoder.encode(this.env.RELAY_ADMISSION_KEY),
          {name: "HMAC", hash: "SHA-256"}, false, ["sign"]);
        const signature = new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(body)));
        const r = await fetch(
          `${this.env.SUPABASE_URL}/functions/v1/relay-admission`,
          {
            method: "POST",
            headers: {
              "X-Relay-Signature": Array.from(signature, byte => byte.toString(16).padStart(2, "0")).join(""),
              "Content-Type": "application/json",
            },
            body,
            signal: AbortSignal.timeout(5000),
          },
        );
        if (!r.ok) throw new Error("admission");
        const result = await r.json() as { role: string };
        if (result.role !== "host" && result.role !== "account") {
          throw new Error("role");
        }
        role = result.role;
      }
      if (ws.readyState !== WebSocket.OPEN) throw new Error("closed");
      if (!this.router.canAdmit(role, role === "host")) throw new Error("capacity");
      if (role === "host") {
        // Only a freshly consumed owner-bound host ticket can displace the old connection.
        // No await between replacement and add: concurrent redemptions serialize here.
        // Closing the old host also discards all old viewer grants/credits and forces reapproval.
        for (const [id, socket] of this.sockets) {
          const old = socket.deserializeAttachment() as Peer;
          if (old.role === "host") this.router.close(id, 1012, "Host reconnected");
        }
      }
      const next: Peer = {
        id: crypto.randomUUID(),
        role,
        admitted: role === "host",
        panes: [],
        subscriptions: {},
        expires: Date.now() + (role === "host" ? 12 * 60 * 60 * 1000 : 130_000),
      };
      this.sockets.set(next.id, ws);
      ws.serializeAttachment(next);
      try { this.router.add(next, ws); }
      catch (error) { this.sockets.delete(next.id); throw error; }
    } catch {
      this.closeSocket(ws, 1008, "Admission refused");
    } finally {
      this.admitting.delete(ws);
    }
  }
  async alarm() {
    let next = Infinity;
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.readyState !== WebSocket.OPEN) continue;
      const attachment = ws.deserializeAttachment() as Attachment | null;
      if (!attachment) {
        ws.close(1008, "Invalid session");
        continue;
      }
      const expires = "id" in attachment
        ? attachment.expires
        : attachment.pendingUntil;
      if (expires <= Date.now()) {
        if ("id" in attachment) {
          this.router.close(attachment.id, 1008, "Session expired");
        } else ws.close(1008, "Admission timeout");
      } else next = Math.min(next, expires);
    }
    if (Number.isFinite(next)) await this.ctx.storage.setAlarm(next);
  }
  private closeSocket(ws: WebSocket, code: number, reason: string) {
    const peer = ws.deserializeAttachment() as Attachment | null;
    if (peer && "id" in peer) this.router.close(peer.id, code, reason);
    if (ws.readyState === WebSocket.OPEN) ws.close(code, reason);
  }
  webSocketClose(ws: WebSocket) {
    const p = ws.deserializeAttachment() as Attachment | null;
    if (p && "id" in p) {
      this.router.remove(p.id);
      this.sockets.delete(p.id);
    }
  }
  webSocketError(ws: WebSocket) {
    this.webSocketClose(ws);
  }
}
