/**
 * Scrubbing secret values out of anything we are about to persist or hand back
 * to the agent.
 *
 * Redacting the raw value only is not enough. By the time a secret has been
 * through a URL, a JSON body or a Basic auth header it is on the wire in an
 * encoded form, and the encoded form is what lands in an echoed response or an
 * error message. So every secret is expanded into the encodings it can
 * plausibly appear in, and all of them are replaced.
 */

/**
 * A secret shorter than this cannot be redacted without shredding unrelated
 * text -- redacting every "a" in a response is not a useful transcript. We
 * refuse to broker such a value rather than promise a redaction we cannot keep.
 */
export const MIN_REDACTABLE_LENGTH = 6;

export interface SecretForRedaction {
  name: string;
  value: string;
  /** Extra literals to scrub, e.g. the base64 of user:secret for Basic auth. */
  extraLiterals?: string[];
}

export type Redactor = (text: string) => string;

export class UnredactableSecretError extends Error {
  constructor(
    public readonly secretName: string,
    public readonly length: number,
  ) {
    super(
      `credential "${secretName}" is ${length} characters, below the ${MIN_REDACTABLE_LENGTH}-character floor ` +
        `required to redact it safely; the broker refuses to handle it`,
    );
    this.name = "UnredactableSecretError";
  }
}

/** Every encoding of a value that could realistically show up in captured text. */
export function encodingsOf(value: string): string[] {
  const out = new Set<string>([value]);

  out.add(encodeURIComponent(value));
  out.add(encodeURI(value));

  const b64 = Buffer.from(value, "utf8").toString("base64");
  out.add(b64);
  out.add(b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")); // base64url

  // How the value looks once embedded in a JSON string, which is where quotes
  // and backslashes stop matching the raw form.
  out.add(JSON.stringify(value).slice(1, -1));

  out.delete("");
  return [...out];
}

/**
 * Build a redactor over a set of secrets.
 *
 * Literals are applied longest-first. A short literal that is a prefix of a
 * longer one would otherwise partially consume it and leave the tail exposed.
 */
export function buildRedactor(secrets: readonly SecretForRedaction[]): Redactor {
  const literals: Array<{ literal: string; name: string }> = [];

  for (const secret of secrets) {
    if (secret.value.length < MIN_REDACTABLE_LENGTH) {
      throw new UnredactableSecretError(secret.name, secret.value.length);
    }
    for (const literal of encodingsOf(secret.value)) {
      literals.push({ literal, name: secret.name });
    }
    for (const extra of secret.extraLiterals ?? []) {
      if (extra.length >= MIN_REDACTABLE_LENGTH) literals.push({ literal: extra, name: secret.name });
    }
  }

  literals.sort((a, b) => b.literal.length - a.literal.length);

  return (text: string): string => {
    if (typeof text !== "string" || text === "") return text;
    let out = text;
    for (const { literal, name } of literals) {
      if (out.includes(literal)) {
        // split/join, not RegExp: the literal is attacker-influenced and
        // escaping it correctly is a bug waiting to happen.
        out = out.split(literal).join(`[redacted:${name}]`);
      }
    }
    return out;
  };
}

/** Recursively redact every string in a JSON-ish value, keys included. */
export function redactDeep(value: unknown, redact: Redactor): unknown {
  if (typeof value === "string") return redact(value);
  if (value === null || typeof value !== "object") return value;
  if (Array.isArray(value)) return value.map((v) => redactDeep(v, redact));
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
    out[redact(k)] = redactDeep(v, redact);
  }
  return out;
}

/**
 * Defence in depth for the boundary where it matters. Used on the way out so a
 * redaction gap becomes a loud failure here rather than a silent leak into the
 * ledger.
 */
export function assertRedacted(text: string, secrets: readonly SecretForRedaction[]): void {
  for (const secret of secrets) {
    for (const literal of encodingsOf(secret.value)) {
      if (literal.length >= MIN_REDACTABLE_LENGTH && text.includes(literal)) {
        throw new Error(`redaction gap: an encoding of "${secret.name}" survived into outbound text`);
      }
    }
  }
}
