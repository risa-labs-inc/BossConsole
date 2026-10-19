/**
 * A minimal SMTP submission client: EHLO, STARTTLS (or implicit TLS on 465), AUTH PLAIN/LOGIN, one
 * message. No dependency, so it runs in the edge runtime and is testable against a local fake.
 * Credentials and message bodies are never logged or put in an error message.
 */

export interface SmtpConfig {
  host: string;
  port: number;
  user: string | null;
  pass: string | null;
  /** Envelope and From address. */
  from: string;
  fromName: string;
  /** Allow a session without TLS. Only for a local catcher (Mailpit); never with credentials off-host. */
  allowPlaintext: boolean;
  /** EHLO name. */
  helo?: string;
}

export interface MailMessage {
  to: string;
  subject: string;
  text: string;
  html: string;
}

export class SmtpError extends Error {
  constructor(readonly stage: string, readonly code: number | null) {
    super(`smtp ${stage} failed${code ? ` (${code})` : ""}`);
    this.name = "SmtpError";
  }
}

export interface Transport {
  connect(opts: { hostname: string; port: number }): Promise<Deno.Conn>;
  connectTls(opts: { hostname: string; port: number }): Promise<Deno.Conn>;
  startTls(conn: Deno.Conn, opts: { hostname: string }): Promise<Deno.Conn>;
}

export const denoTransport: Transport = {
  connect: (o) => Deno.connect({ hostname: o.hostname, port: o.port, transport: "tcp" }),
  connectTls: (o) => Deno.connectTls({ hostname: o.hostname, port: o.port }),
  startTls: (c, o) => Deno.startTls(c as Deno.TcpConn, { hostname: o.hostname }),
};

