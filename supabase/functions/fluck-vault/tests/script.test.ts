/**
 * The page's own inline script, executed.
 *
 * Run: cd supabase/functions/fluck-vault && deno task test
 *
 * `seal.test.ts` proves the SCHEME is coherent by implementing both halves in TypeScript. That
 * leaves the one thing that actually runs on the owner's phone untested: the script string in
 * `page.ts`. A typo in it fails nowhere - it is a string, so `deno check` never looks at it -
 * and the symptom is a card typed at a checkout that silently never arrives.
 *
 * So these cases run the real `SCRIPT` through `new Function`, against a fake form with just
 * enough DOM for it, and decrypt what it produces with the recipient private key. A blob that
 * opens is proof that the browser half and the DGX half agree.
 */
import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import { SCRIPT } from "../page.ts"
import { SEAL_INFO_PREFIX, SEAL_PUBLIC_KEY_BYTES, SEAL_VERSION } from "../seal.ts"

const JTI = "11111111-2222-3333-4444-555555555555"

interface Field {
  value: string
  checked: boolean
  disabled: boolean
  listeners: Record<string, () => void>
  addEventListener(type: string, fn: () => void): void
}

interface Harness {
  submit: (values: Record<string, string | boolean>) => Promise<void>
  ciphertext: () => string
  error: () => string
  submitted: () => number
  enabledFields: () => string[]
  field: (name: string) => Field
}

/**
 * The smallest DOM the script touches.
 *
 * Deliberately minimal: anything it needs that is not here is a call the script makes that this
 * fake does not model, which would be a silent pass. The fake throws on an unknown selector for
 * the same reason.
 */
function page(kind: string, sealKey: string): Harness {
  const fields: Record<string, Field> = {}
  for (const name of ["f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10"]) {
    const listeners: Record<string, () => void> = {}
    fields[name] = {
      value: "",
      checked: false,
      disabled: false,
      listeners,
      addEventListener(type: string, fn: () => void) {
        listeners[type] = fn
      },
    }
  }
  const out = { value: "" }
  const err = { textContent: "" }
  const button = { disabled: false }
  let submitCount = 0
  let handler: ((event: { preventDefault(): void }) => void) | null = null
  let settle: (() => void) | null = null

  const form = {
    elements: fields,
    getAttribute(name: string): string {
      if (name === "data-kind") return kind
      if (name === "data-jti") return JTI
      if (name === "data-key") return sealKey
      throw new Error(`unexpected attribute ${name}`)
    },
    addEventListener(type: string, fn: (event: { preventDefault(): void }) => void) {
      assertEquals(type, "submit")
      handler = fn
    },
    querySelector(selector: string) {
      assertEquals(selector, "button")
      return button
    },
    querySelectorAll(selector: string) {
      assertEquals(selector, "input[name^=f]")
      return Object.values(fields)
    },
    submit() {
      submitCount++
      settle?.()
    },
  }

  const document = {
    getElementById(id: string) {
      if (id === "f") return form
      if (id === "c") return out
      if (id === "e") return err
      throw new Error(`unexpected id ${id}`)
    },
  }

  new Function("document", SCRIPT)(document)
  assert(handler, "the script registered no submit handler")

  return {
    field: (name: string) => fields[name],
    /**
     * Fill the form and press the button.
     *
     * Resolves when the script has either submitted or given up. The race against a timer is
     * what makes "it refused" testable at all: a validation failure returns synchronously and
     * nothing will ever settle, so waiting on the submit alone would hang the suite rather
     * than fail it.
     */
    submit(values) {
      for (const [name, value] of Object.entries(values)) {
        if (typeof value === "boolean") fields[name].checked = value
        else fields[name].value = value
      }
      let timer: number | ReturnType<typeof setTimeout> = 0
      const done = new Promise<void>((resolve) => {
        settle = () => {
          clearTimeout(timer)
          resolve()
        }
        timer = setTimeout(resolve, 250)
      })
      handler!({ preventDefault() {} })
      return done
    },
    ciphertext: () => out.value,
    error: () => err.textContent,
    submitted: () => submitCount,
    enabledFields: () =>
      Object.entries(fields).filter(([, f]) => !f.disabled).map(([name]) => name),
  }
}

