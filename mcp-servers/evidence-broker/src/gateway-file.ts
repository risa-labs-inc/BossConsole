/**
 * The operator approval channel, as files on disk.
 *
 * The agent owns this process's stdio -- that is the MCP transport -- so the
 * operator cannot be prompted there. The obvious alternative is a loopback
 * HTTP control port, and it is the wrong trade: it is new listening surface
 * that then needs its own authentication, and an unauthenticated local
 * endpoint is a bug class BossConsole already has open against itself
 * (risa-labs-inc/BossConsole#1333, #1326).
 *
 * So: the broker writes a request file and waits. The operator answers with
 * `boss-evidence-broker approve <id>` in their own terminal. No port, no
 * listener, and the decision is a durable artifact rather than a click nobody
 * can reconstruct later.
 */

import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { atomicWriteFileSync } from "./store.js";
import type { ApprovalGateway, ApprovalOutcome, ApprovalRequest } from "./approval.js";
import type { Clock, Ids } from "./types.js";

export interface FileGatewayOptions {
  /** Root for `pending/` and `decisions/`. */
  dir: string;
  clock: Clock;
  ids: Ids;
  /** Give up and deny after this long. */
  timeoutMs?: number;
  pollIntervalMs?: number;
  /** Injected so tests do not actually wait. */
  sleep?: (ms: number) => Promise<void>;
}

export interface PendingApproval {
  id: string;
  requestedAt: string;
  credential: string;
  method: string;
  host: string;
  path: string;
  purpose: string;
  actionDigest: string;
  headerNames: string[];
  bodyHash: string | null;
}

export interface OperatorDecision {
  approved: boolean;
  reason?: string;
  decidedAt?: string;
}

const DEFAULT_TIMEOUT_MS = 120_000;
const DEFAULT_POLL_MS = 250;

export class FileApprovalGateway implements ApprovalGateway {
  readonly pendingDir: string;
  readonly decisionsDir: string;
  readonly #timeoutMs: number;
  readonly #pollMs: number;
  readonly #sleep: (ms: number) => Promise<void>;

  constructor(private readonly options: FileGatewayOptions) {
    this.pendingDir = join(options.dir, "pending");
    this.decisionsDir = join(options.dir, "decisions");
    mkdirSync(this.pendingDir, { recursive: true });
    mkdirSync(this.decisionsDir, { recursive: true });
    this.#timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.#pollMs = options.pollIntervalMs ?? DEFAULT_POLL_MS;
    this.#sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  }

  async decide(request: ApprovalRequest): Promise<ApprovalOutcome> {
    const id = this.options.ids.next();
    const pendingPath = join(this.pendingDir, `${id}.json`);
    const decisionPath = join(this.decisionsDir, `${id}.json`);

    const pending: PendingApproval = {
      id,
      requestedAt: this.options.clock.now().toISOString(),
      credential: request.credential,
      method: request.shape.method,
      host: request.shape.host,
      path: request.shape.path,
      purpose: request.purpose,
      actionDigest: request.digest,
      headerNames: request.shape.headerNames,
      bodyHash: request.shape.bodyHash,
    };
    atomicWriteFileSync(pendingPath, `${JSON.stringify(pending, null, 2)}\n`);

    try {
      const deadline = this.options.clock.now().getTime() + this.#timeoutMs;
      for (;;) {
        const decision = readDecision(decisionPath);
        if (decision) {
          return decision.approved
            ? { approved: true }
            : { approved: false, reason: decision.reason ?? "the operator declined" };
        }
        if (this.options.clock.now().getTime() >= deadline) {
          return {
            approved: false,
            reason: `no operator answered within ${Math.round(this.#timeoutMs / 1000)}s, so the request is denied`,
          };
        }
        await this.#sleep(this.#pollMs);
      }
    } finally {
      // Clear the request either way; the evidence chain is the durable record.
      safeRemove(pendingPath);
      safeRemove(decisionPath);
    }
  }

  /** Requests currently awaiting an operator, for the `pending` subcommand. */
  listPending(): PendingApproval[] {
    if (!existsSync(this.pendingDir)) return [];
    const out: PendingApproval[] = [];
    for (const name of readdirSync(this.pendingDir)) {
      if (!name.endsWith(".json")) continue;
      try {
        out.push(JSON.parse(readFileSync(join(this.pendingDir, name), "utf8")) as PendingApproval);
      } catch {
        // A half-written file will be complete on the next poll.
      }
    }
    return out.sort((a, b) => a.requestedAt.localeCompare(b.requestedAt));
  }

  /** Record the operator's answer. Called from the CLI, never from an MCP tool. */
  writeDecision(id: string, decision: OperatorDecision): void {
    const path = join(this.decisionsDir, `${sanitizeId(id)}.json`);
    const body: OperatorDecision = {
      approved: decision.approved,
      ...(decision.reason === undefined ? {} : { reason: decision.reason }),
      decidedAt: decision.decidedAt ?? this.options.clock.now().toISOString(),
    };
    atomicWriteFileSync(path, `${JSON.stringify(body, null, 2)}\n`);
  }
}

function readDecision(path: string): OperatorDecision | undefined {
  if (!existsSync(path)) return undefined;
  try {
    const parsed = JSON.parse(readFileSync(path, "utf8")) as Partial<OperatorDecision>;
    if (typeof parsed.approved !== "boolean") return undefined;
    return parsed.reason === undefined
      ? { approved: parsed.approved }
      : { approved: parsed.approved, reason: parsed.reason };
  } catch {
    // Mid-write. Try again on the next poll rather than reading a torn file.
    return undefined;
  }
}

function safeRemove(path: string): void {
  try {
    rmSync(path, { force: true });
  } catch {
    /* nothing useful to do */
  }
}

/**
 * Keep a caller-supplied id inside the decisions directory.
 *
 * The id reaches this from a CLI argument, and `../` in a filename is how a
 * write lands somewhere it should not.
 */
export function sanitizeId(id: string): string {
  const cleaned = id.replace(/[^A-Za-z0-9._-]/g, "");
  if (cleaned === "" || cleaned === "." || cleaned === "..") {
    throw new Error(`"${id}" is not a usable approval id`);
  }
  return cleaned;
}
