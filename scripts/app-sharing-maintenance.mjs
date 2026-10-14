/** Run once per minute from a trusted scheduler. Never logs credentials. */
import { createHmac, randomUUID } from 'node:crypto';
const endpoint = process.env.APP_SHARING_MAINTENANCE_URL;
const secret = process.env.APP_SHARING_MAINTENANCE_KEY;
if (!endpoint || new URL(endpoint).protocol !== 'https:' || !secret || secret.length < 32) throw Error('Configure HTTPS maintenance URL and maintenance key');
const body = JSON.stringify({nonce:randomUUID(),timestamp:Math.floor(Date.now()/1000)});
const signature = createHmac('sha256',secret).update(body).digest('hex');
const response = await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json','X-App-Sharing-Maintenance':signature},body,signal:AbortSignal.timeout(30000)});
if (!response.ok) throw Error(`Maintenance failed (HTTP ${response.status})`);
const result = await response.json();
console.log(JSON.stringify({claimed:result.claimed,closed:result.closed,failed:result.failed}));
if (result.failed) process.exitCode=1;
