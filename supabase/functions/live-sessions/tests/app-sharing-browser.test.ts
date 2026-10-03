import {assertEquals,assertMatch,assertStringIncludes} from '@std/assert'
import {appSharingBrowser} from '../app-sharing-browser.ts'
const origin='https://cli.example',base=origin+'/api/app-sharing',token='fixture-token-valid',csrf='a'.repeat(64)
function setup(){Deno.env.set('LIVE_SESSIONS_PUBLIC_BASE_URL',origin);Deno.env.set('LIVE_SESSIONS_PUBLIC_BASE_PATH','/');Deno.env.set('SUPABASE_URL','https://stack.example');Deno.env.set('SUPABASE_ANON_KEY','anon')}
function cleanup(){Deno.env.delete('LIVE_SESSIONS_PUBLIC_BASE_URL');Deno.env.delete('LIVE_SESSIONS_PUBLIC_BASE_PATH')}
const cookie=`__Secure-boss_live_at=${token}; __Host-boss_app_csrf=${csrf}`
Deno.test('hosted viewer bootstrap authenticates and reuses CSRF across tabs without exposing JWT',async()=>{setup();try{
 const response=await appSharingBrowser(new Request(base+'-bootstrap',{headers:{cookie}}),()=>Promise.resolve(new Response('{}')))
 assertEquals(response.status,200);assertEquals(await response.json(),{csrf});assertMatch(response.headers.get('set-cookie')!,/HttpOnly; SameSite=Strict/)
 assertEquals((await appSharingBrowser(new Request(base+'-bootstrap'),()=>{throw Error('must not call')})).status,401)
}finally{cleanup()}})
Deno.test('cookie proxy requires nonce and canonical Origin before any authenticated fetch',async()=>{setup();try{
 const cases:Record<string,string>[]=[{cookie,origin},{cookie,origin:'https://attacker.example','X-App-Sharing-CSRF':csrf},{cookie,origin,'X-App-Sharing-CSRF':'b'.repeat(64)}]
 for(const headers of cases){
 const response=await appSharingBrowser(new Request(base,{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:'{"action":"list"}'}),()=>{throw Error('must not fetch')});assertEquals(response.status,403)
 }
}finally{cleanup()}})
Deno.test('cookie proxy pins upstream and rejects host actions or arbitrary paths',async()=>{setup();try{
 const calls:{url:string;init?:RequestInit}[]=[]
 const fetcher=(url:string,init?:RequestInit)=>{calls.push({url,init});return Promise.resolve(new Response(JSON.stringify(url.endsWith('/user')?{id:'user'}:{sessions:[]})))}
 const request=(action:string)=>new Request(base,{method:'POST',headers:{cookie,origin,'X-App-Sharing-CSRF':csrf,'Content-Type':'application/json'},body:JSON.stringify({action,url:'https://attacker.example'})})
 assertEquals((await appSharingBrowser(request('register'),fetcher)).status,400)
 assertEquals(calls.length,1);calls.length=0
 assertEquals((await appSharingBrowser(request('list'),fetcher)).status,200);assertEquals(calls[1].url,'https://stack.example/functions/v1/app-sharing')
 assertEquals((calls[1].init?.headers as Record<string,string>).Authorization,'Bearer '+token)
}finally{cleanup()}})
Deno.test('viewer assets never serve host publisher and use strict self-only scripts',async()=>{setup();try{
 const never=()=>{throw Error('asset must not authenticate')}
 const response=await appSharingBrowser(new Request(origin+'/app-viewer/?session=11111111-1111-4111-8111-111111111111'),never)
 assertEquals(response.status,200);assertStringIncludes(response.headers.get('Content-Security-Policy')!,"worker-src 'self'")
 const html=await response.text();assertStringIncludes(html,'id="client-metrics"');assertStringIncludes(html,'id="remote-metrics"')
 for(const name of ['stats.mjs','performance-bar.mjs']){
  const asset=await appSharingBrowser(new Request(origin+'/app-viewer/'+name),never)
  assertEquals(asset.status,200);assertStringIncludes(asset.headers.get('Content-Type')!,'text/javascript')
 }
 assertEquals((await appSharingBrowser(new Request(origin+'/app-viewer/host.mjs'),never)).status,404)
}finally{cleanup()}})
