# Research Notebook MCP

An MCP server that turns any BOSS agent into a research assistant. It gives your
agent (Claude Code, Codex, Gemini, OpenCode - anything BOSS drives) a set of
tools to **capture cited sources, keep linked notes, and export a bibliography
or a literature-review outline** - all stored as plain files inside the project
you are working in.

Point it at a project, browse and read as usual, and ask your agent to "cite
this page", "note down why this matters", or "draft an outline of what I have so
far". The notebook is a single human-readable `notebook.json` plus the Markdown
and BibTeX files it generates, so nothing is locked away.

## Why this helps researchers

The expensive part of a literature review is not reading, it is **keeping track**:
where a fact came from, which paper made which claim, and how a pile of notes
turns into a structured draft. A general chat agent forgets all of this between
turns. This server gives the agent a durable, structured memory built around the
two things a researcher actually accumulates:

- **Sources** you cite, with real bibliographic metadata (title, authors, date,
  journal or site, DOI) extracted automatically from the page.
- **Notes** you write, each linked to the sources behind it.

From those it can produce a BibTeX file for LaTeX/Overleaf, an annotated
bibliography, or a literature-review outline that groups your notes by theme and
threads in the right citations.

## The tools

| Tool | What it does |
|---|---|
| `cite_url` | Fetch a URL, extract its metadata, and save it as a source (de-duplicates by URL; adds your quote/tags to an existing source). |
| `add_source` | Record a source by hand (a book, or a page you cannot fetch). |
| `update_source` | Correct or enrich a source's fields. |
| `add_quote` | Attach an excerpt (with page/location and your comment) to a source. |
| `add_note` | Write a Markdown research note linked to the sources it draws on. |
| `update_note` | Edit a note's title, content, tags, or links. |
| `list_sources` / `list_notes` | List sources or notes, optionally filtered by tag (or by linked source). |
| `get_source` / `get_note` | Show full detail of one item, including quotes. |
| `search` | Full-text search across sources and notes. |
| `remove_source` / `remove_note` | Delete an item (removing a source unlinks it from notes and reports which). |
| `export_bibtex` | Render sources as BibTeX, optionally writing `references.bib`. |
| `export_markdown` | Render a Markdown bibliography (plain or annotated), optionally writing `references.md`. |
| `generate_outline` | Assemble notes into a literature-review scaffold, optionally writing `outline.md`. |
| `notebook_stats` | Counts of sources, notes, quotes, tags, and types. |

## Quick start

```bash
cd mcp-servers/research-notebook
npm install
npm run build
```

Run it directly (it speaks MCP over stdio):

```bash
RESEARCH_NOTEBOOK_DIR="$PWD/.research-notebook" node dist/index.js
```

### Cite keys

Sources get a human-friendly citation key in the usual author-year-word style,
for example `vaswani2017attention`. Collisions are disambiguated with a trailing
letter (`smith2020a`, `smith2020b`). You can refer to any source by either its
cite key or its internal id in every tool that takes a source.

## Connecting it to BOSS

BOSS connects to MCP servers the same way other agent hosts do. Add an entry
that launches this server and point it at the project folder you want the
notebook to live in. A typical MCP client configuration looks like this:

```json
{
  "mcpServers": {
    "research-notebook": {
      "command": "node",
      "args": ["/absolute/path/to/mcp-servers/research-notebook/dist/index.js"],
      "env": {
        "RESEARCH_NOTEBOOK_DIR": "/absolute/path/to/your/project/.research-notebook",
        "RESEARCH_NOTEBOOK_TITLE": "My Literature Review"
      }
    }
  }
}
```

The same block works for any MCP-capable agent BOSS runs (Claude Code, Codex,
and others). Once connected, the 17 tools above appear alongside the rest of
BOSS's tools and your agent can call them.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `RESEARCH_NOTEBOOK_DIR` | `<cwd>/.research-notebook` | Folder holding `notebook.json` and the generated exports. |
| `RESEARCH_NOTEBOOK_TITLE` | (unset) | Sets the notebook title on first run, used in export headings. |

## What gets written

Everything lives in the notebook directory:

- `notebook.json` - the canonical store (sources and notes). Written atomically.
- `references.bib` - BibTeX, when you ask `export_bibtex` to write.
- `references.md` - Markdown bibliography, when you ask `export_markdown` to write.
- `outline.md` - the literature-review scaffold, when you ask `generate_outline` to write.

Because it is all plain text in your project, it version-controls cleanly and you
can read or edit it without the server. Every source and note needs a non-empty
`id`; the server normalises array fields on load (missing ones become empty,
tags are lowercased and de-duplicated, unknown keys are kept) and refuses to
start if an entry cannot be read at all.

## Example workflow

1. `cite_url` on a paper you are reading, with a `quote` and `tags: ["method"]`.
2. `add_note` capturing your take, linked to that source.
3. Repeat while you read.
4. `generate_outline` to get a themed draft with citations threaded in.
5. `export_bibtex` to drop `references.bib` into your LaTeX project.

## Privacy and safety

- The only network request the server makes is fetching a URL you explicitly
  pass to `cite_url`, and only http/https addresses outside loopback, private
  and link-local ranges (no cloud metadata, no local admin ports). Nothing
  else leaves your machine.
  Fetches give up after 30 seconds.
- All data is stored locally in the notebook directory. There is no external
  service and no telemetry.
- A failed or blocked fetch is reported cleanly; you can always fall back to
  `add_source` to record a citation by hand.

## Development

```bash
npm run typecheck   # tsc --noEmit
npm test            # vitest: unit + in-memory MCP integration tests
npm run build       # emit dist/
```

The test suite covers metadata extraction (OpenGraph, Google Scholar / highwire
`citation_*` tags, JSON-LD, and degenerate pages), cite-key generation and
collisions, the notebook store and its persistence, BibTeX and Markdown
rendering, and a full end-to-end pass driving the real MCP server over an
in-memory transport with a stubbed fetch.

### Layout

```
src/
  types.ts      data model (sources, notes, notebook)
  metadata.ts   pure HTML -> bibliographic metadata extraction
  citekey.ts    author-year-word cite keys, with de-duplication
  notebook.ts   the store: CRUD, search, atomic persistence
  bibtex.ts     BibTeX export
  markdown.ts   bibliography + literature-review outline
  server.ts     MCP tool registration (fetch and store injected)
  index.ts      stdio entry point
```

## License

Apache-2.0, matching the BOSS Console core.
