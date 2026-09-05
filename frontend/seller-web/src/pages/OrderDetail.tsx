import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { fetchMyOrders, type SellerOrderSummary } from '../lib/api';

/** Full history, one tap WhatsApp button to the buyer (56.1's #2 screen). No real buyer-phone field exists on an order anywhere in this project (buyer_email is the only real contact captured at checkout, Week 4) - the WhatsApp link degrades honestly to a mailto: rather than a fabricated wa.me link with no real number behind it. */
export default function OrderDetail() {
  const { orderId } = useParams<{ orderId: string }>();
  const { t } = useTranslation();
  const [order, setOrder] = useState<SellerOrderSummary | null>(null);

  useEffect(() => {
    void fetchMyOrders().then((orders) => {
      setOrder(orders.find((o) => o.orderId === orderId) ?? null);
    });
  }, [orderId]);

  if (!order) return <p className="status">{t('loading')}…</p>;

  return (
    <div className="screen">
      <h1>{t('orderDetail')}</h1>
      <p><strong>{order.buyerEmail}</strong></p>
      <p>Status: {order.orderStatus} / {order.shipmentStatus}</p>
      <a className="big-button" href={`mailto:${order.buyerEmail}`}>
        Contact buyer
      </a>
      <h2>Items</h2>
      <ul>
        {order.items.map((item) => (
          <li key={item.listingId}>
            {item.productName} × {item.quantity} — ₹{item.lineTotal}
          </li>
        ))}
      </ul>
    </div>
  );
}
