import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { myOrders } from "../api/orders";
import type { BuyerOrderSummary } from "../types";

export function OrdersPage() {
  const [orders, setOrders] = useState<BuyerOrderSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    myOrders()
      .then(setOrders)
      .catch(() => setError("Could not load your orders."));
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (orders === null) return <p>Loading your orders...</p>;
  if (orders.length === 0) return <p>You haven't placed any orders yet.</p>;

  return (
    <div>
      <h1>Your orders</h1>
      <ul className="order-list">
        {orders.map((order) => (
          <li key={order.orderId} className="order-list-item">
            <Link to={`/orders/${order.orderId}`}>
              <div className="order-list-header">
                <span>Order {order.orderId.slice(0, 8)}...</span>
                <span className={`status-pill status-${order.status.toLowerCase()}`}>{order.status}</span>
              </div>
              <div className="order-list-meta">
                {new Date(order.createdAt).toLocaleDateString()} · ₹{order.totalAmount.toFixed(2)} ·{" "}
                {order.items.reduce((sum, i) => sum + i.quantity, 0)} item(s)
              </div>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
