import { assertEquals } from "jsr:@std/assert"
import { maskEmail, maskPasskeyId, maskSessionId, maskUserId } from "../utils/logging.ts"

Deno.test("email log masks are bounded and single-line", () => {
  assertEquals(maskEmail('victim@example.com'), 'v***@example.com')
  assertEquals(maskEmail('a@x.com'), '***@x.com')
  assertEquals(maskEmail('@x.com'), '***@x.com')
  assertEquals(maskEmail('missing-at'), '***')
  assertEquals(maskEmail('a@'), '***')
  assertEquals(maskEmail(''), '***')
  assertEquals(maskEmail(null), '***')
  assertEquals(maskEmail(123), '***')
  assertEquals(maskEmail('victim@x.com\nforged'), 'v***@x.com_forged')
  assertEquals(maskEmail(`victim@${'x'.repeat(200)}`), `v***@${'x'.repeat(100)}`)
})

Deno.test("identifier log masks do not disclose short or control-character prefixes", () => {
  assertEquals(maskUserId('user-456'), 'user…')
  assertEquals(maskUserId('abcd'), '***')
  assertEquals(maskUserId(undefined), '***')
  assertEquals(maskUserId(123), '***')
  assertEquals(maskUserId('\nabcde'), '_abc…')
  assertEquals(maskPasskeyId('passkey-secret-123'), 'pass…')

  assertEquals(maskSessionId('session-secret-123'), 'sess…')
  assertEquals(maskSessionId('abc'), '***')
  assertEquals(maskSessionId(null), 'none')
  assertEquals(maskSessionId(undefined), 'none')
  assertEquals(maskSessionId('\n\n\n\nx'), '____…')
})
