import { test } from "node:test";
import assert from "node:assert/strict";
import { type Peer, Router, type Socket, type Wire } from "../src/router";
type Interest = {live: boolean; outputFps: number; previewFps: number};
type Received = Omit<Wire, "panes"> & {delivery?: number; messages?: Wire[]; panes?: string[] | Record<string, Interest>};
class Connection implements Socket {
  messages: Received[] = [];
  interests(): Record<string, Interest> {
    const value = this.messages.filter(m => m.op === "interests").at(-1)?.panes;
    assert(value && !Array.isArray(value));
    return value;
  }
  closed: number | undefined;
  send(raw: string) {
    this.messages.push(JSON.parse(raw) as Received);
  }
  close(code: number) {
    this.closed = code;
  }
  frames() {
    return this.messages.filter((m) => m.op === "frames").flatMap((m) =>
      m.messages ?? []
    ).filter((m) => m.op === "output");
  }
}
const peer = (id: string, role: Peer["role"] = "account"): Peer => ({
  id,
  role,
  admitted: role === "host",
  panes: [],
  subscriptions: {},
  expires: Date.now() + 60000,
});
function room(count = 3) {
  const router = new Router(), host = new Connection();
  router.add(peer("host", "host"), host);
  const viewers = Array.from({ length: count }, (_, i) => {
    const c = new Connection();
    router.add(peer("v" + i), c);
    router.receive(
      "host",
      JSON.stringify({ op: "grant", peer: "v" + i, panes: ["p", "q"] }),
    );
    return c;
  });
  const send = (id: string, m: unknown) =>
    router.receive(id, JSON.stringify(m));
  const subscribe = (i: number, mode: string, pane = "p", fps = 30) => {
    send("v" + i, { op: "subscribe", pane, mode, fps });
    if (pane === "private") return;
    if (mode === "live" || mode === "batch") {
      send("host", {
        op: "snapshot",
        peer: "v" + i,
        pane,
        epoch: "e",
        seq: 0,
        payload: "snapshot",
      });
      send("v" + i, {
        op: "ack",
        through: viewers[i].messages.filter((m) =>
          m.op === "frames"
        ).at(-1)!.delivery,
      });
    }
  };
  const output = (
    seq: number,
    kind = "live",
    pane = "p",
    payload = "encrypted",
  ) => send("host", { op: "output", pane, kind, seq, epoch: "e", payload });
  const dispose = () => router.remove("host");
  return { router, host, viewers, send, subscribe, output, dispose };
}
test("one publication fans out to all authorized viewers; hidden viewers receive nothing", () => {
  const r = room();
  try {
    r.subscribe(0, "live");
    r.subscribe(1, "live");
    r.subscribe(2, "hidden");
    r.output(1);
    assert.equal(r.viewers[0].frames().length, 1);
    assert.equal(r.viewers[1].frames().length, 1);
    assert.equal(r.viewers[2].frames().length, 0);
  } finally {
    r.dispose();
  }
});
test("viewer cannot publish or subscribe outside granted scope", () => {
  const r = room();
  try {
    r.send("v0", {
      op: "output",
      pane: "p",
      kind: "live",
      seq: 1,
      epoch: "e",
      payload: "forged",
    });
    assert.equal(r.viewers[0].closed, 1008);
    r.subscribe(1, "live", "private");
    assert.equal(r.viewers[1].closed, 1008);
    assert.equal(r.host.closed, undefined);
  } finally {
    r.dispose();
  }
});
test("batch preserves every delta in order", async () => {
  const r = room(1);
  try {
    r.subscribe(0, "batch");
    r.output(1);
    r.output(2);
    assert.equal(r.viewers[0].frames().length, 0);
    await new Promise((resolve) => setTimeout(resolve, 60));
    assert.deepEqual(r.viewers[0].frames().map((m) => m.seq), [1, 2]);
  } finally {
    r.dispose();
  }
});
test("preview sends newest trailing screen even if output stops", async () => {
  const r = room(1);
  try {
    r.subscribe(0, "preview");
    r.output(1, "preview");
    r.output(2, "preview");
    r.output(3, "preview");
    await new Promise((resolve) => setTimeout(resolve, 60));
    assert.deepEqual(r.viewers[0].frames().map((m) => m.seq), [1, 3]);
  } finally {
    r.dispose();
  }
});
test("slow viewer is disconnected without interrupting an acknowledging viewer", () => {
  const r = room(2);
  try {
    r.subscribe(0, "live");
    r.subscribe(1, "live");
    for (let i = 0; i < 140; i++) {
      r.output(i, "live", "p", "x".repeat(32768));
      if (i % 10 === 9) r.send("v1", { op: "ack", through: i + 2 });
    }
    assert.equal(r.viewers[0].closed, 1013);
    assert.equal(r.viewers[1].closed, undefined);
    assert.equal(r.viewers[1].frames().length, 140);
  } finally {
    r.dispose();
  }
});
test("handshake frames may be acknowledged before grant", () => {
  const router = new Router(), h = new Connection(), v = new Connection();
  router.add(peer("h", "host"), h);
  router.add(peer("v"), v);
  router.receive(
    "h",
    JSON.stringify({ op: "signal", peer: "v", payload: "key exchange" }),
  );
  router.receive("v", JSON.stringify({ op: "ack", through: 1 }));
  assert.equal(v.closed, undefined);
  router.remove("h");
});
test("hibernation restores delivery numbers and outstanding credit without repeating admission", () => {
  const records = new Map<string, Peer>();
  const r = new Router((p) => records.set(p.id, structuredClone(p))),
    h = new Connection(),
    v = new Connection();
  r.add(peer("h", "host"), h);
  r.add(peer("v"), v);
  r.receive("h", JSON.stringify({ op: "grant", peer: "v", panes: ["p"] }));
  r.receive(
    "v",
    JSON.stringify({ op: "subscribe", pane: "p", mode: "live", fps: 4 }),
  );
  r.receive(
    "h",
    JSON.stringify({
      op: "snapshot",
      peer: "v",
      pane: "p",
      epoch: "e",
      seq: 0,
      payload: "snapshot",
    }),
  );
  const output = JSON.stringify({
    op: "output",
    pane: "p",
    kind: "live",
    seq: 1,
    epoch: "e",
    payload: "cipher",
  });
  r.receive("h", output);
  const restored = new Router();
  restored.add(peer("h", "host"), h, true);
  restored.add(records.get("v")!, v, true);
  restored.receive("h", output);
  assert.deepEqual(v.messages.filter(m => m.op === "frames").map(m => m.delivery), [1, 2]);
  assert(h.messages.some(m => m.op === "resync" && m.peer === "v" && m.pane === "p"));
  restored.receive("h", JSON.stringify({op: "snapshot", peer: "v", pane: "p", epoch: "e", seq: 1, payload: "restored"}));
  restored.receive("h", output);
  assert.deepEqual(
    v.messages.filter((m) => m.op === "frames").map((m) => m.delivery),
    [1, 2, 3, 4],
  );
  restored.remove("h");
  r.remove("h");
});

