import { assertEquals } from "@std/assert";
import { BundleError, validateManifest } from "../manifest.ts";
import { connectorManifest, httpConnector, scriptsConnector, skillManifest } from "./helpers.ts";

function code(m: unknown): string {
  try {
    validateManifest(m);
  } catch (e) {
    if (e instanceof BundleError) return e.code;
    throw e;
  }
  return "ok";
}
const http = (over: Record<string, unknown> = {}, tool: Record<string, unknown> = {}) => {
  const c = httpConnector(over);
  c.tools = [{ ...c.tools[0], ...tool }];
  return connectorManifest(c);
};
const scripts = (over: Record<string, unknown> = {}, tool: Record<string, unknown> = {}) => {
  const c = scriptsConnector(over);
  c.tools = [{ ...c.tools[0], ...tool }];
  return connectorManifest(c);
};

Deno.test("metadata rules", () => {
  assertEquals(code(skillManifest()), "ok");
  assertEquals(code(skillManifest({ schema: 2 })), "invalid_manifest");
  assertEquals(code(skillManifest({ extra: 1 })), "invalid_manifest");
  assertEquals(code(skillManifest({ kind: "app" })), "invalid_manifest");
  assertEquals(code(skillManifest({ slug: "Bad_Slug" })), "invalid_slug");
  assertEquals(code(skillManifest({ slug: "a" })), "invalid_slug");
  assertEquals(code(skillManifest({ slug: "publish" })), "reserved_slug");
  assertEquals(code(skillManifest({ slug: "mine" })), "reserved_slug");
  assertEquals(code(skillManifest({ name: "x" })), "invalid_name");
  assertEquals(code(skillManifest({ name: " padded " })), "invalid_name");
  assertEquals(code(skillManifest({ name: "x".repeat(41) })), "invalid_name");
  assertEquals(code(skillManifest({ name: "tab\there" })), "invalid_name");
  assertEquals(code(skillManifest({ summary: "" })), "invalid_summary");
  assertEquals(code(skillManifest({ summary: "two\nlines" })), "invalid_summary");
  assertEquals(code(skillManifest({ summary: "x".repeat(201) })), "invalid_summary");
  assertEquals(code(skillManifest({ summary: "x".repeat(200) })), "ok");
  assertEquals(code(skillManifest({ description: "x".repeat(4001) })), "invalid_description");
  assertEquals(code(skillManifest({ description: "x".repeat(4000) })), "ok");
  assertEquals(code(skillManifest({ publisher_name: "" })), "invalid_publisher_name");
  assertEquals(code(skillManifest({ publisher_name: "x".repeat(61) })), "invalid_publisher_name");
  assertEquals(code(skillManifest({ category: 5 })), "invalid_manifest");
  assertEquals(code(skillManifest({ category: "development", tags: ["a", "b"] })), "ok");
  assertEquals(
    code(skillManifest({ tags: ["a", "b", "c", "d", "e", "f", "g", "h", "i"] })),
    "too_many_tags",
  );
  assertEquals(code(skillManifest({ tags: ["Bad Tag!"] })), "invalid_tag");
  assertEquals(code(skillManifest({ tags: ["Same", "same"] })), "ok");
});

Deno.test("visibility", () => {
  assertEquals(code(skillManifest({ visibility: { agents: [] } })), "ok");
  assertEquals(code(skillManifest({ visibility: { agents: ["com.acme.agent"] } })), "ok");
  assertEquals(code(skillManifest({ visibility: {} })), "ok");
  assertEquals(
    code(skillManifest({ visibility: { agents: Array.from({ length: 11 }, (_, i) => `a${i}`) } })),
    "invalid_manifest",
  );
  assertEquals(code(skillManifest({ visibility: { agents: [3] } })), "invalid_manifest");
  assertEquals(code(skillManifest({ visibility: { agents: ["bad id"] } })), "invalid_manifest");
  assertEquals(code(skillManifest({ visibility: { groups: [] } })), "invalid_manifest");
});

Deno.test("kind and its section", () => {
  assertEquals(code(skillManifest({ skill: undefined })), "invalid_manifest");
  assertEquals(code(skillManifest({ skill: { entry: "../x" } })), "invalid_manifest");
  assertEquals(code(skillManifest({ skill: { entry: "/x" } })), "invalid_manifest");
  assertEquals(code(skillManifest({ skill: { entry: "a\\b" } })), "invalid_manifest");
  assertEquals(code(skillManifest({ connector: httpConnector() })), "invalid_manifest");
  assertEquals(
    code(connectorManifest(httpConnector(), { skill: { entry: "SKILL.md" } })),
    "invalid_manifest",
  );
  assertEquals(code(connectorManifest(undefined)), "invalid_manifest");
  assertEquals(code(connectorManifest({ type: "grpc" })), "invalid_manifest");
});

