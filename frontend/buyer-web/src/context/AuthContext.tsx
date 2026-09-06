import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import type { Buyer } from "../types";
import { fetchMe } from "../api/auth";

interface AuthContextValue {
  buyer: Buyer | null;
  loading: boolean;
  setToken: (token: string) => void;
  logout: () => void;
  /** ProfilePage calls this with the response updateProfile() already returned - no need to re-fetch /v1/buyers/me right after we just got the fresh row back. */
  setBuyer: (buyer: Buyer) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);
const TOKEN_KEY = "verdant_buyer_token";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [buyer, setBuyer] = useState<Buyer | null>(null);
  const [loading, setLoading] = useState(true);

  async function refresh() {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) {
      setBuyer(null);
      setLoading(false);
      return;
    }
    try {
      setBuyer(await fetchMe());
    } catch {
      // An expired or otherwise rejected token - same "lost session, sign
      // in again" outcome either way, not worth telling apart here.
      localStorage.removeItem(TOKEN_KEY);
      setBuyer(null);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function setToken(token: string) {
    localStorage.setItem(TOKEN_KEY, token);
    setLoading(true);
    refresh();
  }

  function logout() {
    localStorage.removeItem(TOKEN_KEY);
    setBuyer(null);
  }

  return <AuthContext.Provider value={{ buyer, loading, setToken, logout, setBuyer }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within an AuthProvider");
  return ctx;
}
