/**
 * Operator approval, bound to one exact action.
 *
 * Two properties this module exists to guarantee:
 *
 * 1. An approval cannot be replayed for a different action. The operator
 *    approves a digest over the whole request -- credential, method, host,
 *    path, headers, body -- and redemption recomputes that digest. Approve a
 *    GET to /v1/me and you have not approved a DELETE to /v1/account.
 *
 * 2. The agent cannot approve its own request. Nothing here is reachable from
 *    the agent's MCP transport; the gateway is a constructor dependency that
 *    talks to the operator on a separate channel. A gate the caller can open
 *    is not a gate.
 */

import { createHash } from "node:crypto";
import { canonicalJson } from "./canonical.js";
import type { ApprovalMode, Clock, Decision, OutboundRequest } from "./types.js";

/** The shape an approval is bound to. Digested, so no secret material is held. */
export interface ActionShape {
  credential: string;
  method: string;
  host: string;
  path: string;
  /** Sorted header names only -- values can hold the secret. */
  headerNames: string[];
  /** sha256 of the body, or null when there is none. */
  bodyHash: string | null;
}

/** Derive the approvable shape of a request. Never includes a secret value. */
export function actionShapeOf(credential: string, request: OutboundRequest, host: string, path: string): ActionShape {
  const body = request.body;
  return {
    credential,
    method: request.method.toUpperCase(),
    host,
    path,
    headerNames: Object.keys(request.headers ?? {})
      .map((h) => h.toLowerCase())
      .sort(),
    bodyHash: body === undefined ? null : createHash("sha256").update(body, "utf8").digest("hex"),
  };
}

/** Stable digest of an action shape. */
export function actionDigest(shape: ActionShape): string {
  return createHash("sha256").update(canonicalJson(shape), "utf8").digest("hex");
}

export interface Grant {
  id: string;
  digest: string;
  credential: string;
  mode: ApprovalMode;
  grantedAtMs: number;
  expiresAtMs: number;
  /** Redemptions so far. An each-use grant is spent at 1. */
  uses: number;
  /** The exact request the operator approved, replayed verbatim on send. */
  request: OutboundRequest;
  host: string;
}

export interface ApprovalRequest {
  credential: string;
  shape: ActionShape;
  digest: string;
  purpose: string;
}

export type ApprovalOutcome = { approved: true } | { approved: false; reason: string };

/**
 * How the broker reaches the operator.
 *
 * Deliberately an interface: the real implementation prompts on a channel the
 * agent does not control, and tests substitute a scripted one.
 */
export interface ApprovalGateway {
  decide(request: ApprovalRequest): Promise<ApprovalOutcome>;
}

/** Grants in memory, keyed by id, checked against clock and digest on redemption. */
export class GrantRegistry {
  readonly #grants = new Map<string, Grant>();

  constructor(
    private readonly clock: Clock,
    private readonly ttlMs: number,
  ) {}

  issue(params: {
    id: string;
    digest: string;
    credential: string;
    mode: ApprovalMode;
    request: OutboundRequest;
    host: string;
  }): Grant {
    const nowMs = this.clock.now().getTime();
    const grant: Grant = {
      id: params.id,
      digest: params.digest,
      credential: params.credential,
      mode: params.mode,
      grantedAtMs: nowMs,
      expiresAtMs: nowMs + this.ttlMs,
      uses: 0,
      request: params.request,
      host: params.host,
    };
    this.#grants.set(grant.id, grant);
    return grant;
  }

  get(id: string): Grant | undefined {
    return this.#grants.get(id);
  }

  /**
   * Check a grant is still redeemable for this exact digest, and count the use.
   *
   * Order matters: existence, then expiry, then digest match, then spend. A
   * mismatched digest on an expired grant should report the expiry, because
   * that is the fact the operator needs.
   */
  redeem(id: string, digest: string): { ok: true; grant: Grant } | (Decision & { allowed: false }) {
    const grant = this.#grants.get(id);
    if (!grant) {
      return { allowed: false, code: "no-approval", reason: `no grant with id ${id}` };
    }

    const nowMs = this.clock.now().getTime();
    if (nowMs >= grant.expiresAtMs) {
      return {
        allowed: false,
        code: "approval-expired",
        reason: `grant ${id} expired at ${new Date(grant.expiresAtMs).toISOString()}`,
      };
    }

    if (grant.digest !== digest) {
      return {
        allowed: false,
        code: "approval-mismatch",
        reason: `grant ${id} was approved for a different action`,
      };
    }

    if (grant.mode === "each-use" && grant.uses >= 1) {
      return {
        allowed: false,
        code: "approval-spent",
        reason: `grant ${id} is each-use and has already been redeemed`,
      };
    }

    grant.uses += 1;
    return { ok: true, grant };
  }

  /** Drop expired grants. Called opportunistically; correctness never depends on it. */
  prune(): number {
    const nowMs = this.clock.now().getTime();
    let dropped = 0;
    for (const [id, grant] of this.#grants) {
      if (nowMs >= grant.expiresAtMs) {
        this.#grants.delete(id);
        dropped += 1;
      }
    }
    return dropped;
  }

  get size(): number {
    return this.#grants.size;
  }
}

/**
 * Denies everything, with a reason.
 *
 * This is the default when no operator channel is configured. The alternative
 * default -- approve when nobody is listening -- is how a gate becomes
 * decoration, so it is not offered.
 */
export class DenyAllGateway implements ApprovalGateway {
  async decide(): Promise<ApprovalOutcome> {
    return {
      approved: false,
      reason: "no operator approval channel is configured, so the broker denies by default",
    };
  }
}
