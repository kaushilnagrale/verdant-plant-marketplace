import axios from "axios";

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

export const apiClient = axios.create({ baseURL: API_BASE_URL });

// Seller-facing endpoints are authenticated. There is no login screen in
// this project (auth is via Keycloak directly, tokens obtained via
// scripts/token.sh - the same way every backend day this session was
// verified by curl) - the seller pastes a bearer token once, stored in
// localStorage, and every request attaches it automatically.
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem("verdant_seller_token");
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});
