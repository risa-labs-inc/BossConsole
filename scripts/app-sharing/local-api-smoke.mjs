// Real local GoTrue + PostgREST + Edge test. Never accepts a production endpoint.
import {execFileSync} from 'node:child_process';
import {writeFile,chmod} from 'node:fs/promises';
import {randomUUID,randomBytes} from 'node:crypto';
import assert from 'node:assert/strict';
const workdir=process.env.BOSS_APP_TEST_STACK ?? '/tmp/boss-app-sharing-e2e';
const config=JSON.parse(execFileSync('supabase',['status','--workdir',workdir,'--output','json'],{encoding:'utf8',stdio:['ignore','pipe','pipe']}));
const base=config.API_URL;
assert.equal(new URL(base).hostname,'127.0.0.1');assert.equal(new URL(base).port,'55431');
const users=[];const password=randomBytes(32).toString('base64url');
const anon=config.ANON_KEY,service=config.SERVICE_ROLE_KEY;
async function api(path,key,body,method='POST') {
 const r=await fetch(base+path,{method,headers:{apikey:anon,Authorization:`Bearer ${key}`,'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(25000)});
 return {status:r.status,body:await r.json().catch(()=>({}))};
}
async function user(){const email=`app-share-test-${randomUUID()}@example.invalid`;const created=await api('/auth/v1/admin/users',service,{email,password,email_confirm:true});assert.equal(created.status,200);users.push(created.body.id);const auth=await api('/auth/v1/token?grant_type=password',anon,{email,password});assert.equal(auth.status,200);return {id:created.body.id,token:auth.body.access_token};}
let preserve=false;
try{
 const owner=await user(),other=await user();
 const session_id=randomUUID(),generation=randomUUID(),device_id=randomUUID();
 const call=(action,args={},actor=owner)=>api('/functions/v1/app-sharing',actor.token,{action,session_id,generation,...args});
 const prefs=await call('preferencesGet');assert.equal(prefs.status,200);assert.equal(prefs.body.auto_admit,true);
 const registered=await call('register',{device_id,instance_id:randomUUID(),name:'Synthetic local API test',windows:[{id:'window:local',title:'Synthetic'}],viewer_url:`https://app-share-test.invalid/viewer?session=${session_id}#k=${randomBytes(32).toString('base64url')}`,key_epoch:randomUUID(),host_public_key:randomBytes(44).toString('base64url')});assert.equal(registered.status,200);
 const demand=await call('mediaDemand',{host_peer_id:registered.body.host_peer_id});assert.equal(demand.status,200);assert.deepEqual(demand.body.windows,[{window_id:'window:local',viewers:0}]);
 assert.equal((await call('list',{},other)).body.sessions.length,0);
 assert.equal((await call('heartbeat',{},other)).status,403);
 const ticket=await call('admit',{device_id,role:'view'});assert.equal(ticket.status,200);
 const consume={device_id,role:'view',ticket:ticket.body.ticket};const admitted=await call('consume',consume);assert.equal(admitted.status,200);assert.equal((await call('consume',consume)).status,403);
 assert.equal((await call('controlAcquire',{peer_id:admitted.body.peer_id})).status,403);assert.equal((await call('mediaDemand',{peer_id:admitted.body.peer_id})).status,403);
 const changed=await call('preferencesSet',{auto_admit:false,auto_control:true,revision:0});assert.equal(changed.status,200);
 assert.equal((await call('preferencesSet',{auto_admit:true,auto_control:true,revision:0})).status,409);
 assert.equal((await call('heartbeat')).status,403);
 assert.equal((await call('list')).body.sessions.length,0);
 assert.equal((await call('preferencesSet',{auto_admit:true,auto_control:true,revision:changed.body.revision})).status,200);
 console.log('PASS real GoTrue identity, isolated registry, admission/replay/role, CAS conflict, preference retirement');
 const file=process.env.BOSS_TEST_APP_SFU_CREDENTIALS;
 if(file){await writeFile(file,JSON.stringify({endpoint:`${base}/functions/v1/app-sharing`,userId:owner.id,accessToken:owner.token,anonKey:anon,viewerBaseUrl:'https://app-share-test.invalid/viewer'}),{mode:0o600});await chmod(file,0o600);preserve=true;console.log('Dedicated local harness account credentials written (owner-only file)');}
}finally{
 for(const [index,id] of users.entries()){if(preserve&&index===0)continue;await api(`/auth/v1/admin/users/${id}`,service,undefined,'DELETE');}
}
