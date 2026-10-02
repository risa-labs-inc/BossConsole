/**
 * Append-only, hash-chained evidence.
 *
 * Plain append JSONL -- which is what the host's own mcp-calls.jsonl and the
 * neighbouring ledger plugins write -- records what happened but cannot show
 * that the record is intact. Anyone who can write the file can edit a row and
 * nothing detects it.
 *
 * Each entry here commits to its predecessor's hash, so editing or removing a
 * middle row breaks every link after it. Removing rows from the *tail* leaves a
 * chain that is internally consistent, so the head (last seq + hash) is stored
 * separately; verifying against it is what makes truncation detectable.
 */

import { createHash } from "node:crypto";
import { canonicalJson } from "./canonical.js";
import type { EvidenceEntry, SealedEvidence } from "./types.js";

export const GENESIS_HASH = "0".repeat(64);

export interface ChainHead {
  seq: number;
  hash: string;
}

export const GENESIS_HEAD: ChainHead = { seq: 0, hash: GENESIS_HASH };

/** sha256 over the canonical form of the entry, excluding the hash itself. */
export function hashEntry(entry: EvidenceEntry): string {
  const material = canonicalJson({
    seq: entry.seq,
    ts: entry.ts,
    prevHash: entry.prevHash,
    kind: entry.kind,
    payload: entry.payload,
  });
  return createHash("sha256").update(material, "utf8").digest("hex");
}

/** Seals entries in order. Holds only the head, so it is cheap to keep in memory. */
export class EvidenceChain {
  #head: ChainHead;

  constructor(head: ChainHead = GENESIS_HEAD) {
    this.#head = { ...head };
  }

  get head(): ChainHead {
    return { ...this.#head };
  }

  seal(kind: string, payload: Record<string, unknown>, ts: string): SealedEvidence {
    const entry: EvidenceEntry = {
      seq: this.#head.seq + 1,
      ts,
      prevHash: this.#head.hash,
      kind,
      payload,
    };
    const hash = hashEntry(entry);
    this.#head = { seq: entry.seq, hash };
    return { ...entry, hash };
  }
}

export type VerifyResult =
  | { ok: true; entries: number; head: ChainHead }
  | { ok: false; brokenAtSeq: number | null; reason: string };

/**
 * Verify a chain end to end.
 *
 * Pass expectedHead (read from the separately stored head file) to also catch
 * tail truncation, which is invisible to link-checking alone.
 */
export function verifyChain(entries: readonly SealedEvidence[], expectedHead?: ChainHead): VerifyResult {
  let prevHash = GENESIS_HASH;
  let expectedSeq = 1;

  for (const entry of entries) {
    if (entry.seq !== expectedSeq) {
      return {
        ok: false,
        brokenAtSeq: entry.seq,
        reason: `sequence jumped: expected ${expectedSeq}, found ${entry.seq}`,
      };
    }
    if (entry.prevHash !== prevHash) {
      return { ok: false, brokenAtSeq: entry.seq, reason: `entry ${entry.seq} does not link to its predecessor` };
    }
    if (hashEntry(entry) !== entry.hash) {
      return { ok: false, brokenAtSeq: entry.seq, reason: `entry ${entry.seq} has been altered since it was sealed` };
    }
    prevHash = entry.hash;
    expectedSeq += 1;
  }

  const head: ChainHead = { seq: entries.length, hash: prevHash };

  if (expectedHead) {
    if (expectedHead.seq !== head.seq) {
      return {
        ok: false,
        brokenAtSeq: null,
        reason:
          `ledger holds ${head.seq} entries but the head records ${expectedHead.seq}; ` +
          `entries were removed from the end`,
      };
    }
    if (expectedHead.hash !== head.hash) {
      return { ok: false, brokenAtSeq: null, reason: "ledger head does not match the recorded head" };
    }
  }

  return { ok: true, entries: entries.length, head };
}
