---
name: hackathon-demo-video
description: Provides a winning structure, split-screen recording guidelines, and judging rubric alignment for hackathon pitch videos.
triggers:
  - "video script"
  - "demo video"
  - "pitch"
  - "judging rubric"
---

# Hackathon Demo Video & Pitch Blueprint

## 1. The 2-3 Minute Winning Pitch Structure

| Time | Segment | What to Show / Say |
|---|---|---|
| **0:00 - 0:30** | **The Hook & Problem** | "Terminal AI agents (Claude Code, Gemini CLI, Aider) are blind to desktop IDE harnesses. To run an action in the app, agents either need fragile GUI automation or heavy web frameworks." |
| **0:30 - 1:00** | **The Solution & Architecture** | Introduce the Headless CLI Agent MCP Harness. Show the loopback authenticated IPC architecture reusing the 32-byte session secret without open network ports. |
| **1:00 - 2:00** | **The "Magic" Live Demo** | Split-screen: Left pane terminal running Claude Code / Gemini CLI. Right pane BossConsole running. Agent runs `boss mcp invoke mcp__boss__browser_navigate`, right pane navigates live in real-time. |
| **2:00 - 2:30** | **Technical Rigor & Metrics** | "62/62 automated tests passing, <100ms fail-fast cold start, zero-stack-trace stderr isolation, 1 MB bounded buffer protection." |
| **2:30 - 3:00** | **Impact & Call to Action** | "Bridges the gap between external autonomous terminal agents and desktop development harnesses with zero overhead." |

---

## 2. Split-Screen Video Recording Setup

1. **Resolution**: 1080p (1920x1080) or 4K.
2. **Left Half (Terminal)**: High-contrast terminal (e.g. Catppuccin, OneDark) with large font (16pt+).
3. **Right Half (Compose Desktop)**: BossConsole window with browser and terminal tabs visible.
4. **Execution Command**:
   ```bash
   # Demo Sequence
   boss status --json
   boss mcp list --filter browser
   boss mcp invoke mcp__boss__browser_navigate --args '{"url":"https://github.com/risa-labs-inc/BossConsole"}' -r
   ```

---

## 3. Unstop & Devpost Judging Rubric Alignment

- **Technical Execution (30%)**: 62 unit tests, zero-stack-trace stream discipline, Base64 framing.
- **Innovation & Novelty (25%)**: Reusing single-instance loopback socket for headless MCP tool bridge.
- **Developer Experience (25%)**: Works seamlessly with CLI one-liners, UNIX pipes (`| jq`), and `--raw` output.
- **Completeness & Security (20%)**: Session token verification, RBAC enforcement, 1 MB buffer limits.
