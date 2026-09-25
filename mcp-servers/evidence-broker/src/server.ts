/**
 * The MCP surface the agent sees.
 *
 * Read the tool list as a security statement. There is no `approve` tool and
 * no `read_secret` tool, and that is structural rather than an oversight: the
 * agent's transport simply has no verb that grants an approval or returns a
 * credential value. Approval arrives through FileApprovalGateway, on a channel
 * this process's caller does not control.
 *
 * Adding an approve tool here would make every other guarantee in this package
 * decorative, so if you are tempted, that is the reason not to.
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { EvidenceBroker } from "./broker.js";

export const SERVER_NAME = "boss-evidence-broker";
export const SERVER_VERSION = "0.1.0";

function jsonContent(value: unknown) {
  return { content: [{ type: "text" as const, text: JSON.stringify(value, null, 2) }] };
}

export function buildServer(broker: EvidenceBroker): McpServer {
  const server = new McpServer({ name: SERVER_NAME, version: SERVER_VERSION });

  server.registerTool(
    "list_credentials",
    {
      title: "List brokered credentials",
      description:
        "Names and scopes of the credentials this broker can use on your behalf. Returns which hosts and methods " +
        "each one permits and whether it needs approval per use. Never returns a credential value.",
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    async () => jsonContent(broker.listCredentials()),
  );

  server.registerTool(
    "request_credential_use",
    {
      title: "Request use of a credential",
      description:
        "Ask to use a named credential for one specific HTTP request. The broker checks the target host and method " +
        "against the credential's allowlist, then puts the request to the operator. On approval you get a grant id, " +
        "which is redeemable by `send` and nothing else. State the purpose plainly; a human reads it.",
      inputSchema: {
        credential: z.string().min(1).describe("Credential name from list_credentials."),
        method: z.string().min(1).describe("HTTP method, e.g. GET or POST."),
        url: z.string().min(1).describe("Absolute http(s) URL. The host is parsed, not pattern-matched."),
        headers: z
          .record(z.string(), z.string())
          .optional()
          .describe("Request headers. Do not put the credential here; the broker injects it."),
        body: z.string().optional().describe("Request body, if any."),
        purpose: z.string().min(1).describe("Why this call is needed, in one sentence, for the operator."),
      },
    },
    async (args) => {
      const result = await broker.requestUse({
        credential: args.credential,
        method: args.method,
        url: args.url,
        ...(args.headers === undefined ? {} : { headers: args.headers }),
        ...(args.body === undefined ? {} : { body: args.body }),
        purpose: args.purpose,
      });
      return result.granted ? jsonContent(result) : { ...jsonContent(result), isError: true };
    },
  );

  server.registerTool(
    "send",
    {
      title: "Send the approved request",
      description:
        "Execute the exact request the operator approved. Takes a grant id and nothing else -- there is no parameter " +
        "through which a different request could be substituted after approval. The response comes back with every " +
        "encoding of the credential redacted, and redirects are not followed.",
      inputSchema: {
        grantId: z.string().min(1).describe("Grant id from request_credential_use."),
      },
    },
    async (args) => {
      const result = await broker.send(args.grantId);
      return result.sent ? jsonContent(result.response) : { ...jsonContent(result), isError: true };
    },
  );

  server.registerTool(
    "verify_evidence",
    {
      title: "Verify the evidence ledger",
      description:
        "Recompute the hash chain over the whole ledger and check it against the separately stored head. Reports the " +
        "first entry that does not verify, and detects entries removed from the end.",
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    async () => jsonContent(broker.verifyEvidence()),
  );

  server.registerTool(
    "recent_evidence",
    {
      title: "Read recent evidence",
      description:
        "The most recent sealed evidence entries, oldest first. Every allow and every denial is recorded, so this " +
        "answers what was attempted as well as what succeeded.",
      inputSchema: {
        limit: z.number().int().positive().max(500).optional().describe("How many entries to return. Default 50."),
      },
      annotations: { readOnlyHint: true },
    },
    async (args) => jsonContent(broker.recentEvidence(args.limit ?? 50)),
  );

  return server;
}

/** Tool names the agent can call. Asserted in tests so the surface cannot grow silently. */
export const AGENT_TOOL_NAMES = [
  "list_credentials",
  "request_credential_use",
  "send",
  "verify_evidence",
  "recent_evidence",
] as const;
