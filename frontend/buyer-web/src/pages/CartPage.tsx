import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useCart } from "../context/CartContext";
import { checkout, previewCart, verifyPayment } from "../api/cart";
import type { CartPreviewResponse } from "../types";

declare global {
  interface Window {
    Razorpay: new (options: Record<string, unknown>) => { open: () => void };
  }
}

export function CartPage() {
  const { items, removeItem, clear } = useCart();
  const navigate = useNavigate();

  const [pincode, setPincode] = useState("");
  const [email, setEmail] = useState("");
  const [preview, setPreview] = useState<CartPreviewResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const subtotal = items.reduce((sum, i) => sum + i.priceAmount * i.quantity, 0);

  async function handlePreview() {
    if (pincode.length !== 6) {
      setError("Enter a valid 6-digit pincode.");
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const result = await previewCart(
        items.map((i) => ({ listingId: i.listingId, quantity: i.quantity })),
        pincode,
      );
      setPreview(result);
    } catch {
      setError("Could not preview shipping - is the destination pincode serviceable?");
    } finally {
      setLoading(false);
    }
  }

  async function handlePlaceOrder() {
    if (!email) {
      setError("Enter your email to place the order.");
      return;
    }
    if (pincode.length !== 6) {
      setError("Enter a valid 6-digit delivery pincode before placing the order.");
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const result = await checkout(
        email,
        items.map((i) => ({ listingId: i.listingId, quantity: i.quantity })),
        pincode,
      );

      // The buyer's browser talks to Razorpay directly with the public key
      // - the card/UPI details never touch our server. See PaymentGateway /
      // Day 23 for why: this keeps the platform out of PCI-DSS card scope.
      const razorpay = new window.Razorpay({
        key: result.razorpayKeyId,
        amount: Math.round(result.totalAmount.amount * 100),
        currency: result.totalAmount.currency,
        name: "Verdant",
        description: "Live plant order",
        order_id: result.razorpayOrderId,
        prefill: { email },
        handler: async (response: {
          razorpay_order_id: string;
          razorpay_payment_id: string;
          razorpay_signature: string;
        }) => {
          try {
            await verifyPayment(
              result.orderId,
              response.razorpay_order_id,
              response.razorpay_payment_id,
              response.razorpay_signature,
            );
            clear();
            navigate(`/confirmation/${result.orderId}`);
          } catch {
            setError("Payment succeeded but verification failed - contact support with your order id: " + result.orderId);
          }
        },
        modal: {
          ondismiss: () => setError("Checkout was closed before payment completed."),
        },
      });
      razorpay.open();
    } catch {
      setError("Could not start checkout - an item may be out of stock.");
    } finally {
      setLoading(false);
    }
  }

  if (items.length === 0) {
    return <p>Your cart is empty. Go find something green.</p>;
  }

  return (
    <div>
      <h1>Your cart</h1>

      <table className="cart-table">
        <tbody>
          {items.map((item) => (
            <tr key={item.listingId}>
              <td>{item.title}</td>
              <td>× {item.quantity}</td>
              <td>₹{(item.priceAmount * item.quantity).toFixed(2)}</td>
              <td>
                <button className="link-button" onClick={() => removeItem(item.listingId)}>
                  Remove
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p>
        <strong>Subtotal: ₹{subtotal.toFixed(2)}</strong>
      </p>

      <div className="preview-panel">
        <label>
          Delivery pincode{" "}
          <input value={pincode} onChange={(e) => setPincode(e.target.value.trim())} maxLength={6} placeholder="560001" />
        </label>
        <button onClick={handlePreview} disabled={loading}>
          Preview shipping split
        </button>
      </div>

      {/* The buyer sees the split before paying, never after - see AllocationEngine's own doc comment. */}
      {preview && (
        <div className="split-preview">
          <h2>Shipment split</h2>
          {preview.shipments.map((shipment) => (
            <div key={shipment.sellerId} className="shipment-card">
              <div>
                Arrives in <strong>{shipment.transitDays} day(s)</strong> (est. {shipment.estimatedDelivery}) - ₹
                {shipment.shippingCost.amount.toFixed(2)} shipping
              </div>
              <ul>
                {shipment.lines.map((line) => (
                  <li key={line.listingId}>
                    {line.productName} × {line.quantity}
                  </li>
                ))}
              </ul>
            </div>
          ))}
          {preview.unfulfillable.length > 0 && (
            <div className="warning">
              <strong>Some items can't be delivered here:</strong>
              <ul>
                {preview.unfulfillable.map((u) => (
                  <li key={u.listingId}>
                    {u.productName} - {u.reason}
                  </li>
                ))}
              </ul>
            </div>
          )}
          <p>
            <strong>Total shipping: ₹{preview.totalShippingCost.amount.toFixed(2)}</strong>
          </p>
        </div>
      )}

      <div className="checkout-panel">
        <label>
          Email{" "}
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" />
        </label>
        <button onClick={handlePlaceOrder} disabled={loading}>
          Place order &amp; pay
        </button>
      </div>

      {error && <p className="error">{error}</p>}
    </div>
  );
}
