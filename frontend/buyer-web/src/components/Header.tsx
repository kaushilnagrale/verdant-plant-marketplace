import { Link } from "react-router-dom";
import { useCart } from "../context/CartContext";

export function Header() {
  const { items } = useCart();
  const count = items.reduce((sum, i) => sum + i.quantity, 0);

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
