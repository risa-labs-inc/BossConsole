import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, writeFileSync, appendFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { atomicWriteFileSync, FileEvidenceStore, MalformedLedgerError } from "../src/store.js";
import { EvidenceChain, GENESIS_HASH, verifyChain } from "../src/chain.js";

function tempDir(): string {
  return mkdtempSync(join(tmpdir(), "evstore-"));
}

const TS = "2026-09-20T10:00:00.000Z";

test("entries round-trip through the ledger and verify", () => {
  const store = new FileEvidenceStore(join(tempDir(), "evidence"));
  const chain = new EvidenceChain();
  for (let i = 1; i <= 3; i += 1) {
    store.append(chain.seal("brokered-request", { i }, TS));
  }

  const read = store.readAll();
  assert.equal(read.length, 3);
  const result = verifyChain(read, store.readHead());
  assert.equal(result.ok, true);
});

test("the head is written alongside the ledger and tracks the last entry", () => {
  const store = new FileEvidenceStore(join(tempDir(), "evidence"));
  assert.deepEqual(store.readHead(), { seq: 0, hash: GENESIS_HASH });

  const chain = new EvidenceChain();
  const first = chain.seal("k", {}, TS);
  store.append(first);
  assert.deepEqual(store.readHead(), { seq: 1, hash: first.hash });
});

test("an empty ledger directory reads as empty rather than throwing", () => {
  const store = new FileEvidenceStore(join(tempDir(), "evidence"));
  assert.deepEqual(store.readAll(), []);
});

test("truncating the ledger on disk is caught by the head", () => {
  const dir = join(tempDir(), "evidence");
  const store = new FileEvidenceStore(dir);
  const chain = new EvidenceChain();
  const sealed = [1, 2, 3, 4].map((i) => chain.seal("k", { i }, TS));
  for (const entry of sealed) store.append(entry);
  const head = store.readHead();

  // Rewrite the ledger with the last two rows removed.
  const kept = sealed.slice(0, 2).map((e) => JSON.stringify(e)).join("\n");
  writeFileSync(store.ledgerPath, `${kept}\n`, "utf8");

  const result = verifyChain(store.readAll(), head);
  assert.equal(result.ok, false);
  if (result.ok) return;
  assert.match(result.reason, /removed from the end/);
});

test("editing a row on disk is caught", () => {
  const dir = join(tempDir(), "evidence");
  const store = new FileEvidenceStore(dir);
  const chain = new EvidenceChain();
  const sealed = [1, 2, 3].map((i) => chain.seal("k", { i, host: "api.example.com" }, TS));
  for (const entry of sealed) store.append(entry);

  const lines = readFileSync(store.ledgerPath, "utf8").trimEnd().split("\n");
  const second = lines[1];
  assert.ok(second);
  lines[1] = second.replace("api.example.com", "evil.test.com");
  writeFileSync(store.ledgerPath, `${lines.join("\n")}\n`, "utf8");

  const result = verifyChain(store.readAll(), store.readHead());
  assert.equal(result.ok, false);
  if (result.ok) return;
  assert.equal(result.brokenAtSeq, 2);
});

test("a malformed ledger line is reported with its line number, not skipped", () => {
  const store = new FileEvidenceStore(join(tempDir(), "evidence"));
  const chain = new EvidenceChain();
  store.append(chain.seal("k", {}, TS));
  appendFileSync(store.ledgerPath, "{not json\n", "utf8");

  assert.throws(() => store.readAll(), MalformedLedgerError);
  try {
    store.readAll();
  } catch (err) {
    assert.ok(err instanceof MalformedLedgerError);
    assert.equal(err.line, 2);
  }
});

test("blank lines in the ledger are tolerated", () => {
  const store = new FileEvidenceStore(join(tempDir(), "evidence"));
  const chain = new EvidenceChain();
  store.append(chain.seal("k", {}, TS));
  appendFileSync(store.ledgerPath, "\n\n", "utf8");
  assert.equal(store.readAll().length, 1);
});

test("an unreadable head reads as genesis, so verification fails loudly", () => {
  const store = new FileEvidenceStore(join(tempDir(), "evidence"));
  const chain = new EvidenceChain();
  store.append(chain.seal("k", {}, TS));

  writeFileSync(store.headPath, "{corrupt", "utf8");
  assert.deepEqual(store.readHead(), { seq: 0, hash: GENESIS_HASH });
  // Trusting a corrupt pointer would be the dangerous outcome; this fails.
  assert.equal(verifyChain(store.readAll(), store.readHead()).ok, false);

  writeFileSync(store.headPath, JSON.stringify({ seq: "not-a-number" }), "utf8");
  assert.deepEqual(store.readHead(), { seq: 0, hash: GENESIS_HASH });
});

test("atomicWriteFileSync replaces content and leaves no temp file behind", () => {
  const dir = tempDir();
  const path = join(dir, "nested", "head.json");
  atomicWriteFileSync(path, "first\n");
  assert.equal(readFileSync(path, "utf8"), "first\n");

  atomicWriteFileSync(path, "second\n");
  assert.equal(readFileSync(path, "utf8"), "second\n");

  const leftovers = readdirSync(join(dir, "nested")).filter((f) => f.includes(".tmp-"));
  assert.deepEqual(leftovers, []);
});

test("a store reopened on an existing directory resumes the chain", () => {
  const dir = join(tempDir(), "evidence");
  const first = new FileEvidenceStore(dir);
  const chain = new EvidenceChain();
  first.append(chain.seal("k", { i: 1 }, TS));
  first.append(chain.seal("k", { i: 2 }, TS));

  // A fresh process: read the head, carry on from it.
  const reopened = new FileEvidenceStore(dir);
  const resumed = new EvidenceChain(reopened.readHead());
  reopened.append(resumed.seal("k", { i: 3 }, TS));

  const result = verifyChain(reopened.readAll(), reopened.readHead());
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.entries, 3);
});
