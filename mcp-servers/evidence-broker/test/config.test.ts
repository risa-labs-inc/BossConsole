import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { DEFAULT_APPROVAL_TTL_MS, DEFAULT_MAX_RESPONSE_BYTES, findCredential, loadConfig } from "../src/config.js";

/** Compiled to dist/test/, so the package root is two levels up. */
const PACKAGE_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

const VALID = {
  credentials: [
    {
      name: "STRIPE_KEY",
      allowedHosts: ["api.stripe.com", "*.stripe.com"],
      approval: "each-use",
      allowedMethods: ["get", "POST"],
      injection: { kind: "header", header: "Authorization", prefix: "Bearer " },
    },
  ],
};

function errorsFor(raw: unknown): string[] {
  const r = loadConfig(raw);
  assert.equal(r.ok, false, "expected this config to be refused");
  return r.ok ? [] : r.errors;
}

test("a valid config loads, with methods normalized to upper case", () => {
  const r = loadConfig(VALID);
  assert.equal(r.ok, true);
  if (!r.ok) return;
  const cred = findCredential(r.config, "STRIPE_KEY");
  assert.ok(cred);
  assert.deepEqual(cred.allowedMethods, ["GET", "POST"]);
  assert.deepEqual(cred.allowedHosts, ["api.stripe.com", "*.stripe.com"]);
});

test("defaults are applied for the optional limits", () => {
  const r = loadConfig(VALID);
  assert.equal(r.ok, true);
  if (!r.ok) return;
  assert.equal(r.config.maxResponseBytes, DEFAULT_MAX_RESPONSE_BYTES);
  assert.equal(r.config.approvalTtlMs, DEFAULT_APPROVAL_TTL_MS);
});

test("explicit limits override the defaults, and nonsense ones are refused", () => {
  const ok = loadConfig({ ...VALID, maxResponseBytes: 2048, approvalTtlMs: 30_000 });
  assert.equal(ok.ok, true);
  if (ok.ok) {
    assert.equal(ok.config.maxResponseBytes, 2048);
    assert.equal(ok.config.approvalTtlMs, 30_000);
  }
  assert.match(errorsFor({ ...VALID, maxResponseBytes: 0 }).join(" "), /maxResponseBytes/);
  assert.match(errorsFor({ ...VALID, approvalTtlMs: -1 }).join(" "), /approvalTtlMs/);
  assert.match(errorsFor({ ...VALID, maxResponseBytes: 1.5 }).join(" "), /maxResponseBytes/);
});

test("a credential with no hosts is a config error, not a wildcard", () => {
  const errors = errorsFor({ credentials: [{ ...VALID.credentials[0], allowedHosts: [] }] });
  assert.match(errors.join(" "), /non-empty array/);
});

test("a credential with no methods is a config error, not a wildcard", () => {
  const errors = errorsFor({ credentials: [{ ...VALID.credentials[0], allowedMethods: [] }] });
  assert.match(errors.join(" "), /allowedMethods/);
});

test("an allowlist entry that would widen scope is refused at load time", () => {
  for (const host of ["*.com", "*", "api.stripe.com:443", "https://api.stripe.com"]) {
    const errors = errorsFor({ credentials: [{ ...VALID.credentials[0], allowedHosts: [host] }] });
    assert.match(errors.join(" "), /allowedHosts/, `expected ${host} to be refused`);
  }
});

test("duplicate credential names are refused", () => {
  const errors = errorsFor({ credentials: [VALID.credentials[0], VALID.credentials[0]] });
  assert.match(errors.join(" "), /duplicate/);
});

test("an unknown method is refused rather than passed through", () => {
  const errors = errorsFor({ credentials: [{ ...VALID.credentials[0], allowedMethods: ["TRACE"] }] });
  assert.match(errors.join(" "), /TRACE/);
});

test("an unknown approval mode is refused", () => {
  const errors = errorsFor({ credentials: [{ ...VALID.credentials[0], approval: "whenever" }] });
  assert.match(errors.join(" "), /approval must be one of/);
});

test("each injection kind is validated on its own terms", () => {
  const base = VALID.credentials[0];
  const good = [
    { kind: "header", header: "X-Api-Key" },
    { kind: "query", param: "api_key" },
    { kind: "basic", username: "operator" },
  ];
  for (const injection of good) {
    const r = loadConfig({ credentials: [{ ...base, injection }] });
    assert.equal(r.ok, true, `expected ${JSON.stringify(injection)} to load`);
  }

  const bad = [
    { kind: "header", header: "not a valid header" },
    { kind: "header" },
    { kind: "query", param: "" },
    { kind: "basic", username: "" },
    { kind: "cookie", name: "x" },
    {},
  ];
  for (const injection of bad) {
    const errors = errorsFor({ credentials: [{ ...base, injection }] });
    assert.match(errors.join(" "), /injection/, `expected ${JSON.stringify(injection)} to be refused`);
  }
});

test("structurally wrong config is refused with a readable message", () => {
  assert.match(errorsFor(null).join(" "), /must be a JSON object/);
  assert.match(errorsFor([]).join(" "), /must be a JSON object/);
  assert.match(errorsFor({}).join(" "), /credentials must be an array/);
  assert.match(errorsFor({ credentials: {} }).join(" "), /credentials must be an array/);
  assert.match(errorsFor({ credentials: [] }).join(" "), /empty/);
  assert.match(errorsFor({ credentials: ["nope"] }).join(" "), /must be an object/);
  assert.match(errorsFor({ credentials: [{ name: "" }] }).join(" "), /name must be a non-empty string/);
});

test("all errors are collected, not just the first", () => {
  const errors = errorsFor({
    credentials: [{ name: "A", allowedHosts: ["*.com"], approval: "nope", allowedMethods: [], injection: {} }],
  });
  assert.ok(errors.length >= 4, `expected several errors, got ${errors.length}: ${errors.join("; ")}`);
});

test("the config.example.json shipped in the README actually loads", () => {
  // Documentation that does not compile is worse than none, so the example is
  // a test fixture rather than prose.
  const raw = JSON.parse(readFileSync(join(PACKAGE_ROOT, "config.example.json"), "utf8"));
  const r = loadConfig(raw);
  assert.equal(r.ok, true, r.ok ? "" : `example config is invalid: ${r.errors.join("; ")}`);
  if (!r.ok) return;
  // One of each injection kind, so the example covers the whole surface.
  const kinds = r.config.credentials.map((c) => c.injection.kind).sort();
  assert.deepEqual(kinds, ["basic", "header", "query"]);
});

test("findCredential returns undefined for an unknown name", () => {
  const r = loadConfig(VALID);
  assert.equal(r.ok, true);
  if (!r.ok) return;
  assert.equal(findCredential(r.config, "NOPE"), undefined);
});
