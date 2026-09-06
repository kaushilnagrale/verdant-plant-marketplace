import { useParams, Link } from "react-router-dom";

export function ConfirmationPage() {
  const { orderId } = useParams<{ orderId: string }>();

  return (
    <div className="confirmation">
      <h1>🌱 Order placed!</h1>
      <p>
        Your order <code>{orderId}</code> is confirmed and paid.
      </p>
      <p>
        The seller will pack and dispatch it shortly - you'll see it move through the pipeline the same way it was
        built this week: reservation → payment → settlement → shipment, all handled automatically.
      </p>
      <p>
        <Link to={`/orders/${orderId}`}>Track this order</Link>
      </p>
      <Link to="/">Keep browsing</Link>
    </div>
  );
}
