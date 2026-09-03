import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Route, Routes } from "react-router-dom";
import { CartProvider } from "./context/CartContext";
import { Header } from "./components/Header";
import { CatalogPage } from "./pages/CatalogPage";
import { ProductPage } from "./pages/ProductPage";
import { CartPage } from "./pages/CartPage";
import { ConfirmationPage } from "./pages/ConfirmationPage";
import { SellerOrdersPage } from "./pages/SellerOrdersPage";
import { SellerLoginPage } from "./pages/SellerLoginPage";
import "./App.css";

const queryClient = new QueryClient();

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <CartProvider>
        <BrowserRouter>
          <Header />
          <main className="page">
            <Routes>
              <Route path="/" element={<CatalogPage />} />
              <Route path="/product/:id" element={<ProductPage />} />
              <Route path="/cart" element={<CartPage />} />
              <Route path="/confirmation/:orderId" element={<ConfirmationPage />} />
              <Route path="/seller/login" element={<SellerLoginPage />} />
              <Route path="/seller/orders" element={<SellerOrdersPage />} />
            </Routes>
          </main>
        </BrowserRouter>
      </CartProvider>
    </QueryClientProvider>
  );
}
