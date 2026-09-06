import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import type { CartLine } from "../types";

interface CartItem extends CartLine {
  title: string;
  priceAmount: number;
}

interface CartContextValue {
  items: CartItem[];
  addItem: (item: CartItem) => void;
  removeItem: (listingId: string) => void;
  updateQuantity: (listingId: string, quantity: number) => void;
  clear: () => void;
}

const CartContext = createContext<CartContextValue | null>(null);

const STORAGE_KEY = "verdant_cart";

export function CartProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<CartItem[]>(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? (JSON.parse(raw) as CartItem[]) : [];
    } catch {
      return [];
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
    } catch {
      // localStorage can throw (private browsing, quota) - a lost cart is a
      // per-viewer inconvenience, not something worth crashing the page over
    }
  }, [items]);

  function addItem(item: CartItem) {
    setItems((prev) => {
      const existing = prev.find((i) => i.listingId === item.listingId);
      if (existing) {
        return prev.map((i) =>
          i.listingId === item.listingId ? { ...i, quantity: i.quantity + item.quantity } : i,
        );
      }
      return [...prev, item];
    });
  }

  function removeItem(listingId: string) {
    setItems((prev) => prev.filter((i) => i.listingId !== listingId));
  }

  // Sets the exact quantity for a line (the +/- stepper on the cart page
  // calls this with quantity +/- 1). Floors at 1 rather than removing the
  // item at 0 - "Remove" is already a separate, explicit action, and a
  // stepper silently deleting a row it was just decrementing would be a
  // surprising side effect. The real >0 check happens again server-side
  // (CartPricingService) regardless - this is just what stops the "-"
  // button from ever asking for something already invalid.
  function updateQuantity(listingId: string, quantity: number) {
    if (quantity < 1) {
      return;
    }
    setItems((prev) => prev.map((i) => (i.listingId === listingId ? { ...i, quantity } : i)));
  }

  function clear() {
    setItems([]);
  }

  return (
    <CartContext.Provider value={{ items, addItem, removeItem, updateQuantity, clear }}>
      {children}
    </CartContext.Provider>
  );
}

export function useCart(): CartContextValue {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error("useCart must be used within a CartProvider");
  return ctx;
}