test("hidden subscription discards pending batches and stops publication demand", async () => {
  const r = room(1);
  try {
    r.subscribe(0, "batch");
    r.output(1);
    r.subscribe(0, "hidden");
    await new Promise((resolve) => setTimeout(resolve, 60));
    assert.equal(r.viewers[0].frames().length, 0);
    const interests = r.host.interests();
    assert.deepEqual(interests.p, { live: false, outputFps: 0, previewFps: 0 });
  } finally {
    r.dispose();
  }
});
test("mixed preview rates request one host preview stream at maximum demand", () => {
  const r = room();
  try {
    r.subscribe(0, "preview", "p", 4);
    r.subscribe(1, "preview", "p", 10);
    r.subscribe(2, "batch", "p", 4);
    const interests = r.host.interests();
    assert.deepEqual(interests.p, { live: true, outputFps: 4, previewFps: 10 });
  } finally {
    r.dispose();
  }
});
test("revoked viewer receives no further output", () => {
  const r = room(1);
  try {
    r.subscribe(0, "live");
    r.output(1);
    r.send("host", { op: "revoke", peer: "v0" });
    r.output(2);
    assert.equal(r.viewers[0].closed, 1008);
    assert.equal(r.viewers[0].frames().length, 1);
  } finally {
    r.dispose();
  }
});

