/**
 * The broker.
 *
 * The invariant worth stating plainly: a secret value is read inside `send`,
 * attached to one outbound request, and never returned, logged, or sealed into
 * evidence. No method on this class returns a secret, and the response is run
 * through a redactor before it leaves.
 *
 * Every decision -- allow or deny -- is sealed into the evidence chain. A
 * refusal is a fact about the session too, and a ledger that only records
 * successes cannot answer "what did the agent try".
 */

import { createHash } from "node:crypto";
import { actionDigest, actionShapeOf, type ApprovalGateway, GrantRegistry } from "./approval.js";
import type { EvidenceChain } from "./chain.js";
import { verifyChain, type VerifyResult } from "./chain.js";
import { findCredential } from "./config.js";
import { assertRedacted, buildRedactor, type SecretForRedaction } from "./redact.js";
import type { EvidenceStore } from "./store.js";
import { hostAllowed, parseTarget } from "./target.js";
import type {
  BrokerConfig,
  BrokeredResponse,
  Clock,
  CredentialDef,
  DenyCode,
  Ids,
  OutboundRequest,
  SealedEvidence,
  SecretResolver,
} from "./types.js";

export interface FetchResponseLike {
  status: number;
  headers: Record<string, string>;
  body: string;
}

/**
 * The outbound call, injected.
 *
 * `redirect: "manual"` is not a detail. Following a redirect would re-send the
 * credential to whatever host the response named, which is precisely the
 * allowlist decision we just made, made again by the other side.
 */
export type FetchLike = (
  url: string,
  init: { method: string; headers: Record<string, string>; body?: string; redirect: "manual" },
) => Promise<FetchResponseLike>;

export interface BrokerDeps {
  config: BrokerConfig;
  secrets: SecretResolver;
  gateway: ApprovalGateway;
  store: EvidenceStore;
  chain: EvidenceChain;
  clock: Clock;
  ids: Ids;
  fetch: FetchLike;
}

export interface CredentialSummary {
  name: string;
  allowedHosts: string[];
  allowedMethods: string[];
  approval: string;
  injection: string;
}

export type RequestUseResult =
  | { granted: true; grantId: string; actionDigest: string; expiresAt: string; evidenceId: string }
  | { granted: false; code: DenyCode; reason: string; evidenceId: string };

export type SendResult = { sent: true; response: BrokeredResponse } | { sent: false; code: DenyCode; reason: string; evidenceId: string };

export class EvidenceBroker {
  readonly #grants: GrantRegistry;

  constructor(private readonly deps: BrokerDeps) {
    this.#grants = new GrantRegistry(deps.clock, deps.config.approvalTtlMs);
  }

  /**
   * What the agent is allowed to know: names and scopes, never values.
   *
   * This is the discoverability the agent needs -- it can see that
   * STRIPE_KEY exists and reaches api.stripe.com -- with nothing it could
   * exfiltrate.
   */
  listCredentials(): CredentialSummary[] {
    return this.deps.config.credentials.map((c) => ({
      name: c.name,
      allowedHosts: [...c.allowedHosts],
      allowedMethods: [...c.allowedMethods],
      approval: c.approval,
      injection: describeInjection(c),
    }));
  }

