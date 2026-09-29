/**
 * Every byte this function ever sends to a browser.
 *
 * ## One script, byte for byte
 *
 * There is exactly ONE inline script in this module and it is a constant. Nothing is
 * interpolated into it: the recipient key, the `jti` and the form kind arrive as data
 * attributes on the form element, which the script reads. That is not a style preference. The
 * CSP pins the script by its SHA-256 hash, so the hash has to be a constant too; a script
 * assembled per request would need a per request hash, and the first time someone got the
 * escaping wrong the CSP would be pinning attacker chosen bytes.
 *
 * ## What the script does and does not do
 *
 * It fetches nothing, loads nothing, and talks to no origin. It reads the form, validates it
 * locally, seals the values to the DGX public key (see `seal.ts`), disables every plaintext
 * input so the browser cannot serialise it, and posts one base64 blob. The server sees
 * ciphertext and a `jti` and nothing else.
 *
 * ## The copy
 *
 * No dashes, short sentences, and it tells the owner what happened rather than what the system
 * did. Someone reads these at a petrol station at eleven at night.
 */
import { SEAL_INFO_PREFIX } from "./seal.ts"

/** Shared by every page. Hashed into the CSP, so it is a constant like the script. */
export const STYLE = `*{box-sizing:border-box}
body{margin:0;padding:24px 16px 48px;background:#fff;color:#111;
font:17px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
main{max-width:26em;margin:0 auto}
h1{font-size:20px;margin:0 0 8px}
p{margin:0 0 20px;color:#444}
label{display:block;margin:0 0 14px;font-size:14px;color:#444}
input{display:block;width:100%;margin-top:4px;padding:12px;font-size:17px;
border:1px solid #bbb;border-radius:8px;background:#fff;color:#111}
input:focus{outline:2px solid #111;outline-offset:1px}
.row{display:flex;gap:10px}
.row label{flex:1}
button{width:100%;margin-top:8px;padding:14px;font-size:17px;font-weight:600;
border:0;border-radius:8px;background:#111;color:#fff}
.att{display:flex;gap:10px;align-items:flex-start}
.att input{width:auto;margin:3px 0 0}
.note{font-size:14px;color:#666;margin-top:20px}
.err{color:#b00020;font-size:14px;min-height:1.5em;margin:0 0 8px}
@media(prefers-color-scheme:dark){
body{background:#111;color:#eee}p{color:#aaa}label{color:#aaa}
input{background:#1c1c1c;border-color:#444;color:#eee}
input:focus{outline-color:#eee}button{background:#eee;color:#111}
.note{color:#888}.err{color:#ff8a80}}`

/**
 * The client. Sealing, validation, and the submit path, in one constant.
 *
 * `SEAL_INFO_PREFIX` is the single interpolation and it is a module constant, not request data,
 * so the script text is still fixed at build time and its hash is still computable once. It is
 * spliced in rather than written out so the HKDF info string cannot drift from `seal.ts` and
 * leave the DGX unable to decrypt.
 */