test("live resume waits for a snapshot boundary and identical subscriptions preserve queued data", async () => {
  const r = room(1);
  try {
    r.send("v0", { op: "subscribe", pane: "p", mode: "live", fps: 4 });
    r.output(1);
    assert.equal(r.viewers[0].frames().length, 0);
    r.send("host", {
      op: "snapshot",
      peer: "v0",
      pane: "p",
      seq: 1,
      epoch: "e",
      payload: "snapshot",
    });
    r.output(2);
    assert.deepEqual(r.viewers[0].frames().map((m) => m.seq), [2]);
    r.subscribe(0, "batch");
    r.output(3);
    r.send("v0", { op: "subscribe", pane: "p", mode: "batch", fps: 30 });
    await new Promise((resolve) => setTimeout(resolve, 60));
    assert.deepEqual(r.viewers[0].frames().map((m) => m.seq), [2, 3]);
  } finally {
    r.dispose();
  }
});

test("late private replies to a disconnected viewer do not disconnect the host", () => {
  const r = room(2);
  try {
    r.subscribe(1, "live");
    r.router.remove("v0");
    r.send("host", { op: "signal", peer: "v0", payload: "late reply" });
    r.send("host", { op: "grant", peer: "v0", panes: ["p"] });
    r.output(1);
    assert.equal(r.host.closed, undefined);
    assert.equal(r.viewers[1].frames().length, 1);
  } finally {
    r.dispose();
  }
});


test("snapshot replaces buffered deltas only for its pane", async () => {
  const r = room(1);
  try {
    r.subscribe(0, "batch", "p");
    r.subscribe(0, "batch", "q");
    r.output(1, "live", "p");
    r.output(1, "live", "q");
    r.send("host", {op: "snapshot", peer: "v0", pane: "p", epoch: "e", seq: 1, payload: "snapshot"});
    r.output(2, "live", "p");
    await new Promise(resolve => setTimeout(resolve, 70));
    assert.deepEqual(r.viewers[0].frames().filter(m => m.pane === "p").map(m => m.seq), [2]);
    assert.deepEqual(r.viewers[0].frames().filter(m => m.pane === "q").map(m => m.seq), [1]);
  } finally { r.dispose(); }
});

test("scope shrink discards queued output and requires a fresh boundary for retained panes", async () => {
  const r = room(2);
  try {
    r.subscribe(0, "batch", "p");
    r.subscribe(0, "batch", "q");
    r.subscribe(1, "live", "q");
    r.output(1, "live", "p");
    r.output(1, "live", "q");
    r.send("host", { op: "grant", peer: "v0", panes: ["p"] });
    r.output(2, "live", "p");
    r.output(2, "live", "q");
    await new Promise(resolve => setTimeout(resolve, 70));
    assert.deepEqual(r.viewers[0].frames(), [], "queued revoked output must never escape after scope changes");
    assert.deepEqual(r.viewers[1].frames().map(m => m.seq), [1, 2]);
    r.send("host", { op: "snapshot", peer: "v0", pane: "p", epoch: "rotated", seq: 0, payload: "fresh keys" });
    r.send("host", { op: "output", pane: "p", kind: "live", epoch: "rotated", seq: 1, payload: "fresh output" });
    await new Promise(resolve => setTimeout(resolve, 70));
    assert.deepEqual(r.viewers[0].frames().map(m => [m.pane, m.epoch, m.seq]), [["p", "rotated", 1]]);
  } finally { r.dispose(); }
});

test("host disconnect closes every viewer and reconnect starts with no inherited authorization", () => {
  const r = room(2);
  r.subscribe(0, "live");
  r.router.remove("host");
  assert.ok(r.viewers.every(v => v.closed === 1012));
  const host = new Connection(), viewer = new Connection();
  r.router.add(peer("new-host", "host"), host);
  r.router.add(peer("v0"), viewer);
  r.router.receive("v0", JSON.stringify({ op: "subscribe", pane: "p", mode: "live" }));
  assert.equal(viewer.closed, 1008, "an old peer ID does not retain a previous host's grant");
  assert.equal(host.closed, undefined);
  r.router.remove("new-host");
});


test("private fragment credits stay peer-bound and do not consume input rate allowance", () => {
  const r = room(2);
  try {
    for (let part = 0; part < 200; part++) r.send("v0", {
      op: "peerCredit", peer: "v1", payload: JSON.stringify({credit: "transfer", part}),
    });
    const credits = r.host.messages.filter(m => m.op === "peerCredit");
    assert.equal(credits.length, 200);
    assert.ok(credits.every(m => m.peer === "v0"));
    assert.equal(r.viewers[0].closed, undefined);
    r.subscribe(0, "live");
    r.output(1);
    assert.equal(r.viewers[0].frames().length, 1);
    r.send("v0", {op: "peerCredit", payload: "é".repeat(129)});
    assert.equal(r.viewers[0].closed, 1008);
    assert.equal(r.viewers[1].closed, undefined);
    assert.equal(r.host.closed, undefined);
  } finally { r.dispose(); }
});


