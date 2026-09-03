import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { getListing } from "../api/catalog";
import { useCart } from "../context/CartContext";

export function ProductPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { addItem } = useCart();
  const [quantity, setQuantity] = useState(1);
  const [added, setAdded] = useState(false);

  const { data, isLoading, isError } = useQuery({
    queryKey: ["listing", id],
    queryFn: () => getListing(id!),
    enabled: !!id,
  });

  if (isLoading) return <p>Loading…</p>;
  if (isError || !data) return <p className="error">Plant not found.</p>;

  function handleAddToCart() {
    if (!data) return;
    addItem({ listingId: data.id, quantity, title: data.title, priceAmount: data.priceAmount });
    setAdded(true);
  }

  return (
    <div className="product-page">
      <button className="link-button" onClick={() => navigate(-1)}>
        ← Back
      </button>
      <div className="product-layout">
        <div className="product-thumb-large">🪴</div>
        <div>
          <h1>{data.title}</h1>
          {data.productName && <p className="card-subtitle">{data.productName}</p>}
          <p className="card-seller">Sold by {data.sellerName}</p>
          {data.description && <p>{data.description}</p>}
          <p>Handling time: {data.handlingTimeHours} hours</p>
          <p className="card-price">₹{data.priceAmount.toFixed(2)}</p>

          <div className="qty-row">
            <label>
              Quantity{" "}
              <input
                type="number"
                min={1}
                value={quantity}
                onChange={(e) => setQuantity(Math.max(1, Number(e.target.value)))}
              />
            </label>
            <button onClick={handleAddToCart}>Add to cart</button>
          </div>
          {added && <p className="success">Added to cart.</p>}
        </div>
      </div>
    </div>
  );
}