Deno.test("mcp connectors", () => {
  const mcp = (c: Record<string, unknown>) => connectorManifest({ type: "mcp", ...c });
  assertEquals(code(mcp({ url: "https://mcp.example.com/mcp", auth: "none" })), "ok");
  assertEquals(code(mcp({ url: "https://mcp.example.com/mcp", auth: "oauth" })), "ok");
  assertEquals(
    code(mcp({ url: "https://mcp.example.com/mcp", auth: "basic" })),
    "invalid_manifest",
  );
  assertEquals(code(mcp({ url: "http://mcp.example.com/mcp", auth: "none" })), "invalid_connector");
  assertEquals(
    code(mcp({ url: "https://mcp.internal/mcp", auth: "none" })),
    "connector_requires_public_https",
  );
  assertEquals(
    code(mcp({ url: "https://mcp.example.com/mcp?x=1", auth: "none" })),
    "invalid_connector",
  );
  assertEquals(
    code(mcp({ url: "https://mcp.example.com/mcp", auth: "none", extra: 1 })),
    "invalid_manifest",
  );
});

Deno.test("http connectors", () => {
  assertEquals(code(http()), "ok");
  assertEquals(code(connectorManifest(httpConnector({ auth: { type: "none" } }))), "ok");
  assertEquals(
    code(connectorManifest(httpConnector({ auth: { type: "bearer", secret: "TOKEN" } }))),
    "ok",
  );
  assertEquals(code(http({ base_url: "http://api.example.com" })), "invalid_manifest");
  assertEquals(code(http({ base_url: "https://api.example.com:8443" })), "invalid_manifest");
  assertEquals(code(http({ base_url: "https://api.example.com:443/v1" })), "ok");
  assertEquals(code(http({ base_url: "https://10.0.0.1/v1" })), "connector_requires_public_https");
  assertEquals(code(http({ base_url: "https://localhost/v1" })), "connector_requires_public_https");
  assertEquals(code(http({ base_url: "https://svc.local" })), "connector_requires_public_https");
  assertEquals(code(http({ base_url: "https://user@api.example.com" })), "invalid_manifest");
  assertEquals(code(http({ base_url: "https://api.example.com/v1?x=1" })), "invalid_manifest");
  assertEquals(code(http({ base_url: "https://api.example.com/../x" })), "invalid_manifest");
  assertEquals(
    code(http({ auth: { type: "api_key", header: "Host", secret: "K" } })),
    "invalid_manifest",
  );
  assertEquals(
    code(http({ auth: { type: "api_key", header: "cookie", secret: "K" } })),
    "invalid_manifest",
  );
  assertEquals(
    code(http({ auth: { type: "api_key", header: "Bad Header", secret: "K" } })),
    "invalid_manifest",
  );
  assertEquals(
    code(http({ auth: { type: "api_key", header: "X-Key", secret: "lower" } })),
    "invalid_manifest",
  );
  assertEquals(code(http({ auth: { type: "oauth" } })), "invalid_manifest");
  assertEquals(code(http({ auth: { type: "none", secret: "K" } })), "invalid_manifest");
  assertEquals(code(http({}, { method: "TRACE" })), "invalid_manifest");
  assertEquals(code(http({}, { path: "items" })), "invalid_manifest");
  assertEquals(code(http({}, { path: "/a/../b" })), "invalid_manifest");
  assertEquals(code(http({}, { path: "/items/{missing}" })), "invalid_manifest");
  assertEquals(code(http({}, { path: "/items/{id" })), "invalid_manifest");
  assertEquals(code(http({}, { path: "/items?x=1" })), "invalid_manifest");
  assertEquals(code(http({}, { path: "/items/{id}/sub/{q}" })), "ok");
  assertEquals(code(http({}, { query: ["nope"] })), "invalid_manifest");
  assertEquals(code(http({}, { body: ["id"] })), "ok");
  assertEquals(code(http({}, { body: ["nope"] })), "invalid_manifest");
  assertEquals(code(http({}, { name: "Bad-Name" })), "invalid_manifest");
  assertEquals(code(http({}, { input_schema: [] })), "invalid_manifest");
  assertEquals(code(http({}, { file: "tools/a.py" })), "invalid_manifest");
  assertEquals(code(http({}, { description: "" })), "invalid_manifest");
  assertEquals(code(connectorManifest(httpConnector({ tools: [] }))), "invalid_manifest");
  const tool = httpConnector().tools[0];
  assertEquals(code(connectorManifest(httpConnector({ tools: [tool, tool] }))), "invalid_manifest");
  const fifty = Array.from({ length: 50 }, (_, i) => ({ ...tool, name: `t${i}` }));
  assertEquals(code(connectorManifest(httpConnector({ tools: fifty }))), "ok");
  assertEquals(
    code(connectorManifest(httpConnector({ tools: [...fifty, { ...tool, name: "t50" }] }))),
    "invalid_manifest",
  );
});

