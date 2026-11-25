import { strFromU8, unzipSync } from "fflate";

/** The platform-owned CI workflow every engine repo gets; Max's tokens cannot change it (no workflows permission). */
export const WORKFLOW_PATH = ".github/workflows/boss-engine.yml";
export const WORKFLOW_FILE = "boss-engine.yml";
export const ARTIFACT_NAME = "boss-engine";
export const ENGINE_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.max";
/** An engine jar is ~35 MB today; the artifact zip around it stays below this. */
export const MAX_ARTIFACT_BYTES = 120 * 1024 * 1024;

export const WORKFLOW_YAML = `# Written by the BOSS agents platform. It builds this agent's Max engine; the platform imports only
# successful runs of this file on pushes to main. Changes go through a reviewed pull request like any other.
name: BOSS engine
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
  workflow_dispatch:
permissions:
  contents: read
concurrency:
  group: boss-engine-\${{ github.ref }}
  cancel-in-progress: \${{ github.event_name == 'pull_request' }}
jobs:
  engine-build:
    runs-on: ubuntu-latest
    timeout-minutes: 40
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - uses: oven-sh/setup-bun@v2
        with:
          bun-version: '1.4.3'
      - name: Test and bundle web chat
        working-directory: webchat
        run: |
          bun install --frozen-lockfile
          bun run test --maxWorkers=2
          bun run build
      - name: Download boss-plugin-api
        run: |
          set -euo pipefail
          API_VERSION=$(tr -d '[:space:]' < .boss-plugin-api-version)
          mkdir -p build/downloaded-deps
          curl -fsSL --retry 3 -o build/downloaded-deps/boss-plugin-api.jar \\
            "https://github.com/risa-labs-inc/boss-plugin-api/releases/download/v\${API_VERSION}/boss-plugin-api-\${API_VERSION}.jar"
          if [ -f .boss-plugin-api-test-version ]; then
            T=$(tr -d '[:space:]' < .boss-plugin-api-test-version)
            curl -fsSL --retry 3 -o build/downloaded-deps/boss-plugin-api-test.jar \\
              "https://github.com/risa-labs-inc/boss-plugin-api/releases/download/v\${T}/boss-plugin-api-\${T}.jar"
          fi
      - name: Build the engine
        env:
          CI: 'true'
        run: ./gradlew test buildPluginJar :agent-adapter:test --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process
      - name: Check the engine
        run: |
          set -euo pipefail
          JAR=$(ls build/libs/*.jar | grep -v -- '-sources' | head -1)
          unzip -p "$JAR" META-INF/boss-plugin/plugin.json | python3 -c 'import json,sys; m=json.load(sys.stdin); assert m["pluginId"]=="${ENGINE_PLUGIN_ID}", m["pluginId"]; print(m["version"])' > version.txt
          unzip -p "$JAR" META-INF/max-source.properties | grep -qx "commit=$GITHUB_SHA"
          unzip -l "$JAR" META-INF/agent-adapter-template.jar >/dev/null
          mkdir -p out && cp "$JAR" out/engine.jar
          python3 - "$GITHUB_SHA" "$GITHUB_RUN_ID" > out/engine.json <<'PY'
          import hashlib, json, os, sys
          data = open("out/engine.jar", "rb").read()
          print(json.dumps({"commit": sys.argv[1], "run_id": int(sys.argv[2]), "version": open("version.txt").read().strip(),
                            "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}))
          PY
          cat out/engine.json
      - uses: actions/upload-artifact@v4
        with:
          name: ${ARTIFACT_NAME}
          path: out/
          retention-days: 90
          if-no-files-found: error
`;

export interface EngineFacts {
  version: string;
  commit: string;
  jar: Uint8Array;
}

export class EngineCheckError extends Error {}

const SEMVER = /^\d+\.\d+\.\d+$/;

/** The engine jar out of a CI artifact zip, checked: Max's plugin id, a semver, its packed source at `expectCommit`, the adapter template. */
export function engineFromArtifact(zip: Uint8Array, expectCommit: string): EngineFacts {
  let files: Record<string, Uint8Array>;
  try {
    files = unzipSync(zip, { filter: (f) => (f.name === "engine.jar" || f.name === "out/engine.jar") && f.originalSize <= MAX_ARTIFACT_BYTES });
  } catch {
    throw new EngineCheckError("the artifact is not a zip");
  }
  const jar = files["engine.jar"] ?? files["out/engine.jar"];
  if (!jar) throw new EngineCheckError("the artifact has no engine.jar");
  return { ...checkEngineJar(jar, expectCommit), jar };
}

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
  if (!commit || commit !== expectCommit) throw new EngineCheckError("the engine was not built from the run's commit");
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

export function engineJson(agentId: string, baseVersion: string): string {
  return JSON.stringify({ agentId, upstream: "risa-labs-inc/boss-plugin-max", baseVersion, workflow: WORKFLOW_PATH }, null, 2) + "\n";
}
