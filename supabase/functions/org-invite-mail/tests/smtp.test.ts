import { assert, assertEquals, assertRejects, assertStringIncludes } from "@std/assert";
import {
  buildMessage,
  headerText,
  sendMail,
  type SmtpConfig,
  SmtpError,
  type Transport,
} from "../smtp.ts";

interface FakeOptions {
  starttls?: boolean;
  auth?: string;
  rejectRcpt?: boolean;
}

/** A scripted SMTP server on 127.0.0.1. Records every command line and the DATA payload. */
function fakeServer(o: FakeOptions = {}) {
  const listener = Deno.listen({ hostname: "127.0.0.1", port: 0 });
  const lines: string[] = [];
  let data = "";
  const done = (async () => {
    const conn = await listener.accept();
    const enc = new TextEncoder();
    const dec = new TextDecoder();
    const send = (s: string) => conn.write(enc.encode(s + "\r\n"));
    let buf = "";
    let inData = false;
    let tls = false;
    await send("220 fake ESMTP");
    const chunk = new Uint8Array(4096);
    for (;;) {
      const n = await conn.read(chunk);
      if (n === null) break;
      buf += dec.decode(chunk.subarray(0, n));
      let i: number;
      while ((i = buf.indexOf("\r\n")) >= 0) {
        const line = buf.slice(0, i);
        buf = buf.slice(i + 2);
        if (inData) {
          if (line === ".") {
            inData = false;
            await send("250 queued");
          } else data += line + "\r\n";
          continue;
        }
        lines.push(line);
        const cmd = line.toUpperCase();
        if (cmd.startsWith("EHLO")) {
          const caps = ["250-fake"];
          if (o.starttls && !tls) caps.push("250-STARTTLS");
          if (o.auth) caps.push(`250-AUTH ${o.auth}`);
          caps.push("250 8BITMIME");
          await send(caps.join("\r\n"));
        } else if (cmd === "STARTTLS") {
          tls = true;
          await send("220 go ahead");
        } else if (cmd.startsWith("AUTH PLAIN")) await send("235 ok");
        else if (cmd === "AUTH LOGIN") await send("334 VXNlcm5hbWU6");
        else if (lines.at(-2) === "AUTH LOGIN") await send("334 UGFzc3dvcmQ6");
        else if (lines.at(-3) === "AUTH LOGIN") await send("235 ok");
        else if (cmd.startsWith("MAIL FROM")) await send("250 ok");
        else if (cmd.startsWith("RCPT TO")) {
          await send(o.rejectRcpt ? "550 no such user" : "250 ok");
        } else if (cmd === "DATA") {
          inData = true;
          await send("354 go");
        } else if (cmd === "QUIT") {
          await send("221 bye");
          break;
        } else await send("500 what");
      }
    }
    conn.close();
    listener.close();
  })();
  const port = (listener.addr as Deno.NetAddr).port;
  return { port, lines, data: () => data, done };
}

/** A transport whose "TLS" is the same socket, so the protocol around STARTTLS can be checked. */
function plainTransport(seen: string[] = []): Transport {
  return {
    connect: (o) => Deno.connect({ hostname: o.hostname, port: o.port }),
    connectTls: (o) => {
      seen.push("connectTls");
      return Deno.connect({ hostname: o.hostname, port: o.port });
    },
    startTls: (c) => {
      seen.push("startTls");
      return Promise.resolve(c);
    },
  };
}

const cfg = (port: number, over: Partial<SmtpConfig> = {}): SmtpConfig => ({
  host: "127.0.0.1",
  port,
  user: null,
  pass: null,
  from: "noreply@boss.test",
  fromName: "BOSS",
  allowPlaintext: false,
  ...over,
});
const msg = {
  to: "invitee@acme.test",
  subject: "Join Acme",
  text: "hello\n.dot line",
  html: "<p>hi</p>",
};

