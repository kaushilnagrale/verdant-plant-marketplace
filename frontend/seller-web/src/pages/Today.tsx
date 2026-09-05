import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { fetchMyOrders, type SellerOrderSummary } from '../lib/api';
import { enqueueAction, pendingCount } from '../lib/queue';

/**
 * "Today" - task shaped, one screen, large touch targets (56.1's own
 * priority #1). Real orders from GET /v1/seller/orders, grouped by what
 * actually needs doing right now rather than a flat list - the whole
 * point of a task-shaped screen over a generic order list.
 */
export default function Today() {
  const { t } = useTranslation();
  const [orders, setOrders] = useState<SellerOrderSummary[]>([]);
  const [pending, setPending] = useState(0);
  const [loading, setLoading] = useState(true);

  async function load() {
    setLoading(true);
    try {
      const data = await fetchMyOrders();
      setOrders(data);
    } finally {
      setLoading(false);
    }
    setPending(await pendingCount());
  }

  useEffect(() => {
    void load();
  }, []);

  async function act(orderId: string, action: 'pack' | 'dispatch') {
    await enqueueAction({
      endpoint: `/v1/seller/orders/${orderId}/${action}`,
      method: 'POST',
      body: {},
      idempotencyKey: `${action}:${orderId}`,
    });
    // Optimistic - the real state will reconcile on the next load() once
    // the queued action actually flushes (immediately if online now).
    await load();
  }

  if (loading) return <p className="status">{t('loading')}…</p>;

  const toPack = orders.filter((o) => o.shipmentStatus === 'BOOKED');
  const toDispatch = orders.filter((o) => o.shipmentStatus === 'PACKED');

  return (
    <div className="screen">
      <h1>{t('today')}</h1>
      {pending > 0 && <p className="pending-badge">{pending} {t('pendingSync')}</p>}

      <section>
        <h2>{t('toPack')} ({toPack.length})</h2>
        {toPack.map((o) => (
          <div className="order-card" key={o.orderId}>
            <Link to={`/orders/${o.orderId}`}>{o.buyerEmail}</Link>
            <button className="big-button" onClick={() => act(o.orderId, 'pack')}>
              {t('markPacked')}
            </button>
          </div>
        ))}
      </section>

      <section>
        <h2>{t('toDispatch')} ({toDispatch.length})</h2>
        {toDispatch.map((o) => (
          <div className="order-card" key={o.orderId}>
            <Link to={`/orders/${o.orderId}`}>{o.buyerEmail}</Link>
            <button className="big-button" onClick={() => act(o.orderId, 'dispatch')}>
              {t('markDispatched')}
            </button>
          </div>
        ))}
      </section>
    </div>
  );
}
