import test from "node:test";
import assert from "node:assert/strict";

import { hostAllowed, hostMatchesEntry, isIpLiteral, normalizeHost, parseTarget, validateAllowlistEntry } from "../src/target.js";

test("parseTarget accepts plain http and https targets", () => {
  const r = parseTarget("https://api.example.com/v1/things?q=1");
  assert.equal(r.ok, true);
  if (!r.ok) return;
  assert.equal(r.host, "api.example.com");
  assert.equal(r.protocol, "https:");
});

test("parseTarget lowercases and strips the FQDN root dot", () => {
  const r = parseTarget("https://API.Example.COM./x");
  assert.equal(r.ok, true);
  if (!r.ok) return;
  assert.equal(r.host, "api.example.com");
});

test("parseTarget refuses non-http schemes, so no other scheme can carry a secret", () => {
  for (const url of ["file:///etc/passwd", "data:text/plain,hi", "ftp://example.com/x", "boss://open"]) {
    const r = parseTarget(url);
    assert.equal(r.ok, false, `expected ${url} to be refused`);
  }
});

test("parseTarget refuses userinfo, which is ambiguous about the real host", () => {
  // Reads as api.example.com to a human; the actual host is evil.test.
  const r = parseTarget("https://api.example.com@evil.test/v1");
  assert.equal(r.ok, false);
  if (r.ok) return;
  assert.match(r.reason, /userinfo/);
});

test("parseTarget refuses garbage and relative input", () => {
  for (const url of ["", "   ", "not a url", "/v1/things", "https://"]) {
    assert.equal(parseTarget(url).ok, false, `expected ${JSON.stringify(url)} to be refused`);
  }
});

test("exact allowlist entries match exactly and nothing else", () => {
  assert.equal(hostMatchesEntry("api.example.com", "api.example.com"), true);
  assert.equal(hostMatchesEntry("API.EXAMPLE.COM", "api.example.com"), true);
  assert.equal(hostMatchesEntry("api.example.com.", "api.example.com"), true);
  assert.equal(hostMatchesEntry("other.example.com", "api.example.com"), false);
  assert.equal(hostMatchesEntry("example.com", "api.example.com"), false);
});

test("the substring bug class is closed", () => {
  // Every one of these contains "api.example.com" as a substring.
  const attacker = [
    "api.example.com.evil.test",
    "evil.test/api.example.com",
    "notapi.example.com",
    "xapi.example.com",
    "api.example.computer",
  ];
  for (const host of attacker) {
    assert.equal(hostMatchesEntry(host, "api.example.com"), false, `${host} must not match`);
  }
  // And via the full parse path, for the ones that are real hosts.
  const r = parseTarget("https://api.example.com.evil.test/v1");
  assert.equal(r.ok, true);
  if (!r.ok) return;
  assert.equal(hostAllowed(r.host, ["api.example.com"]), false);
});

test("a wildcard matches strict subdomains at any depth but not the apex", () => {
  assert.equal(hostMatchesEntry("a.example.com", "*.example.com"), true);
  assert.equal(hostMatchesEntry("a.b.example.com", "*.example.com"), true);
  // Deliberate: if you want the apex, list it.
  assert.equal(hostMatchesEntry("example.com", "*.example.com"), false);
  assert.equal(hostMatchesEntry("notexample.com", "*.example.com"), false);
  assert.equal(hostMatchesEntry("example.com.evil.test", "*.example.com"), false);
});

test("a wildcard never matches an IP literal", () => {
  assert.equal(hostMatchesEntry("127.0.0.1", "*.0.0.1"), false);
  assert.equal(hostMatchesEntry("10.0.0.7", "*.example.com"), false);
});

test("IP literals are recognised, including the bracketed IPv6 form", () => {
  assert.equal(isIpLiteral("127.0.0.1"), true);
  assert.equal(isIpLiteral("[::1]"), true);
  assert.equal(isIpLiteral("999.1.1.1"), false);
  assert.equal(isIpLiteral("example.com"), false);
  // Leading zeros are read as octal by some resolvers; not our idea of an IP.
  assert.equal(isIpLiteral("010.0.0.1"), false);
});

test("IP literals still work as exact entries", () => {
  assert.equal(hostMatchesEntry("127.0.0.1", "127.0.0.1"), true);
  const r = parseTarget("http://127.0.0.1:7677/mcp");
  assert.equal(r.ok, true);
  if (!r.ok) return;
  assert.equal(hostAllowed(r.host, ["127.0.0.1"]), true);
});

test("an empty allowlist denies everything", () => {
  assert.equal(hostAllowed("api.example.com", []), false);
});

test("allowlist entries that would silently widen scope are refused at load time", () => {
  const bad = ["", "*", "*.com", "*.*.example.com", "api.example.com:443", "https://api.example.com", "ex*ample.com", "*.127.0.0.1"];
  for (const entry of bad) {
    assert.equal(validateAllowlistEntry(entry).ok, false, `expected ${JSON.stringify(entry)} to be refused`);
  }
});

test("well-formed allowlist entries are accepted and normalized", () => {
  for (const [input, expected] of [
    ["api.example.com", "api.example.com"],
    ["API.Example.com.", "api.example.com"],
    ["*.example.com", "*.example.com"],
    ["localhost", "localhost"],
  ] as const) {
    const r = validateAllowlistEntry(input);
    assert.equal(r.ok, true, `expected ${input} to be accepted`);
    if (!r.ok) continue;
    assert.equal(r.normalized, expected);
  }
});

test("normalizeHost leaves a bare dot alone rather than emptying it", () => {
  assert.equal(normalizeHost("."), ".");
});