  /**
   * Ask to use a credential for one specific request.
   *
   * Policy first, operator second. There is no point interrupting a human to
   * approve a call the allowlist already forbids, and asking would train them
   * to click through.
   */
  async requestUse(input: {
    credential: string;
    method: string;
    url: string;
    headers?: Record<string, string>;
    body?: string;
    purpose: string;
  }): Promise<RequestUseResult> {
    const def = findCredential(this.deps.config, input.credential);
    if (!def) {
      return this.#deny("unknown-credential", `no credential named "${input.credential}" is configured`, {
        credential: input.credential,
      });
    }

    const target = parseTarget(input.url);
    if (!target.ok) {
      return this.#deny("bad-target", `target rejected: ${target.reason}`, {
        credential: def.name,
        url: input.url,
      });
    }

    if (!hostAllowed(target.host, def.allowedHosts)) {
      return this.#deny(
        "host-not-allowed",
        `"${def.name}" is not permitted to reach ${target.host}; allowed: ${def.allowedHosts.join(", ")}`,
        { credential: def.name, host: target.host },
      );
    }

    const method = input.method.toUpperCase();
    if (!def.allowedMethods.includes(method)) {
      return this.#deny(
        "method-not-allowed",
        `"${def.name}" is not permitted to issue ${method}; allowed: ${def.allowedMethods.join(", ")}`,
        { credential: def.name, host: target.host, method },
      );
    }

    const request: OutboundRequest = {
      method,
      url: input.url,
      ...(input.headers === undefined ? {} : { headers: input.headers }),
      ...(input.body === undefined ? {} : { body: input.body }),
    };
    const shape = actionShapeOf(def.name, request, target.host, target.url.pathname);
    const digest = actionDigest(shape);

    const outcome = await this.deps.gateway.decide({
      credential: def.name,
      shape,
      digest,
      purpose: input.purpose,
    });

    if (!outcome.approved) {
      return this.#deny("approval-denied", `the operator declined: ${outcome.reason}`, {
        credential: def.name,
        host: target.host,
        method,
        actionDigest: digest,
        purpose: input.purpose,
      });
    }

    this.#grants.prune();
    const grant = this.#grants.issue({
      id: this.deps.ids.next(),
      digest,
      credential: def.name,
      mode: def.approval,
      request,
      host: target.host,
    });

    const sealed = this.#seal("approval-granted", {
      grantId: grant.id,
      credential: def.name,
      host: target.host,
      method,
      path: shape.path,
      actionDigest: digest,
      approval: def.approval,
      purpose: input.purpose,
      expiresAt: new Date(grant.expiresAtMs).toISOString(),
    });

    return {
      granted: true,
      grantId: grant.id,
      actionDigest: digest,
      expiresAt: new Date(grant.expiresAtMs).toISOString(),
      evidenceId: sealed.hash,
    };
  }

  /**
   * Execute exactly the approved request.
   *
   * Note the signature: a grant id and nothing else. There is no parameter
   * through which a different request could be substituted after approval, so
   * the substitution attack is unrepresentable at this boundary rather than
   * merely checked for. GrantRegistry still verifies the digest as defence in
   * depth, and is unit-tested for it.
   */
  async send(grantId: string): Promise<SendResult> {
    const redeemed = this.#grants.redeem(grantId, this.#digestOfGrant(grantId));
    if (!("ok" in redeemed)) {
      return this.#denySend(redeemed.code, redeemed.reason, { grantId });
    }
    const grant = redeemed.grant;

    const def = findCredential(this.deps.config, grant.credential);
    if (!def) {
      return this.#denySend("unknown-credential", `credential "${grant.credential}" is no longer configured`, { grantId });
    }

    // Re-check policy at send time. Config can change between approval and
    // redemption, and the fresher decision is the correct one.
    if (!hostAllowed(grant.host, def.allowedHosts)) {
      return this.#denySend("host-not-allowed", `"${def.name}" may no longer reach ${grant.host}`, {
        grantId,
        host: grant.host,
      });
    }

    const secret = await this.deps.secrets.resolve(def.name);
    if (secret === undefined || secret === "") {
      return this.#denySend("secret-missing", `the vault has no value for "${def.name}"`, { grantId });
    }

    const injected = injectSecret(def, grant.request, secret);
    const redactionSet: SecretForRedaction[] = [
      { name: def.name, value: secret, extraLiterals: injected.extraLiterals },
    ];
    const redact = buildRedactor(redactionSet);

    const response = await this.deps.fetch(injected.url, {
      method: grant.request.method,
      headers: injected.headers,
      ...(grant.request.body === undefined ? {} : { body: grant.request.body }),
      redirect: "manual",
    });

    const rawBody = typeof response.body === "string" ? response.body : "";
    const truncated = Buffer.byteLength(rawBody, "utf8") > this.deps.config.maxResponseBytes;
    const clipped = truncated ? clipToBytes(rawBody, this.deps.config.maxResponseBytes) : rawBody;

    const safeBody = redact(clipped);
    const safeHeaders: Record<string, string> = {};
    for (const [k, v] of Object.entries(response.headers ?? {})) {
      safeHeaders[redact(k)] = redact(v);
    }

    // If anything got past the redactor, fail here rather than hand it over.
    assertRedacted(safeBody, redactionSet);
    assertRedacted(JSON.stringify(safeHeaders), redactionSet);

    const isRedirect = response.status >= 300 && response.status < 400;

    const sealed = this.#seal("brokered-request", {
      grantId: grant.id,
      credential: def.name,
      host: grant.host,
      method: grant.request.method,
      path: safePath(grant.request.url),
      actionDigest: grant.digest,
      status: response.status,
      // An attestation, not an archive: enough to prove later that a saved
      // response is the one we received, without hoarding response bodies.
      responseSha256: createHash("sha256").update(safeBody, "utf8").digest("hex"),
      responseBytes: Buffer.byteLength(safeBody, "utf8"),
      truncated,
      redirectNotFollowed: isRedirect,
      use: grant.uses,
    });

    return {
      sent: true,
      response: {
        status: response.status,
        headers: safeHeaders,
        body: safeBody,
        truncated,
        evidenceId: sealed.hash,
      },
    };
  }

  /** Verify the ledger against its separately stored head. */
  verifyEvidence(): VerifyResult {
    return verifyChain(this.deps.store.readAll(), this.deps.store.readHead());
  }

  /** Most recent entries, newest last. Already free of secret material. */
  recentEvidence(limit = 50): SealedEvidence[] {
    const all = this.deps.store.readAll();
    return limit >= all.length ? all : all.slice(all.length - limit);
  }

  #digestOfGrant(grantId: string): string {
    const grant = this.#grants.get(grantId);
    return grant ? grant.digest : "";
  }

  #seal(kind: string, payload: Record<string, unknown>): SealedEvidence {
    const sealed = this.deps.chain.seal(kind, payload, this.deps.clock.now().toISOString());
    this.deps.store.append(sealed);
    return sealed;
  }

  #deny(code: DenyCode, reason: string, payload: Record<string, unknown>): RequestUseResult {
    const sealed = this.#seal("request-denied", { ...payload, code, reason });
    return { granted: false, code, reason, evidenceId: sealed.hash };
  }

  #denySend(code: DenyCode, reason: string, payload: Record<string, unknown>): SendResult {
    const sealed = this.#seal("send-denied", { ...payload, code, reason });
    return { sent: false, code, reason, evidenceId: sealed.hash };
  }
}

