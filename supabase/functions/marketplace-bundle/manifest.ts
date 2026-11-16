/**
 * Marketplace v2 manifest rules (contract section 1). The database re-validates every submission with the same rules
 * (marketplace_check_manifest); this mirror exists to refuse early with a precise message.
 * Messages name the offending field, never its value, so nothing from the zip is echoed back.
 */

export class BundleError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "BundleError";
  }
}

const bad = (message: string): never => {
  throw new BundleError("invalid_manifest", message);
};

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);
const chars = (s: string) => [...s].length;
// deno-lint-ignore no-control-regex
const CNTRL = /[\u0000-\u001f\u007f-\u009f]/;

const SLUG = /^[a-z0-9][a-z0-9-]{1,46}$/;
const TOOL = /^[a-z][a-z0-9_]{0,47}$/;
const SECRET = /^[A-Z][A-Z0-9_]{0,63}$/;
const HEADER = /^[A-Za-z0-9-]{1,64}$/;
const FORBIDDEN_HEADERS = ["host", "cookie", "content-length", "transfer-encoding", "connection"];
const AGENT_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const TAG = /^[a-z0-9][a-z0-9.+#-]{0,29}$/;
const PUBLIC_HOST = /^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z][a-z0-9-]*[a-z0-9]$/;
const PRIVATE_HOST = /(^|\.)(localhost|local|internal|lan|home|test|invalid|onion)$/;
const BASE_URL = /^https:\/\/[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:443)?(\/[^?#\s]*)?$/;
const HTTP_PATH = /^\/[A-Za-z0-9._~!$&'()*+,;=:@%/{}-]*$/;
const SCRIPT_FILE = /^tools\/[A-Za-z0-9_-]+\.(py|js)$/;
const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"];

export const publicHost = (h: string): boolean =>
  h.length <= 253 && PUBLIC_HOST.test(h) && !PRIVATE_HOST.test(h);

export interface Manifest {
  schema: 1;
  kind: "skill" | "connector";
  slug: string;
  name: string;
  summary: string;
  description?: string;
  publisher_name: string;
  category?: string | null;
  tags?: string[] | null;
  visibility?: { agents?: string[] };
  skill?: { entry: string };
  connector?: Obj;
}

/** Files the zip must contain for this manifest (besides boss.json), with a size cap each. */
export interface RequiredFile {
  path: string;
  maxBytes: number;
}

function only(o: Obj, keys: string[], path: string) {
  for (const k of Object.keys(o)) if (!keys.includes(k)) bad(`${path}: unknown key`);
}

function normTags(raw: unknown[]): string[] {
  const out: string[] = [];
  for (const t of raw) {
    const n = String(t).trim().toLowerCase().replace(/\s+/g, "-");
    if (n === "" || out.includes(n)) continue;
    if (!TAG.test(n)) throw new BundleError("invalid_tag", "tags: a tag is not valid");
    out.push(n);
  }
  if (out.length > 8) throw new BundleError("too_many_tags", "tags: at most 8 tags");
  return out;
}

function checkTool(
  t: unknown,
  i: number,
  type: "http" | "scripts",
  names: Set<string>,
  slug: string,
  runtime: string,
  required: RequiredFile[],
) {
  const p = `connector.tools[${i}]`;
  if (!isObj(t)) return bad(`${p}: must be an object`);
  only(t, [
    "name",
    "description",
    "input_schema",
    "method",
    "path",
    "query",
    "body",
    "file",
    "effect",
    "timeout_s",
  ], p);
  if (typeof t.name !== "string" || !TOOL.test(t.name)) bad(`${p}.name: invalid tool name`);
  if (names.has(t.name as string)) bad(`${p}.name: duplicate tool name`);
  names.add(t.name as string);
  if (chars(slug) + 2 + chars(t.name as string) > 64) {
    bad(`${p}.name: slug and tool name together exceed 62 characters`);
  }
  if (
    typeof t.description !== "string" || t.description.trim() === "" || chars(t.description) > 1000
  ) {
    bad(`${p}.description: 1 to 1000 characters`);
  }
  if (!isObj(t.input_schema)) bad(`${p}.input_schema: must be a JSON Schema object`);
  const schema = t.input_schema as Obj;
  if (new TextEncoder().encode(JSON.stringify(schema)).length > 16384) {
    bad(`${p}.input_schema: too large`);
  }
  if ("properties" in schema && !isObj(schema.properties)) {
    bad(`${p}.input_schema.properties: must be an object`);
  }
  const props = (schema.properties ?? {}) as Obj;

  if (type === "http") {
    for (const k of ["file", "effect", "timeout_s"]) {
      if (k in t) bad(`${p}.${k}: not allowed for an http tool`);
    }
    if (typeof t.method !== "string" || !METHODS.includes(t.method)) {
      bad(`${p}.method: must be one of ${METHODS.join(", ")}`);
    }
    const path = t.path;
    if (
      typeof path !== "string" || path.length > 512 || !HTTP_PATH.test(path) ||
      /(^|\/)\.\.(\/|$)/.test(path)
    ) {
      bad(`${p}.path: must start with / and use plain path characters`);
    }
    const ps = path as string;
    if (/[{}]/.test(ps.replace(/\{[A-Za-z_][A-Za-z0-9_]*\}/g, ""))) {
      bad(`${p}.path: malformed placeholder`);
    }
    for (const m of ps.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g)) {
      if (!(m[1] in props)) bad(`${p}.path: placeholder is not an input_schema property`);
    }
    for (const k of ["query", "body"]) {
      if (!(k in t)) continue;
      const a = t[k];
      if (!Array.isArray(a) || a.length > 50) bad(`${p}.${k}: array of at most 50 keys`);
      for (const key of a as unknown[]) {
        if (typeof key !== "string" || !(key in props)) {
          bad(`${p}.${k}: key is not an input_schema property`);
        }
      }
    }
  } else {
    for (const k of ["method", "path", "query", "body"]) {
      if (k in t) bad(`${p}.${k}: not allowed for a script tool`);
    }
    if (
      typeof t.file !== "string" || !SCRIPT_FILE.test(t.file) ||
      !t.file.endsWith(runtime === "python3" ? ".py" : ".js")
    ) {
      bad(`${p}.file: tools/<name>.py for python3 or tools/<name>.js for node`);
    }
    if ("effect" in t && t.effect !== "read" && t.effect !== "write") {
      bad(`${p}.effect: read or write`);
    }
    if ("timeout_s" in t) {
      const n = t.timeout_s;
      if (typeof n !== "number" || !Number.isInteger(n) || n < 1 || n > 120) {
        bad(`${p}.timeout_s: integer 1 to 120`);
      }
    }
    required.push({ path: t.file as string, maxBytes: 2 * 1024 * 1024 });
  }
}

/** Throws BundleError on the first rule broken; returns the manifest and the files it requires. */
export function validateManifest(raw: unknown): { manifest: Manifest; required: RequiredFile[] } {
  if (!isObj(raw)) return bad("boss.json must be a JSON object");
  const m = raw;
  only(m, [
    "schema",
    "kind",
    "slug",
    "name",
    "summary",
    "description",
    "publisher_name",
    "category",
    "tags",
    "visibility",
    "skill",
    "connector",
  ], "manifest");
  if (m.schema !== 1) bad("schema: must be 1");
  if (m.kind !== "skill" && m.kind !== "connector") bad("kind: skill or connector");

  if (typeof m.slug !== "string" || !SLUG.test(m.slug)) {
    throw new BundleError(
      "invalid_slug",
      "slug: lowercase letters, digits and dashes, 2 to 47 characters",
    );
  }
  if (m.slug === "publish" || m.slug === "mine") {
    throw new BundleError("reserved_slug", "slug: reserved");
  }
  const name = m.name;
  if (
    typeof name !== "string" || name.trim() !== name || chars(name) < 2 || chars(name) > 40 ||
    CNTRL.test(name)
  ) {
    throw new BundleError("invalid_name", "name: 2 to 40 characters, trimmed");
  }
  const summary = m.summary;
  if (
    typeof summary !== "string" || summary.trim() === "" || chars(summary) > 200 ||
    CNTRL.test(summary)
  ) {
    throw new BundleError("invalid_summary", "summary: one line, 1 to 200 characters");
  }
  if ("description" in m && (typeof m.description !== "string" || chars(m.description) > 4000)) {
    throw new BundleError("invalid_description", "description: at most 4000 characters");
  }
  const pub = m.publisher_name;
  if (typeof pub !== "string" || pub.trim() === "" || chars(pub) > 60 || CNTRL.test(pub)) {
    throw new BundleError("invalid_publisher_name", "publisher_name: 1 to 60 characters");
  }
  if ("category" in m && m.category !== null && typeof m.category !== "string") {
    bad("category: must be a string");
  }
  if ("tags" in m && m.tags !== null) {
    if (!Array.isArray(m.tags) || m.tags.some((t) => typeof t !== "string")) {
      bad("tags: array of strings");
    }
    normTags(m.tags as string[]);
  }
  if ("visibility" in m) {
    const v = m.visibility;
    if (!isObj(v)) bad("visibility: must be an object");
    only(v as Obj, ["agents"], "visibility");
    if ("agents" in (v as Obj)) {
      const a = (v as Obj).agents;
      if (
        !Array.isArray(a) || a.length > 10 ||
        a.some((x) => typeof x !== "string" || !AGENT_ID.test(x))
      ) {
        bad("visibility.agents: at most 10 plugin ids");
      }
    }
  }

  const required: RequiredFile[] = [];
  if (m.kind === "skill") {
    if ("connector" in m) bad("connector: not allowed in a skill");
    const s = m.skill;
    if (!isObj(s)) return bad("skill: required for a skill bundle");
    only(s, ["entry"], "skill");
    const e = s.entry;
    if (
      typeof e !== "string" || e.length < 1 || e.length > 200 || /(^[/~])|(\.\.)|\\/.test(e) ||
      CNTRL.test(e)
    ) {
      bad("skill.entry: a relative path inside the bundle");
    }
    required.push({ path: e as string, maxBytes: 256 * 1024 });
    return { manifest: m as unknown as Manifest, required };
  }

  if ("skill" in m) bad("skill: not allowed in a connector");
  const c = m.connector;
  if (!isObj(c) || typeof c.type !== "string") return bad("connector: required with a type");
  if (c.type === "mcp") {
    only(c, ["type", "url", "auth"], "connector");
    if (typeof c.url !== "string" || (c.auth !== "none" && c.auth !== "oauth")) {
      bad("connector: mcp needs url and auth none|oauth");
    }
    const url = c.url as string;
    if (
      url.length > 2048 ||
      !/^https:\/\/[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:443)?(\/[^?#\s]*)?$/.test(url) ||
      /[\\?#@]/.test(url)
    ) {
      throw new BundleError("invalid_connector", "connector.url: must be a plain https URL");
    }
    if (!publicHost(url.slice(8).split("/")[0].split(":")[0].toLowerCase())) {
      throw new BundleError(
        "connector_requires_public_https",
        "connector.url: must be a public host",
      );
    }
    return { manifest: m as unknown as Manifest, required };
  }
  if (c.type !== "http" && c.type !== "scripts") return bad("connector.type: mcp, http or scripts");
  const type = c.type;
  if (!Array.isArray(c.tools) || c.tools.length < 1 || c.tools.length > 50) {
    bad("connector.tools: 1 to 50 tools");
  }

  if (type === "http") {
    only(c, ["type", "base_url", "auth", "tools"], "connector");
    const base = c.base_url;
    if (
      typeof base !== "string" || base.length > 2048 || !BASE_URL.test(base) ||
      /[\\?#@]/.test(base) || /(^|\/)\.\.(\/|$)/.test(base)
    ) {
      bad("connector.base_url: https URL, port 443 only");
    }
    if (!publicHost((base as string).slice(8).split("/")[0].split(":")[0].toLowerCase())) {
      throw new BundleError(
        "connector_requires_public_https",
        "connector.base_url: must be a public host",
      );
    }
    const a = c.auth;
    if (!isObj(a) || typeof a.type !== "string") return bad("connector.auth: required");
    if (a.type === "none") only(a, ["type"], "connector.auth");
    else if (a.type === "api_key") {
      only(a, ["type", "header", "secret"], "connector.auth");
      if (
        typeof a.header !== "string" || !HEADER.test(a.header) ||
        FORBIDDEN_HEADERS.includes(a.header.toLowerCase())
      ) {
        bad("connector.auth.header: not an allowed header name");
      }
      if (typeof a.secret !== "string" || !SECRET.test(a.secret)) {
        bad("connector.auth.secret: UPPER_SNAKE name");
      }
    } else if (a.type === "bearer") {
      only(a, ["type", "secret"], "connector.auth");
      if (typeof a.secret !== "string" || !SECRET.test(a.secret)) {
        bad("connector.auth.secret: UPPER_SNAKE name");
      }
    } else bad("connector.auth.type: none, api_key or bearer");
  } else {
    only(c, ["type", "runtime", "tools", "egress", "secrets"], "connector");
    if (c.runtime !== "python3" && c.runtime !== "node") bad("connector.runtime: python3 or node");
    if ("egress" in c) {
      const e = c.egress;
      if (!Array.isArray(e) || e.length > 20) bad("connector.egress: at most 20 hosts");
      for (const h of e as unknown[]) {
        if (typeof h !== "string" || h !== h.toLowerCase() || !publicHost(h)) {
          bad("connector.egress: exact public lowercase hostnames");
        }
      }
    }
    if ("secrets" in c) {
      const s = c.secrets;
      if (!Array.isArray(s) || s.length > 10) bad("connector.secrets: at most 10");
      const seen = new Set<string>();
      for (const x of s as unknown[]) {
        if (!isObj(x)) return bad("connector.secrets: entries are objects");
        only(x, ["name", "description"], "connector.secrets");
        if (typeof x.name !== "string" || !SECRET.test(x.name) || seen.has(x.name)) {
          bad("connector.secrets: UPPER_SNAKE unique names");
        }
        seen.add(x.name as string);
        if (
          "description" in x && (typeof x.description !== "string" || chars(x.description) > 500)
        ) bad("connector.secrets: description too long");
      }
    }
  }
  if ((m.slug as string).includes("--")) bad("slug: must not contain --");
  const names = new Set<string>();
  (c.tools as unknown[]).forEach((t, i) =>
    checkTool(t, i, type, names, m.slug as string, String(c.runtime), required)
  );
  return { manifest: m as unknown as Manifest, required };
}
