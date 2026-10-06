/**
 * Deterministic doubles. Everything the broker touches that would otherwise be
 * ambient -- time, ids, the vault, the operator, the network -- is injected, so
 * the whole suite runs offline and produces identical output every run.
 */

import { EvidenceChain } from "../src/chain.js";
import { MemoryEvidenceStore } from "../src/store.js";
import { EvidenceBroker, type FetchLike, type FetchResponseLike } from "../src/broker.js";
import type { ApprovalGateway, ApprovalOutcome, ApprovalRequest } from "../src/approval.js";
import type { BrokerConfig, Clock, Ids, SecretResolver } from "../src/types.js";
import { loadConfig } from "../src/config.js";

export class FakeClock implements Clock {
  #ms: number;
  constructor(iso = "2026-09-20T10:00:00.000Z") {
    this.#ms = new Date(iso).getTime();
  }
  now(): Date {
    return new Date(this.#ms);
  }
  advance(ms: number): void {
    this.#ms += ms;
  }
}

export class SeqIds implements Ids {
  #n = 0;
  next(): string {
    this.#n += 1;
    return `grant-${this.#n}`;
  }
}

export class MapSecrets implements SecretResolver {
  constructor(private readonly values: Record<string, string>) {}
  async resolve(name: string): Promise<string | undefined> {
    return this.values[name];
  }
}

/** Approves everything. Named so its use is obvious in review. */
export class AlwaysApproveGateway implements ApprovalGateway {
  readonly seen: ApprovalRequest[] = [];
  async decide(request: ApprovalRequest): Promise<ApprovalOutcome> {
    this.seen.push(request);
    return { approved: true };
  }
}

export class AlwaysDenyGateway implements ApprovalGateway {
  readonly seen: ApprovalRequest[] = [];
  constructor(private readonly reason = "not this time") {}
  async decide(request: ApprovalRequest): Promise<ApprovalOutcome> {
    this.seen.push(request);
    return { approved: false, reason: this.reason };
  }
}

export interface RecordedCall {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
  redirect: string;
}

/** Records what the broker tried to send, and replies with a scripted response. */
export class RecordingFetch {
  readonly calls: RecordedCall[] = [];

  constructor(private readonly reply: FetchResponseLike = { status: 200, headers: { "content-type": "application/json" }, body: '{"ok":true}' }) {}

  get fetch(): FetchLike {
    return async (url, init) => {
      this.calls.push({
        url,
        method: init.method,
        headers: init.headers,
        ...(init.body === undefined ? {} : { body: init.body }),
        redirect: init.redirect,
      });
      return this.reply;
    };
  }

  get lastCall(): RecordedCall {
    const last = this.calls[this.calls.length - 1];
    if (!last) throw new Error("no call was recorded");
    return last;
  }
}

export const STRIPE_SECRET = "sk-live-7f3c92ab41d8";

/** A config covering all three injection kinds. */
export function testConfig(overrides: Record<string, unknown> = {}): BrokerConfig {
  const result = loadConfig({
    credentials: [
      {
        name: "STRIPE_KEY",
        allowedHosts: ["api.stripe.com"],
        approval: "each-use",
        allowedMethods: ["GET", "POST"],
        injection: { kind: "header", header: "Authorization", prefix: "Bearer " },
      },
      {
        name: "SEARCH_KEY",
        allowedHosts: ["*.search.example"],
        approval: "once-per-session",
        allowedMethods: ["GET"],
        injection: { kind: "query", param: "api_key" },
      },
      {
        name: "LEGACY_PW",
        allowedHosts: ["legacy.example.com"],
        approval: "each-use",
        allowedMethods: ["GET"],
        injection: { kind: "basic", username: "operator" },
      },
    ],
    ...overrides,
  });
  if (!result.ok) throw new Error(`test config is invalid: ${result.errors.join("; ")}`);
  return result.config;
}

export interface Harness {
  broker: EvidenceBroker;
  store: MemoryEvidenceStore;
  clock: FakeClock;
  net: RecordingFetch;
  gateway: ApprovalGateway;
}

export function harness(
  options: {
    gateway?: ApprovalGateway;
    reply?: FetchResponseLike;
    config?: BrokerConfig;
    secrets?: Record<string, string>;
  } = {},
): Harness {
  const clock = new FakeClock();
  const store = new MemoryEvidenceStore();
  const net = options.reply ? new RecordingFetch(options.reply) : new RecordingFetch();
  const gateway = options.gateway ?? new AlwaysApproveGateway();
  const broker = new EvidenceBroker({
    config: options.config ?? testConfig(),
    secrets: new MapSecrets(
      options.secrets ?? { STRIPE_KEY: STRIPE_SECRET, SEARCH_KEY: "search-key-abcdef", LEGACY_PW: "legacy-password-99" },
    ),
    gateway,
    store,
    chain: new EvidenceChain(),
    clock,
    ids: new SeqIds(),
    fetch: net.fetch,
  });
  return { broker, store, clock, net, gateway };
}

/** Everything the ledger holds, as one string, for leak assertions. */
export function ledgerText(store: MemoryEvidenceStore): string {
  return store.readAll().map((e) => JSON.stringify(e)).join("\n");
}
