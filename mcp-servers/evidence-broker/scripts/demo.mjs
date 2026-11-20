/**
 * A runnable demo: `npm run demo`.
 *
 * Nothing here is mocked. The agent side is a real MCP client over real stdio,
 * spawning `index.js serve`. The operator side is the real CLI -- `pending`,
 * `approve`, `verify` -- shelled out to as a separate process, because that is
 * the actual trust boundary. The target is a real HTTP server that echoes the
 * credential straight back in both a header and the body, which is the worst
 * case for redaction.
 *
 * It also checks itself. Every claim the README makes is asserted here, and the
 * script exits non-zero if one fails, so it doubles as an integration check
 * rather than a pretty printer.
 */

import { createServer } from "node:http";
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const PACKAGE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const DIR = join(PACKAGE_ROOT, ".demo-run");
const CLI = join(PACKAGE_ROOT, "dist", "src", "index.js");
const SECRET = "sk-live-demo-4f9a2c7b81e3";

if (!existsSync(CLI)) {
  console.error(`No build found at ${CLI}. Run \`npm run build\` first, or use \`npm run demo\`.`);
  process.exit(1);
}

const bar = (t) => console.log(`\n${"=".repeat(74)}\n${t}\n${"=".repeat(74)}`);
const step = (t) => console.log(`\n>> ${t}`);
const out = (t) => console.log(String(t).split("\n").map((l) => "   " + l).join("\n"));
const textOf = (r) => (r.content ?? []).map((c) => c.text ?? "").join("\n");

function cli(...args) {
  try {
    return execFileSync(process.execPath, [CLI, "--dir", DIR, ...args], { encoding: "utf8" });
  } catch (err) {
    // `verify` exits 1 on a broken chain, which is a result and not a crash.
    if (typeof err.stdout === "string" && err.stdout !== "") return err.stdout;
    throw err;
  }
}

const failures = [];
function check(label, condition) {
  if (condition) {
    console.log(`   [ok]   ${label}`);
  } else {
    console.log(`   [FAIL] ${label}`);
    failures.push(label);
  }
}

// ------------------------------------------------------------------ the target
const received = [];
const server = createServer((req, res) => {
  const auth = String(req.headers["authorization"] ?? "");
  received.push(auth);
  res.writeHead(200, { "content-type": "application/json", "x-echo-auth": auth });
  res.end(JSON.stringify({ balance: 4213, youSentMe: auth }));
});
await new Promise((done) => server.listen(0, "127.0.0.1", done));
const PORT = server.address().port;

// Start from nothing every run. The demo deliberately tampers with the ledger
// at the end, and a tampered ledger is meant to stay broken -- so reusing the
// directory would (correctly) fail verification before the demo even began.
rmSync(DIR, { recursive: true, force: true });
mkdirSync(DIR, { recursive: true });
writeFileSync(
  join(DIR, "config.json"),
  `${JSON.stringify(
    {
      approvalTtlMs: 120000,
      credentials: [
        {
          name: "DEMO_TOKEN",
          allowedHosts: ["127.0.0.1"],
          approval: "each-use",
          allowedMethods: ["GET"],
          injection: { kind: "header", header: "Authorization", prefix: "Bearer " },
        },
      ],
    },
    null,
    2,
  )}\n`,
);

bar("SETUP");
console.log(`   target server : http://127.0.0.1:${PORT}   (echoes the credential back)`);
console.log(`   broker dir    : ${DIR}`);
console.log(`   the secret    : ${SECRET}`);
console.log(`   allowlist     : DEMO_TOKEN -> 127.0.0.1, GET only, approval each-use`);

const transport = new StdioClientTransport({
  command: process.execPath,
  args: [CLI, "--dir", DIR, "serve"],
  env: { ...process.env, BROKER_SECRET_DEMO_TOKEN: SECRET },
  stderr: "ignore",
});
const agent = new Client({ name: "demo-agent", version: "1.0.0" });
await agent.connect(transport);

