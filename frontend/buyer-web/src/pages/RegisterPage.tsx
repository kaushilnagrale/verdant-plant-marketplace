import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import axios from "axios";
import { registerBuyer, login } from "../api/auth";
import { useAuth } from "../context/AuthContext";

export function RegisterPage() {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const { setToken } = useAuth();
  const navigate = useNavigate();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (password.length < 8) {
      setError("Password must be at least 8 characters.");
      return;
    }
    setLoading(true);
    try {
      await registerBuyer(name, email, phone, password);
      // Registration doesn't return a token itself (it's a real backend
      // call, not a login) - logging in right after with the same
      // credentials is one extra real round trip, not a shortcut.
      const token = await login(email, password);
      setToken(token);
      navigate("/");
    } catch (err) {
      if (axios.isAxiosError(err) && err.response?.status === 409) {
        setError("An account already exists for this email.");
      } else if (axios.isAxiosError(err) && err.response?.status === 400) {
        setError("Please check your details and try again.");
      } else {
        setError("Could not create your account - please try again.");
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="auth-form">
      <h1>Create your account</h1>
      <form onSubmit={handleSubmit}>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          Phone (optional)
          <input value={phone} onChange={(e) => setPhone(e.target.value)} />
        </label>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={8} />
        </label>
        <button type="submit" disabled={loading}>
          {loading ? "Creating account..." : "Create account"}
        </button>
      </form>
      {error && <p className="error">{error}</p>}
      <p>
        Already have an account? <Link to="/login">Sign in</Link>
      </p>
    </div>
  );
}
