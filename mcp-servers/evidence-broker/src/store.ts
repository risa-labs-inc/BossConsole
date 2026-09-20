/**
 * Evidence persistence.
 *
 * The ledger is append-only JSONL; the head (last seq + hash) lives in a
 * separate small file so tail truncation of the ledger is detectable.
 *
 * Durability is deliberate rather than incidental. A rename is only atomic
 * with respect to the *directory entry* -- it says nothing about whether the
 * file's bytes reached the platter. Publishing a rename over unflushed content
 * can leave a head file that exists, is well-formed, and is empty. So: write,
 * fsync the file, then rename, then fsync the directory. BossConsole has this
 * exact gap open against its own atomicWriteText (risa-labs-inc/BossConsole#1240),
 * which is where the shape of this came from.
 */

import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, writeFileSync, writeSync } from "node:fs";
import { dirname, join } from "node:path";
import { GENESIS_HEAD, type ChainHead } from "./chain.js";
import type { SealedEvidence } from "./types.js";

export interface EvidenceStore {
  append(entry: SealedEvidence): void;
  readAll(): SealedEvidence[];
  readHead(): ChainHead;
}

/** Write a file such that a reader either sees the old bytes or all the new ones. */
export function atomicWriteFileSync(path: string, contents: string): void {
  const dir = dirname(path);
  mkdirSync(dir, { recursive: true });
  const tmp = `${path}.tmp-${process.pid}`;

  const fd = openSync(tmp, "w");
  try {
    writeFileSync(fd, contents, { encoding: "utf8" });
    // Force the bytes out before the rename publishes the name.
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }

  renameSync(tmp, path);
  fsyncDirSync(dir);
}

/**
 * fsync a directory so the rename itself is durable.
 *
 * Not supported on Windows, where opening a directory for this purpose fails.
 * Best-effort by design: on platforms that support it we get the guarantee, and
 * on those that do not we are no worse off than a plain rename.
 */
function fsyncDirSync(dir: string): void {
  let fd: number | undefined;
  try {
    fd = openSync(dir, "r");
    fsyncSync(fd);
  } catch {
    // EPERM/EISDIR/EACCES on Windows and some filesystems. Nothing to do.
  } finally {
    if (fd !== undefined) {
      try {
        closeSync(fd);
      } catch {
        /* already gone */
      }
    }
  }
}

export class MalformedLedgerError extends Error {
  constructor(
    public readonly line: number,
    cause: string,
  ) {
    super(`ledger line ${line} is not a readable evidence entry: ${cause}`);
    this.name = "MalformedLedgerError";
  }
}

/** Ledger on disk: `<dir>/ledger.jsonl` plus `<dir>/head.json`. */
export class FileEvidenceStore implements EvidenceStore {
  readonly ledgerPath: string;
  readonly headPath: string;

  constructor(private readonly dir: string) {
    this.ledgerPath = join(dir, "ledger.jsonl");
    this.headPath = join(dir, "head.json");
    mkdirSync(dir, { recursive: true });
  }

  append(entry: SealedEvidence): void {
    // One line, one entry, flushed before we advertise it in the head. If the
    // process dies between the two, the ledger is ahead of the head, which
    // verify reports as a head mismatch rather than silently accepting.
    const fd = openSync(this.ledgerPath, "a");
    try {
      writeSync(fd, `${JSON.stringify(entry)}\n`, null, "utf8");
      fsyncSync(fd);
    } finally {
      closeSync(fd);
    }

    const head: ChainHead = { seq: entry.seq, hash: entry.hash };
    atomicWriteFileSync(this.headPath, `${JSON.stringify(head, null, 2)}\n`);
  }

  readAll(): SealedEvidence[] {
    if (!existsSync(this.ledgerPath)) return [];
    const raw = readFileSync(this.ledgerPath, "utf8");
    const out: SealedEvidence[] = [];
    const lines = raw.split("\n");
    for (let i = 0; i < lines.length; i += 1) {
      const line = lines[i];
      if (line === undefined || line.trim() === "") continue;
      try {
        out.push(JSON.parse(line) as SealedEvidence);
      } catch (err) {
        throw new MalformedLedgerError(i + 1, err instanceof Error ? err.message : String(err));
      }
    }
    return out;
  }

  readHead(): ChainHead {
    if (!existsSync(this.headPath)) return { ...GENESIS_HEAD };
    try {
      const parsed = JSON.parse(readFileSync(this.headPath, "utf8")) as Partial<ChainHead>;
      if (typeof parsed.seq !== "number" || typeof parsed.hash !== "string") {
        return { ...GENESIS_HEAD };
      }
      return { seq: parsed.seq, hash: parsed.hash };
    } catch {
      // A head we cannot read is reported as genesis, so verify against the
      // ledger fails loudly instead of trusting a corrupt pointer.
      return { ...GENESIS_HEAD };
    }
  }

  get directory(): string {
    return this.dir;
  }
}

/** In-memory store for tests. Same contract, no filesystem. */
export class MemoryEvidenceStore implements EvidenceStore {
  readonly entries: SealedEvidence[] = [];
  #head: ChainHead = { ...GENESIS_HEAD };

  append(entry: SealedEvidence): void {
    this.entries.push(entry);
    this.#head = { seq: entry.seq, hash: entry.hash };
  }

  readAll(): SealedEvidence[] {
    return [...this.entries];
  }

  readHead(): ChainHead {
    return { ...this.#head };
  }
}
