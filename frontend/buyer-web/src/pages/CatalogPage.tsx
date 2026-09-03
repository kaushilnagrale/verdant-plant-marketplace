import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { browseCatalog } from "../api/catalog";

export function CatalogPage() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["catalog"],
    queryFn: browseCatalog,
  });

  if (isLoading) return <p>Loading plants…</p>;
  if (isError) return <p className="error">Could not load the catalog. Is the API running?</p>;
  if (!data || data.length === 0) return <p>No plants listed yet.</p>;

  return (
    <div>
      <h1>Live plants, shipped healthy</h1>
      <div className="grid">
        {data.map((listing) => (
          <Link to={`/product/${listing.id}`} key={listing.id} className="card">
            <div className="card-thumb">🪴</div>
            <div className="card-body">
              <div className="card-title">{listing.title}</div>
              {listing.productName && <div className="card-subtitle">{listing.productName}</div>}
              <div className="card-seller">{listing.sellerName}</div>
              <div className="card-price">₹{listing.priceAmount.toFixed(2)}</div>
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
}
