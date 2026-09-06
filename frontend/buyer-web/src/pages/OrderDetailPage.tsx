import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { orderDetail } from "../api/orders";
import type { BuyerOrderDetail } from "../types";

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const [order, setOrder] = useState<BuyerOrderDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!orderId) return;
    orderDetail(orderId)
      .then(setOrder)
      .catch(() => setError("Could not load this order - it may not exist, or belong to a different account."));
  }, [orderId]);

  if (error) return <p className="error">{error}</p>;
  if (order === null) return <p>Loading order...</p>;

  return (
    <div>
      <h1>Order {order.orderId}</h1>
      <p>
        Placed {new Date(order.createdAt).toLocaleString()} ·{" "}
        <span className={`status-pill status-${order.status.toLowerCase()}`}>{order.status}</span>
      </p>

      <h2>Delivering to</h2>
      <address>
        {order.recipientName}
        <br />
        {order.addressLine1}
        {order.addressLine2 ? <>, {order.addressLine2}</> : null}
        <br />
        {order.city}, {order.state} - {order.pincode}
        <br />
        Phone: {order.recipientPhone}
      </address>

      <h2>Items</h2>
      <table className="cart-table">
        <tbody>
          {order.items.map((item) => (
            <tr key={item.listingId}>
              <td>{item.productName}</td>
              <td>sold by {item.sellerBusinessName}</td>
              <td>× {item.quantity}</td>
              <td>₹{item.lineTotal.toFixed(2)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p>
        <strong>Total: ₹{order.totalAmount.toFixed(2)}</strong>
      </p>

      <h2>Tracking</h2>
      {order.shipments.length === 0 && <p>No shipment has been booked for this order yet.</p>}
      {order.shipments.map((shipment) => (
        <div key={shipment.shipmentId} className="shipment-card">
          <div>
            <strong>{shipment.sellerBusinessName}</strong> - <span className={`status-pill status-${shipment.status.toLowerCase()}`}>{shipment.status}</span>
          </div>
          {shipment.courier && (
            <div>
              {shipment.courier}
              {shipment.awbNumber ? ` · AWB ${shipment.awbNumber}` : ""}
            </div>
          )}
          {shipment.deliveredAt && <div>Delivered {new Date(shipment.deliveredAt).toLocaleString()}</div>}
          {shipment.events.length > 0 && (
            <ul className="tracking-timeline">
              {shipment.events.map((event, i) => (
                <li key={i}>
                  <strong>{event.status}</strong> - {new Date(event.occurredAt).toLocaleString()}
                  {event.location ? ` (${event.location})` : ""}
                  {event.remark ? ` - ${event.remark}` : ""}
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
    </div>
  );
}