export const SCRIPT = `(function(){
var form=document.getElementById("f");if(!form)return;
var out=document.getElementById("c");var err=document.getElementById("e");var sealed=false;
var enc=new TextEncoder();
function unb64(v){var s=atob(v);var a=new Uint8Array(s.length);
for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i);return a}
function b64(buf){var a=new Uint8Array(buf);var s="";
for(var i=0;i<a.length;i++)s+=String.fromCharCode(a[i]);return btoa(s)}
function digits(v){return(v||"").replace(/[^0-9]/g,"")}
function field(n){var el=form.elements[n];return el?el.value:""}
function checked(n){var el=form.elements[n];return!!(el&&el.checked)}
function luhn(n){if(n.length<13||n.length>19)return false;var sum=0,alt=false;
for(var i=n.length-1;i>=0;i--){var d=n.charCodeAt(i)-48;
if(alt){d*=2;if(d>9)d-=9}sum+=d;alt=!alt}return sum%10===0}
function fail(m){err.textContent=m;return null}
var ex=form.elements["f3"];if(ex)ex.addEventListener("input",function(){var d=digits(ex.value).slice(0,4);
ex.value=d.length>2?d.slice(0,2)+"/"+d.slice(2):d});
function payload(){
var kind=form.getAttribute("data-kind");
if(kind==="password"){var p=field("f2");
if(!p)return fail("Enter the password.");
return{kind:"password",username:field("f1"),password:p}}
if(kind==="card"){var pan=digits(field("f2"));
if(!luhn(pan))return fail("That card number does not look right.");
var ed=digits(field("f3"));if(ed.length!==4)return fail("Enter the expiry as MM/YY.");
var exp=ed.slice(0,2)+"/"+ed.slice(2);var mm=parseInt(ed.slice(0,2),10);
if(mm<1||mm>12)return fail("The expiry month must be 01 to 12.");
var name=field("f1").trim();
if(!name)return fail("Enter the name on the card.");
var postal=field("f6").trim();
if(!postal)return fail("Enter the billing postcode.");
if(!checked("f8"))return fail("Only a virtual card with a spending limit can be added. Tick the box.");
var lim=field("f9").trim();
if(!/^[0-9]+(\\.[0-9]{1,2})?$/.test(lim))return fail("Enter the spending limit, like 200 or 200.00.");
var minor=Math.round(parseFloat(lim)*100);
if(!(minor>0))return fail("Enter the spending limit, like 200 or 200.00.");
var cur=field("f10").trim().toUpperCase();
if(!/^[A-Z]{3}$/.test(cur))return fail("Enter the limit currency as three letters, like USD.");
return{kind:"card",name:name,pan:pan,exp:exp,virtual:true,limit_minor:minor,currency:cur,
billing:{line1:field("f4").trim(),
city:field("f5").trim(),postal:postal,country:field("f7").trim()}}}
var cvv=digits(field("f1"));
if(cvv.length<3||cvv.length>4)return fail("Enter the three or four digit code.");
return{kind:"cvv",cvv:cvv}}
function seal(text){
var jti=form.getAttribute("data-jti");
var info=enc.encode(${JSON.stringify(SEAL_INFO_PREFIX)}+jti);
return crypto.subtle.importKey("raw",unb64(form.getAttribute("data-key")),
{name:"ECDH",namedCurve:"P-256"},false,[]).then(function(recipient){
return crypto.subtle.generateKey({name:"ECDH",namedCurve:"P-256"},true,["deriveBits"])
.then(function(pair){return Promise.all([crypto.subtle.exportKey("raw",pair.publicKey),
crypto.subtle.deriveBits({name:"ECDH",public:recipient},pair.privateKey,256)])})
.then(function(both){var epk=new Uint8Array(both[0]);
return crypto.subtle.importKey("raw",both[1],"HKDF",false,["deriveKey"])
.then(function(ikm){return crypto.subtle.deriveKey({name:"HKDF",hash:"SHA-256",
salt:new Uint8Array(32),info:info},ikm,{name:"AES-GCM",length:256},false,["encrypt"])})
.then(function(aes){var iv=crypto.getRandomValues(new Uint8Array(12));
return crypto.subtle.encrypt({name:"AES-GCM",iv:iv,additionalData:enc.encode(jti)},
aes,enc.encode(text)).then(function(ct){var c=new Uint8Array(ct);
var blob=new Uint8Array(1+epk.length+12+c.length);blob[0]=1;blob.set(epk,1);
blob.set(iv,1+epk.length);blob.set(c,1+epk.length+12);return b64(blob)})})})})}
form.addEventListener("submit",function(event){
if(sealed)return;event.preventDefault();err.textContent="";
var value=payload();if(!value)return;
var button=form.querySelector("button");if(button)button.disabled=true;
seal(JSON.stringify(value)).then(function(blob){
var inputs=form.querySelectorAll("input[name^=f]");
for(var i=0;i<inputs.length;i++){inputs[i].value="";inputs[i].checked=false;inputs[i].disabled=true}
out.value=blob;sealed=true;form.submit()},function(){
if(button)button.disabled=false;
err.textContent="This browser could not secure the details. Try Safari or Chrome."})});
})()`

/** Headers every response carries, whatever it is. */
function baseHeaders(): Record<string, string> {
  return {
    "Cache-Control": "no-store",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
    "X-Robots-Tag": "noindex, nofollow, noarchive",
    "Strict-Transport-Security": "max-age=63072000; includeSubDomains; preload",
  }
}

/** Base64 SHA-256 of a string, in the shape a CSP hash source wants. */
export async function sha256Base64(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value) as BufferSource,
  )
  let binary = ""
  for (const byte of new Uint8Array(digest)) binary += String.fromCharCode(byte)
  return btoa(binary)
}

/**
 * The CSP for a page that carries the script.
 *
 * `default-src 'none'` and then only what is actually needed. No connect, no img, no font: the
 * script talks to nobody, so a page that suddenly wants to is a page that has been tampered
 * with, and it will be stopped by the browser rather than by us noticing.
 */
