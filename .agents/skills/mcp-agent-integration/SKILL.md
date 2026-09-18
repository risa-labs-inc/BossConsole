---
name: mcp-agent-integration
description: Guides progressive tool discovery, context efficiency, and external agent recipes (Claude Code, Gemini CLI, Aider, Cursor).
triggers:
  - "agent harness"
  - "claude code"
  - "gemini cli"
  - "aider"
  - "mcp client"
  - "progressive discovery"
---

# MCP CLI Agent Integration & Harness Recipes

## 1. Progressive Tool Discovery (Context Optimization)
Injecting 100+ MCP tool schemas upfront consumes 15,000+ tokens and causes context bloat and hallucination.
External agents must follow the **Progressive Discovery Pattern**:

1. **Search (Lightweight Query)**:
   ```bash
   boss mcp list --filter browser --json
   ```
   *Returns only matching tool names, descriptions, and RBAC tags.*

2. **Inspect (Just-in-Time Schema)**:
   ```bash
   boss mcp describe mcp__boss__browser_navigate --json
   ```
   *Returns the specific schema and parameter contract for the tool needed.*

3. **Execute (Unescaped Stream)**:
   ```bash
   boss mcp invoke mcp__boss__browser_navigate --args '{"url":"https://github.com"}' -r
   ```
   *Returns clean text/stdout without JSON envelope wrapping.*

---

## 2. External Agent Configuration Recipes

### Claude Code (`CLAUDE.md`)
Add to `CLAUDE.md` in repository root:
```markdown
## BossConsole Desktop Tool Harness
When controlling or querying BossConsole, use the CLI bridge:
- Check health: `boss status --json`
- Find tools: `boss mcp list --filter <keyword> --json`
- Inspect tool: `boss mcp describe <tool_name>`
- Invoke tool: `boss mcp invoke <tool_name> --args '<json>' -r`
```

### Gemini CLI / Antigravity (`GEMINI.md`)
Add to `GEMINI.md`:
```markdown
- Always query BossConsole MCP tools via `boss mcp invoke <tool> --args '<json>' -r`.
- Standard output (`stdout`) contains valid results; errors route to `stderr`.
```

### Aider (`.aider.conf.yml`)
```yaml
# Wire custom shell commands for BossConsole
command:
  boss-status: "boss status --json"
  boss-tools: "boss mcp list --json"
```

---

## 3. UNIX Piping & Automation
```bash
# Extract specific fields using jq
boss mcp list --json | jq '.[].name'

# Stream large query from file without shell argument limits
cat query.json | boss mcp invoke mcp__boss__sql_query --stdin -r
```
