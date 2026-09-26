import {
  cookies,
  type Session,
  signSession,
  verifySession,
} from "./session.ts";
export interface Preferences {
  unfocused_mode: "batch" | "preview";
  unfocused_fps: number;
  revision: number;
}
export interface Config {
  publicUrl: string;
  sessionSecret: string;
  previousSecret?: string;
}
export type Rpc = (
  name: string,
  args: Record<string, unknown>,
) => Promise<unknown>;
const esc = (s: unknown) =>
  String(s).replace(
    /[&<>"']/g,
    (c) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    }[c]!),
  );
function response(
  body: string,
  status = 200,
  extra: Record<string, string> = {},
) {
  return new Response(body, {
    status,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "DENY",
      "Vary": "Cookie",
      "Content-Security-Policy":
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
      ...extra,
    },
  });
}
function page(content: string) {
  return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Account settings · BOSS</title>
  <style>body{font:16px system-ui;background:#101317;color:#e8ecf2;margin:0}main{max-width:650px;margin:64px auto;padding:24px}label{display:block;margin:24px 0 8px}select,input,button{font:inherit;padding:10px;border-radius:8px;border:1px solid #526074;background:#202833;color:inherit}p{line-height:1.6;color:#bdc8d8}button{cursor:pointer;margin:24px 12px 0 0}button.primary{background:#3868d9}small{display:block;color:#bdc8d8;margin-top:8px}</style>
  <main><p>BOSS / Your account</p><h1>Account settings</h1>${content}</main></html>`;
}
function form(p: Preferences, s: Session, base: string, saved: boolean) {
  return page(
    `<h2>Remote terminals</h2>${
      saved
        ? '<p role="status">Settings saved. Your devices will pick up the change within a minute.</p>'
        : ""
    }
  <p>These preferences follow your account across BossTerm and BossConsole. Focused panes update immediately. Hidden panes pause their stream while the remote shell keeps running.</p>
  <form method="post" action="${
      esc(base)
    }"><input type="hidden" name="csrf" value="${
      esc(s.csrf)
    }"><input type="hidden" name="revision" value="${p.revision}">
  <label for="mode">Visible, unfocused panes</label><select id="mode" name="mode"><option value="batch" ${
      p.unfocused_mode === "batch" ? "selected" : ""
    }>Full output, batched</option><option value="preview" ${
      p.unfocused_mode === "preview" ? "selected" : ""
    }>Screen preview</option></select>
  <small>Full output keeps every update. Preview saves bandwidth and restores screen history when you focus the pane.</small>
  <label for="fps">Updates per second</label><input id="fps" name="fps" type="number" min="1" max="30" step="1" required value="${p.unfocused_fps}"><small>1–30 updates per second. Default: 4.</small>
  <button class="primary" name="action" value="save">Save settings</button><button name="action" value="reset">Reset to defaults</button></form>`,
  );
}
function validPreferences(v: unknown): v is Preferences {
  const p = v as Preferences;
  return !!p && ["batch", "preview"].includes(p.unfocused_mode) &&
    Number.isInteger(p.unfocused_fps) && p.unfocused_fps >= 1 &&
    p.unfocused_fps <= 30 && Number.isSafeInteger(p.revision) &&
    p.revision >= 0;
}
function defaultRpc(): Rpc {
  return async (name, args) => {
    const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const r = await fetch(
      `${Deno.env.get("SUPABASE_URL")}/rest/v1/rpc/${name}`,
      {
        method: "POST",
        headers: {
          apikey: key,
          Authorization: `Bearer ${key}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(args),
        signal: AbortSignal.timeout(10000),
      },
    );
    if (!r.ok) {
      const error = await r.json().catch(() => ({}));
      throw new Error(error?.code === "40001" ? "conflict" : "rpc");
    }
    return await r.json();
  };
}
export function createApp(config?: Config, rpc: Rpc = defaultRpc()) {
  return async (req: Request): Promise<Response> => {
    try {
      const cfg = config ?? {
        publicUrl: Deno.env.get("USER_SETTINGS_PUBLIC_URL") ??
          "https://api.risaboss.com/functions/v1/user-settings",
        sessionSecret: Deno.env.get("USER_SETTINGS_SESSION_SECRET") ?? "",
        previousSecret: Deno.env.get("USER_SETTINGS_SESSION_SECRET_PREV"),
      };
      if (cfg.sessionSecret.length < 32) {
        return response(page("<h2>Temporarily unavailable</h2>"), 503);
      }
      const pub = new URL(cfg.publicUrl);
      const url = new URL(req.url);
      const base = pub.pathname.replace(/\/$/, "");
      const secure = pub.protocol === "https:";
      const name = secure
        ? "__Secure-boss_user_settings"
        : "boss_user_settings";
      const route = url.pathname.replace(/\/$/, "");
      if (![base, "/user-settings"].includes(route)) {
        return response(page("<h2>Not found</h2>"), 404);
      }
      if (req.method === "GET" && url.searchParams.has("t")) {
        if (req.headers.get("sec-fetch-site") === "cross-site") {
          return response(page("<h2>Open this page from BOSS</h2>"), 403);
        }
        const token = url.searchParams.get("t")!;
        if (!/^[A-Za-z0-9_-]{43}$/.test(token)) {
          return response(page("<h2>This link has expired</h2>"), 400);
        }
        let identity;
        try {
          identity = await rpc("consume_user_settings_handoff", {
            p_token: token,
          }) as { user_id: string };
        } catch {
          return response(
            page(
              "<h2>This link has expired. Open account settings from your app again.</h2>",
            ),
            400,
          );
        }
        if (!identity?.user_id || !/^[0-9a-f-]{36}$/i.test(identity.user_id)) {
          throw new Error("identity");
        }
        const value = await signSession({
          sub: identity.user_id,
          csrf: crypto.randomUUID(),
          exp: Math.floor(Date.now() / 1000) + 1800,
        }, cfg.sessionSecret);
        return response("", 302, {
          "Location": base,
          "Set-Cookie":
            `${name}=${value}; Path=${base}; HttpOnly; SameSite=Lax; Max-Age=1800${
              secure ? "; Secure" : ""
            }`,
        });
      }
      let session: Session | null = null;
      for (const value of cookies(req.headers.get("cookie") ?? "", name)) {
        session = await verifySession(value, cfg.sessionSecret) ??
          (cfg.previousSecret
            ? await verifySession(value, cfg.previousSecret)
            : null);
        if (session) break;
      }
      if (!session) {
        return response(
          page(
            "<h2>Open account settings from BossTerm or BossConsole</h2><p>Your settings page session has expired.</p>",
          ),
          401,
        );
      }
      if (req.method === "GET") {
        const prefs = await rpc("get_user_terminal_preferences", {
          p_actor_id: session.sub,
        });
        if (!validPreferences(prefs)) throw new Error("preferences");
        return response(
          form(prefs, session, base, url.searchParams.get("saved") === "1"),
        );
      }
      if (req.method !== "POST") {
        return response("", 405, { "Allow": "GET, POST" });
      }
      if (
        req.headers.get("origin") !== pub.origin ||
        req.headers.get("sec-fetch-site") === "cross-site" ||
        (req.headers.has("sec-fetch-mode") &&
          req.headers.get("sec-fetch-mode") !== "navigate") ||
        (req.headers.has("sec-fetch-dest") &&
          req.headers.get("sec-fetch-dest") !== "document")
      ) return response(page("<h2>Invalid request</h2>"), 403);
      if (
        !req.headers.get("content-type")?.startsWith(
          "application/x-www-form-urlencoded",
        )
      ) return response("", 415);
      // Bound the body before parsing, including requests without Content-Length.
      const reader = req.body?.getReader();
      let body = "";
      let size = 0;
      if (reader) {
        try {
          while (true) {
            const chunk = await reader.read();
            if (chunk.done) break;
            size += chunk.value.length;
            if (size > 4096) {
              await reader.cancel();
              return response("", 413);
            }
            body += new TextDecoder().decode(chunk.value);
          }
        } finally {
          reader.releaseLock();
        }
      }
      const fields = new URLSearchParams(body);
      if (fields.get("csrf") !== session.csrf) {
        return response(page("<h2>Invalid request</h2>"), 403);
      }
      const action = fields.get("action");
      const mode = action === "reset" ? "batch" : fields.get("mode");
      const fps = action === "reset" ? 4 : Number(fields.get("fps"));
      const revisionText = fields.get("revision");
      const revision = Number(revisionText);
      if (
        !["save", "reset"].includes(action ?? "") || !revisionText ||
        !validPreferences({
          unfocused_mode: mode,
          unfocused_fps: fps,
          revision,
        })
      ) {
        return response(
          page("<h2>Choose a mode and a whole-number rate from 1 to 30.</h2>"),
          400,
        );
      }
      await rpc("set_user_terminal_preferences", {
        p_actor_id: session.sub,
        p_unfocused_mode: mode,
        p_unfocused_fps: fps,
        p_revision: revision,
      });
      return response("", 303, { "Location": base + "?saved=1" });
    } catch (e) {
      const conflict = e instanceof Error && e.message === "conflict";
      return response(
        page(
          `<h2>${
            conflict
              ? "Settings changed on another device. Reload and try again."
              : "Unable to load or save settings. Please try again."
          }</h2>`,
        ),
        conflict ? 409 : 503,
      );
    }
  };
}
