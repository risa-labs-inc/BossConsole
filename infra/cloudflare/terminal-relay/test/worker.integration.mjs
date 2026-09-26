import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { once } from "node:events";
import { convertV4MiniflareOptions, Miniflare } from "miniflare";

function inbox(socket) {
  const queue = [], waiters = [];
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    const waiter = waiters.shift();
    if (waiter) waiter(message);
    else queue.push(message);
  });
  return async () => {
    if (queue.length) return queue.shift();
    return await new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => reject(Error("Relay message timeout")),
        5000,
      );
      waiters.push((message) => {
        clearTimeout(timer);
        resolve(message);
      });
    });
  };
}

test(
  "real Durable Object admits once, fans out once, and enforces guest approval",
  { timeout: 20000 },
  async () => {
    const consumed = new Set();
    const rpc = createServer(async (req, res) => {
      let body = "";
      for await (const chunk of req) body += chunk;
      assert.equal(req.headers.authorization, "Bearer local-test-service-key");
      const request = JSON.parse(body);
      if (
        consumed.has(request.p_token) ||
        !["h".repeat(43), "a".repeat(43)].includes(request.p_token)
      ) {
        res.writeHead(403);
        res.end("{}");
        return;
      }
      consumed.add(request.p_token);
      res.setHeader("Content-Type", "application/json");
      res.end(
        JSON.stringify({
          role: request.p_token.startsWith("h") ? "host" : "account",
        }),
      );
    });
    rpc.listen(0, "127.0.0.1");
    await once(rpc, "listening");
    let mf;
    try {
      mf = new Miniflare(
        convertV4MiniflareOptions({
          workers: [{
            name: "terminal-relay",
            modules: true,
            scriptPath: ".wrangler/test-bundle/worker.js",
            compatibilityDate: "2026-09-25",
            durableObjects: {
              ROOMS: { className: "TerminalRoom", useSQLite: true },
            },
            bindings: {
              SUPABASE_URL: `http://127.0.0.1:${rpc.address().port}`,
              SUPABASE_SERVICE_ROLE_KEY: "local-test-service-key",
            },
          }],
        }),
      );
    } catch (error) {
      rpc.close();
      throw error;
    }
    const sockets = [];
    const room = "11111111-1111-4111-8111-111111111111";
    const connect = async (ticket) => {
      const response = await mf.dispatchFetch(
        `http://relay.test/v1/rooms/${room}`,
        { headers: { Upgrade: "websocket" } },
      );
      assert.equal(response.status, 101);
      const ws = response.webSocket;
      const next = inbox(ws);
      sockets.push(ws);
      ws.accept();
      ws.send(JSON.stringify({ op: "hello", v: 1, room, ticket }));
      const welcome = await next();
      assert.equal(welcome.op, "welcome");
      return { ws, next, id: welcome.peer };
    };
    try {
      const host = await connect("h".repeat(43));
      assert.equal((await host.next()).op, "peers");
      const account = await connect("a".repeat(43));
      assert.equal((await host.next()).op, "join");
      const guest = await connect();
      assert.equal((await host.next()).op, "join");
      const pendingSignal = { op: "signal", payload: "encrypted-handshake" };
      guest.ws.send(JSON.stringify(pendingSignal));
      const forwarded = await host.next();
      assert.equal(forwarded.peer, guest.id);
      assert.equal(forwarded.op, "signal");
      for (const viewer of [account, guest]) {
        host.ws.send(
          JSON.stringify({ op: "grant", peer: viewer.id, panes: ["p"] }),
        );
        assert.equal((await viewer.next()).op, "grant");
        await host.next();
        viewer.ws.send(
          JSON.stringify({ op: "subscribe", pane: "p", mode: "live", fps: 4 }),
        );
        assert.equal((await host.next()).op, "subscribe");
        assert.equal((await host.next()).op, "interests");
        host.ws.send(
          JSON.stringify({
            op: "snapshot",
            peer: viewer.id,
            pane: "p",
            seq: 0,
            epoch: "e",
            payload: "snapshot",
          }),
        );
        assert.equal((await viewer.next()).messages[0].op, "snapshot");
      }
      host.ws.send(
        JSON.stringify({
          op: "output",
          pane: "p",
          kind: "live",
          epoch: "e",
          seq: 1,
          payload: "one-ciphertext",
        }),
      );
      for (const viewer of [account, guest]) {
        const frame = await viewer.next();
        assert.equal(frame.op, "frames");
        assert.equal(frame.messages[0].payload, "one-ciphertext");
        viewer.ws.send(JSON.stringify({ op: "ack", through: frame.delivery }));
      }
      // The same ticket cannot be reused, even for the same room.
      const response = await mf.dispatchFetch(
        `http://relay.test/v1/rooms/${room}`,
        { headers: { Upgrade: "websocket" } },
      );
      const rejected = response.webSocket;
      sockets.push(rejected);
      rejected.accept();
      const closed = new Promise((resolve) =>
        rejected.addEventListener("close", resolve, { once: true })
      );
      rejected.send(
        JSON.stringify({ op: "hello", v: 1, room, ticket: "a".repeat(43) }),
      );
      assert.equal((await closed).code, 1008);
    } finally {
      for (const socket of sockets) {
        try {
          socket.close();
        } catch {}
      }
      await mf.dispose();
      rpc.closeAllConnections();
      await new Promise((resolve) => rpc.close(resolve));
    }
  },
);
