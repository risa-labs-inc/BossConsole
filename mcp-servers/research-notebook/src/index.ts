#!/usr/bin/env node
/**
 * Entry point for the Research Notebook MCP server (stdio transport).
 *
 * The notebook directory is chosen, in order:
 *   1. RESEARCH_NOTEBOOK_DIR (absolute or relative to the cwd)
 *   2. <cwd>/.research-notebook
 *
 * NOTE: stdout is the MCP protocol channel, so all diagnostics go to stderr.
 */
import * as path from "node:path";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { Notebook } from "./notebook.js";
import { createServer } from "./server.js";

async function main(): Promise<void> {
  const dir = path.resolve(
    process.env.RESEARCH_NOTEBOOK_DIR ?? path.join(process.cwd(), ".research-notebook"),
  );
  const notebook = await Notebook.open({ dir });
  if (process.env.RESEARCH_NOTEBOOK_TITLE && !notebook.title) {
    await notebook.setTitle(process.env.RESEARCH_NOTEBOOK_TITLE);
  }

  const server = createServer({ notebook });
  const transport = new StdioServerTransport();
  await server.connect(transport);

  // eslint-disable-next-line no-console
  console.error(`[research-notebook] ready. Notebook: ${notebook.filePath}`);
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error("[research-notebook] fatal:", err);
  process.exit(1);
});
