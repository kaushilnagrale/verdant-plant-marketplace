import Dexie, { type EntityTable } from 'dexie';
import { api } from './api';

// Indian mobile networks drop. A dispatch action that silently fails
// because a tunnel flickered means an order sits unshipped (56.2's own
// framing) - every action queues in IndexedDB first and is retried when
// connectivity returns.
//
// A retried pack/dispatch is already safe without the server reading the
// Idempotency-Key header sent below at all: SellerOrderService's own
// markPacked()/markDispatched() (Week 10) are guarded state transitions
// (WHERE status = 'BOOKED'/'PACKED') - a genuine retry after the first
// call already succeeded finds the status has moved on and returns a
// real 409, not a silent no-op 200. isPermanent() below already treats
// any 4xx (409 included) as "stop retrying" - which for THESE two
// actions specifically means "already applied", not "this failed". The
// header is still sent for a real, honest reason: a future write this
// queue also carries that ISN'T naturally idempotent this way (arbitrary
// stock deltas, say) would need a server that actually reads it, and
// this is where that would plug in - not a currently-load-bearing check.

export interface SellerAction {
  id?: number;
  endpoint: string;
  method: 'POST';
  body: unknown;
  idempotencyKey: string;
  attempts: number;
  createdAt: number;
}

const db = new Dexie('verdant-seller-queue') as Dexie & {
  pendingActions: EntityTable<SellerAction, 'id'>;
};
db.version(1).stores({
  pendingActions: '++id, idempotencyKey, createdAt',
});

export async function enqueueAction(action: Omit<SellerAction, 'id' | 'attempts' | 'createdAt'>): Promise<void> {
  await db.pendingActions.add({ ...action, attempts: 0, createdAt: Date.now() });
  void flush();
}

/** Permanent failures (4xx - the request itself was wrong) are dropped, not retried forever; anything else (network error, 5xx) is left queued for the next flush. */
function isPermanent(error: unknown): boolean {
  const status = (error as { response?: { status?: number } })?.response?.status;
  return status !== undefined && status >= 400 && status < 500;
}

// enqueueAction() below fires flush() without awaiting it (so adding an
// action never blocks on the network), and the 'online' listener does
// the same - real, found by testing this directly rather than assumed:
// two overlapping flush() calls with no guard both read the same
// still-pending action before either deletes it, sending it twice. This
// is the exact same "claim before acting" shape as SearchReindexQueueRepository's
// own SKIP LOCKED claim (Week 9), just a single in-memory flag instead of
// a real DB lock, since this queue only ever runs in one browser tab's
// own JS thread.
let flushing = false;

export async function flush(): Promise<void> {
  if (!navigator.onLine || flushing) return;
  flushing = true;
  try {
    const actions = await db.pendingActions.orderBy('createdAt').toArray();
    for (const action of actions) {
      try {
        await api.post(action.endpoint, action.body, {
          headers: { 'Idempotency-Key': action.idempotencyKey },
        });
        await db.pendingActions.delete(action.id!);
      } catch (e) {
        if (isPermanent(e)) {
          await db.pendingActions.delete(action.id!);
        } else {
          await db.pendingActions.update(action.id!, { attempts: action.attempts + 1 });
        }
      }
    }
  } finally {
    flushing = false;
  }
}

export async function pendingCount(): Promise<number> {
  return db.pendingActions.count();
}

window.addEventListener('online', () => void flush());
