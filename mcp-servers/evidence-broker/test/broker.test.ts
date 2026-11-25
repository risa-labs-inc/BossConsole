import test from "node:test";
import assert from "node:assert/strict";

import { AlwaysDenyGateway, harness, ledgerText, STRIPE_SECRET, testConfig } from "./helpers.js";
import { clipToBytes } from "../src/broker.js";

const PURPOSE = "read the current account balance for the reconciliation report";

test("the happy path: approve, send, and both facts are sealed", async () => {
  const { broker, store, net } = harness();

  const granted = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "https://api.stripe.com/v1/balance",
    purpose: PURPOSE,
  });
  assert.equal(granted.granted, true);
  if (!granted.granted) return;

  const sent = await broker.send(granted.grantId);
  assert.equal(sent.sent, true);
  if (!sent.sent) return;
  assert.equal(sent.response.status, 200);

  // The credential actually went out, in the configured position.
  assert.equal(net.lastCall.headers["Authorization"], `Bearer ${STRIPE_SECRET}`);
  assert.equal(net.lastCall.redirect, "manual");

  const kinds = store.readAll().map((e) => e.kind);
  assert.deepEqual(kinds, ["approval-granted", "brokered-request"]);
  assert.equal(broker.verifyEvidence().ok, true);
});

test("the secret never reaches the agent or the ledger", async () => {
  const { broker, store } = harness({
    // A server that echoes the credential straight back, in three encodings.
    reply: {
      status: 200,
      headers: { "x-echo": `Bearer ${STRIPE_SECRET}` },
      body: JSON.stringify({
        raw: STRIPE_SECRET,
        encoded: encodeURIComponent(STRIPE_SECRET),
        b64: Buffer.from(STRIPE_SECRET, "utf8").toString("base64"),
      }),
    },
  });

  const granted = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "https://api.stripe.com/v1/balance",
    purpose: PURPOSE,
  });
  assert.equal(granted.granted, true);
  if (!granted.granted) return;

  const sent = await broker.send(granted.grantId);
  assert.equal(sent.sent, true);
  if (!sent.sent) return;

  const returned = JSON.stringify(sent.response);
  assert.equal(returned.includes(STRIPE_SECRET), false, "raw secret came back to the agent");
  assert.equal(returned.includes(encodeURIComponent(STRIPE_SECRET)), false, "URL-encoded secret came back");
  assert.equal(returned.includes(Buffer.from(STRIPE_SECRET, "utf8").toString("base64")), false, "base64 secret came back");
  assert.match(returned, /\[redacted:STRIPE_KEY\]/);

  assert.equal(ledgerText(store).includes(STRIPE_SECRET), false, "secret was sealed into evidence");
});

test("listCredentials exposes scope but no values", async () => {
  const { broker } = harness();
  const listed = broker.listCredentials();
  assert.equal(listed.length, 3);
  const serialized = JSON.stringify(listed);
  assert.equal(serialized.includes(STRIPE_SECRET), false);
  const stripe = listed.find((c) => c.name === "STRIPE_KEY");
  assert.ok(stripe);
  assert.deepEqual(stripe.allowedHosts, ["api.stripe.com"]);
  assert.equal(stripe.injection, "header Authorization");
});

test("a host outside the allowlist is refused, and the attempt is recorded", async () => {
  const { broker, store, net } = harness();
  const r = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "https://evil.test/v1/balance",
    purpose: PURPOSE,
  });
  assert.equal(r.granted, false);
  if (r.granted) return;
  assert.equal(r.code, "host-not-allowed");
  assert.equal(net.calls.length, 0, "nothing should have gone out");

  const [entry] = store.readAll();
  assert.ok(entry);
  assert.equal(entry.kind, "request-denied");
  assert.equal(entry.payload["code"], "host-not-allowed");
});

test("the substring near-miss host is refused", async () => {
  const { broker } = harness();
  // Contains "api.stripe.com" as a substring; is not api.stripe.com.
  const r = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "https://api.stripe.com.evil.test/v1/balance",
    purpose: PURPOSE,
  });
  assert.equal(r.granted, false);
  if (r.granted) return;
  assert.equal(r.code, "host-not-allowed");
});

test("a userinfo target is refused before any policy question is asked", async () => {
  const { broker } = harness();
  const r = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "https://api.stripe.com@evil.test/v1",
    purpose: PURPOSE,
  });
  assert.equal(r.granted, false);
  if (r.granted) return;
  assert.equal(r.code, "bad-target");
});

test("a non-http scheme is refused", async () => {
  const { broker } = harness();
  const r = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "file:///etc/passwd",
    purpose: PURPOSE,
  });
  assert.equal(r.granted, false);
  if (r.granted) return;
  assert.equal(r.code, "bad-target");
});