function describeInjection(def: CredentialDef): string {
  switch (def.injection.kind) {
    case "header":
      return `header ${def.injection.header}`;
    case "query":
      return `query ${def.injection.param}`;
    case "basic":
      return `basic auth as ${def.injection.username}`;
  }
}

/** Attach the secret per the credential's injection rule. */
export function injectSecret(
  def: CredentialDef,
  request: OutboundRequest,
  secret: string,
): { url: string; headers: Record<string, string>; extraLiterals: string[] } {
  const headers: Record<string, string> = { ...(request.headers ?? {}) };
  const extraLiterals: string[] = [];
  let url = request.url;

  switch (def.injection.kind) {
    case "header": {
      headers[def.injection.header] = `${def.injection.prefix ?? ""}${secret}`;
      break;
    }
    case "query": {
      const parsed = new URL(url);
      parsed.searchParams.set(def.injection.param, secret);
      url = parsed.toString();
      // The URL-encoded form is covered by encodingsOf, but the assembled
      // query string is what shows up in a server error echo.
      extraLiterals.push(parsed.search);
      break;
    }
    case "basic": {
      const pair = Buffer.from(`${def.injection.username}:${secret}`, "utf8").toString("base64");
      headers["Authorization"] = `Basic ${pair}`;
      extraLiterals.push(pair);
      break;
    }
  }

  return { url, headers, extraLiterals };
}

/** Clip to a byte budget without splitting a multi-byte character. */
export function clipToBytes(text: string, maxBytes: number): string {
  const buf = Buffer.from(text, "utf8");
  if (buf.byteLength <= maxBytes) return text;
  // `toString` on a truncated buffer replaces a split sequence rather than
  // throwing, so trim the replacement character if we landed mid-glyph.
  const clipped = buf.subarray(0, maxBytes).toString("utf8");
  return clipped.endsWith("�") ? clipped.slice(0, -1) : clipped;
}

/** Path only, so a query string with a token in it never reaches the ledger. */
function safePath(rawUrl: string): string {
  try {
    return new URL(rawUrl).pathname;
  } catch {
    return "<unparseable>";
  }
}