async function recipient(): Promise<{ base64: string; privateKey: CryptoKey }> {
  const pair = await crypto.subtle.generateKey(
    { name: "ECDH", namedCurve: "P-256" },
    true,
    ["deriveBits"],
  ) as CryptoKeyPair
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey))
  return { base64: btoa(String.fromCharCode(...raw)), privateKey: pair.privateKey }
}

/** Same as `seal.test.ts`'s reference opener, which is what the DGX mirrors in JCA. */
async function open(privateKey: CryptoKey, jti: string, blob: string): Promise<string> {
  const bytes = Uint8Array.from(atob(blob), (c) => c.charCodeAt(0))
  assertEquals(bytes[0], SEAL_VERSION)
  const epk = bytes.slice(1, 1 + SEAL_PUBLIC_KEY_BYTES)
  const iv = bytes.slice(1 + SEAL_PUBLIC_KEY_BYTES, 1 + SEAL_PUBLIC_KEY_BYTES + 12)
  const ct = bytes.slice(1 + SEAL_PUBLIC_KEY_BYTES + 12)
  const theirs = await crypto.subtle.importKey(
    "raw",
    epk as BufferSource,
    { name: "ECDH", namedCurve: "P-256" },
    false,
    [],
  )
  const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: theirs }, privateKey, 256)
  const ikm = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveKey"])
  const key = await crypto.subtle.deriveKey(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: new Uint8Array(32) as BufferSource,
      info: new TextEncoder().encode(SEAL_INFO_PREFIX + jti) as BufferSource,
    },
    ikm,
    { name: "AES-GCM", length: 256 },
    false,
    ["decrypt"],
  )
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: iv as BufferSource, additionalData: new TextEncoder().encode(jti) },
    key,
    ct as BufferSource,
  )
  return new TextDecoder().decode(plain)
}

Deno.test("the page seals a card the DGX key opens", async () => {
  const { base64, privateKey } = await recipient()
  const harness = page("card", base64)
  await harness.submit({
    f1: "A Person",
    f2: "4111 1111 1111 1111",
    f3: "04/29",
    f4: "1 Street",
    f5: "Town",
    f6: "12345",
    f7: "US",
    f8: true,
    f9: "200",
    f10: "usd",
  })
  assertEquals(harness.submitted(), 1)
  assertEquals(JSON.parse(await open(privateKey, JTI, harness.ciphertext())), {
    kind: "card",
    name: "A Person",
    // Normalised: the owner types spaces and the DGX gets digits.
    pan: "4111111111111111",
    exp: "04/29",
    virtual: true,
    limit_minor: 20000,
    currency: "USD",
    billing: { line1: "1 Street", city: "Town", postal: "12345", country: "US" },
  })
})

Deno.test("a card with no virtual-card attestation and no limit is refused in the browser", async () => {
  const { base64 } = await recipient()

  const unattested = page("card", base64)
  await unattested.submit({
    f1: "A Person",
    f2: "4242424242424242",
    f3: "04/29",
    f6: "12345",
    f9: "200",
    f10: "USD",
  })
  assertEquals(unattested.submitted(), 0)
  assertStringIncludes(unattested.error(), "virtual card")

  const nolimit = page("card", base64)
  await nolimit.submit({
    f1: "A Person",
    f2: "4242424242424242",
    f3: "04/29",
    f6: "12345",
    f8: true,
    f10: "USD",
  })
  assertEquals(nolimit.submitted(), 0)
  assertStringIncludes(nolimit.error(), "spending limit")

  const badCurrency = page("card", base64)
  await badCurrency.submit({
    f1: "A Person",
    f2: "4242424242424242",
    f3: "04/29",
    f6: "12345",
    f8: true,
    f9: "200",
    f10: "dollars",
  })
  assertEquals(badCurrency.submitted(), 0)
  assertStringIncludes(badCurrency.error(), "three letters")
})

