export type RecordType = "TXT" | "CNAME";
/** Records for name/type; [] when the name or type does not exist. Throws when DNS is unavailable. */
export type Resolver = (name: string, type: RecordType) => Promise<string[]>;

const TIMEOUT_MS = 5000;

export function systemResolver(): Resolver {
  return async (name, type) => {
    const signal = AbortSignal.timeout(TIMEOUT_MS);
    try {
      if (type === "TXT") {
        const records = await Deno.resolveDns(name, "TXT", { signal });
        return records.map((chunks) => chunks.join(""));
      }
      return await Deno.resolveDns(name, "CNAME", { signal });
    } catch (e) {
      if (e instanceof Deno.errors.NotFound) return [];
      throw e;
    }
  };
}

// A TXT answer's data is one or more quoted character-strings: "a" "b" -> ab.
function txtData(data: string): string {
  const parts = [...data.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1].replace(/\\(.)/g, "$1"));
  return parts.length ? parts.join("") : data;
}

/** DNS-over-HTTPS JSON API (application/dns-json), e.g. https://cloudflare-dns.com/dns-query. */
export function dohResolver(url: string, fetchFn: typeof fetch = fetch): Resolver {
  const codes: Record<RecordType, number> = { TXT: 16, CNAME: 5 };
  return async (name, type) => {
    const u = new URL(url);
    u.searchParams.set("name", name);
    u.searchParams.set("type", type);
    const r = await fetchFn(u, {
      headers: { Accept: "application/dns-json" },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (!r.ok) throw new Error(`doh ${r.status}`);
    const body = await r.json() as { Status?: number; Answer?: { type: number; data: string }[] };
    if (body.Status === 3) return [];
    if (body.Status !== 0) throw new Error(`doh status ${body.Status}`);
    return (body.Answer ?? []).filter((a) => a.type === codes[type])
      .map((a) => type === "TXT" ? txtData(a.data) : a.data);
  };
}

/** AGENT_DOMAINS_DOH_URL (tests, or hosts without a usable system resolver) wins over Deno.resolveDns. */
export function resolverFromEnv(): Resolver {
  const doh = Deno.env.get("AGENT_DOMAINS_DOH_URL");
  return doh ? dohResolver(doh) : systemResolver();
}
