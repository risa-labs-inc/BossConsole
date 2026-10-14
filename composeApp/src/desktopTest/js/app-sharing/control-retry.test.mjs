import test from 'node:test';
import assert from 'node:assert/strict';
import { acquireControlWithRetry } from '../../../desktopMain/resources/app-sharing/control-retry.mjs';

test('temporary control failures recover automatically; exhausted retries and busy leases are bounded', async () => {
  for (const status of [503, 409]) {
    for (const failures of [2, 10]) {
      let attempts = 0;
      const delays = [], shown = [], controller = new AbortController();
      const result = await acquireControlWithRetry(async () => {
        attempts++;
        if (attempts <= failures) throw Object.assign(new Error('Unavailable'), { status });
      }, { signal: controller.signal, onAttempt: number => shown.push(number), wait: async ms => delays.push(ms) });
      assert.equal(attempts, 3);
      assert.deepEqual(shown, [1, 2, 3]); assert.deepEqual(delays, [1000, 2000]);
      assert.deepEqual(result, failures === 2 ? { connected: true } : { kind: status === 409 ? 'busy' : 'temporary', attempts: 3 });
    }
  }
});

test('denied permissions and invalid protocol responses are not retried', async () => {
  for (const status of [401, 403, 400]) {
    let attempts = 0;
    const result = await acquireControlWithRetry(async () => {
      attempts++; throw Object.assign(new Error('Refused'), { status });
    }, { signal: new AbortController().signal, wait: async () => assert.fail('must not retry') });
    assert.equal(attempts, 1); assert.equal(result.kind, status === 400 ? 'unavailable' : 'denied');
  }
});

test('release, disconnect or navigation cancels a pending retry and retires a late result', async () => {
  const controller = new AbortController(); let attempts = 0;
  const result = await acquireControlWithRetry(async () => {
    attempts++; throw new TypeError('Network unavailable');
  }, { signal: controller.signal, wait: async () => controller.abort() });
  assert.equal(attempts, 1); assert.deepEqual(result, { cancelled: true });
  const pending = new AbortController(); let complete;
  const acquiring = acquireControlWithRetry(() => new Promise(resolve => { complete = resolve; }), { signal: pending.signal });
  pending.abort(); complete(); assert.deepEqual(await acquiring, { cancelled: true });
});