Deno.test("script connectors", () => {
  assertEquals(code(scripts()), "ok");
  assertEquals(code(scripts({ runtime: "node" }, { file: "tools/run.js" })), "ok");
  assertEquals(code(scripts({ runtime: "ruby" })), "invalid_manifest");
  assertEquals(code(scripts({}, { file: "run.py" })), "invalid_manifest");
  assertEquals(code(scripts({}, { file: "tools/../x.py" })), "invalid_manifest");
  assertEquals(code(scripts({}, { file: "tools/x.sh" })), "invalid_manifest");
  assertEquals(code(scripts({}, { effect: "delete" })), "invalid_manifest");
  assertEquals(code(scripts({}, { timeout_s: 0 })), "invalid_manifest");
  assertEquals(code(scripts({}, { timeout_s: 121 })), "invalid_manifest");
  assertEquals(code(scripts({}, { timeout_s: 1.5 })), "invalid_manifest");
  assertEquals(code(scripts({}, { timeout_s: 120 })), "ok");
  assertEquals(code(scripts({}, { method: "GET" })), "invalid_manifest");
  assertEquals(code(scripts({ egress: ["127.0.0.1"] })), "invalid_manifest");
  assertEquals(code(scripts({ egress: ["localhost"] })), "invalid_manifest");
  assertEquals(code(scripts({ egress: ["svc.internal"] })), "invalid_manifest");
  assertEquals(code(scripts({ egress: ["*.example.com"] })), "invalid_manifest");
  assertEquals(code(scripts({ egress: ["API.example.com"] })), "invalid_manifest");
  assertEquals(
    code(scripts({ egress: Array.from({ length: 21 }, (_, i) => `h${i}.example.com`) })),
    "invalid_manifest",
  );
  assertEquals(code(scripts({ egress: [] })), "ok");
  assertEquals(code(scripts({ secrets: [{ name: "A" }, { name: "A" }] })), "invalid_manifest");
  assertEquals(code(scripts({ secrets: [{ name: "a" }] })), "invalid_manifest");
  assertEquals(
    code(scripts({ secrets: Array.from({ length: 11 }, (_, i) => ({ name: `S${i}` })) })),
    "invalid_manifest",
  );
  assertEquals(code(scripts({ extra: 1 })), "invalid_manifest");
  assertEquals(code(scripts({}, { file: "tools/a.b.py" })), "invalid_manifest");
  assertEquals(code(scripts({}, { file: "tools/a.js" })), "invalid_manifest"); // python3 needs .py
  assertEquals(code(scripts({ runtime: "node" }, { file: "tools/a.py" })), "invalid_manifest");
  assertEquals(code(scripts({}, { file: "tools/a_b-c.py" })), "ok");
});

Deno.test("slug and tool name stay unambiguous and within 64 characters", () => {
  const slugged = (m: Record<string, unknown>, slug: string) => ({ ...m, slug });
  assertEquals(code(slugged(http(), "a--b")), "invalid_manifest");
  assertEquals(code(slugged(scripts(), "a--b")), "invalid_manifest");
  assertEquals(code(slugged(skillManifest(), "a--b")), "ok"); // skills have no tools
  assertEquals(code(slugged(http({}, { name: "x".repeat(22) }), "s".repeat(40))), "ok"); // 40+2+22 = 64
  assertEquals(
    code(slugged(http({}, { name: "x".repeat(23) }), "s".repeat(40))),
    "invalid_manifest",
  );
  assertEquals(
    code(slugged(scripts({}, { name: "x".repeat(23) }), "s".repeat(40))),
    "invalid_manifest",
  );
});
