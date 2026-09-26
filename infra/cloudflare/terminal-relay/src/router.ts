/** Routing metadata is public to the relay; payloads are end-to-end encrypted. */
export type Mode = "live" | "batch" | "preview" | "hidden";
export interface Peer {
  id: string;
  role: "host" | "account" | "guest";
  admitted: boolean;
  panes: string[];
  subscriptions: Record<string, { mode: Mode; fps: number; waiting?: boolean }>;
  expires: number;
  delivery?: number;
  outstanding?: [number, number][];
}
export interface Socket {
  send(data: string): void;
  close(code: number, reason: string): void;
}
export interface Wire {
  op: string;
  pane?: string;
  kind?: "live" | "preview";
  seq?: number;
  epoch?: string;
  payload?: string;
  peer?: string;
  panes?: string[];
  mode?: Mode;
  fps?: number;
  through?: number;
}
const MAX_BYTES = 1024 * 1024, MAX_PENDING = 4 * MAX_BYTES;
const MAX_IN_FLIGHT = 4 * MAX_BYTES, MAX_ROOM_PENDING = 16 * MAX_BYTES;
const MAX_PRE_ADMISSION_SIGNAL = 8192, MAX_PRE_ADMISSION_BYTES = 65536;
const bytes = (value: string) => new TextEncoder().encode(value).byteLength;
const ID = /^[A-Za-z0-9_.:-]{1,128}$/;
interface State {
  peer: Peer;
  socket: Socket;
  queued: Map<string, { messages: Wire[]; bytes: number }>;
  bytes: number;
  flight: Map<number, number>;
  flightBytes: number;
  preAdmissionBytes: number;
  serial: number;
  timers: Map<string, ReturnType<typeof setTimeout>>;
  lastPreview: Map<string, number>;
  tokens: number;
  tick: number;
  ackTokens: number;
  ackTick: number;
}
export class Router {
  private peers = new Map<string, State>();
  private queuedBytes = 0;
  constructor(
    private changed: (peer: Peer) => void = () => {},
    private now: () => number = Date.now,
    private removed: (id: string, peer: Peer) => void = () => {},
  ) {}
  canAdmit(role: Peer["role"], replacingHost = false): boolean {
    const hasHost = [...this.peers.values()].some(s => s.peer.role === "host");
    if (role === "host" && hasHost) return replacingHost;
    return this.peers.size < 129 && (role === "host" ||
      [...this.peers.values()].filter(s => !s.peer.admitted).length < 32);
  }
  add(peer: Peer, socket: Socket, restoring = false) {
    // Previously admitted viewers do not occupy a pending-approval slot on wake.
    const eligible = restoring && peer.admitted && peer.role !== "host"
      ? this.peers.size < 129
      : this.canAdmit(peer.role);
    if (this.peers.has(peer.id) || !eligible) throw new Error("Room full or host already connected");
    this.peers.set(peer.id, {
      peer,
      socket,
      queued: new Map(),
      bytes: 0,
      flight: new Map(peer.outstanding ?? []),
      flightBytes: (peer.outstanding ?? []).reduce((sum, [, size]) => sum + size, 0),
      preAdmissionBytes: 0,
      serial: peer.delivery ?? 0,
      timers: new Map(),
      lastPreview: new Map(),
      tokens: peer.role === "host" ? 2000 : peer.admitted ? 100 : 12,
      tick: this.now(),
      ackTokens: 10000,
      ackTick: this.now(),
    });
    if (restoring) {
      // Timer queues are volatile. Never assume the last persisted boundary covers lost deltas.
      for (const subscription of Object.values(peer.subscriptions)) {
        if (subscription.mode === "live" || subscription.mode === "batch") subscription.waiting = true;
      }
      const state = this.peers.get(peer.id)!;
      if (!this.persist(state)) return;
      for (const [pane, subscription] of Object.entries(peer.subscriptions)) {
        if (subscription.waiting && (subscription.mode === "live" || subscription.mode === "batch")) {
          this.host({op: "resync", peer: peer.id, pane, payload: ""});
        }
      }
      return;
    }
    this.send(peer.id, { op: "welcome", peer: peer.id, v: 1 });
    if (peer.role !== "host") {
      this.host({
        op: "join",
        peer: peer.id,
        payload: JSON.stringify({ account: peer.role === "account" }),
      });
    } else {this.send(peer.id, {
        op: "peers",
        payload: JSON.stringify(
          [...this.peers.values()].filter((s) => s.peer.role !== "host").map(
            (s) => ({ id: s.peer.id, account: s.peer.role === "account" }),
          ),
        ),
      });}
  }
  remove(id: string) {
    const s = this.peers.get(id);
    if (!s) return;
    for (const timer of s.timers.values()) clearTimeout(timer);
    this.clearQueued(s);
    this.peers.delete(id);
    this.removed(id, s.peer);
    if (s.peer.role === "host") {
      for (const other of [...this.peers.keys()]) {
        this.close(other, 1012, "Host disconnected");
      }
    } else {
      this.host({ op: "leave", peer: id });
      this.interests();
    }
  }
  close(id: string, code = 1008, reason = "Invalid relay message") {
    const s = this.peers.get(id);
    this.remove(id);
    try {
      s?.socket.close(code, reason);
    } catch {}
  }
  receive(id: string, raw: string) {
    const s = this.peers.get(id);
    if (!s) return;
    try {
      if (bytes(raw) > MAX_BYTES || this.now() > s.peer.expires) {
        throw new Error("expired or oversized");
      }
      const m = JSON.parse(raw) as Wire;
      if (!m || typeof m.op !== "string") throw new Error("message");
      const now = this.now();
      if (!s.peer.admitted && s.peer.role !== "host") {
        // Approval needs a small key-exchange/hello exchange, not the admitted data budget.
        s.tokens = Math.min(12, s.tokens + Math.max(0, now - s.tick) * 4 / 1000);
        s.tick = now;
        if (--s.tokens < 0) throw new Error("pre-admission rate");
      } else if (m.op === "ack" || m.op === "peerCredit") {
        s.ackTokens = Math.min(10000, s.ackTokens + (now - s.ackTick) * 5);
        s.ackTick = now;
        if (--s.ackTokens < 0) throw new Error("ack rate");
      } else {
        const rate = s.peer.role === "host" ? 1000 : 50;
        s.tokens = Math.min(rate * 2, s.tokens + (now - s.tick) * rate / 1000);
        s.tick = now;
        if (--s.tokens < 0) throw new Error("rate");
      }
      if (m.op === "ping") {
        this.send(id, { op: "pong" });
        return;
      }
      if (s.peer.role === "host") {
        this.fromHost(s, m);
        return;
      }
      if (m.op === "signal") {
        if (typeof m.payload !== "string" || bytes(m.payload) >
          (s.peer.admitted ? 65536 : MAX_PRE_ADMISSION_SIGNAL)) {
          throw new Error("signal");
        }
        if (!s.peer.admitted) {
          s.preAdmissionBytes += bytes(m.payload);
          if (s.preAdmissionBytes > MAX_PRE_ADMISSION_BYTES) throw new Error("handshake budget");
        }
        this.host({ op: "signal", peer: id, payload: m.payload });
        return;
      }
      if (m.op === "peerCredit") {
        if (typeof m.payload !== "string" || bytes(m.payload) > 256) {
          throw new Error("private fragment credit");
        }
        // Sender identity is relay-owned; an untrusted viewer cannot credit another peer.
        // The host also matches the unpredictable fragment id and exact part number.
        this.host({ op: "peerCredit", peer: id, payload: m.payload });
        return;
      }
      if (m.op === "ack") {
        if (
          !Number.isSafeInteger(m.through) || m.through! < 0 ||
          m.through! > s.serial
        ) throw new Error("ack");
        let changed = false;
        for (const [n, size] of s.flight) {
          if (n <= m.through!) { s.flight.delete(n); s.flightBytes -= size; changed = true; }
        }
        if (changed) this.persist(s);
        return;
      }
      if (!s.peer.admitted) throw new Error("not admitted");
      if (m.op === "subscribe") {
        if (
          !m.pane || !s.peer.panes.includes(m.pane) ||
          !["live", "batch", "preview", "hidden"].includes(m.mode!) ||
          !Number.isInteger(m.fps) || m.fps! < 1 || m.fps! > 30
        ) throw new Error("subscription");
        const previous = s.peer.subscriptions[m.pane];
        if (previous?.mode === m.mode && previous?.fps === m.fps) return;
        s.peer.subscriptions = {
          ...s.peer.subscriptions,
          [m.pane]: {
            mode: m.mode!,
            fps: m.fps!,
            waiting: m.mode === "live" || m.mode === "batch",
          },
        };
        const timer = s.timers.get(m.pane);
        if (timer !== undefined) clearTimeout(timer);
        s.timers.delete(m.pane);
        s.lastPreview.delete(m.pane);
        this.takeQueued(s, m.pane);
        if (!this.persist(s)) return;
        this.host({ ...m, peer: id });
        this.interests();
        return;
      }
      if (m.op === "input" || m.op === "resync") {
        if (
          !m.pane || !s.peer.panes.includes(m.pane) ||
          typeof m.payload !== "string" || m.payload.length > 65536
        ) throw new Error("input");
        if (m.op === "resync") {
          const subscription = Object.hasOwn(s.peer.subscriptions, m.pane) ? s.peer.subscriptions[m.pane] : undefined;
          if (subscription) subscription.waiting = true;
          this.takeQueued(s, m.pane);
          if (!this.persist(s)) return;
        }
        // The host verifies the sender, role and sequence inside the encrypted payload.
        this.host({ op: m.op, peer: id, pane: m.pane, payload: m.payload });
        return;
      }
      throw new Error("operation");
    } catch {
      this.close(id);
    }
  }
  private fromHost(s: State, m: Wire) {
    if (m.op === "grant") {
      const v = this.peers.get(m.peer!);
      if (!v) return; // A reply can race the viewer closing its connection.
      if (
        v.peer.role === "host" || !Array.isArray(m.panes) ||
        m.panes.length > 1000 || !m.panes.every((p) =>
          typeof p === "string" && ID.test(p)
        )
      ) throw new Error("grant");
      v.peer.panes = [...new Set(m.panes)];
      if (!v.peer.admitted) v.peer.expires = this.now() + 12 * 60 * 60 * 1000;
      v.peer.admitted = true;
      v.peer.subscriptions = Object.fromEntries(
        Object.entries(v.peer.subscriptions).filter(([p]) =>
          v.peer.panes.includes(p)
        ),
      );
      for (const subscription of Object.values(v.peer.subscriptions)) {
        subscription.waiting = subscription.mode === "live" ||
          subscription.mode === "batch";
      }
      for (const timer of v.timers.values()) clearTimeout(timer);
      v.timers.clear();
      this.clearQueued(v);
      if (!this.persist(v)) return;
      this.send(v.peer.id, m);
      this.interests();
      return;
    }
    if (m.op === "revoke") {
      if (m.peer === s.peer.id) throw new Error("self revoke");
      this.close(m.peer!, 1008, "Access revoked");
      return;
    }
    if (m.op === "signal" || m.op === "snapshot") {
      const v = this.peers.get(m.peer!);
      if (!v) return; // A reply can race the viewer closing its connection.
      if (v.peer.role === "host" || typeof m.payload !== "string") {
        throw new Error("target");
      }
      if (
        m.op === "snapshot" &&
        (!v.peer.admitted || !v.peer.panes.includes(m.pane!))
      ) throw new Error("snapshot");
      if (m.op === "snapshot") {
        if (
          !Number.isSafeInteger(m.seq) || m.seq! < 0 || !m.epoch ||
          !ID.test(m.epoch)
        ) throw new Error("snapshot boundary");
        const subscription = Object.hasOwn(v.peer.subscriptions, m.pane!) ? v.peer.subscriptions[m.pane!] : undefined;
        if (subscription) subscription.waiting = false;
        // This snapshot already includes every earlier delta. A delayed batch must
        // never replay those bytes after the receiver installs the new boundary.
        const timer = v.timers.get(m.pane!);
        if (timer !== undefined) clearTimeout(timer);
        v.timers.delete(m.pane!);
        this.takeQueued(v, m.pane!);
        if (!this.persist(v)) return;
      }
      this.deliver(v, [m]);
      return;
    }
    if (
      m.op !== "output" || !m.pane || !ID.test(m.pane) ||
      !["live", "preview"].includes(m.kind!) || !Number.isSafeInteger(m.seq) ||
      m.seq! < 0 || !m.epoch || !ID.test(m.epoch) ||
      typeof m.payload !== "string"
    ) throw new Error("output");
    const messageBytes = bytes(JSON.stringify(m));
    for (const v of [...this.peers.values()]) {
      if (this.now() > v.peer.expires) {
        this.close(v.peer.id, 1008, "Session expired");
        continue;
      }
      const sub = Object.hasOwn(v.peer.subscriptions, m.pane)
        ? v.peer.subscriptions[m.pane]
        : undefined;
      if (
        v.peer.role === "host" || !v.peer.admitted ||
        !v.peer.panes.includes(m.pane) || !sub || sub.mode === "hidden" ||
        (m.kind === "live" && sub.waiting) ||
        (sub.mode === "preview") !== (m.kind === "preview")
      ) continue;
      if (sub.mode === "live") this.deliver(v, [m]);
      else {
        const delay = sub.mode === "preview"
          ? Math.max(
            0,
            1000 / sub.fps -
              (this.now() - (v.lastPreview.get(m.pane) ?? -Infinity)),
          )
          : 1000 / sub.fps;
        if (sub.mode === "preview") {
          // A preview is a complete screen, so only the newest pending one is needed.
          this.takeQueued(v, m.pane);
          if (delay === 0) {
            const pendingTimer = v.timers.get(m.pane);
            if (pendingTimer !== undefined) clearTimeout(pendingTimer);
            v.timers.delete(m.pane);
            v.lastPreview.set(m.pane, this.now());
            this.deliver(v, [m]);
            continue;
          }
        }
        const queue = v.queued.get(m.pane) ?? { messages: [], bytes: 0 };
        queue.messages.push(m); queue.bytes += messageBytes;
        v.queued.set(m.pane, queue);
        v.bytes += messageBytes; this.queuedBytes += messageBytes;
        if (
          v.bytes > MAX_PENDING ||
          this.queuedBytes > MAX_ROOM_PENDING
        ) {
          this.stalled(v);
          continue;
        }
        if (!v.timers.has(m.pane)) {
          const pane = m.pane;
          v.timers.set(
            pane,
            setTimeout(() => {
              v.timers.delete(pane);
              const q = this.takeQueued(v, pane);
              if (q.length) {
                if (q[0].kind === "preview") {
                  v.lastPreview.set(pane, this.now());
                }
                this.deliver(v, q);
              }
            }, delay),
          );
        }
      }
    }
  }
  private takeQueued(s: State, pane: string): Wire[] {
    const queue = s.queued.get(pane);
    if (!queue) return [];
    s.queued.delete(pane);
    s.bytes -= queue.bytes; this.queuedBytes -= queue.bytes;
    return queue.messages;
  }
  private clearQueued(s: State) {
    this.queuedBytes -= s.bytes;
    s.bytes = 0; s.queued.clear();
  }
  private deliver(s: State, messages: Wire[]) {
    let batch: Wire[] = [], batchBytes = 0;
    for (const message of messages) {
      const size = bytes(JSON.stringify(message));
      // Reserve enough for the envelope, delivery integer and commas; each part retains order.
      if (batch.length && batchBytes + size + 128 > MAX_BYTES) {
        this.deliverBatch(s, batch);
        if (!this.peers.has(s.peer.id)) return;
        batch = []; batchBytes = 0;
      }
      batch.push(message); batchBytes += size + 1;
    }
    if (batch.length) this.deliverBatch(s, batch);
  }
  private deliverBatch(s: State, messages: Wire[]) {
    const data = JSON.stringify({
      op: "frames",
      delivery: ++s.serial,
      messages,
    });
    const dataBytes = bytes(data);
    if (dataBytes > MAX_BYTES || s.flight.size >= 128 || s.flightBytes + dataBytes > MAX_IN_FLIGHT) {
      this.stalled(s);
      return;
    }
    // Aggregate up to sixteen adjacent deliveries in one credit record. Partial
    // acknowledgments release a bucket only once its final delivery is applied.
    const last = [...s.flight.keys()].at(-1);
    let credit = dataBytes;
    s.flightBytes += dataBytes;
    if (
      last !== undefined &&
      Math.floor((last - 1) / 16) === Math.floor((s.serial - 1) / 16)
    ) {
      credit += s.flight.get(last)!;
      s.flight.delete(last);
    }
    s.flight.set(s.serial, credit);
    if (!this.persist(s)) return;
    try {
      s.socket.send(data);
    } catch {
      this.close(s.peer.id, 1011, "Connection lost");
    }
  }
  private persist(s: State) {
    s.peer.delivery = s.serial;
    s.peer.outstanding = [...s.flight.entries()];
    // Cloudflare attachments have a 16 KiB limit. Leave room for clone overhead.
    if (bytes(JSON.stringify(s.peer)) > 14000) {
      this.close(s.peer.id, 1009, "Too many subscriptions");
      return false;
    }
    this.changed(s.peer);
    return true;
  }
  private stalled(s: State) {
    this.close(s.peer.id, 1013, "Slow viewer; reconnect for a fresh snapshot");
  }
  private send(id: string, m: unknown) {
    try {
      this.peers.get(id)?.socket.send(JSON.stringify(m));
    } catch {
      this.close(id, 1011, "Connection lost");
    }
  }
  private host(m: unknown) {
    const h = [...this.peers.values()].find((s) => s.peer.role === "host");
    if (h) this.send(h.peer.id, m);
  }
  private interests() {
    const panes: Record<string, { live: boolean; outputFps: number; previewFps: number }> = Object
      .create(null);
    for (const s of this.peers.values()) {
      if (s.peer.admitted && s.peer.role !== "host") {
        for (const [p, sub] of Object.entries(s.peer.subscriptions)) {
          const value = panes[p] ??= { live: false, outputFps: 0, previewFps: 0 };
          if (sub.mode === "live" || sub.mode === "batch") {
            value.live = true; // Compatibility: full-output demand, including batched consumers.
            value.outputFps = Math.max(value.outputFps, sub.mode === "live" ? 60 : sub.fps);
          }
          if (sub.mode === "preview") {
            value.previewFps = Math.max(value.previewFps, sub.fps);
          }
        }
      }
    }
    this.host({ op: "interests", panes });
  }
}
