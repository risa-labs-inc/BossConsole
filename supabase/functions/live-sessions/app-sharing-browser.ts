import { accessCookieName, cookieToken, isSecureRequest, refreshCookieName, sessionCookieHeaders } from './utils/cookies.ts'
import { publicBasePath, publicBaseUrl, readConfig } from './utils/config.ts'
import { jsonResponse } from './utils/responses.ts'
import { appViewerAssets } from './app-viewer-assets.ts'
type Fetch = (url:string,init?:RequestInit)=>Promise<Response>
const ALLOWED = new Set(['list','admit','consume','peerHeartbeat','controlAcquire','controlRenew','controlRelease','mediaCreate','mediaSubscribe','mediaRenegotiate','mediaClose','dataEstablish','dataSubscribe','dataRevoke'])
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
async function readJson(req:Request):Promise<Record<string,unknown>|null>{
 const reader=req.body?.getReader();if(!reader)return null;let size=0;const parts:Uint8Array[]=[];let timer:ReturnType<typeof setTimeout>|undefined
 const deadline=new Promise<never>((_,reject)=>{timer=setTimeout(()=>reject(Error('body timeout')),3000)})
 try{for(;;){const {done,value}=await Promise.race([reader.read(),deadline]);if(done)break;size+=value.length;if(size>131072||parts.length>4096)return null;parts.push(value)}}catch{return null}finally{clearTimeout(timer);void reader.cancel().catch(()=>{});reader.releaseLock()}
 try{const bytes=new Uint8Array(size);let offset=0;for(const part of parts){bytes.set(part,offset);offset+=part.length}const value=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));return value&&typeof value==='object'&&!Array.isArray(value)?value:null}catch{return null}
}
/** Viewer-only cookie bridge: no service-role key, arbitrary endpoint, or host actions. */
export async function appSharingBrowser(request:Request,fetcher:Fetch):Promise<Response>{
 const url=new URL(request.url),secure=isSecureRequest(request.url,request.headers.get('x-forwarded-proto'))
 const base=publicBaseUrl();if(!base)return jsonResponse({error:'not_configured'},503)
 if(url.pathname.includes('/app-viewer')){
  if(request.method!=='GET')return jsonResponse({error:'method_not_allowed'},405)
  const session=url.searchParams.get('session');if(session&&!UUID.test(session))return jsonResponse({error:'invalid_request'},400)
  const suffix=url.pathname.split('/app-viewer/')[1]??'';const name=suffix||'viewer.html'
  if(!Object.hasOwn(appViewerAssets,name))return jsonResponse({error:'not_found'},404)
  const asset=appViewerAssets[name]
  return new Response(asset.body,{headers:{'Content-Type':asset.type,'Cache-Control':'no-store','Referrer-Policy':'no-referrer','X-Content-Type-Options':'nosniff',
   'Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'self'; worker-src 'self'; connect-src 'self'; media-src 'self' blob:; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'"}})
 }
 const cfg=readConfig(),cookie=request.headers.get('cookie'),csrfName=secure?'__Host-boss_app_csrf':'boss_app_csrf'
 if(request.headers.get('sec-fetch-site')==='cross-site')return jsonResponse({error:'forbidden'},403)
 const bootstrap=url.pathname.endsWith('/app-sharing-bootstrap')
 if((bootstrap&&request.method!=='GET')||(!bootstrap&&request.method!=='POST'))return jsonResponse({error:'method_not_allowed'},405)
 if(!bootstrap){
  const expected=cookieToken(cookie,csrfName),supplied=request.headers.get('X-App-Sharing-CSRF')
  if(request.headers.get('origin')!==new URL(base).origin||!expected||!/^[a-f0-9]{64}$/.test(expected)||expected!==supplied)return jsonResponse({error:'forbidden'},403)
  if(!request.headers.get('content-type')?.startsWith('application/json'))return jsonResponse({error:'invalid_request'},415)
 }
 let token=cookieToken(cookie,accessCookieName(secure));const refresh=cookieToken(cookie,refreshCookieName(secure));let cookies:string[]=[]
 if(!token&&!refresh)return jsonResponse({error:'unauthorized',login_url:base},401)
 const valid=async(t:string)=>{const r=await fetcher(`${cfg.supabaseUrl}/auth/v1/user`,{headers:{apikey:cfg.anonKey,Authorization:`Bearer ${t}`},signal:AbortSignal.timeout(5000)});return r.ok}
 try{
  if(!token||!await valid(token)){
   if(!refresh)return jsonResponse({error:'unauthorized',login_url:base},401)
   const response=await fetcher(`${cfg.supabaseUrl}/auth/v1/token?grant_type=refresh_token`,{method:'POST',headers:{apikey:cfg.anonKey,'Content-Type':'application/json'},body:JSON.stringify({refresh_token:refresh}),signal:AbortSignal.timeout(5000)})
   if(!response.ok)return jsonResponse({error:'unauthorized',login_url:base},401)
   const fresh=await response.json();if(typeof fresh.access_token!=='string'||typeof fresh.refresh_token!=='string')return jsonResponse({error:'unauthorized'},401)
   token=fresh.access_token;cookies=sessionCookieHeaders(fresh.access_token,fresh.refresh_token,secure,publicBasePath())
  }
  if(bootstrap){
   const existing=cookieToken(cookie,csrfName)
   const csrf=existing&&/^[a-f0-9]{64}$/.test(existing)?existing:Array.from(crypto.getRandomValues(new Uint8Array(32)),b=>b.toString(16).padStart(2,'0')).join('')
   cookies.push(`${csrfName}=${csrf}; Path=/; HttpOnly; SameSite=Strict; Max-Age=1800${secure?'; Secure':''}`)
   return jsonResponse({csrf},200,cookies)
  }
  const input=await readJson(request)
  if(!input||typeof input.action!=='string'||!ALLOWED.has(input.action))return jsonResponse({error:'invalid_request'},400)
  const response=await fetcher(`${cfg.supabaseUrl}/functions/v1/app-sharing`,{method:'POST',headers:{apikey:cfg.anonKey,Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify(input),signal:AbortSignal.timeout(15000)})
  const value=await response.json().catch(()=>({error:'upstream_unavailable'}))
  if(response.ok)cookies.push(`${csrfName}=${cookieToken(cookie,csrfName)}; Path=/; HttpOnly; SameSite=Strict; Max-Age=1800${secure?'; Secure':''}`)
  return jsonResponse(value,response.status,cookies)
 }catch{return jsonResponse({error:'upstream_unavailable'},503)}
}
