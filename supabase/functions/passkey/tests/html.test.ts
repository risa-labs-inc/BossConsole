import { assert, assertEquals } from "jsr:@std/assert";
import {
  getMobileAuthenticationHTML,
  getMobileErrorHTML,
  getMobileRegistrationHTML,
} from "../utils/html.ts";

const inputs = [
  "normal@example.com",
  "o'connor@example.com",
  "</script><script>globalThis.compromised = true</script><img src=x onerror=alert(1)>",
  "'\\\n\r\u2028\u2029",
  "$& $` $' {{ANON_KEY_JS}} {{EMAIL_HTML}}",
];

function scriptValues(html: string): Record<string, unknown> {
  const blocks = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
  assertEquals(blocks.length, 1, "input must not create additional scripts");
  const source = blocks[0]?.[1];
  assert(source);
  const names = ["challenge", "userId", "email", "sessionId", "rpId", "rpName", "credentialId"]
    .filter((name) => source.includes(`const ${name} =`));
  const initialize = new Function("document", `${source}\nreturn {${names.join(",")}};`);
  // Exercise the generated declarations without triggering a browser ceremony.
  return initialize({
    getElementById: () => ({ addEventListener() {} }),
    addEventListener() {},
  });
}

for (const value of inputs) {
  Deno.test(`registration preserves values without generating markup: ${JSON.stringify(value)}`, async () => {
    const html = await getMobileRegistrationHTML(value, value, value, value, value, value);
    const values = scriptValues(html);
    for (const key of ["challenge", "userId", "email", "sessionId", "rpId", "rpName"]) {
      assertEquals(values[key], value);
    }
    assert(!html.includes("<img src=x"));
    assert(!html.includes("<script>globalThis.compromised"));
  });

  Deno.test(`authentication preserves values without generating markup: ${JSON.stringify(value)}`, async () => {
    const html = await getMobileAuthenticationHTML(value, value, value, value, value, value, 0);
    const values = scriptValues(html);
    for (const key of ["challenge", "email", "sessionId", "rpId", "credentialId"]) {
      assertEquals(values[key], value);
    }
    assert(!html.includes("<img src=x"));
    assert(!html.includes("<script>globalThis.compromised"));
  });
}

Deno.test("error page displays untrusted messages as text", async () => {
  const html = await getMobileErrorHTML('<img src=x onerror="alert(1)"> & $& {{MESSAGE}}');
  assert(html.includes('&lt;img src=x onerror=&quot;alert(1)&quot;&gt; &amp; $&amp; {{MESSAGE}}'));
  assert(!html.includes("<img"));
});