test("batch-only output uses maximum requested rate; focused demand upgrades and departure restores it", () => {
  const r = room(3);
  const interest = () => r.host.interests().p;
  try {
    r.subscribe(0, "batch", "p", 4);
    assert.deepEqual(interest(), {live: true, outputFps: 4, previewFps: 0});
    r.subscribe(1, "batch", "p", 10);
    assert.equal(interest().outputFps, 10);
    r.subscribe(2, "live", "p", 1);
    assert.equal(interest().outputFps, 60);
    r.router.remove("v2");
    assert.equal(interest().outputFps, 10);
    r.subscribe(1, "hidden", "p", 10);
    assert.equal(interest().outputFps, 4);
    r.subscribe(0, "preview", "p", 7);
    assert.deepEqual(interest(), {live: false, outputFps: 0, previewFps: 7});
  } finally { r.dispose(); }
});

test("restored subscriptions retain their batch publication rate after interest recomputation", () => {
  const router = new Router(), host = new Connection();
  router.add(peer("host", "host"), host, true);
  router.add({...peer("batch"), admitted: true, panes: ["p"], subscriptions: {p: {mode: "batch", fps: 4}}}, new Connection(), true);
  router.add({...peer("focused"), admitted: true, panes: ["p"], subscriptions: {p: {mode: "live", fps: 4}}}, new Connection(), true);
  router.remove("focused");
  assert.deepEqual(host.interests().p, {live: true, outputFps: 4, previewFps: 0});
  router.remove("host");
});


test("pre-admission operations share a small flood budget while normal handshake remains available", () => {
  for (const op of ["signal", "peerCredit", "ack"]) {
    let writes = 0;
    const router = new Router(() => writes++, () => 1000), host = new Connection(), viewer = new Connection();
    router.add(peer("host", "host"), host); router.add(peer("v"), viewer);
    for (let i = 0; i < 13; i++) router.receive("v", JSON.stringify({op, payload: "handshake", through: 0}));
    assert.equal(viewer.closed, 1008, op);
    assert.equal(host.closed, undefined);
    assert.ok(host.messages.filter(m => m.op === op).length <= 12);
    assert.equal(writes, 0, "no-op acknowledgments must not persist attachments");
    router.remove("host");
  }
});

test("pre-admission signal bytes are bounded per message and over the handshake lifetime", () => {
  let now = 1000;
  const router = new Router(undefined, () => now), host = new Connection();
  router.add(peer("host", "host"), host);
  const oversized = new Connection(); router.add(peer("big"), oversized);
  router.receive("big", JSON.stringify({op: "signal", payload: "é".repeat(4097)}));
  assert.equal(oversized.closed, 1008);
  const repeated = new Connection(); router.add(peer("repeated"), repeated);
  for (let i = 0; i < 9; i++) {
    now += 1000;
    router.receive("repeated", JSON.stringify({op: "signal", payload: "x".repeat(8192)}));
  }
  assert.equal(repeated.closed, 1008);
  assert.equal(host.messages.filter(m => m.op === "signal").length, 8);
  router.remove("host");
});

test("duplicate and partial acknowledgments persist only released credit", () => {
  let writes = 0;
  const router = new Router(() => writes++), host = new Connection(), viewer = new Connection();
  router.add(peer("host", "host"), host); router.add(peer("v"), viewer);
  router.receive("host", JSON.stringify({op: "signal", peer: "v", payload: "first"}));
  router.receive("host", JSON.stringify({op: "signal", peer: "v", payload: "second"}));
  const before = writes;
  router.receive("v", JSON.stringify({op: "ack", through: 0}));
  router.receive("v", JSON.stringify({op: "ack", through: 1}));
  assert.equal(writes, before, "partial aggregate credit remains outstanding");
  router.receive("v", JSON.stringify({op: "ack", through: 2}));
  assert.equal(writes, before + 1);
  router.receive("v", JSON.stringify({op: "ack", through: 2}));
  assert.equal(writes, before + 1);
  router.remove("host");
});

