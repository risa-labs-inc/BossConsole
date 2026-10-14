// Paired-repository integration fixture: real Worker/DO, loopback only, synthetic one-use host tickets.
// Launched by BossTerm's terminal-backed relay test; never uses developer or Supabase credentials.
import {createServer} from 'node:http';
import {once} from 'node:events';
import {createHmac} from 'node:crypto';
import {Miniflare, convertV4MiniflareOptions} from 'miniflare';
const consumed = new Set();
const rpc = createServer(async (request, response) => {
  try {
    let text = ''; for await (const chunk of request) text += chunk;
    const {p_token} = JSON.parse(text);
    if (request.url !== '/functions/v1/relay-admission' || request.headers['x-relay-signature'] !== createHmac('sha256', 'local-fixture-admission-key-32-characters').update(text).digest('hex') ||
        !/^[A-Za-z0-9_-]{43}$/.test(p_token) || consumed.has(p_token)) {
      response.writeHead(403); response.end('{}'); return;
    }
    consumed.add(p_token);
    response.setHeader('content-type', 'application/json'); response.end(JSON.stringify({role:'host'}));
  } catch { response.writeHead(400); response.end('{}'); }
});
rpc.listen(0, '127.0.0.1'); await once(rpc, 'listening');
const mf = new Miniflare(convertV4MiniflareOptions({workers:[{
  name:'terminal-relay-native-fixture', modules:true, scriptPath:'.wrangler/test-bundle/worker.js',
  compatibilityDate:'2026-09-25', durableObjects:{ROOMS:{className:'TerminalRoom', useSQLite:true}},
  bindings:{SUPABASE_URL:`http://127.0.0.1:${rpc.address().port}`, RELAY_ADMISSION_KEY:'local-fixture-admission-key-32-characters'},
}]}));
console.log('RELAY_FIXTURE_URL=' + (await mf.ready).origin.replace(/^http/, 'ws'));
let closing = false;
const close = async () => {
  if (closing) return; closing = true;
  await mf.dispose(); rpc.closeAllConnections(); await new Promise(resolve => rpc.close(resolve));
  process.exit(0);
};
process.on('SIGTERM', close); process.on('SIGINT', close);
