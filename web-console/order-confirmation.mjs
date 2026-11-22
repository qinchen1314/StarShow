import { bindConfirmationPage } from './order-confirmation-page.mjs';

export const SLOW_AFTER = 30_000;
export const MANUAL_AFTER = 120_000;

export function confirmationStage(elapsedMs) {
  if (elapsedMs >= MANUAL_AFTER) return 'manual';
  if (elapsedMs >= SLOW_AFTER) return 'slow';
  return 'initial';
}

export function pollDelay(elapsedMs, attempt, retryAfterMs, random = Math.random) {
  const stage = confirmationStage(elapsedMs);
  if (stage === 'manual') return null;
  const initialDelays = [1_000, 2_000, 4_000, 5_000];
  const delay = stage === 'slow' ? 15_000 : initialDelays[Math.min(attempt, 3)];
  return Math.max(delay, Number(retryAfterMs) || 0) + Math.floor(random() * 250);
}

export function paymentClock(order, payment, monotonicNow) {
  if (order?.status !== 'WAIT_PAY' || order.nextAction !== 'PAY') return null;
  if (payment && payment.nextAction !== 'PAY') return null;
  const source = payment ?? order;
  const deadline = payment ? payment.paymentExpireTime : order.paymentCreateDeadline;
  const duration = Date.parse(deadline) - Date.parse(source.serverTime);
  if (!Number.isFinite(duration)) return null;
  return Math.max(0, duration - Math.max(0, monotonicNow - source.receivedAt));
}

if (typeof document !== 'undefined') {
  bindConfirmationPage({ confirmationStage, pollDelay, paymentClock, SLOW_AFTER, MANUAL_AFTER });
}
