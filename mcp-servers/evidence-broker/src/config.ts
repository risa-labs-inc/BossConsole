/**
 * Config loading, validated strictly at startup.
 *
 * A misconfigured allowlist is a silently widened allowlist, so every entry is
 * parsed and rejected here rather than at request time. Nothing in this file
 * has a permissive default: a credential with no hosts or no methods is a
 * config error, not a credential that permits everything.
 */

import { validateAllowlistEntry } from "./target.js";
import type { ApprovalMode, BrokerConfig, CredentialDef, Injection } from "./types.js";

export const DEFAULT_MAX_RESPONSE_BYTES = 1_048_576; // 1 MiB
export const DEFAULT_APPROVAL_TTL_MS = 300_000; // 5 minutes

const APPROVAL_MODES: readonly ApprovalMode[] = ["each-use", "once-per-session"];
const KNOWN_METHODS: readonly string[] = ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"];

/** RFC 7230 token, which is what a header name is allowed to be. */
const HEADER_NAME = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;

export type ConfigResult = { ok: true; config: BrokerConfig } | { ok: false; errors: string[] };

export function loadConfig(raw: unknown): ConfigResult {
  const errors: string[] = [];

  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) {
    return { ok: false, errors: ["config must be a JSON object"] };
  }
  const root = raw as Record<string, unknown>;

  const rawCredentials = root["credentials"];
  if (!Array.isArray(rawCredentials)) {
    return { ok: false, errors: ['config.credentials must be an array'] };
  }
  if (rawCredentials.length === 0) {
    errors.push("config.credentials is empty, so the broker could never do anything");
  }

  const credentials: CredentialDef[] = [];
  const seenNames = new Set<string>();

  rawCredentials.forEach((entry, i) => {
    const at = `credentials[${i}]`;
    if (entry === null || typeof entry !== "object" || Array.isArray(entry)) {
      errors.push(`${at} must be an object`);
      return;
    }
    const c = entry as Record<string, unknown>;

    const name = c["name"];
    if (typeof name !== "string" || name.trim() === "") {
      errors.push(`${at}.name must be a non-empty string`);
      return;
    }
    if (seenNames.has(name)) {
      errors.push(`${at}.name "${name}" is a duplicate; credential names must be unique`);
      return;
    }
    seenNames.add(name);

    const rawHosts = c["allowedHosts"];
    const allowedHosts: string[] = [];
    if (!Array.isArray(rawHosts) || rawHosts.length === 0) {
      errors.push(`${at}.allowedHosts must be a non-empty array; there is no implicit "any host"`);
    } else {
      rawHosts.forEach((h, j) => {
        if (typeof h !== "string") {
          errors.push(`${at}.allowedHosts[${j}] must be a string`);
          return;
        }
        const v = validateAllowlistEntry(h);
        if (!v.ok) {
          errors.push(`${at}.allowedHosts[${j}] (${JSON.stringify(h)}) is not usable: ${v.reason}`);
          return;
        }
        allowedHosts.push(v.normalized);
      });
    }

    const approval = c["approval"];
    if (typeof approval !== "string" || !APPROVAL_MODES.includes(approval as ApprovalMode)) {
      errors.push(`${at}.approval must be one of ${APPROVAL_MODES.join(", ")}`);
    }

    const rawMethods = c["allowedMethods"];
    const allowedMethods: string[] = [];
    if (!Array.isArray(rawMethods) || rawMethods.length === 0) {
      errors.push(`${at}.allowedMethods must be a non-empty array; there is no implicit "any method"`);
    } else {
      rawMethods.forEach((m, j) => {
        if (typeof m !== "string") {
          errors.push(`${at}.allowedMethods[${j}] must be a string`);
          return;
        }
        const upper = m.toUpperCase();
        if (!KNOWN_METHODS.includes(upper)) {
          errors.push(`${at}.allowedMethods[${j}] (${JSON.stringify(m)}) is not one of ${KNOWN_METHODS.join(", ")}`);
          return;
        }
        allowedMethods.push(upper);
      });
    }

    const injection = parseInjection(c["injection"], at, errors);

    if (injection && typeof approval === "string" && APPROVAL_MODES.includes(approval as ApprovalMode)) {
      credentials.push({
        name,
        allowedHosts,
        approval: approval as ApprovalMode,
        injection,
        allowedMethods,
      });
    }
  });

  const maxResponseBytes = readPositiveInt(root["maxResponseBytes"], DEFAULT_MAX_RESPONSE_BYTES, "maxResponseBytes", errors);
  const approvalTtlMs = readPositiveInt(root["approvalTtlMs"], DEFAULT_APPROVAL_TTL_MS, "approvalTtlMs", errors);

  if (errors.length > 0) return { ok: false, errors };
  return { ok: true, config: { credentials, maxResponseBytes, approvalTtlMs } };
}

function parseInjection(raw: unknown, at: string, errors: string[]): Injection | undefined {
  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) {
    errors.push(`${at}.injection must be an object`);
    return undefined;
  }
  const inj = raw as Record<string, unknown>;
  const kind = inj["kind"];

  if (kind === "header") {
    const header = inj["header"];
    if (typeof header !== "string" || !HEADER_NAME.test(header)) {
      errors.push(`${at}.injection.header must be a valid header name`);
      return undefined;
    }
    const prefix = inj["prefix"];
    if (prefix !== undefined && typeof prefix !== "string") {
      errors.push(`${at}.injection.prefix must be a string when present`);
      return undefined;
    }
    return prefix === undefined ? { kind: "header", header } : { kind: "header", header, prefix };
  }

  if (kind === "query") {
    const param = inj["param"];
    if (typeof param !== "string" || param.trim() === "") {
      errors.push(`${at}.injection.param must be a non-empty string`);
      return undefined;
    }
    return { kind: "query", param };
  }

  if (kind === "basic") {
    const username = inj["username"];
    if (typeof username !== "string" || username === "") {
      errors.push(`${at}.injection.username must be a non-empty string`);
      return undefined;
    }
    return { kind: "basic", username };
  }

  errors.push(`${at}.injection.kind must be one of header, query, basic`);
  return undefined;
}

function readPositiveInt(raw: unknown, fallback: number, field: string, errors: string[]): number {
  if (raw === undefined) return fallback;
  if (typeof raw !== "number" || !Number.isInteger(raw) || raw <= 0) {
    errors.push(`config.${field} must be a positive integer when present`);
    return fallback;
  }
  return raw;
}

/** Look a credential up by name. Undefined means unknown, which is a denial. */
export function findCredential(config: BrokerConfig, name: string): CredentialDef | undefined {
  return config.credentials.find((c) => c.name === name);
}
