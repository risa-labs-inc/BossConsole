/**
 * Domain types for the evidence broker.
 *
 * Design rule that shapes this whole file: a secret value has exactly one
 * legal path, from the vault to the outbound request, inside this process.
 * It never appears in a type that is returned to the agent.
 */

/** Injected so tests are deterministic. */
export interface Clock {
  now(): Date;
}

/** Injected so evidence ids are deterministic under test. */
export interface Ids {
  next(): string;
}

/** How the broker attaches a secret to an outbound request. */
export type Injection =
  | { kind: "header"; header: string; prefix?: string }
  | { kind: "query"; param: string }
  | { kind: "basic"; username: string };

/** When the operator has to say yes. */
export type ApprovalMode = "each-use" | "once-per-session";

/**
 * A credential the broker is willing to broker.
 *
 * `allowedHosts` is the whole point: a credential is scoped to the hosts it
 * may ever reach, so an allowed tool making an allowed call still cannot
 * carry this secret somewhere it does not belong.
 */
export interface CredentialDef {
  name: string;
  /** Exact host (`api.example.com`) or one wildcard form (`*.example.com`). */
  allowedHosts: string[];
  approval: ApprovalMode;
  injection: Injection;
  /** Methods the credential may be used with. Default-deny; empty means none. */
  allowedMethods: string[];
}

export interface BrokerConfig {
  credentials: CredentialDef[];
  /** Hard ceiling on a brokered response body we will read, in bytes. */
  maxResponseBytes: number;
  /** How long an approval stays redeemable, in milliseconds. */
  approvalTtlMs: number;
}

/** Resolves a credential name to its secret value. Never exposed to the agent. */
export interface SecretResolver {
  resolve(name: string): Promise<string | undefined>;
}

/** The request an agent wants the broker to make on its behalf. */
export interface OutboundRequest {
  method: string;
  url: string;
  headers?: Record<string, string>;
  body?: string;
}

/** What the agent gets back. Secret values are redacted out of every field. */
export interface BrokeredResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
  truncated: boolean;
  evidenceId: string;
}

export type Decision =
  | { allowed: true }
  | { allowed: false; code: DenyCode; reason: string };

export type DenyCode =
  | "unknown-credential"
  | "host-not-allowed"
  | "method-not-allowed"
  | "bad-target"
  | "no-approval"
  | "approval-expired"
  | "approval-mismatch"
  | "approval-denied"
  | "approval-spent"
  | "secret-missing";

export interface EvidenceEntry {
  seq: number;
  ts: string;
  prevHash: string;
  kind: string;
  payload: Record<string, unknown>;
}

export interface SealedEvidence extends EvidenceEntry {
  hash: string;
}
