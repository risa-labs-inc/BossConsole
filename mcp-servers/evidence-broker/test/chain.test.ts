import test from "node:test";
import assert from "node:assert/strict";

import { EvidenceChain, GENESIS_HASH, hashEntry, verifyChain } from "../src/chain.js";
import { canonicalJson } from "../src/canonical.js";
import type { SealedEvidence } from "../src/types.js";

const TS = "2026-09-20T10:00:00.000Z";

function buildChain(n: number): SealedEvidence[] {
  const chain = new EvidenceChain();
  const out: SealedEvidence[] = [];
  for (let i = 1; i <= n; i += 1) {
    out.push(chain.seal("brokered-request", { i, host: "api.example.com" }, TS));
  }
  return out;
}

test("the first entry links to genesis and is numbered 1", () => {
  const [first] = buildChain(1);
  assert.ok(first);
  assert.equal(first.seq, 1);
  assert.equal(first.prevHash, GENESIS_HASH);
  assert.match(first.hash, /^[0-9a-f]{64}$/);
});

test("a well-formed chain verifies, and against its own head", () => {
  const entries = buildChain(5);
  const r = verifyChain(entries);
  assert.equal(r.ok, true);
  if (!r.ok) return;
  assert.equal(r.entries, 5);
  assert.equal(verifyChain(entries, r.head).ok, true);
});

test("editing a payload in the middle is detected at that entry", () => {
  const entries = buildChain(5);
  const target = entries[2];
  assert.ok(target);
  const tampered = entries.map((e, i) => (i === 2 ? { ...e, payload: { ...e.payload, host: "evil.test" } } : e));
  const r = verifyChain(tampered);
  assert.equal(r.ok, false);
  if (r.ok) return;
  assert.equal(r.brokenAtSeq, target.seq);
  assert.match(r.reason, /altered/);
});

test("removing an entry from the middle breaks the link", () => {
  const entries = buildChain(5);
  const r = verifyChain([...entries.slice(0, 2), ...entries.slice(3)]);
  assert.equal(r.ok, false);
  if (r.ok) return;
  assert.match(r.reason, /sequence jumped/);
});

test("re-sealing a tampered entry still fails, because the link no longer holds", () => {
  // The interesting case: an attacker who knows the format recomputes the hash
  // of the row they edited. The next row's prevHash is now wrong.
  const entries = buildChain(4);
  const victim = entries[1];
  assert.ok(victim);
  const edited = { ...victim, payload: { ...victim.payload, host: "evil.test" } };
  const resealed: SealedEvidence = { ...edited, hash: hashEntry(edited) };
  const r = verifyChain([entries[0]!, resealed, entries[2]!, entries[3]!]);
  assert.equal(r.ok, false);
  if (r.ok) return;
  assert.equal(r.brokenAtSeq, 3);
  assert.match(r.reason, /does not link/);
});

test("truncating the tail is invisible to link-checking but caught by the head", () => {
  const entries = buildChain(5);
  const full = verifyChain(entries);
  assert.equal(full.ok, true);
  if (!full.ok) return;

  const truncated = entries.slice(0, 3);
  // Internally consistent on its own -- this is why the head file exists.
  assert.equal(verifyChain(truncated).ok, true);

  const r = verifyChain(truncated, full.head);
  assert.equal(r.ok, false);
  if (r.ok) return;
  assert.match(r.reason, /removed from the end/);
});

test("a head with the right count but the wrong hash is rejected", () => {
  const entries = buildChain(3);
  const r = verifyChain(entries, { seq: 3, hash: "f".repeat(64) });
  assert.equal(r.ok, false);
  if (r.ok) return;
  assert.match(r.reason, /does not match the recorded head/);
});

test("an empty ledger verifies as an empty chain", () => {
  const r = verifyChain([]);
  assert.equal(r.ok, true);
  if (!r.ok) return;
  assert.equal(r.entries, 0);
  assert.equal(r.head.hash, GENESIS_HASH);
});

test("the hash does not depend on key insertion order", () => {
  // Two writers building the same payload differently must agree, or the chain
  // breaks for reasons that have nothing to do with tampering.
  const a = hashEntry({ seq: 1, ts: TS, prevHash: GENESIS_HASH, kind: "k", payload: { x: 1, y: 2 } });
  const b = hashEntry({ seq: 1, ts: TS, prevHash: GENESIS_HASH, kind: "k", payload: { y: 2, x: 1 } });
  assert.equal(a, b);
});

test("canonicalJson sorts keys at every depth and drops undefined", () => {
  assert.equal(canonicalJson({ b: 1, a: { d: 2, c: 3 } }), '{"a":{"c":3,"d":2},"b":1}');
  assert.equal(canonicalJson({ b: undefined, a: 1 }), '{"a":1}');
  assert.throws(() => canonicalJson({ n: Number.NaN }), TypeError);
});

test("the chain head advances by exactly one per seal", () => {
  const chain = new EvidenceChain();
  assert.equal(chain.head.seq, 0);
  const first = chain.seal("k", {}, TS);
  assert.equal(chain.head.seq, 1);
  assert.equal(chain.head.hash, first.hash);
  chain.seal("k", {}, TS);
  assert.equal(chain.head.seq, 2);
});

test("resuming from a persisted head continues the chain rather than restarting", () => {
  const first = buildChain(3);
  const resumed = verifyChain(first);
  assert.equal(resumed.ok, true);
  if (!resumed.ok) return;

  const chain = new EvidenceChain(resumed.head);
  const next = chain.seal("brokered-request", { i: 4 }, TS);
  assert.equal(next.seq, 4);
  assert.equal(verifyChain([...first, next], chain.head).ok, true);
});
