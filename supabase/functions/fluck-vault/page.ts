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
import { MAX_MINOR_AMOUNT, MINOR_UNIT_EXPONENTS } from "./currency.ts"
import {
  escapeHtml,
  FOOTER_ENCRYPTED,
  parseReturnUrl,
  resultHtml,
  sha256Base64,
  shell,
  staticCsp,
  STYLE,
} from "../_shared/connect-ui.ts"

// Hashed into the CSP, so it is a constant like the script. Lives in `_shared` with the rest of
// the chrome; re-exported so the tests and the CSP builder keep one name for it.
export { escapeHtml, sha256Base64, staticCsp, STYLE }

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
function luhn(n){if(n.length<13||n.length>19)return false;var sum=0,alt=false;
for(var i=n.length-1;i>=0;i--){var d=n.charCodeAt(i)-48;
if(alt){d*=2;if(d>9)d-=9}sum+=d;alt=!alt}return sum%10===0}
function fail(m){err.textContent=m;return null}
var EXP=${JSON.stringify(MINOR_UNIT_EXPONENTS)};
var ex=form.elements["f3"];if(ex)ex.addEventListener("input",function(){var d=digits(ex.value).slice(0,4);
ex.value=d.length>2?d.slice(0,2)+"/"+d.slice(2):d});
function payload(){
var kind=form.getAttribute("data-kind");
if(kind==="password"){var p=field("f2");
if(!p)return fail("Enter the password.");
return{kind:"password",username:field("f1"),password:p}}
if(kind==="secret"){var label=field("f1").trim();var v=field("f2");
if(!label)return fail("Enter what this key is for.");
if(!v)return fail("Paste the key.");
var s={kind:"secret",label:label,value:v};
if(enc.encode(JSON.stringify(s)).length>8000)return fail("That is too long to save here.");
return s}
if(kind==="card"){var pan=digits(field("f2"));
if(!luhn(pan))return fail("That card number does not look right.");
var ed=digits(field("f3"));if(ed.length!==4)return fail("Enter the expiry as MM/YY.");
var exp=ed.slice(0,2)+"/"+ed.slice(2);var mm=parseInt(ed.slice(0,2),10);
if(mm<1||mm>12)return fail("The expiry month must be 01 to 12.");
var amex=/^3[47]/.test(pan);var code=digits(field("f11"));
if(code.length!==(amex?4:3))return fail(amex?"Enter the four digit security code.":"Enter the three digit security code.");
var name=field("f1").trim();
if(!name)return fail("Enter the name on the card.");
var postal=field("f6").trim();
if(!postal)return fail("Enter the billing postcode.");
var cur=field("f10").trim().toUpperCase();
if(!/^[A-Z]{3}$/.test(cur))return fail("Enter the limit currency as three letters, like USD.");
if(!Object.prototype.hasOwnProperty.call(EXP,cur))return fail("That is not a currency code. Try USD, EUR or GBP.");
var xp=EXP[cur];
var lim=field("f9").trim();var lm=/^([0-9]+)(?:\\.([0-9]+))?$/.exec(lim);
if(!lm||(lm[2]||"").length>xp)return fail(xp===0?"Enter the spending limit as a whole number, like 200.":"Enter the spending limit, like 200 or 200."+"0000".slice(0,xp)+".");
var minor=parseInt(lm[1]+((lm[2]||"")+"0000").slice(0,xp),10);
if(!(minor>0)||minor>${MAX_MINOR_AMOUNT})return fail("Enter the spending limit, like 200.");
return{kind:"card",name:name,pan:pan,exp:exp,cvv:code,limit_minor:minor,currency:cur,
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
var button=form.querySelector("button");var idle=button?button.textContent:"";
if(button){button.disabled=true;button.textContent="Encrypting\\u2026"}
seal(JSON.stringify(value)).then(function(blob){
var inputs=form.querySelectorAll("input[name^=f]");
for(var i=0;i<inputs.length;i++){inputs[i].value="";inputs[i].checked=false;inputs[i].disabled=true}
out.value=blob;sealed=true;form.submit()},function(){
if(button){button.disabled=false;button.textContent=idle}
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

/** Who asked and where "Back to chat" goes. Both optional, both already validated upstream. */
export interface View {
  agent: string | null
  returnUrl: string | null
}

/**
 * A page with no form: every success, every refusal, every error.
 *
 * The caller only ever passes one of `app.ts`'s own copy strings. With no `view` nothing from
 * the request reaches here, which is what keeps the fixed 400 page fixed. With one, the return
 * URL is re-validated by the shared renderer and a 200 sends the person back after two seconds.
 */
export async function message(
  status: number,
  title: string,
  text: string,
  extra: Record<string, string> = {},
  view?: View,
): Promise<Response> {
  const html = resultHtml({
    tone: status === 200 ? "ok" : status === 404 ? "info" : "bad",
    title,
    text,
    returnUrl: view?.returnUrl,
    redirect: status === 200,
  })
  return new Response(html, {
    status,
    headers: {
      ...baseHeaders(),
      ...extra,
      "Content-Type": "text/html; charset=utf-8",
      "Content-Security-Policy": await staticCsp(),
    },
  })
}

export interface FormPage {
  title: string
  intro: string
  kind: "password" | "card" | "cvv" | "secret"
  /** A secret's fixed label, shown read only. Never a value from the stored item. */
  label?: string
  jti: string
  sealKey: string
  action: string
  submit: string
  note: string
  /** `your agent` or the name; escaped here. */
  agent: string
  returnUrl: string | null
}

const field = (label: string, input: string, wrap = "") =>
  `<label class="f${wrap}"><span>${label}</span>${input}</label>`

/**
 * The form pages. Four of them, one function, because they differ only in their fields.
 *
 * Field names are `f1`..`f11` rather than `cardnumber` or `cvv`. A password manager, a browser
 * autofill heuristic or a crash reporter that recognises a field by name is one more thing
 * holding the value, and none of them are needed here: the owner is typing it on purpose.
 * `autocomplete="off"` says the same thing to the browsers that honour it.
 */
export async function form(page: FormPage): Promise<Response> {
  const text = (name: string, extra = "") =>
    `<input name="${name}" type="text" autocomplete="off"${extra}>`
  const fields = page.kind === "password"
    ? field(
      "Username or email",
      text("f1", ` autocapitalize="none" spellcheck="false"`),
    ) +
      field("Password", `<input name="f2" type="password" autocomplete="off" required>`)
    : page.kind === "secret"
    ? (page.label
      ? field(
        "For",
        `<input name="f1" type="text" value="${
          escapeHtml(page.label)
        }" readonly autocomplete="off" spellcheck="false">`,
      )
      : field(
        "What is it for",
        text("f1", ` autocapitalize="none" spellcheck="false" required`),
      )) +
      field(
        "Key",
        `<input name="f2" type="password" autocomplete="off" autocapitalize="none" spellcheck="false" required>`,
      )
    : page.kind === "card"
    ? `<h2 class="sec">Card</h2>` +
      field("Name on the card", text("f1", ` spellcheck="false" required`)) +
      field("Card number", text("f2", ` inputmode="numeric" required`)) +
      `<div class="row">${
        field(
          "Expiry",
          text("f3", ` inputmode="numeric" placeholder="MM/YY" maxlength="5" required`),
        )
      }${
        field("Security code", text("f11", ` inputmode="numeric" maxlength="4" required`))
      }</div>` +
      `<h2 class="sec">Billing address</h2>` +
      field("Address", text("f4")) +
      `<div class="row">${field("City", text("f5"))}${
        field("Postcode", text("f6", " required"))
      }</div>` +
      field("Country", text("f7")) +
      `<h2 class="sec">Spending limit</h2>` +
      `<div class="row">${field("Amount", text("f9", ` inputmode="decimal" required`))}${
        field("Currency", text("f10", ` value="USD" maxlength="3" spellcheck="false" required`))
      }</div>`
    : field(
      "Security code",
      text("f1", ` inputmode="numeric" required autofocus`),
    )

  const back = parseReturnUrl(page.returnUrl)
  const body = `<p class="eyebrow">Requested by ${escapeHtml(page.agent)}</p>
<h1>${escapeHtml(page.title)}</h1><p>${escapeHtml(page.intro)}</p>
<form id="f" method="post" action="${escapeHtml(page.action)}" autocomplete="off"
data-kind="${page.kind}" data-jti="${escapeHtml(page.jti)}" data-key="${escapeHtml(page.sealKey)}">
${fields}
<p class="err" id="e" role="alert"></p>
<input type="hidden" name="c" id="c"><input type="hidden" name="j" value="${escapeHtml(page.jti)}">
<button class="btn btn-primary" type="submit">${escapeHtml(page.submit)}</button></form>${
    back ? `<a class="cancel" href="${escapeHtml(back)}">Cancel</a>` : ""
  }
<p class="note">${escapeHtml(page.note)}</p>`

  return new Response(
    shell({ title: page.title, body, footer: FOOTER_ENCRYPTED, script: SCRIPT }),
    {
      status: 200,
      headers: {
        ...baseHeaders(),
        "Content-Type": "text/html; charset=utf-8",
        "Content-Security-Policy": await scriptCsp(),
      },
    },
  )
}
