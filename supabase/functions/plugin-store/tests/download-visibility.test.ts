/**
 * Pins who can download an organisation's plugin.
 *
 * The download routes looked the plugin up with get_plugin_with_stats, which resolves visibility
 * for auth.uid(). The function holds a service-role client, so auth.uid() is NULL and every `org`
 * or `unlisted` plugin was "Plugin not found" before canInstall saw the caller: members got 404 for
 * their own organisation's plugins. The lookup now applies no visibility and user_can_install_plugin
 * is the only gate, so these tests stub that predicate and check the route defers to it.
 *
 * Run: deno test --allow-all tests/download-visibility.test.ts
 */
import { assertEquals } from "jsr:@std/assert";
import { OpenAPIHono } from "@hono/zod-openapi";
import type { SupabaseClient } from "@supabase/supabase-js";
import type { PluginStoreContext } from "../types/context.ts";
import download from "../routes/download.ts";
import { resetRateLimits } from "../utils/rate-limit.ts";

const MEMBER_ID = "11111111-1111-1111-1111-111111111111";
const OUTSIDER_ID = "44444444-4444-4444-4444-444444444444";
const PLUGIN_UUID = "22222222-2222-2222-2222-222222222222";
const PLUGIN_ID = "com.example.orgonly";
const MEMBER_API_KEY = `boss_pk_${"M".repeat(32)}`;

/** Structurally valid JWT naming `sub`; the stub's getUser maps it back. */
function jwt(sub: string): string {
  const b64 = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(
      /=+$/,
      "",
    );
  return `${b64({ alg: "HS256", typ: "JWT" })}.${b64({ sub })}.sig`;
}

function subOf(token: string): string | null {
  try {
    return JSON.parse(
      atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")),
    ).sub ?? null;
  } catch {
    return null;
  }
}

interface Recorded {
  rpc: { fn: string; args: Record<string, unknown> }[];
  pluginFilters: [string, unknown][];
}

/**
 * An organisation plugin owned by MEMBER_ID's organisation. user_can_install_plugin answers as
 * the database would: true for the member, false for anyone else or nobody. `published` controls
 * whether the plugins-table read finds the row, since the lookup filters on it.
 */
function stubClient(
  opts: { published?: boolean } = {},
): { client: SupabaseClient; recorded: Recorded } {
  const published = opts.published ?? true;
  const recorded: Recorded = { rpc: [], pluginFilters: [] };

  const versionRow = {
    id: "33333333-3333-3333-3333-333333333333",
    plugin_id: PLUGIN_UUID,
    version: "1.2.1",
    changelog: "",
    min_boss_version: "1.0.0",
    min_ipc_version: "1.0.0",
    min_api_version: "",
    jar_path: `plugins/${PLUGIN_ID}/1.2.1/${PLUGIN_ID}-1.2.1.jar`,
    jar_size: 4096,
    sha256: "a".repeat(64),
    signature: null,
    dependencies: [],
    published_at: "2026-08-15T00:00:00Z",
  };

  interface VersionChain {
    eq: () => VersionChain;
    neq: () => VersionChain;
    gt: () => VersionChain;
    order: () => VersionChain;
    limit: () => VersionChain;
    single: () => Promise<{ data: typeof versionRow; error: null }>;
  }
  const versionChain: VersionChain = {
    eq: () => versionChain,
    neq: () => versionChain,
    gt: () => versionChain,
    order: () => versionChain,
    limit: () => versionChain,
    single: () => Promise.resolve({ data: versionRow, error: null }),
  };

  interface PluginChain {
    eq: (column: string, value: unknown) => PluginChain;
    maybeSingle: () => Promise<
      {
        data: { id: string; required_permissions: string[] } | null;
        error: null;
      }
    >;
  }
  const pluginChain: PluginChain = {
    eq: (column, value) => {
      recorded.pluginFilters.push([column, value]);
      return pluginChain;
    },
    maybeSingle: () => {
      const wantsPublished = recorded.pluginFilters.some(([c, v]) =>
        c === "published" && v === true
      );
      const found = published || !wantsPublished;
      return Promise.resolve({
        data: found ? { id: PLUGIN_UUID, required_permissions: [] } : null,
        error: null,
      });
    },
  };

  const client = {
    auth: {
      getUser: (token: string) => {
        const sub = subOf(token);
        return Promise.resolve(
          sub
            ? { data: { user: { id: sub, email: `${sub}@test` } }, error: null }
            : { data: { user: null }, error: new Error("invalid token") },
        );
      },
    },
    rpc: (fn: string, args: Record<string, unknown> = {}) => {
      recorded.rpc.push({ fn, args });
      if (fn === "user_can_install_plugin") {
        return Promise.resolve({
          data: args.p_user_id === MEMBER_ID,
          error: null,
        });
      }
      if (fn === "validate_plugin_api_key") {
        return Promise.resolve({
          data: [{
            key_id: "key-1",
            user_id: MEMBER_ID,
            scopes: ["publish"],
            org_id: "org-1",
          }],
          error: null,
        });
      }
      // get_plugin_with_stats would resolve for auth.uid() = NULL and hide the plugin.
      if (fn === "get_plugin_with_stats") {
        return Promise.resolve({ data: [], error: null });
      }
      // validate_plugin_api_key and record_plugin_download: nothing to report.
      return Promise.resolve({ data: null, error: null });
    },
    from: (table: string) => ({
      select: () => {
        if (table === "plugins") return pluginChain;
        if (table === "users") {
          const userChain = {
            eq: () => userChain,
            single: () =>
              Promise.resolve({ data: { email: "member@test" }, error: null }),
          };
          return userChain;
        }
        return versionChain;
      },
    }),
    storage: {
      from: (_bucket: string) => ({
        createSignedUrl: () =>
          Promise.resolve({
            data: { signedUrl: "https://storage.example.test/signed-jar" },
            error: null,
          }),
      }),
    },
  } as unknown as SupabaseClient;

  return { client, recorded };
}

