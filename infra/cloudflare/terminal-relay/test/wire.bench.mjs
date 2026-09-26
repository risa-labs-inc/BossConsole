import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {performance} from 'node:perf_hooks';
import {generateKeyPairSync, randomBytes, randomUUID, hkdfSync, createCipheriv, sign} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {Miniflare, convertV4MiniflareOptions} from 'miniflare';

// Real loopback WebSockets + Worker/DO + the shipped browser crypto verifier.
// This is a development-machine load check, not an Internet or UI-rendering capacity claim.
assert(process.env.BOSSTERM_SOURCE_DIR, 'Set BOSSTERM_SOURCE_DIR to the paired BossTerm checkout');
const {RelayOutputReceiver} = await import(pathToFileURL(resolve(process.env.BOSSTERM_SOURCE_DIR,
  'compose-ui/src/desktopMain/resources/share-viewer/relay-crypto.mjs')).href);
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const until = async (predicate, failure) => {
  const end = Date.now() + 30000;
  while (!predicate()) {
    if (failure()) throw failure();
    if (Date.now() > end) throw Error('Benchmark delivery timeout');
    await pause(2);
  }
};
const rpc = createServer(async (req, res) => {
  let body = ''; for await (const chunk of req) body += chunk;
  assert.equal(req.headers.authorization, 'Bearer benchmark-service-key');
  assert.equal(JSON.parse(body).p_token.length, 43);
  res.setHeader('content-type', 'application/json'); res.end(JSON.stringify({role:'host'}));
});
rpc.listen(0, '127.0.0.1'); await once(rpc, 'listening');
let mf;
const results = [];
try {
  mf = new Miniflare(convertV4MiniflareOptions({workers:[{
    name:'terminal-relay-bench', modules:true, scriptPath:'.wrangler/test-bundle/worker.js',
    compatibilityDate:'2026-09-25', durableObjects:{ROOMS:{className:'TerminalRoom', useSQLite:true}},
    bindings:{SUPABASE_URL:`http://127.0.0.1:${rpc.address().port}`, SUPABASE_SERVICE_ROLE_KEY:'benchmark-service-key'},
  }]}));
  const origin = (await mf.ready).origin.replace(/^http/, 'ws');
  for (const panes of [10, 50, 100]) for (const viewers of [1, 3, 10]) {
    const room = randomUUID(), identity = generateKeyPairSync('ed25519');
    let failure, deliveries = 0, subscribed = 0, upstreamBytes = 0, downstreamBytes = 0, cryptoMs = 0;
    const sockets = [], clients = [], latencies = [], publishedAt = new Map();
    const keys = Array.from({length:panes}, (_, i) => {
      const pane = `pane-${i}`, epoch = randomUUID(), root = randomBytes(32);
      return {pane, epoch, aes:hkdfSync('sha256', root, epoch, 'bossterm-relay-v1/live', 32), grant:{
        epoch, key:root.toString('base64url'), hostPublicKey:identity.publicKey.export({type:'spki', format:'der'}).toString('base64url'),
      }};
    });
    const connect = async (ticket, receive) => {
      const socket = new WebSocket(`${origin}/v1/rooms/${room}`);
      sockets.push(socket);
      let welcomed = false, peer;
      socket.addEventListener('close', event => { if (!socket.intentional) failure ??= Error(`Unexpected close ${event.code}: ${event.reason}`); });
      socket.addEventListener('error', () => { failure ??= Error('WebSocket error'); });
      let chain = Promise.resolve();
      socket.addEventListener('message', event => {
        chain = chain.then(async () => {
          const message = JSON.parse(event.data);
          if (message.op === 'welcome') { welcomed = true; peer = message.peer; return; }
          await receive(message, socket);
        }).catch(error => { failure ??= error; });
      });
      await once(socket, 'open');
      socket.send(JSON.stringify({op:'hello', v:1, room, ...(ticket ? {ticket} : {})}));
      await until(() => welcomed, () => failure);
      return {socket, peer};
    };
    try {
      const host = await connect('h'.repeat(43), async (message, socket) => {
        if (message.op === 'subscribe') {
          subscribed++;
          const key = keys.find(key => key.pane === message.pane);
          socket.send(JSON.stringify({op:'snapshot', peer:message.peer, pane:message.pane, epoch:key.epoch, seq:0, payload:'benchmark-admitted-snapshot'}));
        }
      });
      for (let n = 0; n < viewers; n++) {
        const receivers = new Map(await Promise.all(keys.map(async key => [key.pane, await RelayOutputReceiver.create(room, key.pane, key.grant)])));
        let grants = 0, snapshots = 0, last = 0;
        const client = await connect(null, async (message, socket) => {
          if (message.op === 'grant') { grants++; return; }
          if (message.op !== 'frames') return;
          assert.equal(message.delivery, ++last);
          downstreamBytes += Buffer.byteLength(JSON.stringify(message));
          for (const frame of message.messages) {
            if (frame.op === 'snapshot') { snapshots++; continue; }
            assert.equal(frame.op, 'output');
            const before = performance.now();
            const plain = JSON.parse(await receivers.get(frame.pane).decrypt(frame));
            cryptoMs += performance.now() - before;
            assert.equal(plain.t, 'paneOutput'); assert.equal(plain.paneId, frame.pane);
            assert.equal(plain.data.length, 1024);
            latencies.push(performance.now() - publishedAt.get(frame.pane + ':' + frame.seq));
            deliveries++;
          }
          socket.send(JSON.stringify({op:'ack', through:message.delivery}));
        });
        host.socket.send(JSON.stringify({op:'grant', peer:client.peer, panes:keys.map(k => k.pane)}));
        clients.push({...client, ready:() => grants && snapshots === panes});
      }
      // Pace subscriptions per peer, and wait for initial targeted snapshots before timing output.
      for (const key of keys) {
        for (const client of clients) client.socket.send(JSON.stringify({op:'subscribe', pane:key.pane, mode:'live', fps:4}));
        await pause(25);
      }
      await until(() => clients.every(c => c.ready()), () => failure);
      assert.equal(subscribed, panes * viewers);
      downstreamBytes = 0;
      const start = performance.now(); let publishCryptoMs = 0;
      for (let cycle = 1; cycle <= 20; cycle++) {
        const cycleStart = performance.now();
        for (const key of keys) {
          const before = performance.now();
          const frame = {op:'output', room, pane:key.pane, epoch:key.epoch, kind:'live', seq:cycle};
          const metadata = Buffer.from(`bossterm-relay-v1\n${room}\n${key.pane}\n${key.epoch}\nlive\n${cycle}\n`);
          const nonce = Buffer.alloc(12); nonce.writeBigUInt64BE(BigInt(cycle), 4);
          const cipher = createCipheriv('aes-256-gcm', key.aes, nonce); cipher.setAAD(metadata);
          const ciphertext = Buffer.concat([cipher.update(JSON.stringify({t:'paneOutput', paneId:key.pane, data:'x'.repeat(1024)})), cipher.final(), cipher.getAuthTag()]);
          frame.payload = ciphertext.toString('base64url');
          frame.signature = sign(null, Buffer.concat([metadata, ciphertext]), identity.privateKey).toString('base64url');
          publishCryptoMs += performance.now() - before;
          const raw = JSON.stringify(frame); upstreamBytes += Buffer.byteLength(raw);
          publishedAt.set(key.pane + ':' + cycle, performance.now()); host.socket.send(raw);
        }
        await until(() => deliveries === cycle * panes * viewers, () => failure);
        // Respect the real relay's 1000 host control frames/sec rate, with headroom.
        await pause(Math.max(0, panes * 1.25 - (performance.now() - cycleStart)));
      }
      assert.equal(deliveries, panes * 20 * viewers);
      latencies.sort((a,b) => a-b);
      const result = {panes, viewers, publications:panes * 20, deliveries, upstreamBytes, downstreamBytes,
        elapsedMs:+(performance.now()-start).toFixed(1), publishCryptoMs:+publishCryptoMs.toFixed(1),
        aggregateVerifyDecryptMs:+cryptoMs.toFixed(1), p50DeliveryMs:+latencies[Math.floor(latencies.length*.5)].toFixed(1),
        p95DeliveryMs:+latencies[Math.floor(latencies.length*.95)].toFixed(1)};
      results.push(result); console.log(JSON.stringify(result));
    } finally {
      for (const socket of sockets) { socket.intentional = true; socket.close(); }
      await pause(50);
    }
  }
  for (const panes of [10,50,100]) {
    const group = results.filter(r => r.panes === panes);
    assert.equal(new Set(group.map(r => r.publications)).size, 1);
    assert.equal(new Set(group.map(r => r.upstreamBytes)).size, 1, 'Viewer count must not multiply host publications or bytes');
  }
  console.log(JSON.stringify({scope:'Local real Worker WebSockets plus production browser verify/decrypt; excludes UI rendering and Internet latency', results}, null, 2));
} finally {
  await mf?.dispose(); rpc.closeAllConnections(); await new Promise(resolve => rpc.close(resolve));
}
