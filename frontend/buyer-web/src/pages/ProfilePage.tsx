import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { updateProfile } from "../api/auth";
import { useAuth } from "../context/AuthContext";
import type { Buyer } from "../types";

export function ProfilePage() {
  const { buyer, loading: authLoading } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    if (!authLoading && !buyer) {
      navigate("/login", { state: { from: "/profile" } });
    }
  }, [buyer, authLoading, navigate]);

  if (!buyer) return null;

  // Keyed on buyer.id: if this page is ever reached for a different
  // signed-in buyer, ProfileForm remounts with fresh initial state
  // instead of showing the previous buyer's stale name/phone.
  return <ProfileForm key={buyer.id} buyer={buyer} />;
}

function ProfileForm({ buyer }: { buyer: Buyer }) {
  const { setBuyer } = useAuth();
  // Derived directly from the buyer prop at mount, not synced in via an
  // effect - buyer is already guaranteed non-null here (ProfilePage only
  // renders this once it is), so there's nothing to wait for.
  const [name, setName] = useState(buyer.name);
  const [phone, setPhone] = useState(buyer.phone ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) {
      setError("Name is required.");
      return;
    }
    setError(null);
    setSaved(false);
    setSaving(true);
    try {
      const updated = await updateProfile(name.trim(), phone.trim());
      setBuyer(updated);
      setSaved(true);
    } catch {
      setError("Could not save your changes - please try again.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="auth-form">
      <h1>Your profile</h1>
      <form onSubmit={handleSubmit}>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>
        <label>
          Phone
          <input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="9876543210" />
        </label>
        <label>
          Email
          <input value={buyer.email} disabled title="Email is your sign-in id and can't be changed here." />
        </label>
        <button type="submit" disabled={saving}>
          {saving ? "Saving..." : "Save changes"}
        </button>
      </form>
      {saved && <p className="success">Saved.</p>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
