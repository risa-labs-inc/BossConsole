import { type Zippable, zipSync } from "fflate";

const enc = new TextEncoder();
export const text = (s: string) => enc.encode(s);

export const skillManifest = (over: Record<string, unknown> = {}) => ({
  schema: 1,
  kind: "skill",
  slug: "my-skill",
  name: "My skill",
  summary: "Does one thing.",
  publisher_name: "Acme",
  skill: { entry: "SKILL.md" },
  ...over,
});

export const httpConnector = (over: Record<string, unknown> = {}) => ({
  type: "http",
  base_url: "https://api.example.com/v1",
  auth: { type: "api_key", header: "X-Api-Key", secret: "API_KEY" },
  tools: [{
    name: "get_item",
    description: "Get one item",
    method: "GET",
    path: "/items/{id}",
    input_schema: { type: "object", properties: { id: { type: "string" }, q: { type: "string" } } },
    query: ["q"],
  }],
  ...over,
});

export const scriptsConnector = (over: Record<string, unknown> = {}) => ({
  type: "scripts",
  runtime: "python3",
  tools: [{
    name: "run_it",
    description: "Run it",
    file: "tools/run.py",
    input_schema: { type: "object" },
    effect: "read",
    timeout_s: 30,
  }],
  egress: ["api.example.com"],
  secrets: [{ name: "TOKEN", description: "API token" }],
  ...over,
});

export const connectorManifest = (connector: unknown, over: Record<string, unknown> = {}) => ({
  schema: 1,
  kind: "connector",
  slug: "my-connector",
  name: "My connector",
  summary: "Talks to an API.",
  publisher_name: "Acme",
  connector,
  ...over,
});

/** A zip with boss.json and the given files. */
export function bundle(manifest: unknown, files: Record<string, Uint8Array> = {}): Uint8Array {
  const z: Zippable = { "boss.json": text(JSON.stringify(manifest)) };
  for (const [k, v] of Object.entries(files)) z[k] = v;
  return zipSync(z);
}

export const skillBundle = (over: Record<string, unknown> = {}) =>
  bundle(skillManifest(over), { "SKILL.md": text("# Skill\nDo the thing.\n") });

/** Replace the first occurrence of `from` with `to` (same length) in every place it appears. */
export function patchAll(zip: Uint8Array, from: string, to: string): Uint8Array {
  if (from.length !== to.length) throw new Error("same length only");
  const out = zip.slice();
  const f = text(from);
  const t = text(to);
  outer: for (let i = 0; i <= out.length - f.length; i++) {
    for (let j = 0; j < f.length; j++) if (out[i + j] !== f[j]) continue outer;
    out.set(t, i);
  }
  return out;
}

export function findSig(zip: Uint8Array, sig: number, from = 0): number {
  const dv = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  for (let i = from; i <= zip.length - 4; i++) if (dv.getUint32(i, true) === sig) return i;
  return -1;
}