test("a method outside the allowlist is refused", async () => {
  const { broker } = harness();
  const r = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "DELETE",
    url: "https://api.stripe.com/v1/account",
    purpose: "clean up",
  });
  assert.equal(r.granted, false);
  if (r.granted) return;
  assert.equal(r.code, "method-not-allowed");
});

test("an unknown credential is refused", async () => {
  const { broker } = harness();
  const r = await broker.requestUse({
    credential: "NOT_CONFIGURED",
    method: "GET",
    url: "https://api.stripe.com/v1/balance",
    purpose: PURPOSE,
  });
  assert.equal(r.granted, false);
  if (r.granted) return;
  assert.equal(r.code, "unknown-credential");
});

test("the operator declining is a recorded fact, and nothing is sent", async () => {
  const gateway = new AlwaysDenyGateway("looks like a production write");
  const { broker, store, net } = harness({ gateway });

  const r = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "POST",
    url: "https://api.stripe.com/v1/charges",
    purpose: "issue a refund",
  });
  assert.equal(r.granted, false);
  if (r.granted) return;
  assert.equal(r.code, "approval-denied");
  assert.match(r.reason, /looks like a production write/);
  assert.equal(net.calls.length, 0);

  const [entry] = store.readAll();
  assert.ok(entry);
  assert.equal(entry.kind, "request-denied");
});

test("the operator is not consulted about a call policy already forbids", async () => {
  const gateway = new AlwaysDenyGateway();
  const { broker } = harness({ gateway });
  await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "https://evil.test/v1",
    purpose: PURPOSE,
  });
  assert.equal(gateway.seen.length, 0, "an operator asked to rubber-stamp denials learns to click through");
});

test("an each-use grant cannot be redeemed twice", async () => {
  const { broker, net } = harness();
  const granted = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "https://api.stripe.com/v1/balance",
    purpose: PURPOSE,
  });
  assert.equal(granted.granted, true);
  if (!granted.granted) return;

  assert.equal((await broker.send(granted.grantId)).sent, true);
  const second = await broker.send(granted.grantId);
  assert.equal(second.sent, false);
  if (second.sent) return;
  assert.equal(second.code, "approval-spent");
  assert.equal(net.calls.length, 1, "the second send must not reach the network");
});

test("a once-per-session grant is reusable inside its window", async () => {
  const { broker, net } = harness();
  const granted = await broker.requestUse({
    credential: "SEARCH_KEY",
    method: "GET",
    url: "https://eu.search.example/query?q=hello",
    purpose: "look up a term",
  });
  assert.equal(granted.granted, true);
  if (!granted.granted) return;

  assert.equal((await broker.send(granted.grantId)).sent, true);
  assert.equal((await broker.send(granted.grantId)).sent, true);
  assert.equal(net.calls.length, 2);
});

test("a grant expires", async () => {
  const { broker, clock } = harness();
  const granted = await broker.requestUse({
    credential: "SEARCH_KEY",
    method: "GET",
    url: "https://eu.search.example/query?q=hello",
    purpose: "look up a term",
  });
  assert.equal(granted.granted, true);
  if (!granted.granted) return;

  clock.advance(300_001); // just past the default 5 minute TTL
  const sent = await broker.send(granted.grantId);
  assert.equal(sent.sent, false);
  if (sent.sent) return;
  assert.equal(sent.code, "approval-expired");
});

test("an unknown grant id is refused", async () => {
  const { broker } = harness();
  const sent = await broker.send("grant-does-not-exist");
  assert.equal(sent.sent, false);
  if (sent.sent) return;
  assert.equal(sent.code, "no-approval");
});

test("a credential the vault cannot resolve is refused after approval", async () => {
  const { broker, net } = harness({ secrets: { SEARCH_KEY: "search-key-abcdef" } });
  const granted = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "https://api.stripe.com/v1/balance",
    purpose: PURPOSE,
  });
  assert.equal(granted.granted, true);
  if (!granted.granted) return;

  const sent = await broker.send(granted.grantId);
  assert.equal(sent.sent, false);
  if (sent.sent) return;
  assert.equal(sent.code, "secret-missing");
  assert.equal(net.calls.length, 0);
});