Deno.test("plaintext session to a local catcher when allowed: envelope and message", async () => {
  const s = fakeServer();
  await sendMail(cfg(s.port, { allowPlaintext: true }), msg, plainTransport());
  await s.done;
  assertEquals(s.lines.filter((l) => /^(MAIL|RCPT)/.test(l)), [
    "MAIL FROM:<noreply@boss.test>",
    "RCPT TO:<invitee@acme.test>",
  ]);
  assertStringIncludes(s.data(), "To: <invitee@acme.test>");
  assertStringIncludes(s.data(), "Subject: Join Acme");
  assertStringIncludes(s.data(), 'From: "BOSS" <noreply@boss.test>');
  assertEquals(s.lines.at(-1), "QUIT");
});

Deno.test("no TLS on offer and plaintext not allowed: nothing is sent", async () => {
  const s = fakeServer({ auth: "PLAIN" });
  await assertRejects(
    () => sendMail(cfg(s.port, { user: "u", pass: "secret-pass" }), msg, plainTransport()),
    SmtpError,
    "tls_required",
  );
  await s.done.catch(() => undefined);
  assert(
    !s.lines.some((l) => l.startsWith("AUTH") || l.startsWith("MAIL")),
    "no auth or mail without TLS",
  );
});

Deno.test("STARTTLS is used when offered, then AUTH PLAIN after the second EHLO", async () => {
  const s = fakeServer({ starttls: true, auth: "PLAIN LOGIN" });
  const seen: string[] = [];
  await sendMail(cfg(s.port, { user: "user@boss.test", pass: "pw" }), msg, plainTransport(seen));
  await s.done;
  assertEquals(seen, ["startTls"]);
  const order = s.lines.map((l) => l.split(" ")[0]);
  assertEquals(order.slice(0, 5), ["EHLO", "STARTTLS", "EHLO", "AUTH", "MAIL"]);
  const plain = atob(s.lines.find((l) => l.startsWith("AUTH PLAIN"))!.slice("AUTH PLAIN ".length));
  assertEquals(plain, "\0user@boss.test\0pw");
});

Deno.test("port 465 is implicit TLS; AUTH LOGIN when PLAIN is not offered", async () => {
  const s = fakeServer({ auth: "LOGIN" });
  const seen: string[] = [];
  // The fake listens elsewhere: route 465 to it through the transport.
  const t = plainTransport(seen);
  const transport: Transport = { ...t, connectTls: (o) => t.connectTls({ ...o, port: s.port }) };
  await sendMail(cfg(465, { user: "u", pass: "p" }), msg, transport);
  await s.done;
  assertEquals(seen, ["connectTls"]);
  assert(s.lines.includes("AUTH LOGIN"));
  assert(s.lines.includes(btoa("u")) && s.lines.includes(btoa("p")));
});

Deno.test("a refused recipient is an SmtpError with the code, not the server text", async () => {
  const s = fakeServer({ rejectRcpt: true });
  const e = await assertRejects(
    () => sendMail(cfg(s.port, { allowPlaintext: true }), msg, plainTransport()),
    SmtpError,
  );
  assertEquals((e as SmtpError).code, 550);
  assert(!(e as Error).message.includes("no such user"));
  await s.done.catch(() => undefined);
});

Deno.test("addresses with header or SMTP metacharacters are refused before connecting", async () => {
  let connected = false;
  const t: Transport = {
    connect: () => {
      connected = true;
      throw new Error("no");
    },
    connectTls: () => {
      throw new Error("no");
    },
    startTls: (c) => Promise.resolve(c),
  };
  for (const to of ["a@b.test>\r\nRCPT TO:<x@y.test", "a b@c.test", "nobody", "a@b"]) {
    await assertRejects(
      () => sendMail(cfg(25, { allowPlaintext: true }), { ...msg, to }, t),
      SmtpError,
      "address",
    );
  }
  assert(!connected);
});

Deno.test("headers cannot be injected through the subject; non-ASCII is encoded", () => {
  assertEquals(headerText("Join\r\nBcc: evil@x.test"), "Join Bcc: evil@x.test");
  assertEquals(
    headerText("Café"),
    `=?UTF-8?B?${btoa(String.fromCharCode(...new TextEncoder().encode("Café")))}?=`,
  );
  const m = buildMessage(cfg(25), { ...msg, subject: "x\r\nBcc: evil@x.test" });
  assert(!/^Bcc:/m.test(m));
});
