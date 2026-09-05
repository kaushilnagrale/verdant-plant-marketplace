import 'fake-indexeddb/auto';
import { describe, it, expect, vi, beforeEach } from 'vitest';

// api is mocked BEFORE importing queue.ts, which imports it at module
// load time - the real thing this test is about is queue.ts's own
// retry/idempotency decisions, not a real HTTP call.
vi.mock('./api', () => ({ api: { post: vi.fn() } }));

import { api } from './api';
import { enqueueAction, pendingCount } from './queue';

const post = api.post as unknown as ReturnType<typeof vi.fn>;

/**
 * enqueueAction() fires its own internal flush() without awaiting it (by
 * design - adding an action must never block on the network), so there
 * is no single promise a test can await to know "settled". Same real
 * "poll until it reaches the expected state" idiom this whole project's
 * own backend tests already use for a genuinely async pipeline
 * (ProjectionTest.awaitIndexed() et al.), not a sleep.
 */
async function waitUntil(predicate: () => Promise<boolean>, timeoutMs = 2000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((r) => setTimeout(r, 10));
  }
  throw new Error('waitUntil timed out');
}

describe('offline action queue', () => {
  beforeEach(() => {
    post.mockReset();
    Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
  });

  it('a permanent failure (4xx) is dropped, not retried forever', async () => {
    post.mockRejectedValue({ response: { status: 409 } });

    await enqueueAction({ endpoint: '/v1/seller/orders/x/dispatch', method: 'POST', body: {}, idempotencyKey: 'k1' });

    await waitUntil(async () => (await pendingCount()) === 0);
  });

  it('a network/5xx failure stays queued for the next flush, not dropped', async () => {
    post.mockRejectedValue(new Error('network error'));

    await enqueueAction({ endpoint: '/v1/seller/orders/y/pack', method: 'POST', body: {}, idempotencyKey: 'k2' });

    await waitUntil(async () => post.mock.calls.length >= 1);
    expect(await pendingCount()).toBe(1);
  });

  it('a real send success clears the action, sent with its idempotency key as a header', async () => {
    post.mockResolvedValue({ status: 200, data: {} });

    await enqueueAction({ endpoint: '/v1/seller/orders/z/pack', method: 'POST', body: {}, idempotencyKey: 'k3' });

    await waitUntil(async () => (await pendingCount()) === 0);
    expect(post).toHaveBeenCalledWith('/v1/seller/orders/z/pack', {},
      expect.objectContaining({ headers: { 'Idempotency-Key': 'k3' } }));
  });

  it('while offline, nothing is sent at all - actions stay queued', async () => {
    Object.defineProperty(navigator, 'onLine', { value: false, configurable: true });

    await enqueueAction({ endpoint: '/v1/seller/orders/w/pack', method: 'POST', body: {}, idempotencyKey: 'k4' });
    await new Promise((r) => setTimeout(r, 50)); // give any (wrongly) in-flight flush a chance to misbehave

    expect(post).not.toHaveBeenCalled();
    expect(await pendingCount()).toBe(1);
  });
});
