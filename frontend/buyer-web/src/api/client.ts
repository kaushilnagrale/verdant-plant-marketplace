import axios from "axios";

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

export const apiClient = axios.create({ baseURL: API_BASE_URL });

// Seller-facing endpoints are authenticated. There is no real login screen
// for sellers in this project (auth is via Keycloak directly, tokens
// obtained via scripts/token.sh) - the seller pastes a bearer token once,
// stored in localStorage, and every request attaches it automatically.
// Buyer-facing endpoints now have a real login (AuthContext) - the two
// identities are stored under separate keys and picked by request path,
// since a single browser session is never both at once in this small app.
apiClient.interceptors.request.use((config) => {
  const url = config.url ?? "";
  const tokenKey = url.startsWith("/v1/seller") ? "verdant_seller_token" : "verdant_buyer_token";
  const token = localStorage.getItem(tokenKey);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});
