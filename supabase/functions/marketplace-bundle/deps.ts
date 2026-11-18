import { type Deps, type PrecheckArgs, type SubmitArgs, Unauthorized } from "./app.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function env(name: string): string {
  return (Deno.env.get(name) ?? "").trim();
}

/** The real dependencies: PostgREST, GoTrue and Storage over fetch with the project's keys. */
export function restDeps(): Deps {
  const url = env("SUPABASE_URL");
  const anon = env("SUPABASE_ANON_KEY");
  const service = env("SUPABASE_SERVICE_ROLE_KEY");
  // Signed URLs go to browsers and Max, so they must use the public origin when SUPABASE_URL is an internal one.
  const publicUrl = env("MARKETPLACE_PUBLIC_URL") || url;
  const timeout = (ms = 8000) => AbortSignal.timeout(ms);
  const svc = { apikey: service, Authorization: `Bearer ${service}` };

  async function rpc(
    name: string,
    headers: Record<string, string>,
    body: unknown,
  ): Promise<Response> {
    return await fetch(`${url}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers: { ...headers, "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: timeout(),
    });
  }

  return {
    async user(jwt) {
      const r = await fetch(`${url}/auth/v1/user`, {
        headers: { apikey: anon, Authorization: `Bearer ${jwt}` },
        signal: timeout(),
      });
      if (!r.ok) {
        await r.body?.cancel();
        return null;
      }
      const u = await r.json() as { id?: unknown };
      return typeof u.id === "string" && UUID.test(u.id) ? u.id : null;
    },
    async flags() {
      const r = await rpc(
        "marketplace_flags",
        { apikey: anon, Authorization: `Bearer ${anon}` },
        {},
      );
      if (!r.ok) {
        await r.body?.cancel();
        throw new Error(`flags ${r.status}`);
      }
      const f = await r.json() as { bundles?: unknown };
      return { bundles: f.bundles === true };
    },
    async put(path, bytes) {
      const r = await fetch(`${url}/storage/v1/object/marketplace-bundles/${path}`, {
        method: "POST",
        headers: { ...svc, "Content-Type": "application/zip", "x-upsert": "false" },
        body: bytes as unknown as BodyInit,
        signal: timeout(30000),
      });
      if (r.ok) {
        await r.body?.cancel();
        return "created";
      }
      const e = await r.json().catch(() => ({})) as { statusCode?: unknown; error?: unknown };
      if (r.status === 409 || String(e.statusCode) === "409" || e.error === "Duplicate") {
        return "exists";
      }
      throw new Error(`put ${r.status}`);
    },
    async precheck(a: PrecheckArgs) {
      const r = await rpc("marketplace_bundle_precheck", svc, {
        p_user: a.userId,
        p_manifest: a.manifest,
        p_skip_review: a.skipReview,
      });
      if (!r.ok) {
        await r.body?.cancel();
        throw new Error(`precheck ${r.status}`);
      }
      return await r.json() as Record<string, unknown>;
    },
    async remove(path) {
      const r = await fetch(`${url}/storage/v1/object/marketplace-bundles/${path}`, {
        method: "DELETE",
        headers: svc,
        signal: timeout(),
      });
      await r.body?.cancel();
      if (!r.ok && r.status !== 404) throw new Error(`remove ${r.status}`);
    },
    async submit(a: SubmitArgs) {
      const r = await rpc("marketplace_submit_bundle", svc, {
        p_user: a.userId,
        p_manifest: a.manifest,
        p_bundle_path: a.path,
        p_sha256: a.sha256,
        p_size: a.size,
        p_skip_review: a.skipReview,
      });
      if (!r.ok) {
        await r.body?.cancel();
        throw new Error(`submit ${r.status}`);
      }
      return await r.json() as Record<string, unknown>;
    },
    async install(jwt, slug, agent, autoInstall) {
      const headers = { apikey: anon, Authorization: `Bearer ${jwt}` };
      const r = autoInstall
        ? await rpc("marketplace_auto_install_fetch", headers, { p_slug: slug, p_plugin_id: agent })
        : await rpc("marketplace_install_v2", headers, { p_slug: slug, p_agent: agent });
      if (r.status === 401 || r.status === 403) {
        await r.body?.cancel();
        throw new Unauthorized();
      }
      if (!r.ok) {
        const e = await r.json().catch(() => ({})) as { code?: string };
        if (e.code === "42501") throw new Unauthorized();
        throw new Error(`install ${r.status}`);
      }
      return await r.json() as Record<string, unknown>;
    },
    async sign(path) {
      const r = await fetch(`${url}/storage/v1/object/sign/marketplace-bundles/${path}`, {
        method: "POST",
        headers: { ...svc, "Content-Type": "application/json" },
        body: JSON.stringify({ expiresIn: 120 }),
        signal: timeout(),
      });
      if (!r.ok) {
        await r.body?.cancel();
        throw new Error(`sign ${r.status}`);
      }
      const s = await r.json() as { signedURL?: unknown };
      if (typeof s.signedURL !== "string") throw new Error("sign shape");
      return `${publicUrl}/storage/v1${s.signedURL}`;
    },
    async access(userId, versionId) {
      const r = await rpc("marketplace_bundle_access", svc, {
        p_user: userId,
        p_version_id: versionId,
      });
      if (!r.ok) {
        await r.body?.cancel();
        throw new Error(`access ${r.status}`);
      }
      const p = await r.json();
      return typeof p === "string" && p !== "" ? p : null;
    },
  };
}
