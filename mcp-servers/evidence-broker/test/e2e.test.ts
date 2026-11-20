/**
 * End to end, with the real pieces.
 *
 * Everything else in the suite runs on doubles so it is deterministic. This
 * file deliberately does not: a real HTTP server on loopback, the real global
 * fetch, the real filesystem, the real file-based approval channel, and a real
 * operator answering out of band. It is the test that would catch a component
 * that only works when it is mocked.
 */

import test from "node:test";
import assert from "node:assert/strict";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

import { EvidenceBroker, type FetchLike } from "../src/broker.js";
import { EvidenceChain, verifyChain } from "../src/chain.js";
import { loadConfig } from "../src/config.js";
import { FileApprovalGateway } from "../src/gateway-file.js";
import { FileEvidenceStore } from "../src/store.js";
import { SeqIds } from "./helpers.js";
import type { Clock, SecretResolver } from "../src/types.js";

const SECRET = "live-token-8c1f4a09bd";

const realFetch: FetchLike = async (url, init) => {
  const hasBody = init.body !== undefined && init.method !== "GET" && init.method !== "HEAD";
  const response = await fetch(url, {
    method: init.method,
    headers: init.headers,
    ...(hasBody ? { body: init.body } : {}),
    redirect: init.redirect,
  });
  const headers: Record<string, string> = {};
  response.headers.forEach((value, key) => {
    headers[key] = value;
  });
  return { status: response.status, headers, body: await response.text() };
};

/** Echoes the Authorization header back, which is the worst case for redaction. */
async function startEchoServer(): Promise<{ server: Server; port: number; seen: string[] }> {
  const seen: string[] = [];
  const server = createServer((req: IncomingMessage, res: ServerResponse) => {
    const auth = req.headers["authorization"] ?? "";
    seen.push(String(auth));
    if (req.url === "/redirect") {
      res.writeHead(302, { location: "https://evil.test/collect" });
      res.end("");
      return;
    }
    res.writeHead(200, { "content-type": "application/json", "x-echo-auth": String(auth) });
    res.end(JSON.stringify({ youSent: auth, ok: true }));
  });
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("could not bind the test server");
  return { server, port: address.port, seen };
}

const SYSTEM_CLOCK: Clock = { now: () => new Date() };

class FixedSecret implements SecretResolver {
  async resolve(): Promise<string> {
    return SECRET;
  }
}

function buildRealBroker(port: number, dir: string) {
  const configResult = loadConfig({
    credentials: [
      {
        name: "ECHO_TOKEN",
        allowedHosts: ["127.0.0.1"],
        approval: "each-use",
        allowedMethods: ["GET"],
        injection: { kind: "header", header: "Authorization", prefix: "Bearer " },
      },
    ],
    approvalTtlMs: 10_000,
  });
  assert.equal(configResult.ok, true);
  if (!configResult.ok) throw new Error("config");

  const store = new FileEvidenceStore(join(dir, "evidence"));
  const gateway = new FileApprovalGateway({
    dir: join(dir, "approvals"),
    clock: SYSTEM_CLOCK,
    ids: new SeqIds(),
    timeoutMs: 5_000,
    pollIntervalMs: 20,
  });

  const broker = new EvidenceBroker({
    config: configResult.config,
    secrets: new FixedSecret(),
    gateway,
    store,
    chain: new EvidenceChain(store.readHead()),
    clock: SYSTEM_CLOCK,
    ids: { next: () => randomUUID() },
    fetch: realFetch,
  });

  return { broker, store, gateway, base: `http://127.0.0.1:${port}` };
}

