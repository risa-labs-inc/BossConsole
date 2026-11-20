#!/usr/bin/env node
/**
 * Entry point: the MCP server the agent attaches to, and the CLI the operator
 * answers on.
 *
 * These are one binary and two audiences. `serve` speaks MCP over stdio, which
 * the agent owns. `pending` / `approve` / `deny` are what the operator runs in
 * their own terminal. They are deliberately not reachable from each other.
 */

import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { parseArgs } from "node:util";
import { randomUUID } from "node:crypto";

import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { EvidenceBroker, type FetchLike } from "./broker.js";
import { EvidenceChain, verifyChain } from "./chain.js";
import { DEFAULT_APPROVAL_TTL_MS, loadConfig } from "./config.js";
import { FileApprovalGateway, sanitizeId } from "./gateway-file.js";
import { buildServer } from "./server.js";
import { FileEvidenceStore } from "./store.js";
import type { BrokerConfig, Clock, Ids, SecretResolver } from "./types.js";

const SYSTEM_CLOCK: Clock = { now: () => new Date() };
const UUID_IDS: Ids = { next: () => randomUUID() };

/**
 * Secrets from the environment, one variable per credential.
 *
 * Standalone by design: the server runs without BOSS, and wiring it to the
 * host's own vault is a follow-up rather than a prerequisite. The value never
 * leaves this process either way.
 */
class EnvSecretResolver implements SecretResolver {
  async resolve(name: string): Promise<string | undefined> {
    return process.env[`BROKER_SECRET_${name}`];
  }
}

/** stderr, always. stdout belongs to the MCP protocol in serve mode. */
function note(message: string): void {
  process.stderr.write(`${message}\n`);
}

function loadBrokerConfig(dir: string): BrokerConfig {
  const path = join(dir, "config.json");
  if (!existsSync(path)) {
    throw new Error(
      `no config at ${path}. Write one first -- see the README for a worked example. ` +
        `There is no default credential set, because a default allowlist is an accident waiting to happen.`,
    );
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(readFileSync(path, "utf8"));
  } catch (err) {
    throw new Error(`${path} is not valid JSON: ${err instanceof Error ? err.message : String(err)}`);
  }
  const result = loadConfig(parsed);
  if (!result.ok) {
    throw new Error(`${path} is not a usable config:\n  - ${result.errors.join("\n  - ")}`);
  }
  return result.config;
}

/** Real network call. Redirect handling is the caller's decision, not ours. */
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

function buildBroker(dir: string): { broker: EvidenceBroker; gateway: FileApprovalGateway; store: FileEvidenceStore } {
  const config = loadBrokerConfig(dir);
  const store = new FileEvidenceStore(join(dir, "evidence"));

  // Resume the chain rather than restarting it, and say so loudly if what is
  // already on disk does not verify.
  const existing = store.readAll();
  const head = store.readHead();
  const check = verifyChain(existing, head);
  if (!check.ok) {
    note(`WARNING: the existing evidence ledger does not verify: ${check.reason}`);
    note("WARNING: appending to a ledger that already fails verification. Investigate before trusting it.");
  }

  const gateway = new FileApprovalGateway({
    dir: join(dir, "approvals"),
    clock: SYSTEM_CLOCK,
    ids: UUID_IDS,
    timeoutMs: config.approvalTtlMs > 0 ? Math.min(config.approvalTtlMs, DEFAULT_APPROVAL_TTL_MS) : DEFAULT_APPROVAL_TTL_MS,
  });

  const broker = new EvidenceBroker({
    config,
    secrets: new EnvSecretResolver(),
    gateway,
    store,
    chain: new EvidenceChain(check.ok ? check.head : head),
    clock: SYSTEM_CLOCK,
    ids: UUID_IDS,
    fetch: realFetch,
  });

  return { broker, gateway, store };
}

const USAGE = `boss-evidence-broker -- credential brokering and tamper-evident evidence for BOSS operators

  serve                 Run the MCP server on stdio (default).
  pending               List approval requests waiting on you.
  approve <id>          Approve one pending request.
  deny <id> [reason]    Decline one pending request.
  verify                Verify the evidence ledger against its stored head.
  log [n]               Print the most recent n evidence entries (default 20).

Options
  --dir <path>          Broker directory. Default ./.boss/evidence-broker
                        (or $BOSS_EVIDENCE_BROKER_DIR).

Secrets are read from BROKER_SECRET_<CREDENTIAL_NAME> and never leave the process.
`;

async function main(argv: string[]): Promise<number> {
  const { values, positionals } = parseArgs({
    args: argv,
    options: { dir: { type: "string" }, help: { type: "boolean", short: "h" } },
    allowPositionals: true,
  });

  if (values.help) {
    process.stdout.write(USAGE);
    return 0;
  }

  const dir = resolve(values.dir ?? process.env["BOSS_EVIDENCE_BROKER_DIR"] ?? join(".boss", "evidence-broker"));
  const command = positionals[0] ?? "serve";

  switch (command) {
    case "serve": {
      const { broker } = buildBroker(dir);
      const server = buildServer(broker);
      note(`boss-evidence-broker: serving on stdio, broker directory ${dir}`);
      note("boss-evidence-broker: approvals are answered with `boss-evidence-broker approve <id>`");
      await server.connect(new StdioServerTransport());
      return 0;
    }

    case "pending": {
      const { gateway } = buildBroker(dir);
      const pending = gateway.listPending();
      if (pending.length === 0) {
        process.stdout.write("Nothing is waiting on you.\n");
        return 0;
      }
      for (const p of pending) {
        process.stdout.write(
          [
            `id       ${p.id}`,
            `what     ${p.credential} -> ${p.method} ${p.host}${p.path}`,
            `why      ${p.purpose}`,
            `digest   ${p.actionDigest}`,
            `asked    ${p.requestedAt}`,
            "",
          ].join("\n"),
        );
      }
      return 0;
    }

    case "approve":
    case "deny": {
      const id = positionals[1];
      if (id === undefined) {
        note(`${command} needs an approval id. Run \`pending\` to see them.`);
        return 2;
      }
      const { gateway } = buildBroker(dir);
      const approved = command === "approve";
      const reason = positionals.slice(2).join(" ");
      gateway.writeDecision(sanitizeId(id), {
        approved,
        ...(reason === "" ? {} : { reason }),
      });
      process.stdout.write(`${approved ? "Approved" : "Denied"} ${id}.\n`);
      return 0;
    }

    case "verify": {
      const { broker } = buildBroker(dir);
      const result = broker.verifyEvidence();
      process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
      return result.ok ? 0 : 1;
    }

    case "log": {
      const { broker } = buildBroker(dir);
      const n = Number(positionals[1] ?? 20);
      const limit = Number.isInteger(n) && n > 0 ? n : 20;
      for (const entry of broker.recentEvidence(limit)) {
        process.stdout.write(`${JSON.stringify(entry)}\n`);
      }
      return 0;
    }

    default:
      note(`unknown command "${command}"\n`);
      process.stdout.write(USAGE);
      return 2;
  }
}

main(process.argv.slice(2))
  .then((code) => {
    // serve resolves only when the transport closes.
    if (code !== 0) process.exitCode = code;
  })
  .catch((err: unknown) => {
    note(`boss-evidence-broker: ${err instanceof Error ? err.message : String(err)}`);
    process.exitCode = 1;
  });