export async function scriptCsp(): Promise<string> {
  const script = await sha256Base64(SCRIPT)
  const style = await sha256Base64(STYLE)
  return [
    "default-src 'none'",
    `script-src 'self' 'sha256-${script}'`,
    `style-src 'sha256-${style}'`,
    "form-action 'self'",
    "frame-ancestors 'none'",
    "base-uri 'none'",
  ].join("; ")
}

/** The CSP for a page with no script at all. */
export async function staticCsp(): Promise<string> {
  const style = await sha256Base64(STYLE)
  return [
    "default-src 'none'",
    `style-src 'sha256-${style}'`,
    "form-action 'none'",
    "frame-ancestors 'none'",
    "base-uri 'none'",
  ].join("; ")
}

export function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;")
}

function shell(title: string, body: string, withScript: boolean): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>${escapeHtml(title)}</title><style>${STYLE}</style></head>
<body><main>${body}</main>${withScript ? `<script>${SCRIPT}</script>` : ""}</body></html>`
}

/**
 * A page with no form: every success, every refusal, every error.
 *
 * One shape for all of them, and the caller only ever passes one of `app.ts`'s own constants.
 * Nothing from the request reaches here, which is what makes the fixed 400 page fixed.
 */
export async function message(
  status: number,
  title: string,
  text: string,
  extra: Record<string, string> = {},
): Promise<Response> {
  return new Response(
    shell(title, `<h1>${escapeHtml(title)}</h1><p>${escapeHtml(text)}</p>`, false),
    {
      status,
      headers: {
        ...baseHeaders(),
        ...extra,
        "Content-Type": "text/html; charset=utf-8",
        "Content-Security-Policy": await staticCsp(),
      },
    },
  )
}

export interface FormPage {
  title: string
  intro: string
  kind: "password" | "card" | "cvv"
  jti: string
  sealKey: string
  action: string
  submit: string
  note: string
}

/**
 * The form pages. Three of them, one function, because they differ only in their fields.
 *
 * Field names are `f1`..`f7` rather than `cardnumber` or `cvv`. A password manager, a browser
 * autofill heuristic or a crash reporter that recognises a field by name is one more thing
 * holding the value, and none of them are needed here: the owner is typing it on purpose.
 * `autocomplete="off"` says the same thing to the browsers that honour it.
 */
export async function form(page: FormPage): Promise<Response> {
  const fields = page.kind === "password"
    ? `<label>Username or email<input name="f1" type="text" autocomplete="off" autocapitalize="none" spellcheck="false"></label>
<label>Password<input name="f2" type="password" autocomplete="off" required></label>`
    : page.kind === "card"
    ? `<label>Name on the card<input name="f1" type="text" autocomplete="off" spellcheck="false" required></label>
<label>Card number<input name="f2" type="text" inputmode="numeric" autocomplete="off" required></label>
<div class="row"><label>Expiry<input name="f3" type="text" inputmode="numeric" placeholder="MM/YY" maxlength="5" autocomplete="off" required></label>
<label>Postcode<input name="f6" type="text" autocomplete="off" required></label></div>
<label>Billing address<input name="f4" type="text" autocomplete="off"></label>
<div class="row"><label>City<input name="f5" type="text" autocomplete="off"></label>
<label>Country<input name="f7" type="text" autocomplete="off"></label></div>
<div class="row"><label>Spending limit<input name="f9" type="text" inputmode="decimal" autocomplete="off" required></label>
<label>Limit currency<input name="f10" type="text" value="USD" maxlength="3" autocomplete="off" spellcheck="false" required></label></div>
<label class="att"><input name="f8" type="checkbox" required>This is a virtual card with a spending limit</label>`
    : `<label>Security code<input name="f1" type="text" inputmode="numeric" autocomplete="off" required autofocus></label>`

  const body = `<h1>${escapeHtml(page.title)}</h1><p>${escapeHtml(page.intro)}</p>
<form id="f" method="post" action="${escapeHtml(page.action)}" autocomplete="off"
data-kind="${page.kind}" data-jti="${escapeHtml(page.jti)}" data-key="${escapeHtml(page.sealKey)}">
<p class="err" id="e"></p>
${fields}
<input type="hidden" name="c" id="c"><input type="hidden" name="j" value="${escapeHtml(page.jti)}">
<button type="submit">${escapeHtml(page.submit)}</button></form>
<p class="note">${escapeHtml(page.note)}</p>`

  return new Response(shell(page.title, body, true), {
    status: 200,
    headers: {
      ...baseHeaders(),
      "Content-Type": "text/html; charset=utf-8",
      "Content-Security-Policy": await scriptCsp(),
    },
  })
}
