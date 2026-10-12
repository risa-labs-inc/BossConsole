import test from "node:test";
import assert from "node:assert/strict";

import { actionDigest, actionShapeOf, DenyAllGateway, GrantRegistry } from "../src/approval.js";
import { FakeClock } from "./helpers.js";
import type { OutboundRequest } from "../src/types.js";

const REQ: OutboundRequest = { method: "GET", url: "https://api.stripe.com/v1/balance" };

function shapeOf(request: OutboundRequest, path = "/v1/balance") {
  return actionShapeOf("STRIPE_KEY", request, "api.stripe.com", path);
}

test("the digest is stable for the same action", () => {
  assert.equal(actionDigest(shapeOf(REQ)), actionDigest(shapeOf(REQ)));
});

test("the digest changes with the method, so a GET approval is not a DELETE approval", () => {
  const get = actionDigest(shapeOf({ ...REQ, method: "GET" }));
  const del = actionDigest(shapeOf({ ...REQ, method: "DELETE" }));
  assert.notEqual(get, del);
});

test("the digest changes with the path and the host", () => {
  const base = actionDigest(shapeOf(REQ, "/v1/balance"));
  assert.notEqual(base, actionDigest(shapeOf(REQ, "/v1/account")));
  assert.notEqual(base, actionDigest(actionShapeOf("STRIPE_KEY", REQ, "other.stripe.com", "/v1/balance")));
});

test("the digest changes with the body", () => {
  const a = actionDigest(shapeOf({ ...REQ, method: "POST", body: '{"amount":100}' }));
  const b = actionDigest(shapeOf({ ...REQ, method: "POST", body: '{"amount":9999999}' }));
  assert.notEqual(a, b);
});

test("the digest does not depend on header order or case", () => {
  const a = actionDigest(shapeOf({ ...REQ, headers: { "X-A": "1", "x-b": "2" } }));
  const b = actionDigest(shapeOf({ ...REQ, headers: { "X-B": "2", "x-a": "1" } }));
  assert.equal(a, b);
});

test("the action shape carries header names but never header values", () => {
  const shape = shapeOf({ ...REQ, headers: { "X-Secret-Ish": "super-secret-value" } });
  assert.deepEqual(shape.headerNames, ["x-secret-ish"]);
  assert.equal(JSON.stringify(shape).includes("super-secret-value"), false);
});

test("the action shape hashes the body rather than holding it", () => {
  const shape = shapeOf({ ...REQ, method: "POST", body: "card_number=4111111111111111" });
  assert.equal(JSON.stringify(shape).includes("4111111111111111"), false);
  assert.match(String(shape.bodyHash), /^[0-9a-f]{64}$/);
  assert.equal(shapeOf(REQ).bodyHash, null);
});

test("a grant is redeemable once for an each-use credential", () => {
  const clock = new FakeClock();
  const registry = new GrantRegistry(clock, 60_000);
  const digest = actionDigest(shapeOf(REQ));
  registry.issue({ id: "g1", digest, credential: "STRIPE_KEY", mode: "each-use", request: REQ, host: "api.stripe.com" });

  const first = registry.redeem("g1", digest);
  assert.equal("ok" in first, true);

  const second = registry.redeem("g1", digest);
  assert.equal("ok" in second, false);
  if ("ok" in second) return;
  assert.equal(second.code, "approval-spent");
});

test("a once-per-session grant is redeemable repeatedly until it expires", () => {
  const clock = new FakeClock();
  const registry = new GrantRegistry(clock, 60_000);
  const digest = actionDigest(shapeOf(REQ));
  registry.issue({ id: "g1", digest, credential: "SEARCH_KEY", mode: "once-per-session", request: REQ, host: "api.stripe.com" });

  assert.equal("ok" in registry.redeem("g1", digest), true);
  assert.equal("ok" in registry.redeem("g1", digest), true);

  clock.advance(60_001);
  const late = registry.redeem("g1", digest);
  assert.equal("ok" in late, false);
  if ("ok" in late) return;
  assert.equal(late.code, "approval-expired");
});

test("an approval cannot be replayed for a different action", () => {
  const registry = new GrantRegistry(new FakeClock(), 60_000);
  const approved = actionDigest(shapeOf({ ...REQ, method: "GET" }));
  const substituted = actionDigest(shapeOf({ ...REQ, method: "DELETE" }, "/v1/account"));
  registry.issue({ id: "g1", digest: approved, credential: "STRIPE_KEY", mode: "each-use", request: REQ, host: "api.stripe.com" });

  const r = registry.redeem("g1", substituted);
  assert.equal("ok" in r, false);
  if ("ok" in r) return;
  assert.equal(r.code, "approval-mismatch");
});

test("an unknown grant id is a denial, not a crash", () => {
  const registry = new GrantRegistry(new FakeClock(), 60_000);
  const r = registry.redeem("nope", "whatever");
  assert.equal("ok" in r, false);
  if ("ok" in r) return;
  assert.equal(r.code, "no-approval");
});

test("expiry is reported ahead of a digest mismatch, because that is the useful fact", () => {
  const clock = new FakeClock();
  const registry = new GrantRegistry(clock, 1_000);
  registry.issue({ id: "g1", digest: "aaa", credential: "K", mode: "each-use", request: REQ, host: "h" });
  clock.advance(1_001);
  const r = registry.redeem("g1", "bbb");
  assert.equal("ok" in r, false);
  if ("ok" in r) return;
  assert.equal(r.code, "approval-expired");
});

test("prune drops only expired grants", () => {
  const clock = new FakeClock();
  const registry = new GrantRegistry(clock, 1_000);
  registry.issue({ id: "old", digest: "a", credential: "K", mode: "each-use", request: REQ, host: "h" });
  clock.advance(1_001);
  registry.issue({ id: "new", digest: "b", credential: "K", mode: "each-use", request: REQ, host: "h" });

  assert.equal(registry.size, 2);
  assert.equal(registry.prune(), 1);
  assert.equal(registry.size, 1);
  assert.ok(registry.get("new"));
  assert.equal(registry.get("old"), undefined);
});

test("with no operator channel configured, the default is to deny", async () => {
  const outcome = await new DenyAllGateway().decide();
  assert.equal(outcome.approved, false);
  if (outcome.approved) return;
  assert.match(outcome.reason, /no operator approval channel/);
});
