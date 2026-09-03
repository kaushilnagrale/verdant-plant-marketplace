import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { dispatchOrder, myOrders, packOrder } from "../api/seller";

/**
 * The runbook describes two seller screens: "today's orders, and mark
 * packed then dispatched." Implemented as one screen with inline actions
 * per order rather than a separate detail page - the action IS "today's
 * orders" plus a couple of buttons, and a sub-navigation just to reach the
 * same two buttons would add a click without adding anything real.
 */
export function SellerOrdersPage() {
  const queryClient = useQueryClient();
  const hasToken = !!localStorage.getItem("verdant_seller_token");

  const { data, isLoading, isError } = useQuery({
    queryKey: ["seller-orders"],
    queryFn: myOrders,
    enabled: hasToken,
  });

  async function handlePack(orderId: string) {
    await packOrder(orderId);
    queryClient.invalidateQueries({ queryKey: ["seller-orders"] });
  }

  async function handleDispatch(orderId: string) {
    await dispatchOrder(orderId);
    queryClient.invalidateQueries({ queryKey: ["seller-orders"] });
  }

  if (!hasToken) {
    return (
      <p>
        Not signed in. <Link to="/seller/login">Paste a seller token</Link> first.
      </p>
    );
  }
  if (isLoading) return <p>Loading orders…</p>;
  if (isError) return <p className="error">Could not load orders - your token may have expired.</p>;
  if (!data || data.length === 0) return <p>No orders yet.</p>;

  return (
    <div>
      <h1>Today's orders</h1>
      {data.map((order) => (
        <div key={order.orderId} className="order-card">
          <div className="order-card-header">
            <span>
              Order <code>{order.orderId}</code>
            </span>
            <span className={`badge badge-${order.orderStatus.toLowerCase()}`}>{order.orderStatus}</span>
          </div>
          <div>Buyer: {order.buyerEmail}</div>
          <ul>
            {order.items.map((item) => (
              <li key={item.listingId}>
                {item.productName} × {item.quantity} - ₹{item.lineTotal.toFixed(2)}
              </li>
            ))}
          </ul>
          <div className="order-card-footer">
            <span>Shipment: {order.shipmentStatus ?? "not yet booked"}</span>
            {order.shipmentStatus === "BOOKED" && <button onClick={() => handlePack(order.orderId)}>Mark packed</button>}
            {order.shipmentStatus === "PACKED" && (
              <button onClick={() => handleDispatch(order.orderId)}>Mark dispatched</button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}