test("healthy viewers can have several near-maximum publications in flight", () => {
  const r = room(1);
  try {
    r.subscribe(0, "live");
    for (let i = 1; i <= 4; i++) r.output(i, "live", "p", "x".repeat(900_000));
    assert.equal(r.viewers[0].closed, undefined);
    assert.equal(r.viewers[0].frames().length, 4);
    const delivery = r.viewers[0].messages.filter(m => m.op === "frames").at(-1)!.delivery;
    r.send("v0", {op: "ack", through: delivery});
    r.output(5, "live", "p", "x".repeat(900_000));
    assert.equal(r.viewers[0].closed, undefined, "acknowledgment releases running in-flight bytes");
  } finally { r.dispose(); }
});

test("queue and room byte totals stay exact across immediate previews and all removal paths", () => {
  let now = 1000;
  const router = new Router(undefined, () => now), host = new Connection(), viewer = new Connection();
  router.add(peer("host", "host"), host); router.add(peer("v"), viewer);
  const send = (id: string, value: unknown) => router.receive(id, JSON.stringify(value));
  send("host", {op: "grant", peer: "v", panes: ["p", "q"]});
  send("v", {op: "subscribe", pane: "p", mode: "preview", fps: 1});
  send("v", {op: "subscribe", pane: "q", mode: "batch", fps: 1});
  send("host", {op: "snapshot", peer: "v", pane: "q", epoch: "e", seq: 0, payload: "snapshot"});
  const output = (seq: number, pane = "p", kind = "preview") => send("host", {op: "output", pane, kind, seq, epoch: "e", payload: "encrypted"});
  type QueueState = {bytes: number; queued: Map<string, {messages: Wire[]}>; timers: Map<string, unknown>};
  const inspected = router as unknown as {peers: Map<string, QueueState>; queuedBytes: number};
  const state = inspected.peers.get("v")!;
  const invariant = () => {
    const actual = [...state.queued.values()].reduce((sum, q) => sum + q.messages.reduce((n: number, m: unknown) => n + Buffer.byteLength(JSON.stringify(m)), 0), 0);
    assert.equal(state.bytes, actual); assert.equal(inspected.queuedBytes, actual);
  };
  try {
    output(1); output(2); output(1, "q", "live"); invariant();
    now += 1500; // Timer delivery can be delayed while another socket message arrives first.
    output(3); invariant();
    assert.equal(state.queued.has("p"), false);
    assert.equal(state.timers.has("p"), false, "an overdue timer cannot flush the next preview early");
    send("v", {op: "resync", pane: "q", payload: ""}); invariant();
    output(4); invariant();
    send("v", {op: "subscribe", pane: "p", mode: "hidden", fps: 1}); invariant();
    send("v", {op: "subscribe", pane: "p", mode: "preview", fps: 1}); output(5); output(6); invariant();
    send("host", {op: "grant", peer: "v", panes: ["p"]}); invariant();
    output(7); output(8); invariant();
    router.remove("v");
    assert.equal(inspected.queuedBytes, 0);
  } finally { router.remove("host"); }
});

test("capacity preflight does not mutate peers and removal callback covers cascades", () => {
  const removed: string[] = [];
  const router = new Router(undefined, undefined, id => removed.push(id));
  router.add(peer("host", "host"), new Connection());
  assert.equal(router.canAdmit("host"), false);
  assert.equal(router.canAdmit("host", true), true);
  for (let i = 0; i < 32; i++) router.add(peer("v" + i), new Connection());
  assert.equal(router.canAdmit("guest"), false);
  router.close("v0");
  assert.equal(router.canAdmit("guest"), true);
  router.remove("host");
  assert.equal(removed.length, 33);
  assert.equal(new Set(removed).size, 33);
});

test("restoring admitted viewers is independent of the pending approval cap", () => {
  const router = new Router();
  router.add(peer("host", "host"), new Connection(), true);
  for (let i = 0; i < 32; i++) router.add(peer("pending" + i), new Connection(), true);
  assert.equal(router.canAdmit("account"), false);
  const restored = new Connection();
  router.add({...peer("restored"), admitted: true}, restored, true);
  assert.equal(restored.closed, undefined);
  assert.equal(restored.messages.length, 0);
  assert.throws(() => router.add(peer("new"), new Connection()), /Room full/);
  for (let i = 0; i < 95; i++) router.add({...peer("admitted" + i), admitted: true}, new Connection(), true);
  assert.throws(() => router.add({...peer("overflow"), admitted: true}, new Connection(), true), /Room full/);
  router.remove("host");
});