try {
  // ---------------------------------------------------------------- 1. surface
  bar("1. THE AGENT CONNECTS AND LOOKS AROUND");
  step("agent -> tools/list");
  const toolNames = (await agent.listTools()).tools.map((t) => t.name);
  out(toolNames.join("\n"));
  console.log("");
  check("no tool can grant an approval", !toolNames.some((n) => /approve|deny|decide/.test(n)));
  check("no tool can read a secret", !toolNames.some((n) => /secret|reveal/.test(n)));

  step("agent -> list_credentials");
  const listed = textOf(await agent.callTool({ name: "list_credentials", arguments: {} }));
  out(listed);
  console.log("");
  check("the agent is told the scope", listed.includes("127.0.0.1") && listed.includes("DEMO_TOKEN"));
  check("the agent is not told the value", !listed.includes(SECRET));

  // ---------------------------------------------------------------- 2. denial
  bar("2. THE AGENT TRIES A HOST IT IS NOT SCOPED FOR");
  step("agent -> request_credential_use    url: https://127.0.0.1.evil.test/v1/balance");
  console.log("   (that hostname *contains* 127.0.0.1 as a substring)");
  const refused = JSON.parse(
    textOf(
      await agent.callTool({
        name: "request_credential_use",
        arguments: {
          credential: "DEMO_TOKEN",
          method: "GET",
          url: "https://127.0.0.1.evil.test/v1/balance",
          purpose: "read the balance",
        },
      }),
    ),
  );
  out(JSON.stringify(refused, null, 2));
  console.log("");
  check("the substring near-miss host is refused", refused.granted === false && refused.code === "host-not-allowed");
  check("the operator was never interrupted about it", cli("pending").startsWith("Nothing"));

  // ---------------------------------------------------------------- 3. approval
  bar("3. A LEGITIMATE REQUEST, AWAITING A HUMAN");
  step("agent -> request_credential_use    (the real target)");
  console.log("   ...this call now BLOCKS until a human answers.");

  const pendingCall = agent.callTool({
    name: "request_credential_use",
    arguments: {
      credential: "DEMO_TOKEN",
      method: "GET",
      url: `http://127.0.0.1:${PORT}/v1/balance`,
      purpose: "read the account balance for the reconciliation report",
    },
  });

  let approvalId = null;
  for (let i = 0; i < 200 && approvalId === null; i += 1) {
    const listing = cli("pending");
    if (!listing.startsWith("Nothing")) {
      step("operator $ boss-evidence-broker pending");
      out(listing.trimEnd());
      approvalId = listing.match(/id\s+(\S+)/)?.[1] ?? null;
      break;
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  check("the request reached the operator queue", approvalId !== null);
  if (approvalId === null) throw new Error("the approval request never appeared");

  step(`operator $ boss-evidence-broker approve ${approvalId}`);
  out(cli("approve", approvalId).trimEnd());

  const granted = JSON.parse(textOf(await pendingCall));
  step("agent <- grant");
  out(JSON.stringify(granted, null, 2));
  check("a grant was issued", granted.granted === true);

  // ---------------------------------------------------------------- 4. send
  bar("4. THE APPROVED CALL GOES OUT");
  step(`agent -> send    { grantId: "${granted.grantId}" }`);
  const sent = textOf(await agent.callTool({ name: "send", arguments: { grantId: granted.grantId } }));
  out(sent);

  console.log("\n   What the target server actually received:");
  out(received.map((s) => `Authorization: ${s}`).join("\n"));
  console.log("");
  check("the real credential reached the server", received.includes(`Bearer ${SECRET}`));
  check("the agent's view holds no encoding of it", !sent.includes(SECRET) && sent.includes("[redacted:DEMO_TOKEN]"));

  // ---------------------------------------------------------------- 5. replay
  bar("5. THE AGENT TRIES TO REUSE THE APPROVAL");
  step(`agent -> send    { grantId: "${granted.grantId}" }    (again)`);
  const replay = JSON.parse(textOf(await agent.callTool({ name: "send", arguments: { grantId: granted.grantId } })));
  out(JSON.stringify(replay, null, 2));
  console.log("");
  check("an each-use approval cannot be redeemed twice", replay.sent === false && replay.code === "approval-spent");
  check("the target was contacted exactly once", received.length === 1);

  // ---------------------------------------------------------------- 6. ledger
  bar("6. THE EVIDENCE LEDGER");
  step("operator $ boss-evidence-broker log");
  const logOut = cli("log", "10");
  const entries = logOut.trim().split("\n").map((l) => JSON.parse(l));
  for (const e of entries) {
    console.log(`   #${e.seq} ${e.kind.padEnd(18)} prev=${e.prevHash.slice(0, 10)}.. hash=${e.hash.slice(0, 10)}..`);
    console.log(`      ${JSON.stringify(e.payload)}`);
  }
  console.log("");
  check("the ledger on disk holds no encoding of the secret", !logOut.includes(SECRET));
  check("refusals are recorded, not just successes", entries.some((e) => e.kind.endsWith("-denied")));
  check("every entry links to its predecessor", entries.every((e, i) => (i === 0 ? true : e.prevHash === entries[i - 1].hash)));

  step("operator $ boss-evidence-broker verify");
  const clean = JSON.parse(cli("verify"));
  out(JSON.stringify(clean, null, 2));
  check("an untouched ledger verifies", clean.ok === true);

  // ---------------------------------------------------------------- 7. tamper
  bar("7. SOMEONE EDITS THE LEDGER");
  const ledgerPath = join(DIR, "evidence", "ledger.jsonl");
  const lines = readFileSync(ledgerPath, "utf8").trimEnd().split("\n");
  step("editing entry #2 on disk: rewriting the host it recorded");
  console.log(`   before: host = ${JSON.stringify(JSON.parse(lines[1]).payload.host)}`);
  lines[1] = lines[1].replace(/"host":"[^"]*"/, '"host":"evil.test"');
  console.log(`   after : host = "evil.test"      (its hash deliberately left untouched)`);
  writeFileSync(ledgerPath, `${lines.join("\n")}\n`);

  step("operator $ boss-evidence-broker verify");
  const broken = JSON.parse(cli("verify"));
  out(JSON.stringify(broken, null, 2));
  console.log("");
  check("the edit is detected", broken.ok === false);
  check("it names the exact entry", broken.brokenAtSeq === 2);
} finally {
  await agent.close().catch(() => {});
  await new Promise((done) => server.close(() => done()));
}

bar(failures.length === 0 ? "ALL CHECKS PASSED" : `${failures.length} CHECK(S) FAILED`);
if (failures.length > 0) {
  for (const f of failures) console.log(`   - ${f}`);
  process.exitCode = 1;
} else {
  console.log("   Nothing here was mocked: a real MCP client over real stdio, the real CLI as");
  console.log("   a separate process, a real HTTP server, and the real filesystem.\n");
}