const ADDRESS = /^[^\s<>()",;:\\@]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
export const isAddress = (s: string) => s.length <= 254 && ADDRESS.test(s);

const enc = new TextEncoder();
const dec = new TextDecoder();

/** One header value, RFC 2047 encoded when it is not plain ASCII. CR/LF can never reach a header. */
export function headerText(s: string): string {
  const clean = s.replace(/[\r\n]+/g, " ").trim();
  if (/^[\x20-\x7e]*$/.test(clean)) return clean;
  return `=?UTF-8?B?${b64(enc.encode(clean))}?=`;
}

function b64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

const wrap76 = (s: string) => s.replace(/.{1,76}/g, "$&\r\n");

/** The RFC 5322 message, multipart text + HTML, both base64 (so no line is long and no line starts with a dot). */
export function buildMessage(
  cfg: SmtpConfig,
  msg: MailMessage,
  now = new Date(),
  id = crypto.randomUUID(),
): string {
  const boundary = `b-${id}`;
  const domain = cfg.from.split("@")[1] ?? "localhost";
  const from = cfg.fromName
    ? `"${headerText(cfg.fromName).replace(/["\\]/g, "")}" <${cfg.from}>`
    : `<${cfg.from}>`;
  return [
    `From: ${from}`,
    `To: <${msg.to}>`,
    `Subject: ${headerText(msg.subject)}`,
    `Date: ${now.toUTCString()}`,
    `Message-ID: <${id}@${domain}>`,
    "MIME-Version: 1.0",
    "Auto-Submitted: auto-generated",
    `Content-Type: multipart/alternative; boundary="${boundary}"`,
    "",
    `--${boundary}`,
    "Content-Type: text/plain; charset=utf-8",
    "Content-Transfer-Encoding: base64",
    "",
    wrap76(b64(enc.encode(msg.text))),
    `--${boundary}`,
    "Content-Type: text/html; charset=utf-8",
    "Content-Transfer-Encoding: base64",
    "",
    wrap76(b64(enc.encode(msg.html))),
    `--${boundary}--`,
    "",
  ].join("\r\n");
}

/** Line reader over a connection that can be swapped for its TLS upgrade. */
class Session {
  private buf = "";
  constructor(public conn: Deno.Conn, private readonly timeoutMs: number) {}

  private async readLine(): Promise<string> {
    for (;;) {
      const i = this.buf.indexOf("\r\n");
      if (i >= 0) {
        const line = this.buf.slice(0, i);
        this.buf = this.buf.slice(i + 2);
        return line;
      }
      if (this.buf.length > 64 * 1024) throw new SmtpError("reply", null);
      const chunk = new Uint8Array(4096);
      const n = await this.withTimeout(this.conn.read(chunk));
      if (n === null) throw new SmtpError("closed", null);
      this.buf += dec.decode(chunk.subarray(0, n));
    }
  }

  private withTimeout<T>(p: Promise<T>): Promise<T> {
    let t: ReturnType<typeof setTimeout> | undefined;
    const timer = new Promise<never>((_, rej) => {
      t = setTimeout(() => rej(new SmtpError("timeout", null)), this.timeoutMs);
    });
    return Promise.race([p, timer]).finally(() => clearTimeout(t));
  }

  /** A full (possibly multi-line) reply: its code and its text lines. */
  async reply(): Promise<{ code: number; lines: string[] }> {
    const lines: string[] = [];
    for (;;) {
      const line = await this.readLine();
      const m = /^(\d{3})([ -])(.*)$/.exec(line);
      if (!m) throw new SmtpError("reply", null);
      lines.push(m[3]!);
      if (m[2] === " ") return { code: Number(m[1]), lines };
    }
  }

  async write(s: string): Promise<void> {
    const bytes = enc.encode(s);
    let off = 0;
    while (off < bytes.length) off += await this.withTimeout(this.conn.write(bytes.subarray(off)));
  }

  async cmd(
    line: string,
    expect: number[],
    stage: string,
  ): Promise<{ code: number; lines: string[] }> {
    await this.write(`${line}\r\n`);
    const r = await this.reply();
    if (!expect.includes(r.code)) throw new SmtpError(stage, r.code);
    return r;
  }

  /** The bytes buffered after a reply must not survive a TLS upgrade (STARTTLS injection). */
  clear(): void {
    this.buf = "";
  }
}

export async function sendMail(
  cfg: SmtpConfig,
  msg: MailMessage,
  transport: Transport = denoTransport,
  timeoutMs = 15_000,
): Promise<void> {
  if (!isAddress(msg.to) || !isAddress(cfg.from)) throw new SmtpError("address", null);
  const implicitTls = cfg.port === 465;
  const conn = implicitTls
    ? await transport.connectTls({ hostname: cfg.host, port: cfg.port })
    : await transport.connect({ hostname: cfg.host, port: cfg.port });
  const s = new Session(conn, timeoutMs);
  try {
    const greet = await s.reply();
    if (greet.code !== 220) throw new SmtpError("greeting", greet.code);
    const helo = cfg.helo ?? "localhost";
    let caps = (await s.cmd(`EHLO ${helo}`, [250], "ehlo")).lines.map((l) => l.toUpperCase());
    let secure = implicitTls;
    if (!secure && caps.some((c) => c === "STARTTLS")) {
      await s.cmd("STARTTLS", [220], "starttls");
      s.clear();
      s.conn = await transport.startTls(s.conn, { hostname: cfg.host });
      secure = true;
      caps = (await s.cmd(`EHLO ${helo}`, [250], "ehlo")).lines.map((l) => l.toUpperCase());
    }
    if (!secure && !cfg.allowPlaintext) throw new SmtpError("tls_required", null);
    if (cfg.user && cfg.pass) {
      if (!secure && !cfg.allowPlaintext) throw new SmtpError("tls_required", null);
      const auth = caps.find((c) => c.startsWith("AUTH ") || c.startsWith("AUTH=")) ?? "";
      if (/\bPLAIN\b/.test(auth) || !/\bLOGIN\b/.test(auth)) {
        await s.cmd(`AUTH PLAIN ${b64(enc.encode(`\0${cfg.user}\0${cfg.pass}`))}`, [235], "auth");
      } else {
        await s.cmd("AUTH LOGIN", [334], "auth");
        await s.cmd(b64(enc.encode(cfg.user)), [334], "auth");
        await s.cmd(b64(enc.encode(cfg.pass)), [235], "auth");
      }
    }
    await s.cmd(`MAIL FROM:<${cfg.from}>`, [250], "mail_from");
    await s.cmd(`RCPT TO:<${msg.to}>`, [250, 251], "rcpt_to");
    await s.cmd("DATA", [354], "data");
    // Dot-stuffing: base64 bodies never start a line with ".", headers may not either; stuff anyway.
    const body = buildMessage(cfg, msg).replace(/\r\n\./g, "\r\n..");
    await s.write(`${body}\r\n.\r\n`);
    const done = await s.reply();
    if (done.code !== 250) throw new SmtpError("message", done.code);
    await s.cmd("QUIT", [221], "quit").catch(() => undefined);
  } finally {
    try {
      s.conn.close();
    } catch {
      // already closed
    }
  }
}
