import test from "node:test";
import assert from "node:assert/strict";

import {
  assertRedacted,
  buildRedactor,
  encodingsOf,
  MIN_REDACTABLE_LENGTH,
  redactDeep,
  UnredactableSecretError,
} from "../src/redact.js";

const SECRET = "sk-live-9f2a7c4b81";
const secrets = [{ name: "STRIPE_KEY", value: SECRET }];

test("the raw value is redacted", () => {
  const redact = buildRedactor(secrets);
  assert.equal(redact(`Authorization: Bearer ${SECRET}`), "Authorization: Bearer [redacted:STRIPE_KEY]");
});

test("every occurrence is redacted, not just the first", () => {
  const redact = buildRedactor(secrets);
  const out = redact(`${SECRET} and again ${SECRET}`);
  assert.equal(out.includes(SECRET), false);
  assert.equal(out, "[redacted:STRIPE_KEY] and again [redacted:STRIPE_KEY]");
});

test("the URL-encoded form is redacted", () => {
  const value = "p@ss/word+with=chars";
  const redact = buildRedactor([{ name: "PW", value }]);
  const encoded = encodeURIComponent(value);
  assert.notEqual(encoded, value, "test is meaningless if encoding is a no-op");
  assert.equal(redact(`?token=${encoded}`).includes(encoded), false);
});

test("the base64 and base64url forms are redacted", () => {
  const redact = buildRedactor(secrets);
  const b64 = Buffer.from(SECRET, "utf8").toString("base64");
  const b64url = b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  assert.equal(redact(`Authorization: Basic ${b64}`).includes(b64), false);
  assert.equal(redact(`?t=${b64url}`).includes(b64url), false);
});

test("the JSON-escaped form is redacted", () => {
  const value = 'quote"and\\backslash-long';
  const redact = buildRedactor([{ name: "ODD", value }]);
  const escaped = JSON.stringify(value).slice(1, -1);
  assert.notEqual(escaped, value, "test is meaningless if escaping is a no-op");
  assert.equal(redact(`{"k":"${escaped}"}`).includes(escaped), false);
});

test("extra literals cover the Basic auth pair the caller composes", () => {
  const pair = Buffer.from(`operator:${SECRET}`, "utf8").toString("base64");
  const redact = buildRedactor([{ name: "STRIPE_KEY", value: SECRET, extraLiterals: [pair] }]);
  assert.equal(redact(`Authorization: Basic ${pair}`).includes(pair), false);
});

test("a secret too short to redact safely is refused outright", () => {
  assert.throws(() => buildRedactor([{ name: "TINY", value: "abc" }]), UnredactableSecretError);
  // The floor is inclusive: exactly MIN_REDACTABLE_LENGTH is fine.
  const ok = "x".repeat(MIN_REDACTABLE_LENGTH);
  assert.doesNotThrow(() => buildRedactor([{ name: "EDGE", value: ok }]));
});

test("a shorter secret cannot partially consume a longer one", () => {
  // "prefix-secret" contains "prefix-" ... the short one must not eat the
  // longer one's head and leave its tail exposed.
  const long = "prefix-secret-tail-9999";
  const short = "prefix-secret";
  const redact = buildRedactor([
    { name: "SHORT", value: short },
    { name: "LONG", value: long },
  ]);
  const out = redact(`value=${long}`);
  assert.equal(out, "value=[redacted:LONG]");
  assert.equal(out.includes("tail-9999"), false);
});

test("redactDeep scrubs nested values, array members and keys", () => {
  const redact = buildRedactor(secrets);
  const input = {
    [SECRET]: "key-position",
    nested: { token: SECRET, list: [SECRET, "safe", 42, null] },
  };
  const out = redactDeep(input, redact) as Record<string, unknown>;
  const serialized = JSON.stringify(out);
  assert.equal(serialized.includes(SECRET), false);
  assert.equal(Object.keys(out).includes("[redacted:STRIPE_KEY]"), true);
  const nested = out["nested"] as { list: unknown[] };
  assert.deepEqual(nested.list, ["[redacted:STRIPE_KEY]", "safe", 42, null]);
});

test("redactDeep leaves non-string scalars untouched", () => {
  const redact = buildRedactor(secrets);
  assert.equal(redactDeep(42, redact), 42);
  assert.equal(redactDeep(null, redact), null);
  assert.equal(redactDeep(true, redact), true);
});

test("assertRedacted passes clean text and throws on a leak", () => {
  assert.doesNotThrow(() => assertRedacted("nothing to see", secrets));
  assert.throws(() => assertRedacted(`leaked ${SECRET}`, secrets), /redaction gap/);
  // An encoded leak is caught too, not just the raw form.
  const b64 = Buffer.from(SECRET, "utf8").toString("base64");
  assert.throws(() => assertRedacted(`leaked ${b64}`, secrets), /redaction gap/);
});

test("encodingsOf always includes the raw value and never the empty string", () => {
  const forms = encodingsOf(SECRET);
  assert.equal(forms.includes(SECRET), true);
  assert.equal(forms.includes(""), false);
});

test("the redactor is a no-op on empty input", () => {
  const redact = buildRedactor(secrets);
  assert.equal(redact(""), "");
});