function mountApp(
  client: SupabaseClient,
): OpenAPIHono<{ Variables: PluginStoreContext }> {
  const app = new OpenAPIHono<{ Variables: PluginStoreContext }>();
  app.use("*", async (ctx, next) => {
    ctx.set("supabase", client);
    await next();
  });
  app.route("/", download);
  return app;
}

const ROUTES = [`/${PLUGIN_ID}/download`, `/${PLUGIN_ID}/download/1.2.1`];
const PRIVATE_VISIBILITIES = ["org", "unlisted"] as const;

for (const path of ROUTES) {
  Deno.test(`${path}: an organisation member can download their organisation's plugin`, async () => {
    resetRateLimits();
    const { client, recorded } = stubClient();
    const res = await mountApp(client).request(path, {
      headers: { Authorization: `Bearer ${jwt(MEMBER_ID)}` },
    });

    assertEquals(res.status, 200);
    assertEquals((await res.json()).version, "1.2.1");
    // The plugin lookup did not decide visibility...
    assertEquals(
      recorded.rpc.some((c) => c.fn === "get_plugin_with_stats"),
      false,
    );
    // ...the install predicate did, for the caller named by the token.
    const gate = recorded.rpc.find((c) => c.fn === "user_can_install_plugin");
    assertEquals(gate?.args, {
      p_user_id: MEMBER_ID,
      p_plugin_id: PLUGIN_UUID,
    });
  });

  Deno.test(`${path}: a non-member and an anonymous caller get the same 404 as a missing plugin`, async () => {
    resetRateLimits();
    const app = mountApp(stubClient().client);
    const outsider = await app.request(path, {
      headers: { Authorization: `Bearer ${jwt(OUTSIDER_ID)}` },
    });
    const anonymous = await app.request(path);

    // Indistinguishable from "no such plugin", so the route still cannot enumerate private ids.
    for (const res of [outsider, anonymous]) {
      assertEquals(res.status, 404);
      assertEquals(await res.json(), { error: "Plugin not found" });
    }
  });

  Deno.test(`${path}: an unpublished plugin stays undownloadable`, async () => {
    resetRateLimits();
    const { client, recorded } = stubClient({ published: false });
    const res = await mountApp(client).request(path, {
      headers: { Authorization: `Bearer ${jwt(MEMBER_ID)}` },
    });

    assertEquals(res.status, 404);
    assertEquals(await res.json(), { error: "Plugin not found" });
    assertEquals(recorded.pluginFilters, [["plugin_id", PLUGIN_ID], [
      "published",
      true,
    ]]);
  });

  for (const visibility of PRIVATE_VISIBILITIES) {
    Deno.test(`${path}: an API key for an organisation member can download an ${visibility} plugin`, async () => {
      resetRateLimits();
      const { client, recorded } = stubClient();
      const res = await mountApp(client).request(path, {
        headers: { "x-api-key": MEMBER_API_KEY },
      });

      assertEquals(res.status, 200);
      const gate = recorded.rpc.find((c) => c.fn === "user_can_install_plugin");
      assertEquals(gate?.args, {
        p_user_id: MEMBER_ID,
        p_plugin_id: PLUGIN_UUID,
      });
    });
  }
}
