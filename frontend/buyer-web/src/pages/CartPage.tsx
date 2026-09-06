import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useCart } from "../context/CartContext";
import { useAuth } from "../context/AuthContext";
import { checkout, previewCart, verifyPayment } from "../api/cart";
import type { CartPreviewResponse } from "../types";

declare global {
  interface Window {
    Razorpay: new (options: Record<string, unknown>) => { open: () => void };
  }
}

export function CartPage() {
  const { items, removeItem, updateQuantity, clear } = useCart();
  const { buyer, loading: authLoading } = useAuth();
  const navigate = useNavigate();

  const [pincode, setPincode] = useState("");
  const [recipientName, setRecipientName] = useState("");
  const [recipientPhone, setRecipientPhone] = useState("");
  const [addressLine1, setAddressLine1] = useState("");
  const [addressLine2, setAddressLine2] = useState("");
  const [city, setCity] = useState("");
  const [state, setState] = useState("");
  const [preview, setPreview] = useState<CartPreviewResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const subtotal = items.reduce((sum, i) => sum + i.priceAmount * i.quantity, 0);

  // A quantity change invalidates any shipment-split preview already on
  // screen (it was computed for the old quantities) - clearing it rather
  // than leaving a stale split visible next to the new line totals.
  function changeQuantity(listingId: string, nextQuantity: number) {
    updateQuantity(listingId, nextQuantity);
    setPreview(null);
  }

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
    if (!buyer) {
      navigate("/login", { state: { from: "/cart" } });
      return;
    }
    if (pincode.length !== 6) {
      setError("Enter a valid 6-digit delivery pincode before placing the order.");
      return;
    }
    if (!recipientName || !recipientPhone || !addressLine1 || !city || !state) {
      setError("Fill in the full delivery address before placing the order.");
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const result = await checkout(
        buyer.email,
        items.map((i) => ({ listingId: i.listingId, quantity: i.quantity })),
        pincode,
        { recipientName, recipientPhone, addressLine1, addressLine2: addressLine2 || null, city, state },
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
        prefill: { email: buyer.email, contact: recipientPhone },
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
              <td>
                <div className="qty-stepper">
                  <button
                    type="button"
                    className="qty-button"
                    onClick={() => changeQuantity(item.listingId, item.quantity - 1)}
                    disabled={item.quantity <= 1}
                    aria-label={`Decrease quantity of ${item.title}`}
                  >
                    −
                  </button>
                  <span className="qty-value">{item.quantity}</span>
                  <button
                    type="button"
                    className="qty-button"
                    onClick={() => changeQuantity(item.listingId, item.quantity + 1)}
                    aria-label={`Increase quantity of ${item.title}`}
                  >
                    +
                  </button>
                </div>
              </td>
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

      {!authLoading && !buyer && (
        <div className="checkout-panel">
          <p>
            <Link to="/login" state={{ from: "/cart" }}>
              Sign in
            </Link>{" "}
            or{" "}
            <Link to="/register" state={{ from: "/cart" }}>
              create an account
            </Link>{" "}
            to check out.
          </p>
        </div>
      )}

      {buyer && (
        <div className="checkout-panel address-form">
          <h2>Delivery address</h2>
          <label>
            Recipient name
            <input value={recipientName} onChange={(e) => setRecipientName(e.target.value)} />
          </label>
          <label>
            Phone
            <input value={recipientPhone} onChange={(e) => setRecipientPhone(e.target.value)} />
          </label>
          <label>
            Address line 1
            <input value={addressLine1} onChange={(e) => setAddressLine1(e.target.value)} />
          </label>
          <label>
            Address line 2 (optional)
            <input value={addressLine2} onChange={(e) => setAddressLine2(e.target.value)} />
          </label>
          <label>
            City
            <input value={city} onChange={(e) => setCity(e.target.value)} />
          </label>
          <label>
            State
            <input value={state} onChange={(e) => setState(e.target.value)} />
          </label>
          <button onClick={handlePlaceOrder} disabled={loading}>
            Place order &amp; pay
          </button>
        </div>
      )}

      {error && <p className="error">{error}</p>}
    </div>
  );
}
