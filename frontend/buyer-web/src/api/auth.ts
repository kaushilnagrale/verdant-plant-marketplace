import axios from "axios";
import { apiClient } from "./client";
import type { Buyer } from "../types";

// Real login goes straight to Keycloak's own token endpoint (a direct
// resource-owner-password-credentials grant), not through the backend -
// verdant-buyer-web is a public client made exactly for this, the same
// real mechanism scripts/token.sh already uses for sellers, just called
// from the browser instead of a shell script. Registration is different:
// there's no public "create a user" grant, so that goes through the
// backend (POST /v1/buyers/register), which holds the real, narrowly-
// scoped Keycloak admin credentials this frontend never sees.
const KEYCLOAK_BASE_URL = import.meta.env.VITE_KEYCLOAK_BASE_URL ?? "http://localhost:8081";
const KEYCLOAK_REALM = "verdant";
const BUYER_CLIENT_ID = "verdant-buyer-web";

export async function registerBuyer(name: string, email: string, phone: string, password: string): Promise<void> {
  await apiClient.post("/v1/buyers/register", { name, email, phone: phone || null, password });
}

/** Throws (axios error) on bad credentials - the caller shows a real "incorrect email or password" message, never guesses which of the two was wrong (Keycloak itself doesn't say, on purpose). */
export async function login(email: string, password: string): Promise<string> {
  const body = new URLSearchParams({
    grant_type: "password",
    client_id: BUYER_CLIENT_ID,
    username: email,
    password,
  });
  const res = await axios.post<{ access_token: string }>(
    `${KEYCLOAK_BASE_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`,
    body,
    { headers: { "Content-Type": "application/x-www-form-urlencoded" } },
  );
  return res.data.access_token;
}

export async function fetchMe(): Promise<Buyer> {
  const res = await apiClient.get<Buyer>("/v1/buyers/me");
  return res.data;
}
