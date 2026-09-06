import { Link, useNavigate } from "react-router-dom";
import { useCart } from "../context/CartContext";
import { useAuth } from "../context/AuthContext";

export function Header() {
  const { items } = useCart();
  const { buyer, logout } = useAuth();
  const navigate = useNavigate();
  // Distinct listings in the cart, not total units - 22 of one plant is
  // still 1 item added, the same way Gmail's unread count isn't "total
  // words unread."
  const count = items.length;

  function handleLogout() {
    logout();
    navigate("/");
  }

  return (
    <header className="site-header">
      <Link to="/" className="brand">
        🌿 Verdant
      </Link>
      <nav>
        <Link to="/">Catalog</Link>
        <Link to="/cart">Cart{count > 0 ? ` (${count})` : ""}</Link>
        {buyer ? (
          <>
            <Link to="/orders">My orders</Link>
            <span className="header-buyer-name">{buyer.name}</span>
            <button className="link-button" onClick={handleLogout}>
              Sign out
            </button>
          </>
        ) : (
          <Link to="/login">Sign in</Link>
        )}
        <Link to="/seller/orders">Seller</Link>
      </nav>
    </header>
  );
}
