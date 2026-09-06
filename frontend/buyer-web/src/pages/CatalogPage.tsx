import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { browseCatalog } from "../api/catalog";
import { search } from "../api/search";
import type { CatalogListingSummary, SearchResults } from "../types";

const PINCODE_STORAGE_KEY = "verdant_last_pincode";

export function CatalogPage() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["catalog"],
    queryFn: browseCatalog,
  });

  const [query, setQuery] = useState("");
  const [pincode, setPincode] = useState(() => localStorage.getItem(PINCODE_STORAGE_KEY) ?? "");
  const [results, setResults] = useState<SearchResults | null>(null);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);

  async function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!query.trim()) {
      setSearchError("Enter something to search for.");
      return;
    }
    if (pincode.length !== 6) {
      setSearchError("Enter a valid 6-digit delivery pincode - results are filtered to what can actually reach you.");
      return;
    }
    setSearchError(null);
    setSearching(true);
    try {
      localStorage.setItem(PINCODE_STORAGE_KEY, pincode);
      setResults(await search(query.trim(), pincode));
    } catch {
      // Genuinely ambiguous on purpose (matches this app's own preview/
      // checkout error handling elsewhere): could be an unrecognized
      // pincode, or search itself being unavailable - either way there's
      // nothing more specific to tell a buyer that's actually true.
      setSearchError("Could not search right now - check the pincode and try again.");
      setResults(null);
    } finally {
      setSearching(false);
    }
  }

  function clearSearch() {
    setQuery("");
    setResults(null);
    setSearchError(null);
  }

  return (
    <div>
      <h1>Live plants, shipped healthy</h1>

      <form className="search-bar" onSubmit={handleSearch}>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search for a plant (e.g. money plant, snake plant)"
          aria-label="Search"
        />
        <input
          value={pincode}
          onChange={(e) => setPincode(e.target.value.trim())}
          maxLength={6}
          placeholder="Delivery pincode"
          aria-label="Delivery pincode"
        />
        <button type="submit" disabled={searching}>
          {searching ? "Searching..." : "Search"}
        </button>
        {results && (
          <button type="button" className="link-button" onClick={clearSearch}>
            Clear
          </button>
        )}
      </form>
      {searchError && <p className="error">{searchError}</p>}

      {results ? <SearchResultsGrid results={results} /> : <BrowseGrid isLoading={isLoading} isError={isError} data={data} />}
    </div>
  );
}

function SearchResultsGrid({ results }: { results: SearchResults }) {
  if (results.hits.length === 0) {
    return (
      <p>
        Nothing matched that search for this delivery pincode - either nothing sells it, or nothing that does can
        reach you in time to arrive alive.
      </p>
    );
  }
  return (
    <div className="grid">
      {results.hits.map((hit) => (
        <Link to={`/product/${hit.listingId}`} key={hit.listingId} className="card">
          <div className="card-thumb">🪴</div>
          <div className="card-body">
            <div className="card-title">{hit.displayName}</div>
            <div className="card-subtitle">Ships from: {hit.originZone}</div>
            <div className="card-price">₹{hit.priceAmount.toFixed(2)}</div>
            {!hit.inStock && <div className="status-pill status-cancelled">Out of stock</div>}
          </div>
        </Link>
      ))}
    </div>
  );
}

interface BrowseGridProps {
  isLoading: boolean;
  isError: boolean;
  data: CatalogListingSummary[] | undefined;
}

/** The original, un-filtered "everything, anywhere" browse view - unchanged default when no search is active. */
function BrowseGrid({ isLoading, isError, data }: BrowseGridProps) {
  if (isLoading) return <p>Loading plants…</p>;
  if (isError) return <p className="error">Could not load the catalog. Is the API running?</p>;
  if (!data || data.length === 0) return <p>No plants listed yet.</p>;

  return (
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
  );
}