test("large queued batch frames split into bounded delivery envelopes without losing order", async () => {
  const r = room(1);
  try {
    r.subscribe(0, "batch", "p", 30);
    r.output(1, "live", "p", "a".repeat(900_000));
    r.output(2, "live", "p", "b".repeat(900_000));
    assert.equal(r.viewers[0].closed, undefined);
    await new Promise(resolve => setTimeout(resolve, 60));
    assert.equal(r.viewers[0].closed, undefined);
    assert.deepEqual(r.viewers[0].frames().map(m => m.seq), [1, 2]);
    for (const message of r.viewers[0].messages.filter(m => m.op === "frames")) {
      assert(Buffer.byteLength(JSON.stringify(message)) <= 1024 * 1024);
    }
  } finally { r.dispose(); }
});

test("a delayed near-limit preview replaces older pending frames without disconnecting", async () => {
  const r = room(1);
  try {
    r.subscribe(0, "preview", "p", 10);
    r.output(1, "preview");
    r.output(2, "preview", "p", "a".repeat(900_000));
    r.output(3, "preview", "p", "b".repeat(900_000));
    assert.equal(r.viewers[0].closed, undefined);
    await new Promise(resolve => setTimeout(resolve, 130));
    assert.deepEqual(r.viewers[0].frames().map(m => m.seq), [1, 3]);
    assert.equal(r.viewers[0].closed, undefined);
  } finally { r.dispose(); }
});

test("restored batch queues request a new snapshot before forwarding further deltas", async () => {
  const records = new Map<string, Peer>();
  const old = new Router(p => records.set(p.id, structuredClone(p)));
  const host = new Connection(), viewer = new Connection();
  old.add(peer("host", "host"), host); old.add(peer("v"), viewer);
  const send = (r: Router, id: string, message: unknown) => r.receive(id, JSON.stringify(message));
  send(old, "host", {op:"grant", peer:"v", panes:["p"]});
  send(old, "v", {op:"subscribe", pane:"p", mode:"batch", fps:30});
  send(old, "host", {op:"snapshot", peer:"v", pane:"p", epoch:"e", seq:0, payload:"initial"});
  send(old, "host", {op:"output", pane:"p", kind:"live", epoch:"e", seq:1, payload:"lost queue"});
  old.remove("v"); old.remove("host"); // Drop volatile timers, as an eviction would.
  const restored = new Router();
  restored.add(peer("host", "host"), host, true);
  const persisted = records.get("v")!;
  restored.add(persisted, viewer, true);
  try {
    assert.equal(persisted.subscriptions.p.waiting, true);
    assert(host.messages.some(m => m.op === "resync" && m.peer === "v" && m.pane === "p"));
    send(restored, "host", {op:"output", pane:"p", kind:"live", epoch:"e", seq:2, payload:"withheld"});
    await new Promise(resolve => setTimeout(resolve, 60));
    assert.equal(viewer.frames().length, 0);
    send(restored, "host", {op:"snapshot", peer:"v", pane:"p", epoch:"e", seq:2, payload:"fresh"});
    send(restored, "host", {op:"output", pane:"p", kind:"live", epoch:"e", seq:3, payload:"new"});
    await new Promise(resolve => setTimeout(resolve, 60));
    assert.deepEqual(viewer.frames().map(m => m.seq), [3]);
  } finally { restored.remove("host"); }
});

test("prototype-named granted panes never mutate inherited subscription objects", () => {
  const r = room(1);
  try {
    for (const pane of ["constructor", "toString", "__proto__"]) {
      r.send("host", {op:"grant", peer:"v0", panes:[pane]});
      r.send("v0", {op:"resync", pane, payload:""});
      r.send("host", {op:"snapshot", peer:"v0", pane, epoch:"e", seq:0, payload:"snapshot"});
    }
    assert.equal(Object.hasOwn(Object, "waiting"), false);
    assert.equal(Object.hasOwn(Object.prototype.toString, "waiting"), false);
    assert.equal(Object.hasOwn(Object.prototype, "waiting"), false);
  } finally { r.dispose(); }
});
