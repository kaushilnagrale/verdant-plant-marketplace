import { Link } from "react-router-dom";
import { useCart } from "../context/CartContext";

export function Header() {
  const { items } = useCart();
  // Distinct listings in the cart, not total units - 22 of one plant is
  // still 1 item added, the same way Gmail's unread count isn't "total
  // words unread."
  const count = items.length;

  return (
    <header className="site-header">
      <Link to="/" className="brand">
        🌿 Verdant
      </Link>
      <nav>
        <Link to="/">Catalog</Link>
        <Link to="/cart">Cart{count > 0 ? ` (${count})` : ""}</Link>
        <Link to="/seller/orders">Seller</Link>
      </nav>
    </header>
  );
}
