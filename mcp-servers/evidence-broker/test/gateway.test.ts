import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, existsSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { FileApprovalGateway, sanitizeId } from "../src/gateway-file.js";
import { actionDigest, actionShapeOf, type ApprovalRequest } from "../src/approval.js";
import { FakeClock, SeqIds } from "./helpers.js";

function tempDir(): string {
  return mkdtempSync(join(tmpdir(), "evbroker-"));
}

const REQUEST: ApprovalRequest = (() => {
  const shape = actionShapeOf(
    "STRIPE_KEY",
    { method: "POST", url: "https://api.stripe.com/v1/refunds", body: '{"charge":"ch_1"}' },
    "api.stripe.com",
    "/v1/refunds",
  );
  return { credential: "STRIPE_KEY", shape, digest: actionDigest(shape), purpose: "refund a duplicate charge" };
})();

test("an operator approval is picked up and returned", async () => {
  const clock = new FakeClock();
  const dir = tempDir();
  const gateway = new FileApprovalGateway({
    dir,
    clock,
    ids: new SeqIds(),
    timeoutMs: 10_000,
    pollIntervalMs: 100,
    // The operator answers on the second poll. Deterministic, and no real wait.
    sleep: async (ms) => {
      clock.advance(ms);
      gateway.writeDecision("grant-1", { approved: true });
    },
  });

  const outcome = await gateway.decide(REQUEST);
  assert.equal(outcome.approved, true);
});

test("an operator denial carries the reason back", async () => {
  const clock = new FakeClock();
  const gateway = new FileApprovalGateway({
    dir: tempDir(),
    clock,
    ids: new SeqIds(),
    timeoutMs: 10_000,
    pollIntervalMs: 100,
    sleep: async (ms) => {
      clock.advance(ms);
      gateway.writeDecision("grant-1", { approved: false, reason: "this is a production refund" });
    },
  });

  const outcome = await gateway.decide(REQUEST);
  assert.equal(outcome.approved, false);
  if (outcome.approved) return;
  assert.match(outcome.reason, /production refund/);
});

test("silence is a denial, not an approval", async () => {
  const clock = new FakeClock();
  const gateway = new FileApprovalGateway({
    dir: tempDir(),
    clock,
    ids: new SeqIds(),
    timeoutMs: 1_000,
    pollIntervalMs: 250,
    // Nobody ever answers; time just passes.
    sleep: async (ms) => clock.advance(ms),
  });

  const outcome = await gateway.decide(REQUEST);
  assert.equal(outcome.approved, false);
  if (outcome.approved) return;
  assert.match(outcome.reason, /no operator answered/);
});

test("the pending request is written for the operator, then cleaned up", async () => {
  const clock = new FakeClock();
  const dir = tempDir();
  let seenWhileWaiting: string[] = [];

  const gateway = new FileApprovalGateway({
    dir,
    clock,
    ids: new SeqIds(),
    timeoutMs: 10_000,
    pollIntervalMs: 100,
    sleep: async (ms) => {
      clock.advance(ms);
      // Snapshot what the operator would see mid-flight.
      seenWhileWaiting = readdirSync(join(dir, "pending"));
      gateway.writeDecision("grant-1", { approved: true });
    },
  });

  await gateway.decide(REQUEST);

  assert.deepEqual(seenWhileWaiting, ["grant-1.json"]);
  // Afterwards the queue is empty; the evidence chain is the durable record.
  assert.deepEqual(readdirSync(join(dir, "pending")), []);
  assert.equal(existsSync(join(dir, "decisions", "grant-1.json")), false);
});

test("listPending shows the operator what to decide, without any secret material", async () => {
  const clock = new FakeClock();
  const dir = tempDir();
  const captured: string[] = [];

  const gateway = new FileApprovalGateway({
    dir,
    clock,
    ids: new SeqIds(),
    timeoutMs: 10_000,
    pollIntervalMs: 100,
    sleep: async (ms) => {
      clock.advance(ms);
      captured.push(JSON.stringify(gateway.listPending()));
      gateway.writeDecision("grant-1", { approved: true });
    },
  });

  await gateway.decide(REQUEST);

  const snapshot = captured[0];
  assert.ok(snapshot);
  const pending = JSON.parse(snapshot) as Array<Record<string, unknown>>;
  assert.equal(pending.length, 1);
  const entry = pending[0];
  assert.ok(entry);
  assert.equal(entry["credential"], "STRIPE_KEY");
  assert.equal(entry["method"], "POST");
  assert.equal(entry["host"], "api.stripe.com");
  assert.equal(entry["path"], "/v1/refunds");
  assert.equal(entry["purpose"], "refund a duplicate charge");
  // The body was hashed, not carried.
  assert.equal(snapshot.includes("ch_1"), false);
});

test("listPending is empty when nothing is waiting", () => {
  const gateway = new FileApprovalGateway({ dir: tempDir(), clock: new FakeClock(), ids: new SeqIds() });
  assert.deepEqual(gateway.listPending(), []);
});

test("a decision id cannot escape the decisions directory", () => {
  assert.equal(sanitizeId("grant-1"), "grant-1");
  assert.equal(sanitizeId("../../etc/passwd"), "....etcpasswd");
  assert.throws(() => sanitizeId("../"), /not a usable approval id/);
  assert.throws(() => sanitizeId(""), /not a usable approval id/);
  assert.throws(() => sanitizeId("///"), /not a usable approval id/);
});

test("a decision for a different id is not mistaken for this one", async () => {
  const clock = new FakeClock();
  const gateway = new FileApprovalGateway({
    dir: tempDir(),
    clock,
    ids: new SeqIds(),
    timeoutMs: 1_000,
    pollIntervalMs: 250,
    sleep: async (ms) => {
      clock.advance(ms);
      // Answering the wrong request must not release this one.
      gateway.writeDecision("grant-99", { approved: true });
    },
  });

  const outcome = await gateway.decide(REQUEST);
  assert.equal(outcome.approved, false);
});
