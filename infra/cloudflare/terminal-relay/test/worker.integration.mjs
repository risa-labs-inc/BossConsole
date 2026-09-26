import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { once } from "node:events";
import { readFile } from "node:fs/promises";
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
    let rpcCalls = 0;
    const rpc = createServer(async (req, res) => {
      rpcCalls++;
      let body = "";
      for await (const chunk of req) body += chunk;
      assert.equal(req.headers.authorization, "Bearer local-test-service-key");
      const request = JSON.parse(body);
      if (
        consumed.has(request.p_token) ||
        !["h".repeat(43), "j".repeat(43), "a".repeat(43)].includes(request.p_token)
      ) {
        res.writeHead(403);
        res.end("{}");
        return;
      }
      consumed.add(request.p_token);
      res.setHeader("Content-Type", "application/json");
      res.end(
        JSON.stringify({
          role: request.p_token.startsWith("a") ? "account" : "host",
        }),
      );
    });
    rpc.listen(0, "127.0.0.1");
    await once(rpc, "listening");
    let mf;
    try {
      // Test-only private-state probe: production Worker exports no diagnostic endpoint.
      const bundle = await readFile(".wrangler/test-bundle/worker.js", "utf8");
      const entry = "async fetch(_request) {";
      assert(bundle.includes(entry));
      const script = bundle.replace(entry, entry + `
        if (_request.headers.has("x-test-routing-state")) return Response.json({sockets: this.sockets.size});
      `);
      mf = new Miniflare(
        convertV4MiniflareOptions({
          workers: [{
            name: "terminal-relay",
            modules: true,
            script,
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
    const socketCount = async () => {
      const response = await mf.dispatchFetch(`http://relay.test/v1/rooms/${room}`, {
        headers: {Upgrade:"websocket", "x-test-routing-state":"1"},
      });
      return (await response.json()).sockets;
    };
    const rawConnection = async () => {
      const response = await mf.dispatchFetch(
        `http://relay.test/v1/rooms/${room}`,
        { headers: { Upgrade: "websocket" } },
      );
      assert.equal(response.status, 101);
      const ws = response.webSocket;
      const next = inbox(ws);
      sockets.push(ws);
      ws.accept();
      return {ws, next};
    };
    const connect = async (ticket) => {
      const {ws, next} = await rawConnection();
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
      // Neither a valid account ticket nor a guest hello may replace the active host.
      host.ws.send(JSON.stringify({op:"ping"}));
      assert.equal((await host.next()).op, "pong");
      assert.equal(await socketCount(), 3);
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
      // A character-small but UTF-8-oversized hello is refused before any RPC.
      for (const data of ["{".repeat(5000), JSON.stringify({op:"hello", v:1, room, ticket:"j".repeat(43), padding:"界".repeat(1400)})]) {
        const candidate = await rawConnection();
        const denied = once(candidate.ws, "close");
        const before = rpcCalls;
        candidate.ws.send(data);
        assert.equal((await denied)[0].code, 1009);
        assert.equal(rpcCalls, before);
      }
      // A forged ticket cannot disturb the active host or its admitted viewers.
      const forged = await rawConnection();
      const denied = once(forged.ws, "close");
      forged.ws.send(JSON.stringify({op:"hello", v:1, room, ticket:"x".repeat(43)}));
      assert.equal((await denied)[0].code, 1008);
      host.ws.send(JSON.stringify({op:"ping"}));
      assert.equal((await host.next()).op, "pong");
      // Fresh valid owner admission replaces a predecessor whose close event has not arrived.
      // Its viewers must reconnect with no inherited authorization or delivery credits.
      const oldClosed = [host, account, guest].map(peer => once(peer.ws, "close"));
      const replacement = await connect("j".repeat(43));
      const peers = await replacement.next();
      assert.equal(peers.op, "peers");
      assert.deepEqual(JSON.parse(peers.payload), []);
      for (const event of await Promise.all(oldClosed)) assert.equal(event[0].code, 1012);
      assert.equal(await socketCount(), 1, "server-closed predecessors must leave no socket-map entries");
      const freshGuest = await connect();
      assert.equal((await replacement.next()).op, "join");
      const unapproved = once(freshGuest.ws, "close");
      freshGuest.ws.send(JSON.stringify({op:"subscribe",pane:"p",mode:"live",fps:4}));
      assert.equal((await unapproved)[0].code, 1008);
      assert.equal(await socketCount(), 1, "protocol-refused viewer must be removed without waiting for a close callback");
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
