#!/usr/bin/env node
// Node 20+; binds loopback only and serves an explicit resource allowlist.
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const here = fileURLToPath(new URL('.', import.meta.url));
const production = resolve(here, '../../composeApp/src/desktopMain/resources/app-sharing');
const output = process.argv[2] ? resolve(process.argv[2]) : null;
const productionFiles = new Set(['media.mjs', 'crypto.mjs', 'encoded-worker.mjs', 'recovery.mjs']);
const fixtureFiles = new Set(['benchmark.html', 'benchmark.css', 'benchmark.mjs', 'benchmark-metrics.mjs', 'benchmark-production.mjs']);
let origin;
const server = createServer(async (request, response) => {
  const deny = () => { response.writeHead(400); response.end(); };
  try {
    if (request.headers.host !== new URL(origin).host) return deny();
    const name = new URL(request.url, origin).pathname.slice(1);
    if (request.method === 'POST' && name === 'result') {
      if (request.headers.origin !== origin || request.headers['content-type'] !== 'application/json') return deny();
      let body = '';
      for await (const chunk of request) { body += chunk; if (body.length > 100_000) return deny(); }
      const report = JSON.parse(body);
      // Reports contain aggregates only. Never accept arbitrary logs, SDP or configurations.
      if (!['boss-sharing-benchmark/1', 'boss-sharing-benchmark/2'].includes(report.schema) || Object.keys(report).some(key => !['schema', 'environment', 'scenario', 'phases', 'coverage', 'valid', 'invalidReasons'].includes(key))) return deny();
      if (output) { await mkdir(output, { recursive: true }); await writeFile(resolve(output, `benchmark-${Date.now()}.json`), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 }); }
      response.writeHead(200, { 'Content-Type': 'application/json' }); response.end('{}'); return;
    }
    if (request.method !== 'GET') return deny();
    const filename = name || 'benchmark.html';
    const directory = fixtureFiles.has(filename) ? here : productionFiles.has(filename) ? production : null;
    if (!directory) { response.writeHead(404); response.end(); return; }
    const body = await readFile(resolve(directory, filename));
    response.writeHead(200, {
      'Content-Type': filename.endsWith('.html') ? 'text/html; charset=utf-8' : filename.endsWith('.css') ? 'text/css; charset=utf-8' : 'text/javascript; charset=utf-8',
      'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
      'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; worker-src 'self'; connect-src 'self'; media-src blob:; base-uri 'none'; frame-ancestors 'none'",
    }); response.end(body);
  } catch { if (!response.headersSent) response.writeHead(500); response.end(); }
});
server.listen(0, '127.0.0.1', () => { origin = `http://127.0.0.1:${server.address().port}`; console.log(`Benchmark: ${origin}/`); console.log(output ? `Aggregate reports: ${output}` : 'Reports shown in page only (pass an output directory to save).'); });