Deno.test("the page seals a password and a cvv", async () => {
  const { base64, privateKey } = await recipient()

  const password = page("password", base64)
  await password.submit({ f1: "someone@example.com", f2: "correct horse battery" })
  assertEquals(JSON.parse(await open(privateKey, JTI, password.ciphertext())), {
    kind: "password",
    username: "someone@example.com",
    password: "correct horse battery",
  })

  const cvv = page("cvv", base64)
  await cvv.submit({ f1: "123" })
  assertEquals(JSON.parse(await open(privateKey, JTI, cvv.ciphertext())), {
    kind: "cvv",
    cvv: "123",
  })
})

Deno.test("a card that fails Luhn is refused in the browser and never sent", async () => {
  const { base64 } = await recipient()
  const harness = page("card", base64)
  await harness.submit({ f1: "A Person", f2: "4111111111111112", f3: "04/29", f6: "12345" })
  assertEquals(harness.submitted(), 0)
  assertEquals(harness.ciphertext(), "")
  assertStringIncludes(harness.error(), "does not look right")
})

Deno.test("a malformed expiry and a short code are refused in the browser", async () => {
  const { base64 } = await recipient()
  const expiry = page("card", base64)
  await expiry.submit({ f1: "A Person", f2: "4111111111111111", f3: "2029-04", f6: "12345" })
  assertEquals(expiry.submitted(), 0)
  assertStringIncludes(expiry.error(), "MM/YY")

  const short = page("cvv", base64)
  await short.submit({ f1: "12" })
  assertEquals(short.submitted(), 0)
  assertStringIncludes(short.error(), "three or four")
})

Deno.test("the plaintext inputs are disabled before the form submits", async () => {
  const { base64 } = await recipient()
  const harness = page("cvv", base64)
  await harness.submit({ f1: "4321" })
  assertEquals(harness.submitted(), 1)
  // The browser serialises only ENABLED controls, so the POST carries the blob and nothing
  // else. This is what makes the server's plaintext refusal a second line of defence rather
  // than the only one.
  assertEquals(harness.enabledFields(), [])
  assert(harness.ciphertext().length > 0)
})

Deno.test("a page served a broken sealing key sends nothing and says so", async () => {
  const harness = page("cvv", btoa("not a p-256 point"))
  await harness.submit({ f1: "123" })
  assertEquals(harness.submitted(), 0)
  assertEquals(harness.ciphertext(), "")
  assertStringIncludes(harness.error(), "could not secure")
})

Deno.test("the expiry field formats itself as MM/YY while the owner types digits", async () => {
  const { base64 } = await recipient()
  const harness = page("card", base64)
  const f3 = harness.field("f3")
  for (const typed of ["0", "04", "042", "0429", "04295"]) {
    f3.value = typed
    f3.listeners["input"]()
  }
  assertEquals(f3.value, "04/29")
})

Deno.test("four bare digits are accepted as an expiry and a bad month is refused", async () => {
  const { base64, privateKey } = await recipient()
  const ok = page("card", base64)
  await ok.submit({
    f1: "A Person",
    f2: "4111111111111111",
    f3: "0429",
    f6: "12345",
    f8: true,
    f9: "200",
    f10: "usd",
  })
  assertEquals(ok.submitted(), 1)
  assertEquals(JSON.parse(await open(privateKey, JTI, ok.ciphertext())).exp, "04/29")
  const bad = page("card", base64)
  await bad.submit({
    f1: "A Person",
    f2: "4111111111111111",
    f3: "13/29",
    f6: "12345",
    f8: true,
    f9: "200",
    f10: "usd",
  })
  assertEquals(bad.submitted(), 0)
  assertStringIncludes(bad.error(), "01 to 12")
})
