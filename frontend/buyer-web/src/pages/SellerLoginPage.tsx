import { useState } from "react";
import { useNavigate } from "react-router-dom";

/**
 * There is no real login screen anywhere in this project - seller auth is
 * via Keycloak directly (scripts/token.sh), the same way every backend day
 * this session was verified. Pasting a token here is the honest equivalent
 * of that, not a real OAuth2/PKCE flow built just for this frontend.
 */
export function SellerLoginPage() {
  const [token, setToken] = useState(localStorage.getItem("verdant_seller_token") ?? "");
  const navigate = useNavigate();

  function handleSave() {
    localStorage.setItem("verdant_seller_token", token.trim());
    navigate("/seller/orders");
  }

  return (
    <div className="seller-login">
      <h1>Seller sign-in</h1>
      <p>
        Paste a token obtained via <code>./scripts/token.sh &lt;seller-username&gt;</code>. There is no login form
        here on purpose - real auth goes through Keycloak, not this frontend.
      </p>
      <textarea rows={6} value={token} onChange={(e) => setToken(e.target.value)} placeholder="eyJhbGciOi..." />
      <button onClick={handleSave} disabled={!token.trim()}>
        Save token
      </button>
    </div>
  );
}
