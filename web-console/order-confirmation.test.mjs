import test from 'node:test';
import assert from 'node:assert/strict';
import { confirmationStage, paymentClock, pollDelay } from './order-confirmation.mjs';

test('confirmation changes stage exactly at 30 seconds and two minutes', () => {
  assert.deepEqual([29_999, 30_000, 30_001, 119_999, 120_000, 120_001].map(confirmationStage),
    ['initial', 'slow', 'slow', 'slow', 'manual', 'manual']);
  const jitter = () => 0.4;
  assert.deepEqual([0, 1, 2, 3, 4].map(attempt => pollDelay(0, attempt, '500', jitter)),
    [1_100, 2_100, 4_100, 5_100, 5_100]);
  assert.equal(pollDelay(29_999, 5, '7000', jitter), 7_100);
  assert.equal(pollDelay(30_000, 5, '500', jitter), 15_100);
  assert.equal(pollDelay(119_999, 5, '18000', jitter), 18_100);
  assert.equal(pollDelay(120_000, 5, '500', jitter), null);
});

test('only a persisted payable order starts a countdown and an existing payment supplies its deadline', () => {
  const order = { status: 'WAIT_PAY', nextAction: 'PAY', receivedAt: 10,
    serverTime: '2026-11-22T12:00:00.000+08:00', paymentCreateDeadline: '2026-11-22T12:00:30.000+08:00' };
  const payment = { nextAction: 'PAY', receivedAt: 40,
    serverTime: '2026-11-22T12:00:10.000+08:00', paymentExpireTime: '2026-11-22T12:00:15.000+08:00' };
  for (const status of ['PENDING_CONFIRM', 'SUBMITTING', 'UNKNOWN', 'PAID', 'CLOSED']) {
    assert.equal(paymentClock({ ...order, status }, payment, 40), null);
  }
  assert.equal(paymentClock({ ...order, nextAction: 'CHECK_PAYMENT' }, payment, 40), null);
  assert.equal(paymentClock(order, null, 10), 30_000);
  assert.equal(paymentClock(order, payment, 40), 5_000);
  assert.equal(paymentClock(order, payment, 5_039), 1);
  assert.equal(paymentClock(order, payment, 5_040), 0);
  assert.equal(paymentClock(order, payment, 5_041), 0);
});
