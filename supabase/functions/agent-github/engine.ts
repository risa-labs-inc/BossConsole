import { strFromU8, unzipSync } from "fflate";

export const ENGINE_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.max";
/** An engine jar is ~35 MB today; uploads stay below the private bucket's limit. */
export const MAX_ENGINE_BYTES = 100 * 1024 * 1024;

export class EngineCheckError extends Error {}

const SEMVER = /^\d+\.\d+\.\d+$/;

/** A Max-built engine jar, checked: Max's plugin id, a semver, its packed source at `expectCommit`, a pin-enforcing adapter template. */
export function checkEngineJar(jar: Uint8Array, expectCommit: string): { version: string; commit: string } {
  let meta: Record<string, Uint8Array>;
  try {
    meta = unzipSync(jar, {
      filter: (f) => f.name === "META-INF/boss-plugin/plugin.json" || f.name === "META-INF/max-source.properties"
        || f.name === "META-INF/agent-adapter-template.jar" ? f.originalSize <= (f.name.endsWith(".jar") ? 20 * 1024 * 1024 : 64 * 1024) : false,
    });
  } catch {
    throw new EngineCheckError("the engine is not a jar");
  }
  const pj = meta["META-INF/boss-plugin/plugin.json"];
  if (!pj) throw new EngineCheckError("the engine has no plugin.json");
  let manifest: { pluginId?: unknown; version?: unknown };
  try { manifest = JSON.parse(strFromU8(pj)); } catch { throw new EngineCheckError("the engine's plugin.json is not JSON"); }
  if (manifest.pluginId !== ENGINE_PLUGIN_ID) throw new EngineCheckError("the jar is not a Max engine");
  if (typeof manifest.version !== "string" || !SEMVER.test(manifest.version)) throw new EngineCheckError("the engine has no plain version");
  const props = meta["META-INF/max-source.properties"];
  if (!props) throw new EngineCheckError("the engine does not carry its source");
  const commit = /^commit=([0-9a-f]{40})$/m.exec(strFromU8(props))?.[1];
  if (!commit || commit !== expectCommit) throw new EngineCheckError("the engine was not built from the claimed commit");
  const template = meta["META-INF/agent-adapter-template.jar"];
  if (!template) throw new EngineCheckError("the engine carries no adapter template");
  let capabilities: unknown;
  try {
    const files = unzipSync(template, {filter: f => f.name === "META-INF/max-agent-capabilities.json" && f.originalSize < 8192});
    capabilities = JSON.parse(strFromU8(files["META-INF/max-agent-capabilities.json"])).capabilities;
  } catch { capabilities = null; }
  if (!Array.isArray(capabilities) || !capabilities.includes("repo-engine-pin-v1")) {
    throw new EngineCheckError("rebase onto a newer Max: this adapter does not enforce repository engine pins");
  }
  return { version: manifest.version, commit };
}

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", bytes as BufferSource);
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
