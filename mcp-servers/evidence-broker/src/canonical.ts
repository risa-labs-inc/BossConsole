/**
 * Deterministic JSON, so a hash over a record is stable across processes and
 * Node versions. `JSON.stringify` preserves insertion order, which is fine
 * until two writers build the same object differently; then the chain breaks
 * for a reason that has nothing to do with tampering.
 */

/** Serialize with object keys sorted at every depth. */
export function canonicalJson(value: unknown): string {
  return JSON.stringify(normalize(value));
}

function normalize(value: unknown): unknown {
  if (value === null || typeof value !== "object") {
    if (typeof value === "number" && !Number.isFinite(value)) {
      throw new TypeError("canonicalJson: non-finite number is not serializable");
    }
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(normalize);
  }
  const source = value as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  for (const key of Object.keys(source).sort()) {
    const v = source[key];
    // Drop undefined rather than letting JSON.stringify silently omit it,
    // so the shape is explicit and identical on both sides of a verify.
    if (v !== undefined) out[key] = normalize(v);
  }
  return out;
}
