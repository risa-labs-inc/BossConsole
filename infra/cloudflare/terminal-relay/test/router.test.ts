import { test } from "node:test";
import assert from "node:assert/strict";
import { type Peer, Router, type Socket } from "../src/router";
class Connection implements Socket {
  messages: any[] = [];
  closed: number | undefined;
  send(raw: string) {
    this.messages.push(JSON.parse(raw));
  }
  close(code: number) {
    this.closed = code;
  }
  frames() {
    return this.messages.filter((m) => m.op === "frames").flatMap((m) =>
      m.messages
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
        ).at(-1).delivery,
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
      r.output(i, "live", "p", "x".repeat(8192));
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
  assert.deepEqual(
    v.messages.filter((m) => m.op === "frames").map((m) => m.delivery),
    [1, 2, 3],
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
    const interests = r.host.messages.filter((m) => m.op === "interests").at(
      -1,
    );
    assert.deepEqual(interests.panes.p, { live: false, outputFps: 0, previewFps: 0 });
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
    const interests = r.host.messages.filter((m) => m.op === "interests").at(
      -1,
    );
    assert.deepEqual(interests.panes.p, { live: true, outputFps: 4, previewFps: 10 });
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
  const interest = () => r.host.messages.filter(m => m.op === "interests").at(-1).panes.p;
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
  assert.deepEqual(host.messages.filter(m => m.op === "interests").at(-1).panes.p, {live: true, outputFps: 4, previewFps: 0});
  router.remove("host");
});