test("a real approved call reaches a real server, and the transcript is clean", async () => {
  const { server, port, seen } = await startEchoServer();
  const dir = mkdtempSync(join(tmpdir(), "evbroker-e2e-"));
  try {
    const { broker, store, gateway, base } = buildRealBroker(port, dir);

    const granted = broker.requestUse({
      credential: "ECHO_TOKEN",
      method: "GET",
      url: `${base}/things`,
      purpose: "fetch the list of things",
    });

    // The operator, out of band, exactly as the CLI does it.
    const approve = (async () => {
      for (let i = 0; i < 100; i += 1) {
        if (gateway.listPending().length > 0) {
          const [pending] = gateway.listPending();
          if (pending) {
            gateway.writeDecision(pending.id, { approved: true });
            return pending;
          }
        }
        await new Promise((r) => setTimeout(r, 10));
      }
      throw new Error("the approval request never appeared");
    })();

    const [grant, pending] = await Promise.all([granted, approve]);
    assert.equal(grant.granted, true);
    if (!grant.granted) return;
    assert.equal(pending.purpose, "fetch the list of things");

    const sent = await broker.send(grant.grantId);
    assert.equal(sent.sent, true);
    if (!sent.sent) return;
    assert.equal(sent.response.status, 200);

    // The server really did receive the credential.
    assert.deepEqual(seen, [`Bearer ${SECRET}`]);

    // And it came back echoed in body and header, yet the agent sees neither.
    const returned = JSON.stringify(sent.response);
    assert.equal(returned.includes(SECRET), false, "the secret survived into the agent's view");
    assert.match(returned, /\[redacted:ECHO_TOKEN\]/);

    // The ledger on disk is clean and verifies.
    const onDisk = readFileSync(store.ledgerPath, "utf8");
    assert.equal(onDisk.includes(SECRET), false, "the secret was written to the ledger");
    const verified = verifyChain(store.readAll(), store.readHead());
    assert.equal(verified.ok, true);
    if (!verified.ok) return;
    assert.equal(verified.entries, 2);
  } finally {
    await new Promise<void>((done) => server.close(() => done()));
  }
});

test("a real denial keeps the credential off the wire entirely", async () => {
  const { server, port, seen } = await startEchoServer();
  const dir = mkdtempSync(join(tmpdir(), "evbroker-e2e-deny-"));
  try {
    const { broker, gateway, base } = buildRealBroker(port, dir);

    const requested = broker.requestUse({
      credential: "ECHO_TOKEN",
      method: "GET",
      url: `${base}/things`,
      purpose: "fetch the list of things",
    });

    const deny = (async () => {
      for (let i = 0; i < 100; i += 1) {
        const [pending] = gateway.listPending();
        if (pending) {
          gateway.writeDecision(pending.id, { approved: false, reason: "not during the freeze" });
          return;
        }
        await new Promise((r) => setTimeout(r, 10));
      }
      throw new Error("the approval request never appeared");
    })();

    const [result] = await Promise.all([requested, deny]);
    assert.equal(result.granted, false);
    if (result.granted) return;
    assert.equal(result.code, "approval-denied");
    assert.match(result.reason, /not during the freeze/);

    // The server was never contacted.
    assert.deepEqual(seen, []);
  } finally {
    await new Promise<void>((done) => server.close(() => done()));
  }
});

test("a real redirect is not followed, so the credential is not re-offered elsewhere", async () => {
  const { server, port } = await startEchoServer();
  const dir = mkdtempSync(join(tmpdir(), "evbroker-e2e-redir-"));
  try {
    const { broker, store, gateway, base } = buildRealBroker(port, dir);

    const requested = broker.requestUse({
      credential: "ECHO_TOKEN",
      method: "GET",
      url: `${base}/redirect`,
      purpose: "follow the report link",
    });
    const approve = (async () => {
      for (let i = 0; i < 100; i += 1) {
        const [pending] = gateway.listPending();
        if (pending) {
          gateway.writeDecision(pending.id, { approved: true });
          return;
        }
        await new Promise((r) => setTimeout(r, 10));
      }
      throw new Error("the approval request never appeared");
    })();

    const [grant] = await Promise.all([requested, approve]);
    assert.equal(grant.granted, true);
    if (!grant.granted) return;

    const sent = await broker.send(grant.grantId);
    assert.equal(sent.sent, true);
    if (!sent.sent) return;
    // 302 handed back as data. The broker did not chase it to evil.test.
    assert.equal(sent.response.status, 302);

    const last = store.readAll().at(-1);
    assert.ok(last);
    assert.equal(last.payload["redirectNotFollowed"], true);
  } finally {
    await new Promise<void>((done) => server.close(() => done()));
  }
});
