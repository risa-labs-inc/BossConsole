import assert from "node:assert/strict";
import { performance } from "node:perf_hooks";
import { type Peer, Router, type Socket } from "../src/router";

// Measures in-process routing only: no network, encryption, rendering, or timer latency.
const results = [];
for (const panes of [10, 50, 100]) {
  for (const viewers of [1, 3, 10]) {
    let now = Date.now(), deliveries = 0, downstreamBytes = 0;
    const router = new Router(() => {}, () => now);
    const peer = (id: string, role: Peer["role"]): Peer => ({
      id,
      role,
      admitted: role === "host",
      panes: [],
      subscriptions: {},
      expires: now + 60000,
    });
    const ids = Array.from(
      { length: panes },
      (_, i) => `11111111-1111-4111-8111-${String(i).padStart(12, "0")}`,
    );
    const last = new Map<string, number>();
    const connection = (id: string): Socket => ({
      send(text) {
        const m = JSON.parse(text);
        if (m.op === "frames") {
          last.set(id, m.delivery);
          deliveries += m.messages.filter((m: any) => m.op === "output").length;
          downstreamBytes += Buffer.byteLength(text);
        }
      },
      close(code, reason) {
        throw Error(`${id} closed ${code}: ${reason}`);
      },
    });
    router.add(peer("host", "host"), connection("host"));
    const send = (id: string, message: unknown) =>
      router.receive(id, JSON.stringify(message));
    for (let i = 0; i < viewers; i++) {
      const id = "viewer-" + i;
      router.add(peer(id, "account"), connection(id));
      send("host", { op: "grant", peer: id, panes: ids });
      for (const pane of ids) {
        send(id, { op: "subscribe", pane, mode: "live", fps: 4 });
        send("host", {
          op: "snapshot",
          peer: id,
          pane,
          epoch: "epoch",
          seq: 0,
          payload: "snapshot",
        });
      }
      send(id, { op: "ack", through: last.get(id) });
    }
    downstreamBytes = 0;
    const start = performance.now();
    let publications = 0, upstreamBytes = 0;
    for (let cycle = 1; cycle <= 20; cycle++) {
      now += 250;
      for (const pane of ids) {
        const raw = JSON.stringify({
          op: "output",
          pane,
          kind: "live",
          seq: cycle,
          epoch: "epoch",
          payload: "x".repeat(1024),
        });
        publications++;
        upstreamBytes += Buffer.byteLength(raw);
        router.receive("host", raw);
      }
      for (let i = 0; i < viewers; i++) {
        send("viewer-" + i, { op: "ack", through: last.get("viewer-" + i) });
      }
    }
    const elapsedMs = performance.now() - start;
    assert.equal(deliveries, publications * viewers);
    results.push({
      panes,
      viewers,
      publications,
      deliveries,
      upstreamBytes,
      downstreamBytes,
      elapsedMs: Number(elapsedMs.toFixed(2)),
    });
    // Avoid treating intentional cleanup as a benchmark failure.
    for (let i = 0; i < viewers; i++) {
      router.remove("viewer-" + i);
    }
    router.remove("host");
  }
}
console.log(
  JSON.stringify(
    {
      scope:
        "In-process routing only; simulated 20 update cycles, no network or rendering",
      results,
    },
    null,
    2,
  ),
);