test("query injection puts the secret in the URL and still redacts it", async () => {
  const { broker, net, store } = harness({
    reply: { status: 200, headers: {}, body: "ok" },
  });
  const granted = await broker.requestUse({
    credential: "SEARCH_KEY",
    method: "GET",
    url: "https://eu.search.example/query?q=hello",
    purpose: "look up a term",
  });
  assert.equal(granted.granted, true);
  if (!granted.granted) return;
  await broker.send(granted.grantId);

  assert.match(net.lastCall.url, /api_key=search-key-abcdef/);
  // The ledger records the path only, so the query string never lands there.
  const text = ledgerText(store);
  assert.equal(text.includes("search-key-abcdef"), false);
  assert.equal(text.includes("api_key"), false);
});

test("basic auth injection composes the pair and redacts the encoded form", async () => {
  const pw = "legacy-password-99";
  const pair = Buffer.from(`operator:${pw}`, "utf8").toString("base64");
  const { broker, net } = harness({
    reply: { status: 401, headers: { "www-authenticate": `Basic ${pair}` }, body: pair },
  });

  const granted = await broker.requestUse({
    credential: "LEGACY_PW",
    method: "GET",
    url: "https://legacy.example.com/report",
    purpose: "pull the nightly report",
  });
  assert.equal(granted.granted, true);
  if (!granted.granted) return;

  const sent = await broker.send(granted.grantId);
  assert.equal(sent.sent, true);
  if (!sent.sent) return;

  assert.equal(net.lastCall.headers["Authorization"], `Basic ${pair}`);
  const returned = JSON.stringify(sent.response);
  assert.equal(returned.includes(pair), false, "the base64 pair leaked back to the agent");
  assert.equal(returned.includes(pw), false);
});

test("a redirect is recorded and deliberately not followed", async () => {
  const { broker, store, net } = harness({
    reply: { status: 302, headers: { location: "https://evil.test/collect" }, body: "" },
  });
  const granted = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "https://api.stripe.com/v1/balance",
    purpose: PURPOSE,
  });
  assert.equal(granted.granted, true);
  if (!granted.granted) return;

  const sent = await broker.send(granted.grantId);
  assert.equal(sent.sent, true);
  if (!sent.sent) return;
  assert.equal(sent.response.status, 302);
  assert.equal(net.calls.length, 1, "following the redirect would re-decide the allowlist on the server's terms");

  const last = store.readAll().at(-1);
  assert.ok(last);
  assert.equal(last.payload["redirectNotFollowed"], true);
});

test("an oversized response is clipped and flagged", async () => {
  const config = testConfig({ maxResponseBytes: 64 });
  const { broker } = harness({ config, reply: { status: 200, headers: {}, body: "x".repeat(500) } });
  const granted = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "https://api.stripe.com/v1/balance",
    purpose: PURPOSE,
  });
  assert.equal(granted.granted, true);
  if (!granted.granted) return;

  const sent = await broker.send(granted.grantId);
  assert.equal(sent.sent, true);
  if (!sent.sent) return;
  assert.equal(sent.response.truncated, true);
  assert.equal(Buffer.byteLength(sent.response.body, "utf8") <= 64, true);
});

test("clipToBytes does not split a multi-byte character", () => {
  // Three-byte characters against a budget that lands mid-glyph.
  const text = "日本語テキスト";
  const out = clipToBytes(text, 7);
  assert.equal(Buffer.byteLength(out, "utf8") <= 7, true);
  assert.equal(out.includes("�"), false);
  assert.equal(clipToBytes("short", 100), "short");
});

test("the chain stays verifiable across a mixed run of allows and denials", async () => {
  const { broker, store } = harness();
  await broker.requestUse({ credential: "NOPE", method: "GET", url: "https://api.stripe.com/v1", purpose: "x" });
  await broker.requestUse({ credential: "STRIPE_KEY", method: "GET", url: "https://evil.test/v1", purpose: "x" });
  const ok = await broker.requestUse({
    credential: "STRIPE_KEY",
    method: "GET",
    url: "https://api.stripe.com/v1/balance",
    purpose: PURPOSE,
  });
  assert.equal(ok.granted, true);
  if (!ok.granted) return;
  await broker.send(ok.grantId);
  await broker.send(ok.grantId); // spent

  const result = broker.verifyEvidence();
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.entries, 5);
  assert.deepEqual(store.readAll().map((e) => e.kind), [
    "request-denied",
    "request-denied",
    "approval-granted",
    "brokered-request",
    "send-denied",
  ]);
});

test("recentEvidence returns the tail, newest last", async () => {
  const { broker } = harness();
  for (let i = 0; i < 4; i += 1) {
    await broker.requestUse({ credential: "NOPE", method: "GET", url: "https://x.test/", purpose: "x" });
  }
  assert.equal(broker.recentEvidence(2).length, 2);
  const all = broker.recentEvidence(100);
  assert.equal(all.length, 4);
  assert.equal(all.at(-1)?.seq, 4);
});
